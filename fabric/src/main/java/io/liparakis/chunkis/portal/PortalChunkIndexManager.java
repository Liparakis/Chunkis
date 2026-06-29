package io.liparakis.chunkis.portal;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Dimension-local index of chunks that currently contain nether portal blocks.
 *
 * <p>Chunkis cannot rely on vanilla's POI index alone because POI discovery may
 * happen before a destination chunk is loaded and restored. This index gives the
 * portal lookup path a cheap, persisted set of candidate chunks to load first.</p>
 */
public final class PortalChunkIndexManager {

    /**
     * Index key label mapped in NBT structure metadata.
     */
    private static final String PORTAL_CHUNKS_KEY = "portal_chunks";

    /**
     * Concurrent mapping of world dimensions to their local chunk indices.
     */
    private static final ConcurrentHashMap<net.minecraft.registry.RegistryKey<World>, PortalChunkIndex> INDICES =
            new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private PortalChunkIndexManager() {
        throw new AssertionError("Utility class");
    }

    /**
     * Inspects a WorldChunk and updates index entries accordingly.
     *
     * @param world server world reference
     * @param chunk world chunk reference to analyze
     */
    public static void updateChunk(final ServerWorld world, final WorldChunk chunk) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(chunk, "chunk");

        updateChunk(world, chunk.getPos(), chunkHasPortalBlocks(chunk));
    }

    /**
     * Updates index entries at target chunk coordinates.
     *
     * @param world           server world reference
     * @param pos             chunk coordinates position
     * @param hasPortalBlocks true if chunk has portal blocks
     */
    public static void updateChunk(
            final ServerWorld world,
            final ChunkPos pos,
            final boolean hasPortalBlocks
    ) {
        getIndex(world).update(pos, hasPortalBlocks);
    }

    /**
     * Resolves the set of chunk coordinates containing portal blocks within bounding limits.
     *
     * @param world     server world reference
     * @param minChunkX minimum X coordinate
     * @param maxChunkX maximum X coordinate
     * @param minChunkZ minimum Z coordinate
     * @param maxChunkZ maximum Z coordinate
     * @return set of ChunkPos containing portals
     */
    public static Set<ChunkPos> getPortalChunksInRange(
            final ServerWorld world,
            final int minChunkX,
            final int maxChunkX,
            final int minChunkZ,
            final int maxChunkZ
    ) {
        return getIndex(world).getPortalChunksInRange(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
    }

    /**
     * Flushes and closes target dimension index manager.
     *
     * @param world server world reference, may be null
     */
    public static void close(final ServerWorld world) {
        if (world == null) {
            return;
        }

        final PortalChunkIndex index = INDICES.remove(world.getRegistryKey());
        if (index != null) {
            index.close();
        }
    }

    /**
     * Closes all active manager indices.
     */
    public static void clear() {
        INDICES.values()
                .forEach(PortalChunkIndex::close);
        INDICES.clear();
    }

    /**
     * Resolves the dimension index helper.
     *
     * @param world server world reference
     * @return resolved PortalChunkIndex helper
     */
    private static PortalChunkIndex getIndex(final ServerWorld world) {
        return INDICES.computeIfAbsent(
                world.getRegistryKey(),
                ignored -> new PortalChunkIndex(resolveIndexPath(world))
        );
    }

    /**
     * Resolves the local path mapping index files on disk.
     *
     * @param world server world reference
     * @return resolved Path string
     */
    private static Path resolveIndexPath(final ServerWorld world) {
        return ChunkisStoragePaths.computePortalIndexFile(
                Objects.requireNonNull(world.getServer())
                        .getSavePath(WorldSavePath.ROOT),
                world.getRegistryKey()
        );
    }

    /**
     * Evaluates if any blocks inside a WorldChunk matches portal block states.
     *
     * @param chunk world chunk reference to analyze
     * @return true if portals were found
     */
    private static boolean chunkHasPortalBlocks(final WorldChunk chunk) {
        final boolean[] found = {false};
        final java.util.function.Predicate<BlockState> portalPredicate = state -> state.isOf(Blocks.NETHER_PORTAL);

        chunk.forEachBlockMatchingPredicate(portalPredicate, (pos, state) -> found[0] = true);
        return found[0];
    }

    /**
     * Local class index instance tracking coordinates containing portal blocks.
     */
    private static final class PortalChunkIndex {

        /**
         * The file path where index values are serialized.
         */
        private final Path path;

        /**
         * Set containing tracked chunk long values.
         */
        private final LongOpenHashSet portalChunks;

        /**
         * True if index is dirty.
         */
        private boolean dirty;

        /**
         * Constructor.
         *
         * @param path index file path to map
         */
        private PortalChunkIndex(final Path path) {
            this.path = Objects.requireNonNull(path, "path");
            this.portalChunks = load(path);
            this.dirty = false;
        }

        /**
         * Loads chunk indices long records from index file.
         *
         * @param path target index file path
         * @return set containing resolved long indices
         */
        private static LongOpenHashSet load(final Path path) {
            final LongOpenHashSet chunks = new LongOpenHashSet();

            if (!Files.isRegularFile(path)) {
                return chunks;
            }

            try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
                final NbtCompound root = NbtIo.readCompound(input);
                root.getLongArray(PORTAL_CHUNKS_KEY)
                        .ifPresent(values -> {
                            for (final long value : values) {
                                chunks.add(value);
                            }
                        });
            } catch (final Exception e) {
                Chunkis.LOGGER.warn("Chunkis: Failed to load portal index {}", path, e);
            }

            return chunks;
        }

        /**
         * Updates coordinates presence within local tracking indices.
         *
         * @param pos             chunk coordinates position
         * @param hasPortalBlocks true if portal blocks are present
         */
        synchronized void update(final ChunkPos pos, final boolean hasPortalBlocks) {
            final long key = pos.toLong();
            final boolean changed = hasPortalBlocks
                    ? portalChunks.add(key)
                    : portalChunks.remove(key);

            if (!changed) {
                return;
            }

            dirty = true;
            save();
        }

        /**
         * Resolves the list of coordinate positions matching range constraints.
         *
         * @param minChunkX minimum X coordinate
         * @param maxChunkX maximum X coordinate
         * @param minChunkZ minimum Z coordinate
         * @param maxChunkZ maximum Z coordinate
         * @return set containing resolved ChunkPos matches
         */
        synchronized Set<ChunkPos> getPortalChunksInRange(
                final int minChunkX,
                final int maxChunkX,
                final int minChunkZ,
                final int maxChunkZ
        ) {
            final Set<ChunkPos> chunks = new LinkedHashSet<>();

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    final long key = ChunkPos.toLong(chunkX, chunkZ);
                    if (portalChunks.contains(key)) {
                        chunks.add(new ChunkPos(chunkX, chunkZ));
                    }
                }
            }

            return chunks;
        }

        /**
         * Closes index saving values if dirty.
         */
        synchronized void close() {
            if (dirty) {
                save();
            }
        }

        /**
         * Saves index long array coordinates back to NBT file structure.
         */
        private void save() {
            try {
                Files.createDirectories(path.getParent());

                final NbtCompound root = new NbtCompound();
                root.putLongArray(PORTAL_CHUNKS_KEY, portalChunks.toLongArray());

                try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
                    NbtIo.writeCompound(root, output);
                }

                dirty = false;
            } catch (final IOException e) {
                Chunkis.LOGGER.error("Chunkis: Failed to save portal index {}", path, e);
            }
        }
    }
}
