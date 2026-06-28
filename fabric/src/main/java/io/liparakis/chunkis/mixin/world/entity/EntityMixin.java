package io.liparakis.chunkis.mixin.world.entity;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.PayloadWatchTracer;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.entity.replay.ScheduledEntityReplayQueue;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(Entity.class)
public abstract class EntityMixin {

    @Unique
    private static final String REMOVE_SOURCE = "EntityMixin#chunkis$removeEntityPayload";

    @Shadow
    private World world;

    @Inject(method = "remove", at = @At("HEAD"))
    private void chunkis$traceWatchedRemoval(
            final Entity.RemovalReason reason,
            final CallbackInfo ci
    ) {
        final Entity self = (Entity) (Object) this;
        if (!(this.world instanceof ServerWorld serverWorld)) {
            return;
        }
        PayloadWatchTracer.traceLiveEntityRemoved(
                serverWorld,
                self,
                reason,
                null,
                "EntityMixin#chunkis$traceWatchedRemoval"
        );
        if (!chunkis$shouldPreserveEntityPayload(reason)) {
            chunkis$removeEntityPayload(serverWorld, self);
        }
    }

    @Inject(method = "onStartedTrackingBy", at = @At("HEAD"))
    private void chunkis$traceStartedTrackingBy(
            final ServerPlayerEntity player,
            final CallbackInfo ci
    ) {
        final Entity self = (Entity) (Object) this;
        if (!(this.world instanceof ServerWorld serverWorld)) {
            return;
        }
        PayloadWatchTracer.traceEntityTrackingEvent(
                serverWorld,
                self,
                player,
                "entity-tracking-start",
                "EntityMixin#chunkis$traceStartedTrackingBy",
                "watched entity started tracking for player"
        );
    }

    @Inject(method = "onStoppedTrackingBy", at = @At("HEAD"))
    private void chunkis$traceStoppedTrackingBy(
            final ServerPlayerEntity player,
            final CallbackInfo ci
    ) {
        final Entity self = (Entity) (Object) this;
        if (!(this.world instanceof ServerWorld serverWorld)) {
            return;
        }
        PayloadWatchTracer.traceEntityTrackingEvent(
                serverWorld,
                self,
                player,
                "entity-tracking-stop",
                "EntityMixin#chunkis$traceStoppedTrackingBy",
                "watched entity stopped tracking for player"
        );
    }

    @SuppressWarnings("unchecked")
    private void chunkis$removeEntityPayload(final ServerWorld world, final Entity entity) {
        final ChunkPos chunkPos = entity.getChunkPos();
        final WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z, false);
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)
                || !(deltaDuck.chunkis$getDelta() instanceof ChunkDelta<?, ?> rawDelta)) {
            ScheduledEntityReplayQueue.acknowledge(entity.getUuidAsString());
            return;
        }
        final ChunkDelta<?, NbtCompound> delta = (ChunkDelta<?, NbtCompound>) rawDelta;
        final UUID targetUuid = entity.getUuid();
        delta.removeEntity(entity.getId());
        delta.removeEntitiesMatching(nbt -> nbt != null
                && nbt.getIntArray("UUID")
                .map(net.minecraft.util.Uuids::toUuid)
                .map(targetUuid::equals)
                .orElse(false));
        GlobalChunkTracker.markDirty(chunk, REMOVE_SOURCE);
        ScheduledEntityReplayQueue.acknowledge(entity.getUuidAsString());
    }

    private static boolean chunkis$shouldPreserveEntityPayload(final Entity.RemovalReason reason) {
        return reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER;
    }
}

