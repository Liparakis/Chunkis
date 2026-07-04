package io.liparakis.chunkis.mixin.world.chunk;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import java.util.Objects;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for the base {@link Chunk} class to provide {@link ChunkDelta}
 * capability to all chunk types.
 *
 * <p>Implements {@link ChunkisDeltaDuck} to attach a per-chunk delta, and
 * overrides {@code needsSaving()} so that a chunk is always considered dirty
 * when its delta has unsaved changes - even if vanilla would report it clean.</p>
 */
@Mixin(Chunk.class)
public abstract class CommonChunkMixin implements ChunkisDeltaDuck {

    /**
     * The attached Chunkis delta state.
     */
    @Unique
    private volatile ChunkDelta<?, ?> chunkis$delta;

    /**
     * The active restore session operation ID.
     */
    @Unique
    private volatile String chunkis$restoreOperationId;

    /**
     * True if the active restore session loaded state from storage.
     */
    @Unique
    private volatile boolean chunkis$restoreLoadedFromStorage;

    /**
     * Default constructor for CommonChunkMixin.
     */
    public CommonChunkMixin() {
        throw new AssertionError("Utility class");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChunkDelta<?, ?> chunkis$getDelta() {
        return chunkis$delta;
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if {@code delta} is {@code null}
     */
    @Override
    public void chunkis$setDelta(final ChunkDelta<?, ?> delta) {
        this.chunkis$delta = Objects.requireNonNull(delta, "ChunkDelta cannot be null");
        notifyTrackerIfWorldChunk();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String chunkis$getRestoreOperationId() {
        return chunkis$restoreOperationId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void chunkis$setRestoreOperationId(final String operationId) {
        this.chunkis$restoreOperationId = operationId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean chunkis$wasRestoreLoadedFromStorage() {
        return chunkis$restoreLoadedFromStorage;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void chunkis$setRestoreLoadedFromStorage(final boolean restoreLoadedFromStorage) {
        this.chunkis$restoreLoadedFromStorage = restoreLoadedFromStorage;
    }

    /**
     * Injected at the return of {@code needsSaving()} to override the result
     * when the Chunkis delta is dirty, even if vanilla considers the chunk clean.
     *
     * @param cir the returnable callback; return value is overridden to
     *            {@code true} when the delta reports dirty
     */
    @Inject(method = "needsSaving", at = @At("RETURN"), cancellable = true)
    private void chunkis$onNeedsSaving(final CallbackInfoReturnable<Boolean> cir) {
        if (shouldOverrideSavingFlag(cir.getReturnValueZ())) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Injected at the head of {@code markNeedsSaving()} to keep the Chunkis delta
     * and {@link GlobalChunkTracker} in sync when vanilla marks a chunk dirty.
     *
     * @param ci the Mixin {@link CallbackInfo}; unused but required by the
     *           injection contract
     */
    @Inject(method = "markNeedsSaving", at = @At("HEAD"))
    private void chunkis$onMarkNeedsSaving(final CallbackInfo ci) {
        if ((Object) this instanceof ChunkisMutationGuardDuck guardDuck
                && guardDuck.chunkis$getMutationTrackingScope()
                .currentCause()
                != ChunkMutationTrackingScope.Cause.NONE) {
            return;
        }
        if ((Object) this instanceof WorldChunk worldChunk) {
            PendingChunkMutationSuppression.currentCause(worldChunk);
        }
        if (this.chunkis$delta == null) {
            return;
        }
        if (!ChunkDeltaOwnership.shouldMirrorVanillaDirtyState(this.chunkis$delta)) {
            return;
        }

        if (!this.chunkis$delta.markDirtyIfClean("CommonChunkMixin#chunkis$onMarkNeedsSaving")) {
            return;
        }

        notifyTrackerIfWorldChunk();
    }

    /**
     * Returns {@code true} if the saving flag should be overridden to {@code true}.
     * <p>
     * This is the case when vanilla reports the chunk as clean but the Chunkis
     * delta has unsaved changes.
     *
     * @param currentlySaving the value vanilla {@code needsSaving()} returned
     * @return {@code true} if the return value should be overridden
     */
    @Unique
    private boolean shouldOverrideSavingFlag(final boolean currentlySaving) {
        return !currentlySaving && chunkis$delta != null && chunkis$delta.isDirty();
    }

    /**
     * Notifies {@link GlobalChunkTracker} if this chunk instance is a
     * {@link WorldChunk}.
     *
     * <p>The {@code instanceof} pattern match is used rather than a cast
     * on {@code this} directly, because at the {@link Chunk} mixin level
     * {@code this} may be any {@link Chunk} subtype.</p>
     */
    @Unique
    private void notifyTrackerIfWorldChunk() {
        if ((Object) this instanceof WorldChunk worldChunk) {
            GlobalChunkTracker.markDirty(worldChunk);
        }
    }
}