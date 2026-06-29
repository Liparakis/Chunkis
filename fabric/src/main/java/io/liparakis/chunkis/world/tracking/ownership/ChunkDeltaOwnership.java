package io.liparakis.chunkis.world.tracking.ownership;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;

/**
 * Classifies whether a delta represents actual Chunkis-owned chunk state or is
 * only an untouched placeholder attached to a live chunk instance.
 */
public final class ChunkDeltaOwnership {

    private ChunkDeltaOwnership() {
        throw new AssertionError("Utility class");
    }

    public static boolean hasChunkisOwnedState(final ChunkDelta<?, ?> delta) {
        return delta != null && delta.hasOwnershipClaim();
    }

    public static boolean shouldMirrorVanillaDirtyState(final ChunkDelta<?, ?> delta) {
        return hasChunkisOwnedState(delta);
    }

    public static boolean hasChunkisPersistenceAnchor(final ChunkDelta<?, ?> delta) {
        if (delta == null) {
            return false;
        }
        return hasChunkisPersistenceAnchorBaseOnly(delta.getChunkMetadata())
                || hasChunkisFullBaselineOnly(delta.getChunkMetadata());
    }

    public static boolean hasChunkisPersistenceAnchorBaseOnly(final Object metadata) {
        return CisNbtUtil.hasPersistedBaseChunkNbt(metadata);
    }

    public static boolean hasChunkisFullBaselineOnly(final Object metadata) {
        return CisNbtUtil.hasFullBlockBaseline(metadata);
    }

    public static boolean hasReplayPayload(final ChunkDelta<?, ?> delta) {
        return delta != null
                && (!delta.getBlockInstructions()
                .isEmpty()
                || !delta.getBlockEntities()
                .isEmpty()
                || delta.countNonNullEntities() > 0);
    }

    public static boolean hasRestorableChunkisState(final ChunkDelta<?, ?> delta) {
        return (hasReplayPayload(delta) || hasChunkisPersistenceAnchor(delta));
    }
}


