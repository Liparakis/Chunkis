package io.liparakis.chunkis.world;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks chunk-scoped passive load suppression before a WorldChunk instance is fully live.
 */
public final class PendingChunkMutationSuppression {

    private static final ConcurrentHashMap<Key, Entry> PENDING = new ConcurrentHashMap<>();

    private PendingChunkMutationSuppression() {
        throw new AssertionError("Utility class");
    }

    public static void begin(
            final RegistryKey<World> worldKey,
            final ChunkPos chunkPos,
            final ChunkMutationTrackingScope.Cause cause
    ) {
        if (cause == ChunkMutationTrackingScope.Cause.NONE) {
            return;
        }
        PENDING.put(new Key(worldKey, chunkPos.x, chunkPos.z), new Entry(cause));
    }

    public static ChunkMutationTrackingScope.Cause currentCause(final WorldChunk chunk) {
        return currentCause(chunk.getWorld().getRegistryKey(), chunk.getPos());
    }

    public static ChunkMutationTrackingScope.Cause currentCause(
            final RegistryKey<World> worldKey,
            final ChunkPos chunkPos
    ) {
        final Entry entry = PENDING.get(new Key(worldKey, chunkPos.x, chunkPos.z));
        return entry != null ? entry.cause : ChunkMutationTrackingScope.Cause.NONE;
    }

    public static boolean shouldTrace(
            final RegistryKey<World> worldKey,
            final ChunkPos chunkPos,
            final ChunkMutationTrackingScope.Cause cause
    ) {
        final Entry entry = PENDING.get(new Key(worldKey, chunkPos.x, chunkPos.z));
        if (entry == null || entry.cause != cause || entry.traced) {
            return false;
        }
        entry.traced = true;
        return true;
    }

    public static void end(final RegistryKey<World> worldKey, final ChunkPos chunkPos) {
        PENDING.remove(new Key(worldKey, chunkPos.x, chunkPos.z));
    }

    private record Key(RegistryKey<World> worldKey, int chunkX, int chunkZ) {
        private Key {
            Objects.requireNonNull(worldKey, "worldKey");
        }
    }

    private static final class Entry {
        private final ChunkMutationTrackingScope.Cause cause;
        private volatile boolean traced;

        private Entry(final ChunkMutationTrackingScope.Cause cause) {
            this.cause = cause;
        }
    }
}
