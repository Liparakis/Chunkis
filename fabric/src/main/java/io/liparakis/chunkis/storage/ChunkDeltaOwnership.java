package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.ChunkDelta;

/**
 * Classifies whether a delta represents actual Chunkis-owned chunk state or is
 * only an untouched placeholder attached to a live chunk instance.
 */
public final class ChunkDeltaOwnership {

    private ChunkDeltaOwnership() {
        throw new AssertionError("Utility class");
    }

    public static boolean hasChunkisOwnedState(final ChunkDelta<?, ?> delta) {
        return delta != null && (hasReplayPayload(delta) || hasChunkisPersistenceAnchor(delta));
    }

    public static boolean shouldMirrorVanillaDirtyState(final ChunkDelta<?, ?> delta) {
        return delta != null && (delta.isDirty() || hasChunkisOwnedState(delta));
    }

    public static boolean hasChunkisPersistenceAnchor(final ChunkDelta<?, ?> delta) {
        if (delta == null) {
            return false;
        }
        final Object metadata = delta.getChunkMetadata();
        return CisNbtUtil.hasPersistedBaseChunkNbt(metadata)
                || CisNbtUtil.hasFullBlockBaseline(metadata);
    }

    public static boolean hasReplayPayload(final ChunkDelta<?, ?> delta) {
        return delta != null
                && (!delta.getBlockInstructions().isEmpty()
                || !delta.getBlockEntities().isEmpty()
                || delta.countNonNullEntities() > 0);
    }
}
