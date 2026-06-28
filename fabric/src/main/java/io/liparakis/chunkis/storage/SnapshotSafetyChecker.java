package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.world.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.PendingChunkMutationSuppression;
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
