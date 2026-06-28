package io.liparakis.chunkis.migration;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.storage.io.CisStorage;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class McaMigrator {

    private static final Logger LOGGER = Chunkis.LOGGER;
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int REGION_SIZE = 32;

    // Leave one logical core free for the server thread.
    private static final int MIGRATION_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    private McaMigrator() {
    }

    public static void migrateWorld(ServerWorld world) {
        Path regionDir = resolveMcaRegionDir(world);
        LOGGER.info("Checking MCA region directory for world {}: {}",
                world.getRegistryKey().getValue(), regionDir);

        if (!Files.exists(regionDir)) {
            LOGGER.info("No MCA region directory found at {}; skipping migration.", regionDir);
            return;
        }

        // Collect all matching region file paths up-front so we can submit them
        // to a thread pool without holding a DirectoryStream open across I/O.
        List<int[]> regions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            for (Path path : stream) {
                Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    regions.add(new int[]{
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2))
                    });
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to iterate region directory for dimension {}",
                    world.getRegistryKey().getValue(), e);
            return;
        }

        if (regions.isEmpty()) {
            return;
        }

        // Parallel migration is safe: RegionFile.read/write are synchronized,
        // the region-file cache is guarded by a ReadWriteLock, CisMapping uses
        // RW-locking internally, and compression state is ThreadLocal.
        CisStorage<?, ?, ?, ?> storage = FabricCisStorageHelper.getStorage(world);
        AtomicInteger totalMigrated = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(MIGRATION_THREADS);

        try {
            List<Future<?>> futures = new ArrayList<>(regions.size());
            for (int[] coords : regions) {
                int rx = coords[0], rz = coords[1];
                Path mcaPath = regionDir.resolve(
                        "r." + rx + "." + rz + ".mca");
                futures.add(executor.submit(() -> {
                    int count = migrateRegionFile(world, storage, mcaPath, rx, rz);
                    totalMigrated.addAndGet(count);
                }));
            }
            // Wait for all migrations to finish and surface any exceptions.
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    LOGGER.error("A region migration task failed", e);
                }
            }
        } finally {
            executor.shutdown();
        }

        int migrated = totalMigrated.get();
        LOGGER.info("Chunkis MCA Migration complete for world {}. Converted {} chunks total.",
                world.getRegistryKey().getValue(), migrated);
    }

    /**
     * Resolves the {@code region/} directory for the given world dimension.
     */
    private static Path resolveMcaRegionDir(ServerWorld world) {
        Path root = Objects.requireNonNull(world.getServer()).getSavePath(WorldSavePath.ROOT);
        return ChunkisStoragePaths.computeVanillaRegionDirectory(root, world.getRegistryKey());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int migrateRegionFile(
            ServerWorld world,
            CisStorage storage, // raw type contained to this method
            Path mcaPath,
            int rx, int rz) {

        int migrated = 0;
        StorageKey storageKey = new StorageKey("chunk", world.getRegistryKey(), "chunk");
        LOGGER.info("Chunkis migrator: converting {} to CIS...", mcaPath.getFileName());

        try (RegionFile regionFile = new RegionFile(
                storageKey, mcaPath, mcaPath.getParent(), true)) {

            // Single mutable BlockPos reused across every block in every chunk
            // to avoid allocating up to 16*16*384 = ~98 000 short-lived objects
            // per region file.
            BlockPos.Mutable mutablePos = new BlockPos.Mutable();
            PalettesFactory palettesFactory = PalettesFactory.fromRegistryManager(world.getRegistryManager());

            for (int x = 0; x < REGION_SIZE; x++) {
                for (int z = 0; z < REGION_SIZE; z++) {

                    ChunkPos globalPos = new ChunkPos((rx << 5) + x, (rz << 5) + z);

                    try (DataInputStream in = regionFile.getChunkInputStream(globalPos)) {
                        if (in == null)
                            continue;

                        NbtCompound nbt = NbtIo.readCompound(in, NbtSizeTracker.ofUnlimitedBytes());
                        if (nbt == null)
                            continue;

                        // PointOfInterestStorage is not thread-safe: synchronize
                        // only this call so its internal Long2ObjectOpenHashMap
                        // is never mutated by two threads at once.
                        ProtoChunk proto;
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

                        ChunkDelta<BlockState, NbtCompound> delta = buildChunkDelta(proto, nbt, globalPos, mutablePos);

                        if (!delta.isEmpty()) {
                            storage.save(new CisChunkPos(globalPos.x, globalPos.z), delta);
                            migrated++;
                        }
                    } catch (Exception e) {
                        LOGGER.error("Failed to migrate chunk {} in {}",
                                globalPos, mcaPath.getFileName(), e);
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("Failed to read region file {}", mcaPath.getFileName(), e);
        }

        backupRegionFile(mcaPath);

        LOGGER.info("Finished {}. Converted {} chunks.", mcaPath.getFileName(), migrated);
        return migrated;
    }

    /**
     * Builds a {@link ChunkDelta} from a deserialized {@link ProtoChunk}.
     *
     * <p>
     * Extracted so the hot path (block iteration) is clearly isolated and
     * the
     * mutable {@link BlockPos} lifetime is obvious.
     */
    private static ChunkDelta<BlockState, NbtCompound> buildChunkDelta(
            ProtoChunk proto,
            NbtCompound sourceNbt,
            ChunkPos globalPos,
            BlockPos.Mutable mutablePos) {

        ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>();
        NbtCompound structureData = CisNbtUtil.extractStructureData(sourceNbt);
        delta.setSuppressInitialRepopulation(true);
        delta.setChunkMetadata(CisNbtUtil.createChunkMetadata(structureData, true), false);

        int startX = globalPos.getStartX();
        int startZ = globalPos.getStartZ();
        int bottomY = proto.getBottomY();
        int topY = bottomY + proto.getHeight();

        // --- Block states ---
        // Reuse mutablePos to avoid allocating ~98 000 BlockPos objects per chunk
        // column.
        for (int by = bottomY; by < topY; by++) {
            //
            for (int bx = 0; bx < 16; bx++) {
                for (int bz = 0; bz < 16; bz++) {
                    mutablePos.set(startX + bx, by, startZ + bz);
                    BlockState state = proto.getBlockState(mutablePos);
                    if (state != null && !state.isAir()) {
                        delta.addBlockChange(bx, by, bz, state);
                    }
                }
            }
        }

        // --- Block entities ---
        // Use entrySet() to avoid a second map lookup per key.
        Map<BlockPos, NbtCompound> beNbts = proto.getBlockEntityNbts();
        for (Map.Entry<BlockPos, NbtCompound> entry : beNbts.entrySet()) {
            NbtCompound beNbt = entry.getValue();
            if (beNbt != null) {
                BlockPos bePos = entry.getKey();
                delta.addBlockEntityData(bePos.getX(), bePos.getY(), bePos.getZ(), beNbt);
            }
        }

        // --- Entities ---
        for (NbtCompound entityNbt : proto.getEntities()) {
            delta.addPendingEntity(entityNbt);
        }

        return delta;
    }

    private static void backupRegionFile(Path mcaPath) {
        Path backupPath = mcaPath.resolveSibling(mcaPath.getFileName() + ".backup");
        try {
            Files.move(mcaPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Backed up {} → {}", mcaPath.getFileName(), backupPath.getFileName());
        } catch (IOException e) {
            LOGGER.error("Failed to back up {}", mcaPath.getFileName(), e);
        }
    }
}



