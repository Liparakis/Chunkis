package io.liparakis.chunkis.world.restoration.capture;

import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import net.minecraft.server.world.ServerWorld;

import java.util.Map;

/**
 * Compatibility shim for the removed deferred base-capture queue.
 *
 * <p>The codebase no longer has any path that enqueues deferred base captures.
 * Keeping the public surface avoids churn in shutdown, debug, and test code
 * while making the actual behavior explicit: there is nothing queued, so every
 * operation is a no-op and every snapshot is empty.</p>
 *
 * @author Liparakis
 * @version 1.1
 */
public final class BaseChunkCaptureScheduler {

    /**
     * Utility class, not instantiable.
     */
    private BaseChunkCaptureScheduler() {
        throw new AssertionError("Utility class");
    }

    /**
     * No-op compatibility entry point for the removed deferred base-capture tick.
     *
     * @param world the world whose scheduler should be ticked
     */
    public static void tick(final ServerWorld world) {
    }

    /**
     * No-op compatibility entry point for shutdown paths.
     *
     * @param world the world being closed; {@code null} is a no-op
     */
    public static void flushAndClose(final ServerWorld world) {
    }

    /**
     * No-op compatibility entry point for global teardown.
     */
    public static void clear() {
    }

    /**
     * Returns an empty snapshot because deferred base capture queueing is absent.
     *
     * @param world world whose queue should be inspected; {@code null} returns an empty map
     * @return queued captures keyed by chunk position
     */
    public static Map<DebugChunkKey, QueuedCaptureSnapshot> snapshot(final ServerWorld world) {
        return Map.of();
    }

    /**
     * Diagnostic snapshot for one queued base capture.
     *
     * @param chunkKey queued chunk coordinate
     * @param dirtyState whether the queued chunk's delta was dirty when sampled
     */
    public record QueuedCaptureSnapshot(
            DebugChunkKey chunkKey,
            boolean dirtyState
    ) {
    }
}
