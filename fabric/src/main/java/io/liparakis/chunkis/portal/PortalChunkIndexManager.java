package io.liparakis.chunkis.portal;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dimension-local index of chunks that currently contain nether portal blocks.
 *
 * <p>Chunkis cannot rely on vanilla's POI index alone because POI discovery may
 * happen before a destination chunk is loaded and restored. This index gives the
 * portal lookup path a cheap, persisted set of candidate chunks to load first.</p>
 *
 * @author Liparakis
 * @version 1.2
 *
 */
public final class PortalChunkIndexManager {

    private static final String PORTAL_CHUNKS_KEY = "portal_chunks";
    private static final ConcurrentHashMap<net.minecraft.registry.RegistryKey<World>, PortalChunkIndex> INDICES =
            new ConcurrentHashMap<>();

    private PortalChunkIndexManager() {
        throw new AssertionError("Utility class");
    }

    public static void updateChunk(final ServerWorld world, final WorldChunk chunk) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(chunk, "chunk");

        updateChunk(world, chunk.getPos(), chunkHasPortalBlocks(chunk));
    }

    public static void updateChunk(
            final ServerWorld world,
            final ChunkPos pos,
            final boolean hasPortalBlocks
    ) {
        getIndex(world).update(pos, hasPortalBlocks);
    }

    public static Set<ChunkPos> getPortalChunksInRange(
            final ServerWorld world,
            final int minChunkX,
            final int maxChunkX,
            final int minChunkZ,
            final int maxChunkZ
    ) {
        return getIndex(world).getPortalChunksInRange(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
    }

    public static void close(final ServerWorld world) {
        if (world == null) {
            return;
        }

        final PortalChunkIndex index = INDICES.remove(world.getRegistryKey());
        if (index != null) {
            index.close();
        }
    }

    public static void clear() {
        INDICES.values().forEach(PortalChunkIndex::close);
        INDICES.clear();
    }

    private static PortalChunkIndex getIndex(final ServerWorld world) {
        return INDICES.computeIfAbsent(
                world.getRegistryKey(),
                ignored -> new PortalChunkIndex(resolveIndexPath(world))
        );
    }

    private static Path resolveIndexPath(final ServerWorld world) {
        return ChunkisStoragePaths.computePortalIndexFile(
                Objects.requireNonNull(world.getServer()).getSavePath(WorldSavePath.ROOT),
                world.getRegistryKey()
        );
    }

    private static boolean chunkHasPortalBlocks(final WorldChunk chunk) {
        final boolean[] found = { false };
        final java.util.function.Predicate<BlockState> portalPredicate = state -> state.isOf(Blocks.NETHER_PORTAL);

        chunk.forEachBlockMatchingPredicate(portalPredicate, (pos, state) -> found[0] = true);
        return found[0];
    }

    private static final class PortalChunkIndex {
        private final Path path;
        private final LongOpenHashSet portalChunks;
        private boolean dirty;

        private PortalChunkIndex(final Path path) {
            this.path = Objects.requireNonNull(path, "path");
            this.portalChunks = load(path);
            this.dirty = false;
        }

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

        synchronized void close() {
            if (dirty) {
                save();
            }
        }

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

        private static LongOpenHashSet load(final Path path) {
            final LongOpenHashSet chunks = new LongOpenHashSet();

            if (!Files.isRegularFile(path)) {
                return chunks;
            }

            try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
                final NbtCompound root = NbtIo.readCompound(input);
                root.getLongArray(PORTAL_CHUNKS_KEY).ifPresent(values -> {
                    for (final long value : values) {
                        chunks.add(value);
                    }
                });
            } catch (final Exception e) {
                Chunkis.LOGGER.warn("Chunkis: Failed to load portal index {}", path, e);
            }

            return chunks;
        }
    }
}



