package io.liparakis.chunkis.world.tracking.ownership;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;

/**
 * Classifies whether a delta represents actual Chunkis-owned chunk state or is
 * only an untouched placeholder attached to a live chunk instance.
 */
public final class ChunkDeltaOwnership {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkDeltaOwnership() {
        throw new AssertionError("Utility class");
    }

    /**
     * Checks if the delta has Chunkis-owned state.
     *
     * @param delta chunk delta to inspect
     * @return true if Chunkis owns this state
     */
    public static boolean hasChunkisOwnedState(final ChunkDelta<?, ?> delta) {
        return delta != null && delta.hasOwnershipClaim();
    }

    /**
     * Evaluates if we should mirror the vanilla dirty state.
     *
     * @param delta chunk delta to inspect
     * @return true if mirroring should occur
     */
    public static boolean shouldMirrorVanillaDirtyState(final ChunkDelta<?, ?> delta) {
        return hasChunkisOwnedState(delta);
    }

    /**
     * Checks if the delta has a Chunkis persistence anchor.
     *
     * @param delta chunk delta to inspect
     * @return true if a persistence anchor is found
     */
    public static boolean hasChunkisPersistenceAnchor(final ChunkDelta<?, ?> delta) {
        if (delta == null) {
            return false;
        }
        return hasChunkisPersistenceAnchorBaseOnly(delta.getChunkMetadata())
                || hasChunkisFullBaselineOnly(delta.getChunkMetadata());
    }

    /**
     * Checks if metadata contains a base chunk NBT only.
     *
     * @param metadata chunk metadata object
     * @return true if base chunk compound is present
     */
    public static boolean hasChunkisPersistenceAnchorBaseOnly(final Object metadata) {
        return CisNbtUtil.hasPersistedBaseChunkNbt(metadata);
    }

    /**
     * Checks if metadata contains full block baseline flags.
     *
     * @param metadata chunk metadata object
     * @return true if full baseline is configured
     */
    public static boolean hasChunkisFullBaselineOnly(final Object metadata) {
        return CisNbtUtil.hasFullBlockBaseline(metadata);
    }

    /**
     * Checks if the delta contains replayable instructions or payloads.
     *
     * @param delta chunk delta to inspect
     * @return true if replay payloads are present
     */
    public static boolean hasReplayPayload(final ChunkDelta<?, ?> delta) {
        return delta != null
                && (!delta.getBlockInstructions()
                .isEmpty()
                || !delta.getBlockEntities()
                .isEmpty()
                || delta.countNonNullEntities() > 0);
    }

    /**
     * Checks if the chunk delta represents restorable Chunkis state.
     *
     * @param delta chunk delta to inspect
     * @return true if restorable Chunkis state is available
     */
    public static boolean hasRestorableChunkisState(final ChunkDelta<?, ?> delta) {
        return (hasReplayPayload(delta) || hasChunkisPersistenceAnchor(delta));
    }
}
