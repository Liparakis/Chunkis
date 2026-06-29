package io.liparakis.chunkis.debug.watch;

import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks watched entity transfer and reload assertions across chunk unload/load boundaries.
 *
 * <p>This state is specific to payload watch diagnostics and intentionally lives
 * outside PayloadWatchTracer so the tracer can focus on trace entry
 * points rather than long-lived entity watch bookkeeping.</p>
 */
public final class EntityWatchTracker {

    private static final long ENTITY_RELOAD_TIMEOUT_TICKS = 40L;
    private static final ConcurrentHashMap<EntityWatchKey, EntityWatchState> ENTITY_WATCH_STATE =
            new ConcurrentHashMap<>();
    private static final AtomicLong CURRENT_SERVER_TICK = new AtomicLong();

    private EntityWatchTracker() {
        throw new AssertionError("Utility class");
    }

    public static void tickAssertions() {
        final long currentServerTick = CURRENT_SERVER_TICK.incrementAndGet();
        for (final Map.Entry<EntityWatchKey, EntityWatchState> entry : ENTITY_WATCH_STATE.entrySet()) {
            final EntityWatchState state = entry.getValue();
            if (!state.unloadedWithoutReload
                    || state.asserted
                    || !state.hasKnownChunk
                    || currentServerTick - state.unloadTick < ENTITY_RELOAD_TIMEOUT_TICKS) {
                continue;
            }
            state.asserted = true;
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.WATCHED_ENTITY_UNLOADED_NOT_RELOADED,
                    "PayloadWatchTracer#tickEntityReloadAssertions",
                    "watched entity was unloaded from the live server manager and no entity-manager-load or "
                            + "entity-tracking-start followed within " + ENTITY_RELOAD_TIMEOUT_TICKS + " ticks",
                    entry.getKey().worldId,
                    new DebugChunkKey(state.lastKnownChunkX, state.lastKnownChunkZ),
                    null,
                    null,
                    null,
                    null,
                    PayloadWatchTarget.entity(entry.getKey().worldId, entry.getKey().entityUuid),
                    "entity-unload-timeout",
                    "entity@" + entry.getKey().entityUuid + " world=" + entry.getKey().worldId
            );
        }
    }

    public static void markTransfer(
            final PayloadWatchTarget target,
            final String stage,
            final ChunkPos chunkPos
    ) {
        final EntityWatchState state = ENTITY_WATCH_STATE.computeIfAbsent(
                new EntityWatchKey(target.worldId(), target.entityUuid()),
                ignored -> new EntityWatchState()
        );
        state.lastKnownChunkX = chunkPos.x;
        state.lastKnownChunkZ = chunkPos.z;
        state.hasKnownChunk = true;
        if ("entity-manager-unload".equals(stage)) {
            state.unloadedWithoutReload = true;
            state.unloadTick = CURRENT_SERVER_TICK.get();
            return;
        }
        if ("entity-manager-load".equals(stage)) {
            state.unloadedWithoutReload = false;
            state.asserted = false;
        }
    }

    public static void clearPendingReload(final PayloadWatchTarget target) {
        final EntityWatchState state = ENTITY_WATCH_STATE.get(
                new EntityWatchKey(target.worldId(), target.entityUuid())
        );
        if (state != null) {
            state.unloadedWithoutReload = false;
            state.asserted = false;
        }
    }

    public static void rememberChunk(final PayloadWatchTarget target, final ChunkPos chunkPos) {
        final EntityWatchState state = ENTITY_WATCH_STATE.computeIfAbsent(
                new EntityWatchKey(target.worldId(), target.entityUuid()),
                ignored -> new EntityWatchState()
        );
        state.lastKnownChunkX = chunkPos.x;
        state.lastKnownChunkZ = chunkPos.z;
        state.hasKnownChunk = true;
    }

    public static boolean shouldTraceForChunk(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target
    ) {
        final EntityWatchState state = ENTITY_WATCH_STATE.get(
                new EntityWatchKey(worldId, target.entityUuid())
        );
        return state == null
                || !state.hasKnownChunk
                || (state.lastKnownChunkX == chunkPos.x && state.lastKnownChunkZ == chunkPos.z);
    }

    public static void assertReloadedAfterUnload(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId,
            @Nullable final Entity liveEntity
    ) {
        final EntityWatchState state = ENTITY_WATCH_STATE.get(
                new EntityWatchKey(worldId, target.entityUuid())
        );
        if (state == null || !state.unloadedWithoutReload || liveEntity != null || state.asserted) {
            return;
        }
        state.asserted = true;
        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.WATCHED_ENTITY_UNLOADED_NOT_RELOADED,
                "PayloadWatchTracer#assertEntityReloadedAfterUnload",
                "watched entity was unloaded from the live server manager and chunk became sendable again without "
                        + "entity-manager-load or entity-tracking-start",
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                operationId,
                null,
                null,
                target,
                "chunk-full-entity",
                target.describe()
        );
    }

    private record EntityWatchKey(String worldId, String entityUuid) {
    }

    private static final class EntityWatchState {
        private volatile boolean unloadedWithoutReload;
        private volatile boolean asserted;
        private volatile long unloadTick;
        private volatile int lastKnownChunkX;
        private volatile int lastKnownChunkZ;
        private volatile boolean hasKnownChunk;
    }
}
