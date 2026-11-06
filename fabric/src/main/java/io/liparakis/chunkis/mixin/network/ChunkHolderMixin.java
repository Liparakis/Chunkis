package io.liparakis.chunkis.mixin.network;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.util.ChunkSectionDebugUtil;
import io.liparakis.chunkis.world.restoration.core.ChunkRestorer;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes vanilla full-chunk packet transmission to replay pending entities
 * before a restored chunk becomes visible to players.
 *
 * <p>
 * Injected at {@code HEAD} of {@code ChunkHolder#sendPacketToPlayers}. When a
 * {@link ClientboundLevelChunkWithLightPacket} is detected, pending restored entities are replayed
 * before the packet is sent.
 *
 * @see ChunkHolder
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {

    /**
     * Returns true if the given packet is a {@link ClientboundLevelChunkWithLightPacket}.
     *
     * @param packet the packet to test
     * @return true if this is a full chunk data packet
     */
    @Unique
    private static boolean isChunkDataPacket(final Packet<?> packet) {
        return packet instanceof ClientboundLevelChunkWithLightPacket;
    }

    /**
     * Shadow of {@link ChunkHolder#getTickingChunk()}.
     *
     * @return the loaded world chunk, or null if not yet fully loaded
     */
    @Shadow
    public abstract LevelChunk getTickingChunk();

    /**
     * Intercepts full-chunk packet sends to replay pending restored entities.
     *
     * <p>
     * Guards (evaluated in order):
     * <ol>
     * <li>Packet type returns immediately for any non-chunk-data packet.</li>
     * <li>Chunk availability returns if the holder's chunk is not yet loaded.</li>
     * </ol>
     *
     * @param players the players about to receive the packet
     * @param packet  the packet being dispatched
     * @param ci      mixin callback (not cancelled)
     */
    @Inject(method = "broadcast", at = @At("HEAD"))
    private void chunkis$onSendPacketToPlayers(
            final List<ServerPlayer> players,
            final Packet<?> packet,
            final CallbackInfo ci) {

        if (!isChunkDataPacket(packet)) {
            return;
        }

        final LevelChunk chunk = this.getTickingChunk();
        if (chunk == null) {
            return;
        }
        if (chunk instanceof ChunkisDeltaDuck deltaDuck
                && chunk.getLevel() instanceof ServerLevel serverWorld
                && deltaDuck.chunkis$getDelta() instanceof ChunkDelta<?, ?> rawDelta) {
            @SuppressWarnings("unchecked") final ChunkDelta<BlockState, CompoundTag> delta =
                    (ChunkDelta<BlockState, CompoundTag>) rawDelta;
            ChunkRestorer.replayPendingEntitiesIfNeeded(
                    serverWorld,
                    chunk,
                    delta,
                    deltaDuck.chunkis$getRestoreOperationId()
            );
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CLIENT_SYNC,
                ChunkTraceEventType.CHUNK_SENT_TO_CLIENT_SUMMARY,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                "ChunkHolderMixin#chunkis$onSendPacketToPlayers",
                "sending vanilla chunk packet summary: players=" + players.size()
                        + ", " + ChunkSectionDebugUtil.summarize(chunk),
                chunk.getLevel()
                        .dimension()
                        .identifier()
                        .toString(),
                new DebugChunkKey(chunk.getPos().x(), chunk.getPos().z()),
                null,
                null,
                null,
                null
        );
        PayloadWatchTracer.traceLiveChunkState(
                chunk,
                ChunkTraceEventType.WATCH_PRESENT_AFTER_CHUNK_FULL,
                "chunk-full",
                "ChunkHolderMixin#chunkis$onSendPacketToPlayers",
                null,
                null
        );
        if (chunk.getLevel() instanceof ServerLevel serverWorld
                && chunk instanceof ChunkisDeltaDuck deltaDuck
                && deltaDuck.chunkis$getDelta() instanceof ChunkDelta<?, ?> rawDelta) {
            @SuppressWarnings("unchecked") final ChunkDelta<BlockState, CompoundTag> delta =
                    (ChunkDelta<BlockState, CompoundTag>) rawDelta;
            PayloadWatchTracer.traceEntityPresenceAfterChunkFull(
                    serverWorld,
                    chunk,
                    delta,
                    deltaDuck.chunkis$getRestoreOperationId(),
                    "ChunkHolderMixin#chunkis$onSendPacketToPlayers"
            );
        }
        PayloadWatchTracer.traceLiveChunkState(
                chunk,
                ChunkTraceEventType.WATCH_PRESENT_BEFORE_CLIENT_SEND,
                "before-client-send",
                "ChunkHolderMixin#chunkis$onSendPacketToPlayers",
                null,
                null
        );

        PayloadWatchTracer.traceLiveChunkState(
                chunk,
                ChunkTraceEventType.WATCH_PRESENT_AFTER_CLIENT_SEND,
                "client-send",
                "ChunkHolderMixin#chunkis$onSendPacketToPlayers",
                null,
                null
        );
    }
}
