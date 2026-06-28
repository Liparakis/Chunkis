package io.liparakis.chunkis.world.restoration.capture;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import net.minecraft.world.chunk.WorldChunk;

public final class SnapshotSafetyChecker {

    private SnapshotSafetyChecker() {
        throw new AssertionError("Utility class");
    }

    public static boolean isSnapshotUnsafe(final WorldChunk chunk) {
        if (PendingChunkMutationSuppression.currentCause(chunk)
                != ChunkMutationTrackingScope.Cause.NONE) {
            return true;
        }
        if (chunk instanceof ChunkisMutationGuardDuck guardDuck
                && guardDuck.chunkis$getMutationTrackingScope().currentCause()
                != ChunkMutationTrackingScope.Cause.NONE) {
            return true;
        }
        return chunk instanceof ChunkisDeltaDuck deltaDuck
                && deltaDuck.chunkis$getRestoreOperationId() != null;
    }
}

