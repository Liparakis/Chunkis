package io.liparakis.chunkis.mixin.world.entity;

import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.world.entity.replay.ScheduledEntityReplayQueue;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.state.LoadedChunks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin intercepting Entity removal and tracking updates.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    /**
     * Trace source identification tag label.
     */
    @Unique
    private static final String REMOVE_SOURCE = "EntityMixin#chunkis$removeEntityPayload";

    /**
     * Shadowed reference to the world containing the entity.
     */
    @Shadow
    private Level level;

    /**
     * Decides if the entity payload should be kept when removal occurs.
     *
     * @param reason the removal reason
     * @return true if payload should be preserved
     */
    @Unique
    private static boolean chunkis$shouldPreserveEntityPayload(final Entity.RemovalReason reason) {
        return reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER;
    }

    /**
     * Injected at the head of Entity#remove to log removal events and prune payloads.
     *
     * @param reason the removal reason
     * @param ci     callback info helper
     */
    @Inject(method = "remove", at = @At("HEAD"))
    private void chunkis$traceWatchedRemoval(
            final Entity.RemovalReason reason,
            final CallbackInfo ci
    ) {
        final Entity self = (Entity) (Object) this;
        if (!(this.level instanceof ServerLevel serverWorld)) {
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

    /**
     * Injected at the head of Entity#onStartedTrackingBy to log player tracking events.
     *
     * @param player player beginning to track
     * @param ci     callback info helper
     */
    @Inject(method = "startSeenByPlayer", at = @At("HEAD"))
    private void chunkis$traceStartedTrackingBy(
            final ServerPlayer player,
            final CallbackInfo ci
    ) {
        final Entity self = (Entity) (Object) this;
        if (!(this.level instanceof ServerLevel serverWorld)) {
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

    /**
     * Injected at the head of Entity#onStoppedTrackingBy to log player tracking events.
     *
     * @param player player ending track
     * @param ci     callback info helper
     */
    @Inject(method = "stopSeenByPlayer", at = @At("HEAD"))
    private void chunkis$traceStoppedTrackingBy(
            final ServerPlayer player,
            final CallbackInfo ci
    ) {
        final Entity self = (Entity) (Object) this;
        if (!(this.level instanceof ServerLevel serverWorld)) {
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

    /**
     * Removes the entity data entry from the chunk delta on entity deaths/kills.
     *
     * @param world  target server world
     * @param entity target entity being deleted
     */
    @Unique
    @SuppressWarnings("unchecked")
    private void chunkis$removeEntityPayload(final ServerLevel world, final Entity entity) {
        final ChunkPos chunkPos = entity.chunkPosition();
        final LevelChunk chunk = LoadedChunks.getIfAccessible(world, chunkPos.x(), chunkPos.z());
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)
                || !(deltaDuck.chunkis$getDelta() instanceof ChunkDelta<?, ?> rawDelta)) {
            ScheduledEntityReplayQueue.acknowledge(entity.getStringUUID());
            return;
        }
        final ChunkDelta<?, CompoundTag> delta = (ChunkDelta<?, CompoundTag>) rawDelta;
        final String targetUuid = entity.getStringUUID();
        delta.removeEntity(entity.getId());
        delta.removeEntitiesMatching(nbt -> EntityPayloadNbt.hasUuid(nbt, targetUuid));
        // Without this, autosave skips an already-saved chunk and the removed entity comes back.
        chunk.markUnsaved();
        GlobalChunkTracker.markDirty(chunk, REMOVE_SOURCE);
        ScheduledEntityReplayQueue.acknowledge(entity.getStringUUID());
    }
}
