package io.liparakis.chunkis.mixin.world.server;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.world.entity.capture.ChunkEntityNbtCapture;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureUtil;
import io.liparakis.chunkis.world.tracking.ownership.OwnedDeltaAccess;
import io.liparakis.chunkis.world.tracking.ownership.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.TickContainerAccess;
import io.liparakis.chunkis.world.tracking.state.LoadedChunks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.liparakis.chunkis.world.tracking.settle.GenerationSettling;
import io.liparakis.chunkis.world.tracking.settle.GenerationTickDuck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin intercepting ServerWorld actions, scheduling entity replays and captures.
 */
@Mixin(ServerLevel.class)
public abstract class ServerWorldMixin {

    /**
     * Runs a due block tick, as generation settling when generation left it behind.
     *
     * @param pos      tick position
     * @param block    ticked block
     * @param original vanilla tick
     */
    @WrapMethod(method = "tickBlock")
    private void chunkis$tickBlock(final BlockPos pos, final Block block, final Operation<Void> original) {
        final LevelChunk chunk = chunkis$tickedChunk(pos);
        if (chunk != null && chunkis$takeGenerationTick(chunk.getBlockTicks(), pos)) {
            GenerationSettling.run(true, () -> original.call(pos, block));
        } else {
            original.call(pos, block);
        }
    }

    /**
     * Runs a due fluid tick, as generation settling when generation left it behind.
     *
     * @param pos      tick position
     * @param fluid    ticked fluid
     * @param original vanilla tick
     */
    @WrapMethod(method = "tickFluid")
    private void chunkis$tickFluid(final BlockPos pos, final Fluid fluid, final Operation<Void> original) {
        final LevelChunk chunk = chunkis$tickedChunk(pos);
        if (chunk != null && chunkis$takeGenerationTick(chunk.getFluidTicks(), pos)) {
            GenerationSettling.run(true, () -> original.call(pos, fluid));
        } else {
            original.call(pos, fluid);
        }
    }

    /**
     * Returns the loaded chunk a tick runs in, without loading one.
     *
     * @param pos tick position
     * @return the chunk, or {@code null}
     */
    @Unique
    private LevelChunk chunkis$tickedChunk(final BlockPos pos) {
        return ((ServerLevel) (Object) this).getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Takes whether a due tick is a generation tick.
     *
     * @param scheduler the chunk's scheduler for the tick's kind
     * @param pos       tick position
     * @return {@code true} for a generation tick
     */
    @Unique
    private static boolean chunkis$takeGenerationTick(final TickContainerAccess<?> scheduler, final BlockPos pos) {
        return scheduler instanceof GenerationTickDuck duck
                && GenerationSettling.takeGenerationTick(duck, pos.asLong());
    }

    /**
     * Trace source identification tag label.
     */
    @Unique
    private static final String SOURCE = "ServerWorldMixin#chunkis$afterSpawnEntity";

    /**
     * Injected at the return of spawnEntity to register spawned entities into the chunk delta.
     *
     * @param entity target entity spawned
     * @param cir    callback info returnable wrapper
     */
    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void chunkis$afterSpawnEntity(
            final Entity entity,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()
                || entity == null
                || entity instanceof Player
                || !entity.isAlive()) {
            return;
        }

        final ServerLevel world = (ServerLevel) (Object) this;
        final ChunkPos chunkPos = entity.chunkPosition();
        if (PendingChunkMutationSuppression.currentCause(world.dimension(), chunkPos)
                != ChunkMutationTrackingScope.Cause.NONE) {
            return;
        }
        final LevelChunk chunk = LoadedChunks.getIfAccessible(world, chunkPos.x(), chunkPos.z());
        if (chunk instanceof ChunkisMutationGuardDuck guardDuck
                && guardDuck.chunkis$getMutationTrackingScope()
                .currentCause()
                != ChunkMutationTrackingScope.Cause.NONE) {
            return;
        }
        if (!(chunk instanceof ChunkisDeltaDuck)) {
            ChunkOwnershipTraceHelper.traceDecision(
                    world.dimension(),
                    chunkPos,
                    "BYPASSED",
                    ChunkTraceReason.CHUNK_NOT_DELTA_CAPABLE,
                    SOURCE,
                    null,
                    null
            );
            return;
        }

        final ChunkDelta<BlockState, CompoundTag> delta = OwnedDeltaAccess.getOrClaim(chunk,
                ChunkTraceReason.PLAYER_OR_COMMAND_EDIT, SOURCE);
        BaseChunkCaptureUtil.captureBaseChunkIfMissing(world, chunk, delta);

        final CompoundTag entityNbt = ChunkEntityNbtCapture.serializeEntityNbt(entity);
        if (entityNbt == null || entityNbt.isEmpty()) {
            return;
        }

        final String uuid = entity.getStringUUID();
        delta.removeEntitiesMatching(nbt -> EntityPayloadNbt.hasUuid(nbt, uuid));
        delta.putEntity(entity.getId(), entityNbt);
        // Entities never flag the chunk themselves; without this, autosave skips an already-saved chunk.
        chunk.markUnsaved();
        GlobalChunkTracker.markDirty(chunk, SOURCE);
    }
}
