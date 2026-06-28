package io.liparakis.chunkis.world.tracking.suppression;

import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
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
            final RegistryKey<World> worldKey, final ChunkPos chunkPos,
            final ChunkMutationTrackingScope.Cause cause, final String source) {
        begin(worldKey, chunkPos.x, chunkPos.z, cause, source);
    }

    static void begin(
            final RegistryKey<World> worldKey, final int chunkX, final int chunkZ,
            final ChunkMutationTrackingScope.Cause cause, final String source) {
        if (cause == ChunkMutationTrackingScope.Cause.NONE) {
            return;
        }
        PENDING.put(new Key(worldKey, chunkX, chunkZ), new Entry(cause));
        traceLifecycle(
                ChunkTraceEventType.SUPPRESSION_CONTEXT_STARTED, reasonForCause(cause), source, "began pending" +
                        " suppression cause=" + cause.name().toLowerCase(), worldKey, chunkX, chunkZ
        );
    }

    public static ChunkMutationTrackingScope.Cause currentCause(final WorldChunk chunk) {
        final ChunkMutationTrackingScope.Cause cause = currentCause(chunk.getWorld().getRegistryKey(), chunk.getPos());
        if (cause == ChunkMutationTrackingScope.Cause.BASE_APPLY && chunk.getWorld() instanceof ServerWorld serverWorld && ChunkStatus.FULL.equals(chunk.getStatus()) && serverWorld.getServer() != null && serverWorld.getServer().getThread() == Thread.currentThread()) {
            end(chunk.getWorld().getRegistryKey(), chunk.getPos());
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS, ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR, ChunkTraceReason.SUPPRESSION_CONTEXT_LEAK,
                    "PendingChunkMutationSuppression#currentCause", "cleared leaked base-apply suppression on live " +
                            "chunk" + " status=" + chunk.getStatus() + " thread=" + Thread.currentThread().getName(),
                    chunk.getWorld().getRegistryKey().getValue().toString(), new DebugChunkKey(
                            chunk.getPos().x,
                            chunk.getPos().z
                    ), null, null, null, null
            );
            return ChunkMutationTrackingScope.Cause.NONE;
        }
        return cause;
    }

    public static ChunkMutationTrackingScope.Cause currentCause(
            final RegistryKey<World> worldKey,
            final ChunkPos chunkPos) {
        return currentCause(worldKey, chunkPos.x, chunkPos.z);
    }

    static ChunkMutationTrackingScope.Cause currentCause(
            final RegistryKey<World> worldKey, final int chunkX,
            final int chunkZ) {
        final Entry entry = PENDING.get(new Key(worldKey, chunkX, chunkZ));
        return entry != null ? entry.cause : ChunkMutationTrackingScope.Cause.NONE;
    }

    public static boolean shouldTrace(
            final RegistryKey<World> worldKey, final ChunkPos chunkPos,
            final ChunkMutationTrackingScope.Cause cause) {
        return shouldTrace(worldKey, chunkPos.x, chunkPos.z, cause);
    }

    static boolean shouldTrace(
            final RegistryKey<World> worldKey, final int chunkX, final int chunkZ,
            final ChunkMutationTrackingScope.Cause cause) {
        final Entry entry = PENDING.get(new Key(worldKey, chunkX, chunkZ));
        if (entry == null || entry.cause != cause || entry.traced) {
            return false;
        }
        entry.traced = true;
        return true;
    }

    public static void end(final RegistryKey<World> worldKey, final ChunkPos chunkPos) {
        end(worldKey, chunkPos, "PendingChunkMutationSuppression#end");
    }

    public static void end(final RegistryKey<World> worldKey, final ChunkPos chunkPos, final String source) {
        end(worldKey, chunkPos.x, chunkPos.z, source);
    }

    static void end(final RegistryKey<World> worldKey, final int chunkX, final int chunkZ, final String source) {
        final Entry removed = PENDING.remove(new Key(worldKey, chunkX, chunkZ));
        if (removed == null) {
            return;
        }
        traceLifecycle(
                ChunkTraceEventType.SUPPRESSION_CONTEXT_ENDED, reasonForCause(removed.cause), source, "ended " +
                        "pending suppression cause=" + removed.cause.name().toLowerCase(), worldKey, chunkX, chunkZ
        );
    }

    private static ChunkTraceReason reasonForCause(final ChunkMutationTrackingScope.Cause cause) {
        return switch (cause) {
            case PASSIVE_LOAD -> ChunkTraceReason.PASSIVE_VANILLA_LOAD;
            case BASE_APPLY -> ChunkTraceReason.INTERNAL_RESTORE_BASE_APPLY;
            case RESTORE -> ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE;
            case NONE -> ChunkTraceReason.NONE;
        };
    }

    private static void traceLifecycle(
            final ChunkTraceEventType eventType, final ChunkTraceReason reason,
            final String source, final String message, final RegistryKey<World> worldKey,
            final int chunkX, final int chunkZ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE, eventType, ChunkTraceSeverity.INFO, reason, source,
                message, worldKey.getValue().toString(), new DebugChunkKey(chunkX, chunkZ), null, null, null, null
        );
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
