package io.liparakis.chunkis.debug;

import io.liparakis.chunkis.core.ChunkDelta;

public final class ChunkTraceInvariants {

    private ChunkTraceInvariants() {
        throw new AssertionError("Utility class");
    }

    public static boolean shouldAssertNonEmptyRestore(
            final ChunkDelta<?, ?> delta,
            final int appliedCount
    ) {
        if (delta == null || appliedCount != 0) {
            return false;
        }

        return !delta.getBlockInstructions().isEmpty() || !delta.getBlockEntities().isEmpty();
    }
}
