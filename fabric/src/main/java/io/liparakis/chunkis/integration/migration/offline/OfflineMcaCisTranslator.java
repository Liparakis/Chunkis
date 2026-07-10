package io.liparakis.chunkis.integration.migration.offline;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.adapter.FabricBlockRegistryAdapter;
import io.liparakis.chunkis.adapter.FabricBlockStateAdapter;
import io.liparakis.chunkis.adapter.FabricNbtAdapter;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.integration.migration.MigrationProgressTracker;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.storage.mapping.CisMapping;
import io.liparakis.chunkis.core.mapping.PropertyPacker;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettesFactory;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;
import org.slf4j.Logger;

/**
 * Offline MCA-to-CIS translator invoked before normal singleplayer world
 * startup.
 *
 * <p>This translator does not depend on live {@code ServerWorld} instances. It
 * reads raw chunk NBT from vanilla region files, deserializes through
 * {@link SerializedChunk#fromNbt(HeightLimitView, PalettesFactory, NbtCompound)},
 * captures one authoritative full CIS snapshot per present chunk, validates the
 * written chunk records, and retires fully converted regions to
 * {@code .backup}.</p>
 */
public final class OfflineMcaCisTranslator {

    /**
     * Translator version stored in migration reports.
     */
    public static final String TRANSLATOR_VERSION = "offline-mca-to-cis-v1";

    /**
     * Logger instance for migration output.
     */
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Pattern regex matching vanilla region file names.
     */
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /**
     * Side length of one region in chunks.
     */
    private static final int REGION_SIZE = 32;

    /**
     * Reserves enough heap for one decoded source chunk, its authoritative delta,
     * and compression buffers. This limits parallelism before the JVM is under
     * allocation pressure.
     */
    private static final long MIGRATION_BASELINE_HEAP_BYTES = 2L * 1024L * 1024L * 1024L;

    /**
     * Heap budget reserved for each concurrently decoding worker.
     */
    private static final long MIGRATION_WORKER_HEAP_BYTES = 512L * 1024L * 1024L;

    /**
     * Private constructor to prevent utility class instantiation.
     */
    private OfflineMcaCisTranslator() {
        throw new AssertionError("Utility class");
    }

    /**
     * Translates one dimension's MCA region files into authoritative CIS
     * snapshots.
     *
     * @param saveRoot        world save root
     * @param worldKey        dimension registry key
     * @param registryManager registry manager used to decode palette contents
     * @param dimensionType   dimension height limits
     * @throws IOException if storage initialization or report writing fails
     */
    public static OfflineWorldMigrationReport translateWorld(final Path saveRoot,
            final RegistryKey<World> worldKey,
            final DynamicRegistryManager registryManager,
            final RegistryEntry<DimensionType> dimensionType) throws IOException {
        final String worldId = worldKey.getValue()
                .toString();
        final Path regionDir = ChunkisStoragePaths.computeVanillaRegionDirectory(saveRoot, worldKey);
        final Path entityDir = ChunkisStoragePaths.computeVanillaEntitiesDirectory(saveRoot, worldKey);
        LOGGER.info("Checking MCA region directory for world {}: {}", worldId, regionDir);

        if (!Files.exists(regionDir)) {
            LOGGER.info("No MCA region directory found at {}; skipping migration.", regionDir);
            return OfflineWorldMigrationReport.empty(worldKey);
        }

        final StorageKey storageKey = new StorageKey("chunk", worldKey, "chunk");
        final List<RegionTask> regions = collectRegionTasks(regionDir, entityDir, storageKey);
        if (regions.isEmpty()) {
            return OfflineWorldMigrationReport.empty(worldKey);
        }

        final Path storageDir = ChunkisStoragePaths.computeRegionsDirectory(saveRoot, worldKey);
        final Path mappingFile = ChunkisStoragePaths.computeMappingFile(saveRoot, worldKey);
        final HeightLimitView heightLimitView = HeightLimitView.create(dimensionType.value()
                        .minY(),
                dimensionType.value()
                        .height());
        final PalettesFactory palettesFactory = PalettesFactory.fromRegistryManager(registryManager);

        final int workers = workerCountFor(regions.size(),
                Runtime.getRuntime()
                        .availableProcessors(),
                Runtime.getRuntime()
                        .maxMemory());
        final long totalChunks = regions.stream()
                .mapToLong(RegionTask::presentChunks)
                .sum();
        final long totalBytes = regions.stream()
                .mapToLong(RegionTask::fileSizeBytes)
                .sum();
        int handledChunks = 0;
        int failedChunks = 0;
        int retiredRegions = 0;
        MigrationProgressTracker.begin(worldId, regions.size(), totalChunks, totalBytes, workers);

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = buildStorage(storageDir, mappingFile);
        final AtomicInteger workerNumbers = new AtomicInteger();
        final ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
            final Thread thread = new Thread(runnable, "Chunkis-McaToCis-" + workerNumbers.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            final List<Future<OfflineRegionMigrationReport>> futures = new ArrayList<>(regions.size());
            for (final RegionTask region : regions) {
                futures.add(executor.submit(() -> translateTask(storage,
                        worldKey,
                        heightLimitView,
                        palettesFactory,
                        regionDir,
                        entityDir,
                        region)));
            }
            for (final Future<OfflineRegionMigrationReport> future : futures) {
                final OfflineRegionMigrationReport report;
                try {
                    report = future.get();
                } catch (final InterruptedException e) {
                    Thread.currentThread()
                            .interrupt();
                    throw new IOException("Interrupted while waiting for MCA-to-CIS migration", e);
                } catch (final ExecutionException e) {
                    throw new IOException("MCA-to-CIS migration task failed", e.getCause());
                }
                handledChunks += report.handledChunks();
                failedChunks += report.failedChunks();
                if (report.retired()) {
                    retiredRegions++;
                }
            }
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread()
                        .interrupt();
            }
            storage.close();
            MigrationProgressTracker.finish();
        }

        LOGGER.info("Chunkis MCA Migration complete for world {}. Handled {}, failed {}, retired {} region(s).",
                worldId,
                handledChunks,
                failedChunks,
                retiredRegions);

        return new OfflineWorldMigrationReport(worldKey, regions.size(), handledChunks, failedChunks, retiredRegions);
    }

    /**
     * Collects region coordinates for all matching {@code .mca} files in the
     * directory.
     */
    private static List<RegionCoordinates> collectRegions(final Path regionDir) throws IOException {
        final List<RegionCoordinates> regions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            for (final Path path : stream) {
                final Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName()
                        .toString());
                if (matcher.matches()) {
                    regions.add(new RegionCoordinates(Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2))));
                }
            }
        }
        regions.sort(Comparator.comparingInt(RegionCoordinates::regionX)
                .thenComparingInt(RegionCoordinates::regionZ));
        return regions;
    }

    /**
     * Counts source chunks and source file bytes before workers start so progress
     * and byte-weighted ETA remain accurate without retaining chunk payloads.
     */
    private static List<RegionTask> collectRegionTasks(final Path regionDir,
            final Path entityDir,
            final StorageKey storageKey)
            throws IOException {
        final List<RegionTask> tasks = new ArrayList<>();
        for (final RegionCoordinates region : collectRegions(regionDir)) {
            final Path mcaPath = regionDir.resolve(region.fileName());
            int presentChunks = 0;
            try (RegionFile regionFile = new RegionFile(storageKey, mcaPath, mcaPath.getParent(), true)) {
                for (int localX = 0; localX < REGION_SIZE; localX++) {
                    for (int localZ = 0; localZ < REGION_SIZE; localZ++) {
                        if (regionFile.hasChunk(new ChunkPos((region.regionX() << 5) + localX,
                                (region.regionZ() << 5) + localZ))) {
                            presentChunks++;
                        }
                    }
                }
            }
            long fileSizeBytes = Files.size(mcaPath);
            final Path entityPath = entityDir.resolve(region.fileName());
            if (Files.isRegularFile(entityPath)) {
                fileSizeBytes += Files.size(entityPath);
            }
            tasks.add(new RegionTask(region, presentChunks, fileSizeBytes));
        }
        return tasks;
    }

    /**
     * Calculates the safe worker count from CPU, heap, and region limits.
     */
    static int workerCountFor(final int regionCount, final int availableProcessors, final long maxHeapBytes) {
        if (regionCount <= 0) {
            return 0;
        }
        final int cpuWorkers = Math.max(1, availableProcessors - 2);
        final long migrationHeap = Math.max(0L, maxHeapBytes - MIGRATION_BASELINE_HEAP_BYTES);
        final int heapWorkers = Math.max(1,
                (int) Math.min(Integer.MAX_VALUE, migrationHeap / MIGRATION_WORKER_HEAP_BYTES));
        return Math.min(regionCount, Math.min(cpuWorkers, heapWorkers));
    }

    /**
     * Converts one queued region task and publishes its worker progress.
     */
    private static OfflineRegionMigrationReport translateTask(final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final RegistryKey<World> worldKey,
            final HeightLimitView heightLimitView,
            final PalettesFactory palettesFactory,
            final Path regionDir,
            final Path entityDir,
            final RegionTask task) {
        final int worker = Integer.parseInt(Thread.currentThread()
                .getName()
                .replace("Chunkis-McaToCis-", ""));
        final Path mcaPath = regionDir.resolve(task.coordinates()
                .fileName());
        MigrationProgressTracker.startRegion(worker,
                mcaPath.getFileName()
                        .toString(),
                task.presentChunks(),
                task.fileSizeBytes());
        try {
            final OfflineRegionMigrationReport report = translateRegion(storage,
                    worldKey,
                    heightLimitView,
                    palettesFactory,
                    mcaPath,
                    entityDir.resolve(task.coordinates()
                            .fileName()),
                    task.coordinates()
                            .regionX(),
                    task.coordinates()
                            .regionZ(),
                    worker);
            MigrationProgressTracker.completeRegion(worker);
            return report;
        } catch (final Exception e) {
            LOGGER.error("Failed to migrate region {}", mcaPath.getFileName(), e);
            MigrationProgressTracker.updateRegion(worker, task.presentChunks(), 0, Math.max(1, task.presentChunks()));
            MigrationProgressTracker.completeRegion(worker);
            return new OfflineRegionMigrationReport(worldKey.getValue()
                    .toString(),
                    mcaPath.getFileName()
                            .toString(),
                    task.presentChunks(),
                    0,
                    Math.max(1, task.presentChunks()),
                    false,
                    TRANSLATOR_VERSION);
        }
    }

    /**
     * Translates one MCA region file and retires it when every present chunk
     * converts and validates successfully.
     */
    private static OfflineRegionMigrationReport translateRegion(final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final RegistryKey<World> worldKey,
            final HeightLimitView heightLimitView,
            final PalettesFactory palettesFactory,
            final Path mcaPath,
            final Path entityMcaPath,
            final int regionX,
            final int regionZ,
            final int worker) throws IOException {
        final StorageKey storageKey = new StorageKey("chunk", worldKey, "chunk");
        final StorageKey entityStorageKey = new StorageKey("entities", worldKey, "entities");
        int presentChunks = 0;
        int processedChunks = 0;
        int handledChunks = 0;
        int failedChunks = 0;

        LOGGER.info("Chunkis pre-launch migrator: converting {} to CIS...", mcaPath.getFileName());

        try (RegionFile regionFile = new RegionFile(storageKey, mcaPath, mcaPath.getParent(), true);
                RegionFile entityRegionFile = openOptionalRegionFile(entityStorageKey, entityMcaPath)) {
            for (int localX = 0; localX < REGION_SIZE; localX++) {
                for (int localZ = 0; localZ < REGION_SIZE; localZ++) {
                    final ChunkPos chunkPos = new ChunkPos((regionX << 5) + localX, (regionZ << 5) + localZ);

                    try (DataInputStream input = regionFile.getChunkInputStream(chunkPos)) {
                        if (input == null) {
                            continue;
                        }

                        presentChunks++;
                        final NbtCompound sourceNbt = NbtIo.readCompound(input,
                                NbtSizeTracker.of(FabricNbtAdapter.MAX_NBT_SIZE));
                        if (sourceNbt == null) {
                            failedChunks++;
                            continue;
                        }

                        final ChunkDelta<BlockState, NbtCompound> delta = buildChunkDelta(heightLimitView,
                                palettesFactory,
                                sourceNbt,
                                loadOptionalEntityChunkNbt(entityRegionFile, chunkPos),
                                chunkPos);
                        if (isOmittableEmptyChunk(delta)) {
                            if (!clearOmittedEmptyChunk(storage, chunkPos)) {
                                failedChunks++;
                                LOGGER.error("Failed to omit empty placeholder chunk {} from {}",
                                        chunkPos,
                                        mcaPath.getFileName());
                                continue;
                            }
                            handledChunks++;
                            LOGGER.info(
                                    "Omitting empty MCA placeholder chunk {} from {} so vanilla can treat it as absent.",
                                    chunkPos,
                                    mcaPath.getFileName());
                            continue;
                        }
                        if (!storage.replace(new CisChunkPos(chunkPos.x, chunkPos.z), delta)) {
                            failedChunks++;
                            LOGGER.error("Failed to save migrated chunk {} from {}", chunkPos, mcaPath.getFileName());
                            continue;
                        }

                        final MigrationValidationResult vr = validateMigratedChunk(storage, chunkPos, delta);
                        if (!vr.valid()) {
                            failedChunks++;
                            LOGGER.error("Failed to validate migrated chunk {} from {}: code={} details={}",
                                    chunkPos,
                                    mcaPath.getFileName(),
                                    vr.failureCode(),
                                    vr.details());
                            continue;
                        }

                        handledChunks++;
                    } catch (final Exception e) {
                        failedChunks++;
                        LOGGER.error("Failed to migrate chunk {} in {}", chunkPos, mcaPath.getFileName(), e);
                    } finally {
                        processedChunks++;
                        MigrationProgressTracker.updateRegion(worker, processedChunks, handledChunks, failedChunks);
                    }
                }
            }
        }

        final boolean retired = new OfflineRegionMigrationReport(worldKey.getValue()
                .toString(),
                mcaPath.getFileName()
                        .toString(),
                presentChunks,
                handledChunks,
                failedChunks,
                false,
                TRANSLATOR_VERSION).canRetireSourceRegion();
        if (retired) {
            retireRegionFile(mcaPath);
        } else {
            LOGGER.warn("Chunkis pre-launch migrator: keeping {} in place because present={}, handled={}, failed={}.",
                    mcaPath.getFileName(),
                    presentChunks,
                    handledChunks,
                    failedChunks);
        }

        final OfflineRegionMigrationReport report = new OfflineRegionMigrationReport(worldKey.getValue()
                .toString(),
                mcaPath.getFileName()
                        .toString(),
                presentChunks,
                handledChunks,
                failedChunks,
                retired,
                TRANSLATOR_VERSION);
        LOGGER.info("Finished {}. Handled {}, failed {}, coverage {}/{}.",
                mcaPath.getFileName(),
                handledChunks,
                failedChunks,
                handledChunks,
                presentChunks);
        return report;
    }

    /**
     * Captures one authoritative full CIS snapshot from raw serialized chunk
     * data.
     */
    public static ChunkDelta<BlockState, NbtCompound> buildChunkDelta(final HeightLimitView heightLimitView,
            final PalettesFactory palettesFactory,
            final NbtCompound sourceNbt,
            final NbtCompound entitySourceNbt,
            final ChunkPos chunkPos) {
        final SerializedChunk serialized = SerializedChunk.fromNbt(heightLimitView, palettesFactory, sourceNbt);
        if (serialized == null) {
            throw new IllegalStateException("Vanilla could not deserialize chunk " + chunkPos);
        }

        final ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>();
        final NbtCompound structureData = CisNbtUtil.extractStructureData(sourceNbt);
        final NbtCompound preservedAuxiliary = CisNbtUtil.extractPreservedAuxiliaryChunkNbtFromChunkRoot(sourceNbt);
        final boolean portalChunk = captureBlocks(serialized, delta);

        delta.setSuppressInitialRepopulation(true);
        delta.setChunkMetadata(createMigratedChunkMetadata(structureData, preservedAuxiliary, portalChunk), false);
        captureBlockEntities(serialized, sourceNbt, chunkPos, delta);
        captureEntities(serialized, sourceNbt, entitySourceNbt, delta);
        return delta;
    }

    /**
     * Compatibility overload for callers that only have chunk-region NBT.
     */
    public static ChunkDelta<BlockState, NbtCompound> buildChunkDelta(final HeightLimitView heightLimitView,
            final PalettesFactory palettesFactory,
            final NbtCompound sourceNbt,
            final ChunkPos chunkPos) {
        return buildChunkDelta(heightLimitView, palettesFactory, sourceNbt, null, chunkPos);
    }

    /**
     * Captures all non-air block states from serialized chunk sections.
     *
     * @return {@code true} when at least one nether portal block was observed
     */
    private static boolean captureBlocks(final SerializedChunk serialized,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        boolean portalChunk = false;

        for (final SerializedChunk.SectionData sectionData : serialized.sectionData()) {
            final ChunkSection section = sectionData.chunkSection();
            if (section == null || section.isEmpty()) {
                continue;
            }

            final int sectionBottomY = sectionData.y() * 16;
            for (int localY = 0; localY < 16; localY++) {
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        final BlockState state = section.getBlockState(localX, localY, localZ);
                        if (state == null || state.isAir()) {
                            continue;
                        }

                        delta.addBlockChange(localX, sectionBottomY + localY, localZ, state);
                        if (!portalChunk && state.isOf(Blocks.NETHER_PORTAL)) {
                            portalChunk = true;
                        }
                    }
                }
            }
        }

        return portalChunk;
    }

    /**
     * Captures serialized block entities using local chunk X/Z coordinates.
     */
    private static void captureBlockEntities(final SerializedChunk serialized,
            final NbtCompound sourceNbt,
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        final int chunkStartX = chunkPos.getStartX();
        final int chunkStartZ = chunkPos.getStartZ();

        for (final NbtCompound blockEntityNbt : serialized.blockEntities()) {
            addBlockEntityPayload(delta, chunkStartX, chunkStartZ, blockEntityNbt);
        }
        for (final NbtCompound blockEntityNbt : CisNbtUtil.extractCompoundList(chunkPayloadRoot(sourceNbt),
                "block_entities")) {
            addBlockEntityPayload(delta, chunkStartX, chunkStartZ, blockEntityNbt);
        }
    }

    /**
     * Adds one block-entity payload using local chunk coordinates.
     */
    private static void addBlockEntityPayload(final ChunkDelta<BlockState, NbtCompound> delta,
            final int chunkStartX,
            final int chunkStartZ,
            final NbtCompound blockEntityNbt) {
        if (blockEntityNbt == null || !blockEntityNbt.contains("id")) {
            throw new IllegalStateException("Block entity payload is missing required id field");
        }

        final int worldX = blockEntityNbt.getInt("x")
                .orElseThrow();
        final int worldY = blockEntityNbt.getInt("y")
                .orElseThrow();
        final int worldZ = blockEntityNbt.getInt("z")
                .orElseThrow();
        delta.addBlockEntityData(worldX - chunkStartX, worldY, worldZ - chunkStartZ, blockEntityNbt.copy());
    }

    /**
     * Captures serialized chunk entities from both inline and external entity storage.
     */
    private static void captureEntities(final SerializedChunk serialized,
            final NbtCompound sourceNbt,
            final NbtCompound entitySourceNbt,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        final Set<String> seen = new HashSet<>();

        for (final NbtCompound entityNbt : serialized.entities()) {
            addEntityPayload(delta, seen, entityNbt);
        }
        for (final NbtCompound entityNbt : extractEntityPayloads(chunkPayloadRoot(sourceNbt))) {
            addEntityPayload(delta, seen, entityNbt);
        }
        for (final NbtCompound entityNbt : extractEntityPayloads(chunkPayloadRoot(entitySourceNbt))) {
            addEntityPayload(delta, seen, entityNbt);
        }
    }

    /**
     * Captures one serialized chunk entity as a pending entity.
     */
    private static void addEntityPayload(final ChunkDelta<BlockState, NbtCompound> delta,
            final Set<String> seen,
            final NbtCompound entityNbt) {
        if (entityNbt == null) {
            return;
        }
        final String key = entityPayloadKey(entityNbt);
        if (!seen.add(key)) {
            return;
        }
        delta.addPendingEntity(entityNbt.copy());
    }

    /**
     * Produces a stable dedupe key for migrated entity payloads.
     */
    private static String entityPayloadKey(final NbtCompound entityNbt) {
        return entityNbt.getIntArray("UUID")
                .map(array -> "uuid:" + java.util.Arrays.toString(array))
                .orElseGet(() -> "nbt:" + entityNbt);
    }

    /**
     * Extracts entity payload compounds from modern or legacy root layouts.
     */
    static List<NbtCompound> extractEntityPayloads(final NbtCompound root) {
        final List<NbtCompound> payloads = CisNbtUtil.extractCompoundList(root, "Entities");
        return payloads.isEmpty() ? CisNbtUtil.extractCompoundList(root, "entities") : payloads;
    }

    /**
     * Returns the nested legacy {@code Level} payload root when present.
     */
    static NbtCompound chunkPayloadRoot(final NbtCompound root) {
        if (root == null) {
            return null;
        }
        return root.getCompound("Level")
                .orElse(root);
    }

    /**
     * Creates authoritative migrated metadata.
     */
    public static NbtCompound createMigratedChunkMetadata(final NbtCompound structureData,
            final NbtCompound preservedAuxiliaryChunkNbt,
            final boolean portalChunk) {
        final NbtCompound metadata = CisNbtUtil.createChunkMetadataTakingOwnership(structureData,
                true,
                true,
                null,
                portalChunk);
        CisNbtUtil.putPreservedAuxiliaryChunkNbt(metadata, preservedAuxiliaryChunkNbt);
        CisNbtUtil.markMigratedAuthoritativeChunk(metadata);
        return metadata;
    }

    /**
     * Returns whether a translated chunk record is an empty placeholder that
     * should be omitted entirely so future loads treat it as absent.
     */
    static boolean isOmittableEmptyChunk(final ChunkDelta<BlockState, NbtCompound> delta) {
        return delta.getBlockChangesCount() == 0 && delta.getBlockEntities()
                .isEmpty() && delta.countPendingEntities() == 0;
    }

    /**
     * Validates that the stored migrated chunk still matches the translated
     * authoritative snapshot shape, not just its marker flags.
     */
    static MigrationValidationResult validateMigratedChunk(final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> expected) {
        final ChunkDelta<BlockState, NbtCompound> stored = storage.load(new CisChunkPos(chunkPos.x, chunkPos.z));
        return matchesMigratedChunkShape(expected, stored);
    }

    static MigrationValidationResult matchesMigratedChunkShape(final ChunkDelta<BlockState, NbtCompound> expected,
            final ChunkDelta<BlockState, NbtCompound> actual) {
        if (expected == null || actual == null || actual.isEmpty()) {
            return MigrationValidationResult.failure("NULL_OR_EMPTY", "expected or actual is null/empty");
        }

        if (!CisNbtUtil.hasFullBlockBaseline(actual.getChunkMetadata())) {
            return MigrationValidationResult.failure("MISSING_BASELINE", "missing full block baseline flag");
        }
        if (!CisNbtUtil.isMigratedAuthoritativeChunk(actual.getChunkMetadata())) {
            return MigrationValidationResult.failure("NOT_AUTHORITATIVE", "missing migrated authoritative marker");
        }
        final Map<Long, String> expectedNonAir = canonicalNonAirBlockMap(expected);
        final Map<Long, String> actualNonAir = canonicalNonAirBlockMap(actual);
        if (expectedNonAir.size() != actualNonAir.size()) {
            return MigrationValidationResult.failure("BLOCK_COUNT_MISMATCH",
                    "expectedNonAir=" + expectedNonAir.size() + ", actualNonAir=" + actualNonAir.size());
        }
        if (expected.getBlockEntities()
                .size() != actual.getBlockEntities()
                .size()) {
            return MigrationValidationResult.failure("BE_COUNT_MISMATCH",
                    "expected=" + expected.getBlockEntities()
                            .size() + ", actual=" + actual.getBlockEntities()
                            .size());
        }
        if (expected.countPendingEntities() != actual.countPendingEntities()) {
            return MigrationValidationResult.failure("ENTITY_COUNT_MISMATCH",
                    "expected=" + expected.countPendingEntities() + ", actual=" + actual.countPendingEntities());
        }

        if (!NbtHelper.matches(CisNbtUtil.extractPersistedStructureMetadata(expected.getChunkMetadata()),
                CisNbtUtil.extractPersistedStructureMetadata(actual.getChunkMetadata()),
                true)) {
            return MigrationValidationResult.failure("STRUCTURE_META_MISMATCH", "structure metadata differs");
        }
        if (!NbtHelper.matches(CisNbtUtil.extractPreservedAuxiliaryChunkNbt(expected.getChunkMetadata()),
                CisNbtUtil.extractPreservedAuxiliaryChunkNbt(actual.getChunkMetadata()),
                true)) {
            return MigrationValidationResult.failure("AUX_META_MISMATCH", "auxiliary metadata differs");
        }

        final MigrationValidationResult blocks = validateBlocks(expected, actual);
        if (!blocks.valid()) {
            return blocks;
        }
        final MigrationValidationResult blockEntities = validateBlockEntities(expected, actual);
        if (!blockEntities.valid()) {
            return blockEntities;
        }
        final MigrationValidationResult entities = validateEntities(expected, actual);
        if (!entities.valid()) {
            return entities;
        }
        return MigrationValidationResult.success();
    }

    /**
     * Compares canonicalized non-air block state payloads between expected and stored chunks.
     *
     * @param expected translated authoritative snapshot
     * @param actual   stored CIS payload loaded back from disk
     * @return validation result describing the first block-level mismatch class
     */
    private static MigrationValidationResult validateBlocks(final ChunkDelta<BlockState, NbtCompound> expected,
            final ChunkDelta<BlockState, NbtCompound> actual) {
        final Map<Long, String> expectedNonAir = canonicalNonAirBlockMap(expected);
        final Map<Long, String> actualNonAir = canonicalNonAirBlockMap(actual);

        if (!expectedNonAir.keySet()
                .equals(actualNonAir.keySet())) {
            return MigrationValidationResult.failure("BLOCK_POS_MISMATCH",
                    "non-air positions differ: expected=" + expectedNonAir.size() + ", actual=" + actualNonAir.size());
        }

        int mismatchCount = 0;
        for (final Map.Entry<Long, String> entry : expectedNonAir.entrySet()) {
            final long pos = entry.getKey();
            final String expKey = entry.getValue();
            final String actKey = actualNonAir.get(pos);
            if (!expKey.equals(actKey)) {
                mismatchCount++;
                if (mismatchCount <= 10) {
                    LOGGER.warn("Block mismatch at {}: expected={} actual={}", BlockPos.fromLong(pos), expKey, actKey);
                }
            }
        }
        if (mismatchCount > 10) {
            LOGGER.warn("... {} additional block mismatches suppressed", mismatchCount - 10);
        }
        if (mismatchCount > 0) {
            return MigrationValidationResult.failure("BLOCK_STATE_MISMATCH", mismatchCount + " block state mismatches");
        }
        return MigrationValidationResult.success();
    }

    /**
     * Builds a stable string key for a block state so validation compares semantic state
     * rather than palette order or object identity.
     *
     * @param state block state to canonicalize
     * @return registry id plus sorted property assignments
     */
    private static String canonicalBlockStateKey(final BlockState state) {
        if (state == null) {
            return "null";
        }
        final StringBuilder sb = new StringBuilder(Registries.BLOCK.getId(state.getBlock())
                .toString());
        final Map<Property<?>, Comparable<?>> props = new TreeMap<>(Comparator.comparing(Property::getName));
        props.putAll(state.getEntries());
        if (!props.isEmpty()) {
            sb.append('[');
            sb.append(props.entrySet()
                    .stream()
                    .map(e -> e.getKey()
                            .getName() + "=" + e.getValue())
                    .collect(Collectors.joining(",")));
            sb.append(']');
        }
        return sb.toString();
    }

    /**
     * Builds a canonical map of non-air block states keyed by local position.
     * AIR and null states are excluded because they are not authoritative
     * semantic changes for migration validation.
     */
    private static Map<Long, String> canonicalNonAirBlockMap(final ChunkDelta<BlockState, NbtCompound> delta) {
        final Map<Long, String> result = new HashMap<>();
        delta.forEachBlockInstruction((x, y, z, paletteId, state) -> {
            if (state != null && !state.isAir()) {
                result.put(BlockPos.asLong(x, y, z), canonicalBlockStateKey(state));
            }
        });
        return result;
    }

    /**
     * Compares block-entity payloads between expected and stored chunks using recursive NBT matching.
     *
     * @param expected translated authoritative snapshot
     * @param actual   stored CIS payload loaded back from disk
     * @return validation result describing any block-entity mismatch
     */
    private static MigrationValidationResult validateBlockEntities(final ChunkDelta<BlockState, NbtCompound> expected,
            final ChunkDelta<BlockState, NbtCompound> actual) {
        final Set<Long> keys = new HashSet<>();
        keys.addAll(expected.getBlockEntities()
                .keySet());
        keys.addAll(actual.getBlockEntities()
                .keySet());
        int mismatchCount = 0;
        for (final long key : keys) {
            final NbtCompound exp = expected.getBlockEntities()
                    .get(key);
            final NbtCompound act = actual.getBlockEntities()
                    .get(key);
            if (!NbtHelper.matches(exp, act, true)) {
                mismatchCount++;
                final BlockPos pos = BlockPos.fromLong(key);
                if (mismatchCount <= 10) {
                    LOGGER.warn("Block entity mismatch at {}: expected keys={} actual keys={}",
                            pos,
                            exp.getKeys(),
                            act != null ? act.getKeys() : "null");
                }
            }
        }
        if (mismatchCount > 10) {
            LOGGER.warn("... {} additional block-entity mismatches suppressed", mismatchCount - 10);
        }
        if (mismatchCount > 0) {
            return MigrationValidationResult.failure("BLOCK_ENTITY_MISMATCH",
                    mismatchCount + " block entity mismatches");
        }
        return MigrationValidationResult.success();
    }

    /**
     * Compares pending-entity lists after normalizing them to sorted string representations.
     */
    private static MigrationValidationResult validateEntities(final ChunkDelta<BlockState, NbtCompound> expected,
            final ChunkDelta<BlockState, NbtCompound> actual) {
        final List<String> expectedEntities = new ArrayList<>();
        final List<String> actualEntities = new ArrayList<>();
        expected.forEachEntity(entity -> expectedEntities.add(entity == null ? "<null>" : entity.toString()));
        actual.forEachEntity(entity -> actualEntities.add(entity == null ? "<null>" : entity.toString()));
        expectedEntities.sort(String::compareTo);
        actualEntities.sort(String::compareTo);
        if (!Objects.equals(expectedEntities, actualEntities)) {
            return MigrationValidationResult.failure("ENTITY_MISMATCH",
                    "entity list differs: expected=" + expectedEntities.size() + ", actual=" + actualEntities.size());
        }
        return MigrationValidationResult.success();
    }

    /**
     * Clears any stale CIS entry for an omitted placeholder without mutating the
     * source MCA region before its verified backup is created.
     */
    private static boolean clearOmittedEmptyChunk(final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos chunkPos) {
        final CisChunkPos cisChunkPos = new CisChunkPos(chunkPos.x, chunkPos.z);
        if (!storage.contains(cisChunkPos)) {
            return true;
        }

        return storage.save(cisChunkPos, new ChunkDelta<>());
    }

    /**
     * Opens a region file only when the target path exists.
     */
    private static RegionFile openOptionalRegionFile(final StorageKey storageKey, final Path regionPath)
            throws IOException {
        if (regionPath == null || !Files.exists(regionPath)) {
            return null;
        }
        return new RegionFile(storageKey, regionPath, regionPath.getParent(), true);
    }

    /**
     * Loads one chunk payload from an optional external entity-region file.
     */
    private static NbtCompound loadOptionalEntityChunkNbt(final RegionFile entityRegionFile, final ChunkPos chunkPos)
            throws IOException {
        if (entityRegionFile == null) {
            return null;
        }
        try (DataInputStream input = entityRegionFile.getChunkInputStream(chunkPos)) {
            return input == null ? null : NbtIo.readCompound(input, NbtSizeTracker.of(FabricNbtAdapter.MAX_NBT_SIZE));
        }
    }

    /**
     * Safely retires a real MCA region file only after verified backup.
     * 0KB placeholder files are never backed up or retired as real source.
     */
    static void retireRegionFile(final Path mcaPath) throws IOException {
        if (!Files.exists(mcaPath)) {
            LOGGER.warn("retireRegionFile called on non-existent path: {}", mcaPath);
            return;
        }
        final long size = Files.size(mcaPath);
        if (size == 0) {
            LOGGER.info("Skipping retirement of 0KB placeholder: {}", mcaPath.getFileName());
            // Delete the empty placeholder safely
            Files.deleteIfExists(mcaPath);
            return;
        }

        final Path backupPath = mcaPath.resolveSibling(mcaPath.getFileName() + ".backup");

        // Do not overwrite existing backup without explicit versioning (safety)
        if (Files.exists(backupPath)) {
            LOGGER.warn("Backup already exists, skipping retirement to avoid data loss: {}", backupPath);
            return;
        }

        // Copy first (safer than move for verification)
        Files.copy(mcaPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

        // Verify backup
        final long backupSize = Files.size(backupPath);
        if (backupSize != size) {
            // Backup failed verification - remove partial backup and abort
            Files.deleteIfExists(backupPath);
            throw new IOException(
                    "Backup verification failed for " + mcaPath + " (source=" + size + ", backup=" + backupSize + ")");
        }

        // Only after successful verified backup, remove source
        Files.delete(mcaPath);
        LOGGER.info("Verified backup created and source retired: {} -> {}",
                mcaPath.getFileName(),
                backupPath.getFileName());
    }

    /**
     * Builds a standalone CIS storage instance rooted at the supplied paths.
     */
    private static CisStorage<Block, BlockState, Property<?>, NbtCompound> buildStorage(final Path storageDir,
            final Path mappingFile) throws IOException {
        Files.createDirectories(storageDir);
        Files.createDirectories(mappingFile.getParent());

        final FabricBlockRegistryAdapter registryAdapter = new FabricBlockRegistryAdapter();
        final FabricBlockStateAdapter stateAdapter = new FabricBlockStateAdapter();
        final FabricNbtAdapter nbtAdapter = new FabricNbtAdapter();
        final PropertyPacker<Block, BlockState, Property<?>> packer = new PropertyPacker<>(stateAdapter);
        final CisMapping<Block, BlockState, Property<?>> mapping = new CisMapping<>(mappingFile,
                registryAdapter,
                stateAdapter,
                packer);
        return new CisStorage<>(storageDir, mapping, nbtAdapter, Blocks.AIR.getDefaultState());
    }

    /**
     * Immutable region coordinates parsed from a region filename.
     *
     * @param regionX region X coordinate in 32x32 chunk units
     * @param regionZ region Z coordinate in 32x32 chunk units
     */
    private record RegionCoordinates(int regionX, int regionZ) {

        /**
         * Reconstructs the vanilla MCA filename for these region coordinates.
         *
         * @return region filename in {@code r.<x>.<z>.mca} form
         */
        private String fileName() {
            return "r." + regionX + "." + regionZ + ".mca";
        }
    }

    /**
     * A bounded work item containing coordinates, a header-derived chunk count,
     * and source file bytes; it never retains decoded NBT or chunk deltas.
     *
     * @param coordinates   region coordinates
     * @param presentChunks chunks present in the source header
     * @param fileSizeBytes region plus optional entity file size
     */
    private record RegionTask(RegionCoordinates coordinates, int presentChunks, long fileSizeBytes) {

    }
}
