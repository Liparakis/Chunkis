package io.liparakis.chunkis.world.tracking.ownership;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.chunk.WorldChunk;

/**
 * The one place that turns a chunk's attached delta into a Chunkis-owned one.
 *
 * <p>Every mutation hook (block, block entity, entity spawn, entity save) needs the
 * same step: fetch the chunk's delta, create it if absent, and claim ownership if
 * unclaimed, tracing the decision. Keeping it here makes the ownership policy a
 * single decision instead of a copy in each hook.</p>
 */
public final class OwnedDeltaAccess {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private OwnedDeltaAccess() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns the chunk's delta, creating it when absent and claiming ownership when
     * unclaimed.
     *
     * @param chunk  chunk whose delta is needed; must implement {@link ChunkisDeltaDuck}
     * @param reason ownership reason recorded on a new claim
     * @param source caller label recorded on a new claim and in traces
     * @return the owned delta attached to {@code chunk}
     */
    @SuppressWarnings("unchecked")
    public static ChunkDelta<BlockState, NbtCompound> getOrClaim(final WorldChunk chunk,
            final ChunkTraceReason reason, final String source) {
        final ChunkisDeltaDuck duck = (ChunkisDeltaDuck) chunk;
        ChunkDelta<BlockState, NbtCompound> delta = (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta();
        if (delta == null) {
            delta = new ChunkDelta<>(BlockState::isAir);
            duck.chunkis$setDelta(delta);
        }
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            delta.claimOwnership(reason.name(), source);
            ChunkOwnershipTraceHelper.traceDecision(
                    chunk.getWorld()
                            .getRegistryKey(),
                    chunk.getPos(),
                    "CLAIMED",
                    reason,
                    source,
                    delta,
                    PendingChunkMutationSuppression.currentCause(chunk)
            );
        }
        return delta;
    }
}
