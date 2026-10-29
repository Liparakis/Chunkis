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
import io.liparakis.chunkis.world.tracking.state.LoadedChunks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.liparakis.chunkis.world.tracking.settle.GenerationSettling;
import io.liparakis.chunkis.world.tracking.settle.GenerationTickDuck;
import net.minecraft.block.Block;
import net.minecraft.fluid.Fluid;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.tick.BasicTickScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin intercepting ServerWorld actions, scheduling entity replays and captures.
 */
@Mixin(ServerWorld.class)
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
        final WorldChunk chunk = chunkis$tickedChunk(pos);
        if (chunk != null && chunkis$takeGenerationTick(chunk.getBlockTickScheduler(), pos)) {
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
        final WorldChunk chunk = chunkis$tickedChunk(pos);
        if (chunk != null && chunkis$takeGenerationTick(chunk.getFluidTickScheduler(), pos)) {
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
    private WorldChunk chunkis$tickedChunk(final BlockPos pos) {
        return ((ServerWorld) (Object) this).getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Takes whether a due tick is a generation tick.
     *
     * @param scheduler the chunk's scheduler for the tick's kind
     * @param pos       tick position
     * @return {@code true} for a generation tick
     */
    @Unique
    private static boolean chunkis$takeGenerationTick(final BasicTickScheduler<?> scheduler, final BlockPos pos) {
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
    @Inject(method = "spawnEntity", at = @At("RETURN"))
    private void chunkis$afterSpawnEntity(
            final Entity entity,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()
                || entity == null
                || entity instanceof PlayerEntity
                || !entity.isAlive()) {
            return;
        }

        final ServerWorld world = (ServerWorld) (Object) this;
        final ChunkPos chunkPos = entity.getChunkPos();
        if (PendingChunkMutationSuppression.currentCause(world.getRegistryKey(), chunkPos)
                != ChunkMutationTrackingScope.Cause.NONE) {
            return;
        }
        final WorldChunk chunk = LoadedChunks.getIfAccessible(world, chunkPos.x, chunkPos.z);
        if (chunk instanceof ChunkisMutationGuardDuck guardDuck
                && guardDuck.chunkis$getMutationTrackingScope()
                .currentCause()
                != ChunkMutationTrackingScope.Cause.NONE) {
            return;
        }
        if (!(chunk instanceof ChunkisDeltaDuck)) {
            ChunkOwnershipTraceHelper.traceDecision(
                    world.getRegistryKey(),
                    chunkPos,
                    "BYPASSED",
                    ChunkTraceReason.CHUNK_NOT_DELTA_CAPABLE,
                    SOURCE,
                    null,
                    null
            );
            return;
        }

        final ChunkDelta<BlockState, NbtCompound> delta = OwnedDeltaAccess.getOrClaim(chunk,
                ChunkTraceReason.PLAYER_OR_COMMAND_EDIT, SOURCE);
        BaseChunkCaptureUtil.captureBaseChunkIfMissing(world, chunk, delta);

        final NbtCompound entityNbt = ChunkEntityNbtCapture.serializeEntityNbt(entity);
        if (entityNbt == null || entityNbt.isEmpty()) {
            return;
        }

        final String uuid = entity.getUuidAsString();
        delta.removeEntitiesMatching(nbt -> EntityPayloadNbt.hasUuid(nbt, uuid));
        delta.putEntity(entity.getId(), entityNbt);
        // Entities never flag the chunk themselves; without this, autosave skips an already-saved chunk.
        chunk.markNeedsSaving();
        GlobalChunkTracker.markDirty(chunk, SOURCE);
    }
}
