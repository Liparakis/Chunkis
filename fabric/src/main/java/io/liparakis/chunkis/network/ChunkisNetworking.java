package io.liparakis.chunkis.network;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.storage.codec.network.CisNetworkEncoder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Objects;

/**
 * Lightweight networking handler that sends Chunkis chunk deltas to players.
 *
 * <p>
 * Called from {@link io.liparakis.chunkis.mixin.network.ChunkHolderMixin}
 * whenever a {@code ChunkDataS2CPacket} is about to be sent, so every player
 * who receives vanilla chunk data also receives the corresponding Chunkis
 * delta.
 *
 * <p>
 * The send pipeline is:
 * <ol>
 * <li>Guard: chunk must implement {@link ChunkisDeltaDuck} and carry a
 * non-empty delta.</li>
 * <li>Capture: live block-entities and entities are snapshotted into the
 * delta.</li>
 * <li>Encode: delta is serialized by the thread-local
 * {@link CisNetworkEncoder}.</li>
 * <li>Size-check: payloads exceeding {@value #MAX_DELTA_SIZE} bytes are dropped
 * with a log.</li>
 * <li>Send: encoded bytes are wrapped in a {@link ChunkDeltaPayload} (with
 * optional
 * compression) and sent via {@link ServerPlayNetworking}.</li>
 * </ol>
 */
public final class ChunkisNetworking {

    private static final String SEND_SOURCE = "ChunkisNetworking#sendDelta";

    /**
     * Hard ceiling on outgoing delta size. Payloads larger than this are dropped.
     */
    private static final int MAX_DELTA_SIZE = 1_024_000; // 1 MB

    /**
     * Thread-local encoder - each thread reuses a single instance, eliminating
     * per-call allocation on the packet-send hot path.
     */
    @SuppressWarnings("rawtypes")
    private static final ThreadLocal<CisNetworkEncoder> ENCODER_POOL = ThreadLocal
            .withInitial(FabricNetworkCodecFactory::createEncoder);

    private ChunkisNetworking() {
        throw new AssertionError("Utility class");
    }

    public static void sendDelta(final ServerPlayerEntity player, final WorldChunk chunk) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(chunk, "chunk must not be null");

        final ChunkPos chunkPos = chunk.getPos();
        final String worldId = chunk.getWorld().getRegistryKey().getValue().toString();
        final String operationId = ChunkTraceStore.nextOperationId("sync");
        final ChunkDelta<?, ?> delta = extractDelta(chunk);

        if (delta == null) {
            traceSyncFailure(
                    worldId,
                    chunkPos,
                    operationId,
                    ChunkTraceReason.EMPTY_DELTA,
                    "skipped delta send because chunk had no non-empty delta",
                    null
            );
            return;
        }
        if (isPlayerUnavailable(player)) {
            traceSyncFailure(
                    worldId,
                    chunkPos,
                    operationId,
                    ChunkTraceReason.PLAYER_UNAVAILABLE,
                    "skipped delta send because player was unavailable",
                    null
            );
            return;
        }

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CLIENT_SYNC,
                ChunkTraceEventType.CLIENT_SYNC_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                SEND_SOURCE,
                "starting delta send to player " + player.getName().getString(),
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                operationId,
                delta.isDirty(),
                null
        );
        encodeAndSend(player, chunkPos, worldId, delta, operationId);
    }

    private static ChunkDelta<?, ?> extractDelta(final WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return null;
        }

        final ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();
        return (delta == null || delta.isEmpty()) ? null : delta;
    }

    @SuppressWarnings("unchecked")
    private static void encodeAndSend(
            final ServerPlayerEntity player,
            final ChunkPos pos,
            final String worldId,
            final ChunkDelta<?, ?> delta,
            final String operationId
    ) {
        try {
            final byte[] rawData = ENCODER_POOL.get().encode(delta);

            if (exceedsSizeLimit(rawData)) {
                traceSyncFailure(
                        worldId,
                        pos,
                        operationId,
                        ChunkTraceReason.PAYLOAD_TOO_LARGE,
                        "skipped delta send because payload exceeded size limit",
                        rawData.length
                );
                Chunkis.LOGGER.error(
                        "Chunkis: Delta too large for chunk ({}, {}): {} bytes - skipping",
                        pos.x, pos.z, rawData.length);
                return;
            }

            final ChunkDeltaPayload payload = ChunkDeltaPayload.create(rawData, pos.x, pos.z);
            ServerPlayNetworking.send(player, payload);
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_TX_END,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    SEND_SOURCE,
                    "sent delta to player " + player.getName().getString(),
                    worldId,
                    new DebugChunkKey(pos.x, pos.z),
                    null,
                    operationId,
                    delta.isDirty(),
                    payload.data().length
            );

        } catch (final Exception e) {
            traceSyncFailure(
                    worldId,
                    pos,
                    operationId,
                    ChunkTraceReason.IO_EXCEPTION,
                    "delta send failed with exception",
                    null
            );
            Chunkis.LOGGER.error("Chunkis: Failed to send delta for chunk ({}, {})", pos.x, pos.z, e);
        }
    }

    private static boolean isPlayerUnavailable(final ServerPlayerEntity player) {
        return player.isRemoved() || player.isDisconnected();
    }

    private static boolean exceedsSizeLimit(final byte[] data) {
        return data.length > MAX_DELTA_SIZE;
    }

    private static void traceSyncFailure(
            final String worldId,
            final ChunkPos pos,
            final String operationId,
            final ChunkTraceReason reason,
            final String message,
            final Integer byteSize
    ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CLIENT_SYNC,
                ChunkTraceEventType.CLIENT_SYNC_FAILED,
                reason == ChunkTraceReason.IO_EXCEPTION
                        ? ChunkTraceSeverity.ERROR
                        : ChunkTraceSeverity.WARN,
                reason,
                SEND_SOURCE,
                message,
                worldId,
                new DebugChunkKey(pos.x, pos.z),
                null,
                operationId,
                null,
                byteSize
        );
    }
}
