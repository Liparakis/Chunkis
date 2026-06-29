package io.liparakis.chunkis.mixin.world.server;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.api.ChunkisMutationGuardDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.world.entity.capture.ChunkEntityNbtCapture;
import io.liparakis.chunkis.world.entity.replay.ScheduledEntityReplayQueue;
import io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureUtil;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.ownership.ChunkOwnershipTraceHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import io.liparakis.chunkis.world.tracking.suppression.PendingChunkMutationSuppression;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {

    @Unique
    private static final String SOURCE = "ServerWorldMixin#chunkis$afterSpawnEntity";

    @Inject(method = "tick", at = @At("TAIL"))
    private void chunkis$drainScheduledEntityReplayQueue(
            final java.util.function.BooleanSupplier shouldKeepTicking,
            final CallbackInfo ci
                                                        ) {
        ScheduledEntityReplayQueue.tick((ServerWorld) (Object) this);
    }

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
        final WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z, false);
        if (chunk instanceof ChunkisMutationGuardDuck guardDuck
                && guardDuck.chunkis$getMutationTrackingScope().currentCause()
                != ChunkMutationTrackingScope.Cause.NONE) {
            return;
        }
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
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

        @SuppressWarnings("unchecked")
        ChunkDelta<BlockState, NbtCompound> delta =
                (ChunkDelta<BlockState, NbtCompound>) deltaDuck.chunkis$getDelta();
        if (delta == null) {
            delta = new ChunkDelta<>(BlockState::isAir);
            deltaDuck.chunkis$setDelta(delta);
        }
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            delta.claimOwnership(
                    ChunkTraceReason.PLAYER_OR_COMMAND_EDIT.name(),
                    SOURCE
                                );
            ChunkOwnershipTraceHelper.traceDecision(
                    world.getRegistryKey(),
                    chunkPos,
                    "CLAIMED",
                    ChunkTraceReason.PLAYER_OR_COMMAND_EDIT,
                    SOURCE,
                    delta,
                    null
                                                   );
        }
        BaseChunkCaptureUtil.captureAndPersistBaseChunkIfMissing(world, chunk, delta);

        final NbtCompound entityNbt = ChunkEntityNbtCapture.serializeEntityNbt(entity);
        if (entityNbt == null || entityNbt.isEmpty()) {
            return;
        }

        delta.removeEntitiesMatching(nbt -> nbt != null
                && nbt.getIntArray("UUID")
                      .map(Uuids::toUuid)
                      .map(entity.getUuid()::equals)
                      .orElse(false));
        delta.putEntity(entity.getId(), entityNbt);
        GlobalChunkTracker.markDirty(chunk, SOURCE);
    }

}


