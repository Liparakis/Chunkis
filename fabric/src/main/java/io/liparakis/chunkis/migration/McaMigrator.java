package io.liparakis.chunkis.migration;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.PalettesFactory;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;
import org.slf4j.Logger;

/**
 * Migration engine converting legacy vanilla Minecraft region MCA files into Chunkis CIS files.
 */
public final class McaMigrator {

    /**
     * Logger instance for writing migration logs.
     */
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Pattern regex matching vanilla region file names.
     */
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /**
     * Side sizing dimensions of single region files.
     */
    private static final int REGION_SIZE = 32;

    /**
     * Private constructor to prevent utility class instantiation.
     */
    private McaMigrator() {
    }

    /**
     * Migrates vanilla region files (*.mca) in the world's region directory to Chunkis CIS region files.
     *
     * @param world server world dimension to migrate
     */
    public static void migrateWorld(final ServerWorld world) {
        final Path regionDir = resolveMcaRegionDir(world);
        LOGGER.info("Checking MCA region directory for world {}: {}",
                world.getRegistryKey()
                        .getValue(), regionDir);

        if (!Files.exists(regionDir)) {
            LOGGER.info("No MCA region directory found at {}; skipping migration.", regionDir);
            return;
        }

        // Collect all matching region file paths up-front so we can submit them
        // to a thread pool without holding a DirectoryStream open across I/O.
        final List<int[]> regions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            for (final Path path : stream) {
                final Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName()
                        .toString());
                if (matcher.matches()) {
                    regions.add(new int[]{
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2))
                    });
                }
            }
        } catch (final IOException e) {
            LOGGER.error("Failed to iterate region directory for dimension {}",
                    world.getRegistryKey()
                            .getValue(), e);
            return;
        }

        if (regions.isEmpty()) {
            return;
        }

        final CisStorage<?, ?, ?, ?> storage = FabricCisStorageHelper.getStorage(world);
        int totalMigrated = 0;
        MigrationProgressTracker.begin(world, regions.size());
        try {
            for (int i = 0; i < regions.size(); i++) {
                final int[] coords = regions.get(i);
                final int rx = coords[0];
                final int rz = coords[1];
                final Path mcaPath = regionDir.resolve("r." + rx + "." + rz + ".mca");
                MigrationProgressTracker.region(world, mcaPath.getFileName().toString(), i + 1, regions.size());
                totalMigrated += migrateRegionFile(world, storage, mcaPath, rx, rz);
            }
        } finally {
            MigrationProgressTracker.finish(world);
        }

        LOGGER.info("Chunkis MCA Migration complete for world {}. Converted {} chunks total.",
                world.getRegistryKey()
                        .getValue(), totalMigrated);
    }

    /**
     * Resolves the {@code region/} directory for the given world dimension.
     *
     * @param world the world dimension to inspect
     * @return absolute path to the dimension's vanilla region directory
     */
    private static Path resolveMcaRegionDir(final ServerWorld world) {
        final Path root = Objects.requireNonNull(world.getServer())
                .getSavePath(WorldSavePath.ROOT);
        return ChunkisStoragePaths.computeVanillaRegionDirectory(root, world.getRegistryKey());
    }

    /**
     * Migrates a single MCA region file into CIS format.
     *
     * @param world   server world dimension
     * @param storage Chunkis storage manager instance
     * @param mcaPath path to vanilla MCA file
     * @param rx      region X coordinate
     * @param rz      region Z coordinate
     * @return count of migrated chunks
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int migrateRegionFile(
            final ServerWorld world,
            final CisStorage storage, // raw type contained to this method
            final Path mcaPath,
            final int rx, final int rz) {

        int migrated = 0;
        int failed = 0;
        final StorageKey storageKey = new StorageKey("chunk", world.getRegistryKey(), "chunk");
        LOGGER.info("Chunkis migrator: converting {} to CIS...", mcaPath.getFileName());

        try (RegionFile regionFile = new RegionFile(
                storageKey, mcaPath, mcaPath.getParent(), true)) {

            // Single mutable BlockPos reused across every block in every chunk
            // to avoid allocating up to 16*16*384 = ~98 000 short-lived objects
            // per region file.
            final BlockPos.Mutable mutablePos = new BlockPos.Mutable();
            final PalettesFactory palettesFactory = PalettesFactory.fromRegistryManager(world.getRegistryManager());

            for (int x = 0; x < REGION_SIZE; x++) {
                for (int z = 0; z < REGION_SIZE; z++) {

                    final ChunkPos globalPos = new ChunkPos((rx << 5) + x, (rz << 5) + z);

                    try (DataInputStream in = regionFile.getChunkInputStream(globalPos)) {
                        if (in == null) {
                            continue;
                        }

                        final NbtCompound nbt = NbtIo.readCompound(in, NbtSizeTracker.ofUnlimitedBytes());
                        if (nbt == null) {
                            continue;
                        }

                        // PointOfInterestStorage is not thread-safe: synchronize
                        // only this call so its internal Long2ObjectOpenHashMap
                        // is never mutated by two threads at once.
                        final ProtoChunk proto;
                        synchronized (world.getPointOfInterestStorage()) {
                            proto = Objects.requireNonNull(SerializedChunk.fromNbt(
                                            world,
                                            palettesFactory,
                                            nbt)
                                    )
                                    .convert(
                                            world,
                                            world.getPointOfInterestStorage(),
                                            storageKey,
                                            globalPos
                                    );
                        }

                        final ChunkDelta<BlockState, NbtCompound> delta = buildChunkDelta(proto,
                                nbt,
                                globalPos,
                                mutablePos);

                        if (!delta.isEmpty()) {
                            if (storage.save(FabricCisStorageHelper.toStoragePos(globalPos), delta)) {
                                migrated++;
                            } else {
                                failed++;
                                LOGGER.error("Failed to save migrated chunk {} from {}",
                                        globalPos, mcaPath.getFileName());
                            }
                        }
                    } catch (final Exception e) {
                        failed++;
                        LOGGER.error("Failed to migrate chunk {} in {}",
                                globalPos, mcaPath.getFileName(), e);
                    }
                }
            }

        } catch (final Exception e) {
            LOGGER.error("Failed to read region file {}", mcaPath.getFileName(), e);
            failed++;
        }

        if (failed == 0) {
            backupRegionFile(mcaPath);
        } else {
            LOGGER.warn(
                    "Chunkis migrator: keeping {} in place because {} chunk(s) failed migration. "
                            + "This region is not safe to retire yet.",
                    mcaPath.getFileName(),
                    failed
            );
        }

        LOGGER.info("Finished {}. Converted {} chunks, failed {}.", mcaPath.getFileName(), migrated, failed);
        return migrated;
    }

    /**
     * Builds a {@link ChunkDelta} from a deserialized {@link ProtoChunk}.
     *
     * <p>Extracted so the hot path (block iteration) is clearly isolated and the
     * mutable {@link BlockPos} lifetime is obvious.</p>
     *
     * @param proto      the deserialized proto chunk
     * @param sourceNbt  raw source chunk NBT compound
     * @param globalPos  coordinates of the chunk
     * @param mutablePos mutable block pos reused to avoid allocations
     * @return constructed ChunkDelta container
     */
    private static ChunkDelta<BlockState, NbtCompound> buildChunkDelta(
            final ProtoChunk proto,
            final NbtCompound sourceNbt,
            final ChunkPos globalPos,
            final BlockPos.Mutable mutablePos) {

        final ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>();
        final NbtCompound structureData = CisNbtUtil.extractStructureData(sourceNbt);
        delta.setSuppressInitialRepopulation(true);
        delta.setChunkMetadata(createMigratedChunkMetadata(structureData), false);

        final int startX = globalPos.getStartX();
        final int startZ = globalPos.getStartZ();
        final int bottomY = proto.getBottomY();
        final int topY = bottomY + proto.getHeight();

        // --- Block states ---
        // Reuse mutablePos to avoid allocating ~98 000 BlockPos objects per chunk column.
        for (int by = bottomY; by < topY; by++) {
            for (int bx = 0; bx < 16; bx++) {
                for (int bz = 0; bz < 16; bz++) {
                    mutablePos.set(startX + bx, by, startZ + bz);
                    final BlockState state = proto.getBlockState(mutablePos);
                    if (state != null && !state.isAir()) {
                        delta.addBlockChange(bx, by, bz, state);
                    }
                }
            }
        }

        // --- Block entities ---
        // Use entrySet() to avoid a second map lookup per key.
        final Map<BlockPos, NbtCompound> beNbts = proto.getBlockEntityNbts();
        for (final Map.Entry<BlockPos, NbtCompound> entry : beNbts.entrySet()) {
            final NbtCompound beNbt = entry.getValue();
            if (beNbt != null) {
                final BlockPos bePos = entry.getKey();
                delta.addBlockEntityData(bePos.getX(), bePos.getY(), bePos.getZ(), beNbt);
            }
        }

        // --- Entities ---
        for (final NbtCompound entityNbt : proto.getEntities()) {
            delta.addPendingEntity(entityNbt);
        }

        return delta;
    }

    static NbtCompound createMigratedChunkMetadata(final NbtCompound structureData) {
        return CisNbtUtil.createChunkMetadataTakingOwnership(structureData, true, true);
    }

    /**
     * Renames the original MCA file to indicate it was backed up after migration.
     *
     * @param mcaPath path to vanilla MCA file
     */
    private static void backupRegionFile(final Path mcaPath) {
        final Path backupPath = mcaPath.resolveSibling(mcaPath.getFileName() + ".backup");
        try {
            Files.move(mcaPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Backed up {} -> {}", mcaPath.getFileName(), backupPath.getFileName());
        } catch (final IOException e) {
            LOGGER.error("Failed to back up {}", mcaPath.getFileName(), e);
        }
    }
}
