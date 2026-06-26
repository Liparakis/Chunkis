package io.liparakis.chunkis.mixin.network;

import io.liparakis.chunkis.debug.ChunkSectionDebugUtil;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.network.ChunkisNetworking;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

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
     * <li>Packet type — returns immediately for any non-chunk-data packet.</li>
     * <li>Chunk availability — returns if the holder's chunk is not yet loaded.</li>
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

        if (!isChunkDataPacket(packet)) return;

        final WorldChunk chunk = this.getWorldChunk();
        if (chunk == null) return;
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

        for (final ServerPlayerEntity player : players) {
            ChunkisNetworking.sendDelta(player, chunk);
        }
    }

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
}
