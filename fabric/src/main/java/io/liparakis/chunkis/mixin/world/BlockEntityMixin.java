package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.ChunkTraceReason;

import io.liparakis.chunkis.storage.BaseChunkCaptureUtil;
import io.liparakis.chunkis.storage.ChunkDeltaOwnership;
import io.liparakis.chunkis.storage.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.world.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.ChunkBlockEntityCapture;
import io.liparakis.chunkis.world.GlobalChunkTracker;
import io.liparakis.chunkis.world.PendingChunkMutationSuppression;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link BlockEntity} that intercepts {@code markDirty()} calls to
 * integrate with the Chunkis delta-tracking and global chunk-tracking systems.
 *
 * <p>When a block entity marks itself dirty on a {@link ServerWorld}, this mixin:</p>
 * <ol>
 *   <li>Resolves the {@link WorldChunk} containing the block entity.</li>
 *   <li>If the chunk implements {@link ChunkisDeltaDuck}, traces the pre-dirty
 *       state, captures base chunk data if missing, marks the delta and chunk dirty,
 *       and proactively captures the block entity's current NBT state.</li>
 *   <li>Unconditionally notifies {@link GlobalChunkTracker} that the chunk is dirty.</li>
 * </ol>
 *
 * <p><strong>Chunk lifecycle safety:</strong> No chunk references, world references,
 * or registry lookups are retained beyond the scope of the inject method. All
 * operations are synchronous and execute on the server tick thread.</p>
 *
 * @author Liparakis
 * @version 1.3
 *
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {


    @Shadow
    protected World world;

    @Shadow
    public abstract BlockPos getPos();

    /**
     * Injected at the head of {@link BlockEntity#markDirty()} to trigger Chunkis
     * delta and global dirty tracking.
     *
     * <p>Guards are applied via early returns to keep nesting flat. The chunk
     * reference is resolved fresh from the world on each invocation and is never
     * retained beyond this call.</p>
     *
     * @param ci the Mixin {@link CallbackInfo}; unused but required by the
     *           injection contract
     */
    @Inject(method = "markDirty()V", at = @At("HEAD"))
    private void chunkis$onMarkDirty(final CallbackInfo ci) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        final WorldChunk chunk = serverWorld.getWorldChunk(getPos());
        if (chunk == null) {
            return;
        }
        if (chunk instanceof ChunkisMutationGuardDuck guardDuck
                && guardDuck.chunkis$getMutationTrackingScope().currentCause() != ChunkMutationTrackingScope.Cause.NONE) {
            return;
        }
        if (PendingChunkMutationSuppression.currentCause(chunk) != ChunkMutationTrackingScope.Cause.NONE) {
            return;
        }
        handleChunkDelta(chunk, serverWorld);
        GlobalChunkTracker.markDirty(chunk);
    }

    /**
     * Handles Chunkis delta tracking for {@code chunk} if it implements
     * {@link ChunkisDeltaDuck}.
     *
     * <p>In order: traces pre-dirty state, captures a missing base chunk,
     * marks the delta and chunk dirty, then proactively captures the current
     * block entity NBT state.</p>
     *
     * <p>Returns immediately without side effects if the chunk does not
     * implement {@link ChunkisDeltaDuck}.</p>
     *
     * @param chunk       the chunk containing this block entity; non-null
     * @param serverWorld the world the chunk belongs to; used for registry
     *                    manager access and base capture
     */
    @Unique
    @SuppressWarnings("unchecked")
    private void handleChunkDelta(final WorldChunk chunk, final ServerWorld serverWorld) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return;
        }
        ChunkDelta<BlockState, NbtCompound> delta =
                (ChunkDelta<BlockState, NbtCompound>) deltaDuck.chunkis$getDelta();
        if (delta == null) {
            delta = new ChunkDelta<>(BlockState::isAir);
            deltaDuck.chunkis$setDelta(delta);
        }
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            ChunkOwnershipTraceHelper.claimOwnership(
                    delta,
                    ChunkTraceReason.PLAYER_OR_COMMAND_EDIT,
                    "BlockEntityMixin#handleChunkDelta"
            );
            ChunkOwnershipTraceHelper.traceDecision(
                    chunk.getWorld().getRegistryKey(),
                    chunk.getPos(),
                    "CLAIMED",
                    ChunkTraceReason.PLAYER_OR_COMMAND_EDIT,
                    "BlockEntityMixin#handleChunkDelta",
                    delta,
                    PendingChunkMutationSuppression.currentCause(chunk)
            );
        }

        BaseChunkCaptureUtil.captureBaseChunk(serverWorld, chunk, delta);
        delta.markDirty("BlockEntityMixin#handleChunkDelta");
        chunk.markNeedsSaving();
        captureBlockEntityNbt(serverWorld, delta);
    }

    /**
     * Proactively captures the current NBT state of this block entity into
     * {@code delta}.
     *
     * <p>The self-cast {@code (BlockEntity)(Object) this} is the standard Mixin
     * pattern for referring to the target class from inside a mixin. The
     * {@code delta} parameter is already the typed {@code ChunkDelta<BlockState,
     * NbtCompound>} after the cast in {@link #handleChunkDelta}, so no additional
     * suppression is needed here.</p>
     *
     * <p>Failures are logged as errors and swallowed to avoid disrupting the
     * vanilla {@code markDirty()} call that triggered this inject.</p>
     *
     * @param serverWorld the world whose registry manager is used for NBT
     *                    serialisation; never retained beyond this call
     * @param delta       the typed delta to capture the block entity into
     */
    @Unique
    @SuppressWarnings("ConstantConditions")
    private void captureBlockEntityNbt(
            final ServerWorld serverWorld,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        try {
            ChunkBlockEntityCapture.captureBlockEntity(
                    (BlockEntity) (Object) this,
                    serverWorld.getRegistryManager(),
                    delta
            );
        } catch (final Exception e) {
            Chunkis.LOGGER.error(
                    "Chunkis: Failed to proactively capture block entity NBT at {}",
                    getPos(), e
            );
        }
    }
}
