package io.liparakis.chunkis.integration.migration.offline;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.adapter.FabricBlockRegistryAdapter;
import io.liparakis.chunkis.adapter.FabricBlockStateAdapter;
import io.liparakis.chunkis.adapter.FabricNbtAdapter;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.core.mapping.PropertyPacker;
import io.liparakis.chunkis.integration.migration.MigrationProgressTracker;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.storage.mapping.CisMapping;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.World;
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
     * Progress slot of the current migration worker thread, set by the pool's thread
     * factory. Replaces parsing the number back out of the thread name.
     */
    private static final ThreadLocal<Integer> WORKER_NUMBER = ThreadLocal.withInitial(() -> 0);

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
        final List<RegionCoordinates> retirable = new ArrayList<>();
        MigrationProgressTracker.begin(worldId, regions.size(), totalChunks, totalBytes, workers);

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = buildStorage(storageDir, mappingFile);
        final AtomicInteger workerNumbers = new AtomicInteger();
        final ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
            final int workerNumber = workerNumbers.incrementAndGet();
            final Thread thread = new Thread(() -> {
                WORKER_NUMBER.set(workerNumber);
                runnable.run();
            }, "Chunkis-McaToCis-" + workerNumber);
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
            for (int i = 0; i < futures.size(); i++) {
                final Future<OfflineRegionMigrationReport> future = futures.get(i);
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
                if (report.canRetireSourceRegion()) {
                    retirable.add(regions.get(i)
                            .coordinates());
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

        // Sources are retired only now, after storage.close() forced every CIS region to disk.
        for (final RegionCoordinates region : retirable) {
            final boolean chunksRetired = RegionFileRetirement.retire(regionDir.resolve(region.fileName()));
            final boolean entitiesRetired = RegionFileRetirement.retire(entityDir.resolve(region.fileName()));
            if (chunksRetired && entitiesRetired) {
                retiredRegions++;
            }
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
        final int worker = WORKER_NUMBER.get();
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
                    final boolean headerPresent = regionFile.hasChunk(chunkPos);

                    try (DataInputStream input = regionFile.getChunkInputStream(chunkPos)) {
                        if (input == null) {
                            if (headerPresent) {
                                // Vanilla returns null for a header entry it cannot read; that is a failure.
                                presentChunks++;
                                failedChunks++;
                                LOGGER.error("Unreadable chunk {} in {}: header entry present but stream unavailable",
                                        chunkPos, mcaPath);
                            }
                            continue;
                        }

                        presentChunks++;
                        final NbtCompound sourceNbt = NbtIo.readCompound(input,
                                NbtSizeTracker.of(FabricNbtAdapter.MAX_NBT_SIZE));
                        if (sourceNbt == null) {
                            failedChunks++;
                            continue;
                        }

                        final ChunkDelta<BlockState, NbtCompound> delta = ChunkSnapshotBuilder.buildChunkDelta(heightLimitView,
                                palettesFactory,
                                sourceNbt,
                                loadOptionalEntityChunkNbt(entityRegionFile, chunkPos),
                                chunkPos);
                        if (ChunkSnapshotBuilder.isOmittableEmptyChunk(delta)) {
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

                        final MigrationValidationResult vr = MigrationChunkValidator.validateMigratedChunk(storage, chunkPos, delta);
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

        // Retirement happens in translateWorld after CIS storage is flushed and closed.
        final OfflineRegionMigrationReport report = new OfflineRegionMigrationReport(worldKey.getValue()
                .toString(),
                mcaPath.getFileName()
                        .toString(),
                presentChunks,
                handledChunks,
                failedChunks,
                false,
                TRANSLATOR_VERSION);
        if (!report.canRetireSourceRegion()) {
            LOGGER.warn("Chunkis pre-launch migrator: keeping {} in place because present={}, handled={}, failed={}.",
                    mcaPath.getFileName(),
                    presentChunks,
                    handledChunks,
                    failedChunks);
        }
        LOGGER.info("Finished {}. Handled {}, failed {}, coverage {}/{}.",
                mcaPath.getFileName(),
                handledChunks,
                failedChunks,
                handledChunks,
                presentChunks);
        return report;
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
