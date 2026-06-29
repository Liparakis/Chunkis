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
import io.liparakis.chunkis.network.ChunkisNetworking;
import io.liparakis.chunkis.world.restoration.core.ChunkRestorer;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts chunk packet transmission to piggyback Chunkis delta packets
 * alongside vanilla {@link ChunkDataS2CPacket} sends.
 *
 * <p>
 * Injected at {@code HEAD} of {@code ChunkHolder#sendPacketToPlayers}. When a
 * {@link ChunkDataS2CPacket} is detected, {@link ChunkisNetworking#sendDelta} is
 * called for each player. The chunk reference is hoisted outside the player loop
 * to avoid repeated virtual dispatch.
 *
 * @author Liparakis
 * @version 1.2
 * @see ChunkHolder
 * @see ChunkisNetworking#sendDelta(ServerPlayerEntity, WorldChunk)
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {

    /**
     * Returns true if the given packet is a {@link ChunkDataS2CPacket}.
     *
     * @param packet the packet to test
     * @return true if this is a full chunk data packet
     */
    @Unique
    private static boolean isChunkDataPacket(final Packet<?> packet) {
        return packet instanceof ChunkDataS2CPacket;
    }

    /**
     * Shadow of {@link ChunkHolder#getWorldChunk()}.
     *
     * @return the loaded world chunk, or null if not yet fully loaded
     */
    @Shadow
    public abstract WorldChunk getWorldChunk();

    /**
     * Intercepts packet sends and dispatches a Chunkis delta to each player
     * when the outgoing packet is a {@link ChunkDataS2CPacket}.
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
    @Inject(method = "sendPacketToPlayers", at = @At("HEAD"))
    private void chunkis$onSendPacketToPlayers(
            final List<ServerPlayerEntity> players,
            final Packet<?> packet,
            final CallbackInfo ci) {

        if (!isChunkDataPacket(packet)) {
            return;
        }

        final WorldChunk chunk = this.getWorldChunk();
        if (chunk == null) {
            return;
        }
        if (chunk instanceof ChunkisDeltaDuck deltaDuck
                && chunk.getWorld() instanceof ServerWorld serverWorld
                && deltaDuck.chunkis$getDelta() instanceof ChunkDelta<?, ?> rawDelta) {
            @SuppressWarnings("unchecked") final ChunkDelta<BlockState, NbtCompound> delta =
                    (ChunkDelta<BlockState, NbtCompound>) rawDelta;
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
                chunk.getWorld().getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
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
        if (chunk.getWorld() instanceof ServerWorld serverWorld
                && chunk instanceof ChunkisDeltaDuck deltaDuck
                && deltaDuck.chunkis$getDelta() instanceof ChunkDelta<?, ?> rawDelta) {
            @SuppressWarnings("unchecked") final ChunkDelta<BlockState, NbtCompound> delta =
                    (ChunkDelta<BlockState, NbtCompound>) rawDelta;
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

        for (final ServerPlayerEntity player : players) {
            ChunkisNetworking.sendDelta(player, chunk);
        }
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

