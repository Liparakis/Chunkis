package io.liparakis.chunkis.world.restoration.capture;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Checks safety constraints before taking snapshots of live world chunks.
 */
public final class SnapshotSafetyChecker {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private SnapshotSafetyChecker() {
        throw new AssertionError("Utility class");
    }

    /**
     * Evaluates if taking a snapshot of a chunk is unsafe (e.g. during active restores or suppressed tracking).
     *
     * @param chunk target world chunk
     * @return true if taking snapshot is unsafe
     */
    public static boolean isSnapshotUnsafe(final WorldChunk chunk) {
        if (PendingChunkMutationSuppression.currentCause(chunk)
                != ChunkMutationTrackingScope.Cause.NONE) {
            return true;
        }
        if (chunk instanceof ChunkisMutationGuardDuck guardDuck
                && guardDuck.chunkis$getMutationTrackingScope()
                .currentCause()
                != ChunkMutationTrackingScope.Cause.NONE) {
            return true;
        }
        return chunk instanceof ChunkisDeltaDuck deltaDuck
                && deltaDuck.chunkis$getRestoreOperationId() != null;
    }
}
