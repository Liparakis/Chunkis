package io.liparakis.chunkis.world.tracking.suppression;

import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Tracks chunk-scoped passive load suppression before a WorldChunk instance is fully live.
 */
public final class PendingChunkMutationSuppression {

    /** Backing register mapping active pending suppression context keys to records. */
    private static final ConcurrentHashMap<Key, Entry> PENDING = new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private PendingChunkMutationSuppression() {
        throw new AssertionError("Utility class");
    }

    /**
     * Begins a pending suppression scope for the target chunk and coordinates.
     *
     * @param worldKey registry key mapping levels
     * @param chunkPos coordinates pos mapping
     * @param cause    suppression context cause
     * @param source   caller identifier tag
     */
    public static void begin(
            final RegistryKey<World> worldKey, final ChunkPos chunkPos,
            final ChunkMutationTrackingScope.Cause cause, final String source) {
        begin(worldKey, chunkPos.x, chunkPos.z, cause, source);
    }

    /**
     * Begins a pending suppression scope using raw coordinate values.
     *
     * @param worldKey registry key mapping levels
     * @param chunkX   chunk X coordinate
     * @param chunkZ   chunk Z coordinate
     * @param cause    suppression context cause
     * @param source   caller identifier tag
     */
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

    /**
     * Resolves the current suppression cause for a given WorldChunk.
     *
     * @param chunk target world chunk
     * @return suppression cause active
     */
    public static ChunkMutationTrackingScope.Cause currentCause(final WorldChunk chunk) {
        final ChunkMutationTrackingScope.Cause cause = currentCause(chunk.getWorld().getRegistryKey(), chunk.getPos());
        if (cause == ChunkMutationTrackingScope.Cause.BASE_APPLY && chunk.getWorld() instanceof ServerWorld serverWorld
                && ChunkStatus.FULL.equals(chunk.getStatus()) && serverWorld.getServer() != null
                && serverWorld.getServer().getThread() == Thread.currentThread()) {
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

    /**
     * Resolves the current suppression cause for a given registry key and ChunkPos.
     *
     * @param worldKey registry key mapping levels
     * @param chunkPos coordinates pos mapping
     * @return suppression cause active
     */
    public static ChunkMutationTrackingScope.Cause currentCause(
            final RegistryKey<World> worldKey,
            final ChunkPos chunkPos) {
        return currentCause(worldKey, chunkPos.x, chunkPos.z);
    }

    /**
     * Resolves the current suppression cause using raw coordinate values.
     *
     * @param worldKey registry key mapping levels
     * @param chunkX   chunk X coordinate
     * @param chunkZ   chunk Z coordinate
     * @return suppression cause active
     */
    static ChunkMutationTrackingScope.Cause currentCause(
            final RegistryKey<World> worldKey, final int chunkX,
            final int chunkZ) {
        final Entry entry = PENDING.get(new Key(worldKey, chunkX, chunkZ));
        return entry != null ? entry.cause : ChunkMutationTrackingScope.Cause.NONE;
    }

    /**
     * Checks if a suppression trace event should be emitted for the given cause.
     *
     * @param worldKey registry key mapping levels
     * @param chunkPos coordinates pos mapping
     * @param cause    active suppression cause
     * @return true if trace should be emitted
     */
    public static boolean shouldTrace(
            final RegistryKey<World> worldKey, final ChunkPos chunkPos,
            final ChunkMutationTrackingScope.Cause cause) {
        return shouldTrace(worldKey, chunkPos.x, chunkPos.z, cause);
    }

    /**
     * Checks if a suppression trace event should be emitted using raw coordinate values.
     *
     * @param worldKey registry key mapping levels
     * @param chunkX   chunk X coordinate
     * @param chunkZ   chunk Z coordinate
     * @param cause    active suppression cause
     * @return true if trace should be emitted
     */
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

    /**
     * Ends a pending suppression scope.
     *
     * @param worldKey registry key mapping levels
     * @param chunkPos coordinates pos mapping
     */
    public static void end(final RegistryKey<World> worldKey, final ChunkPos chunkPos) {
        end(worldKey, chunkPos, "PendingChunkMutationSuppression#end");
    }

    /**
     * Ends a pending suppression scope with a custom trace source.
     *
     * @param worldKey registry key mapping levels
     * @param chunkPos coordinates pos mapping
     * @param source   caller identifier tag
     */
    public static void end(final RegistryKey<World> worldKey, final ChunkPos chunkPos, final String source) {
        end(worldKey, chunkPos.x, chunkPos.z, source);
    }

    /**
     * Ends a pending suppression scope using raw coordinate values.
     *
     * @param worldKey registry key mapping levels
     * @param chunkX   chunk X coordinate
     * @param chunkZ   chunk Z coordinate
     * @param source   caller identifier tag
     */
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

    /**
     * Resolves the matching trace reason for the active suppression cause.
     *
     * @param cause active suppression cause
     * @return trace reason
     */
    private static ChunkTraceReason reasonForCause(final ChunkMutationTrackingScope.Cause cause) {
        return switch (cause) {
            case PASSIVE_LOAD -> ChunkTraceReason.PASSIVE_VANILLA_LOAD;
            case BASE_APPLY -> ChunkTraceReason.INTERNAL_RESTORE_BASE_APPLY;
            case RESTORE -> ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE;
            case NONE -> ChunkTraceReason.NONE;
        };
    }

    /**
     * Emits a lifecycle trace event.
     *
     * @param eventType event type mapping
     * @param reason    reason detail
     * @param source    caller identifier tag
     * @param message   description detail text
     * @param worldKey  owning world reference
     * @param chunkX    coordinate X
     * @param chunkZ    coordinate Z
     */
    private static void traceLifecycle(
            final ChunkTraceEventType eventType, final ChunkTraceReason reason,
            final String source, final String message, final RegistryKey<World> worldKey,
            final int chunkX, final int chunkZ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE, eventType, ChunkTraceSeverity.INFO, reason, source,
                message, worldKey.getValue().toString(), new DebugChunkKey(chunkX, chunkZ), null, null, null, null
                              );
    }

    /**
     * Record mapping coordinate bounds inside dimensions.
     *
     * @param worldKey registry key mapping levels
     * @param chunkX   chunk X coordinate
     * @param chunkZ   chunk Z coordinate
     */
    private record Key(RegistryKey<World> worldKey, int chunkX, int chunkZ) {

        /**
         * Constructor.
         *
         * @param worldKey registry key mapping levels
         * @param chunkX   chunk X coordinate
         * @param chunkZ   chunk Z coordinate
         */
        private Key {
            Objects.requireNonNull(worldKey, "worldKey");
        }
    }

    /**
     * Wrapper tracking pending active suppression values.
     */
    private static final class Entry {

        /** Suppression cause context. */
        private final ChunkMutationTrackingScope.Cause cause;

        /** True if suppression context has been traced. */
        private volatile boolean traced;

        /**
         * Constructor.
         *
         * @param cause active suppression cause
         */
        private Entry(final ChunkMutationTrackingScope.Cause cause) {
            this.cause = cause;
        }
    }
}
