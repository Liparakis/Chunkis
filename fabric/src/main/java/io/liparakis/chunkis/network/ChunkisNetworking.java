package io.liparakis.chunkis.network;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.storage.codec.network.CisNetworkEncoder;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Lightweight networking handler that sends Chunkis chunk deltas to players.
 *
 * <p>Called from {@code io.liparakis.chunkis.mixin.network.ChunkHolderMixin}
 * whenever a {@code ChunkDataS2CPacket} is about to be sent, so every player
 * who receives vanilla chunk data also receives the corresponding Chunkis
 * delta.</p>
 *
 * <p>The send pipeline is:</p>
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
 * optional compression) and sent via {@link ServerPlayNetworking}.</li>
 * </ol>
 */
public final class ChunkisNetworking {

    /**
     * Trace source identification tag label.
     */
    private static final String SEND_SOURCE = "ChunkisNetworking#sendDelta";

    /**
     * Hard ceiling on outgoing delta size. Payloads larger than this are dropped.
     */
    private static final int MAX_DELTA_SIZE = 1_024_000; // 1 MB

    /**
     * Thread-local encoder.
     */
    @SuppressWarnings("rawtypes")
    private static final ThreadLocal<CisNetworkEncoder> ENCODER_POOL = ThreadLocal
            .withInitial(FabricNetworkCodecFactory::createEncoder);

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkisNetworking() {
        throw new AssertionError("Utility class");
    }

    /**
     * Attempts to send the Chunkis delta of a chunk to a player.
     *
     * @param player destination player entity
     * @param chunk  source world chunk
     */
    public static void sendDelta(final ServerPlayerEntity player, final WorldChunk chunk) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(chunk, "chunk must not be null");

        if (isPlayerUnavailable(player)) {
            return;
        }

        final ChunkPos chunkPos = chunk.getPos();
        final String worldId = chunk.getWorld()
                .getRegistryKey()
                .getValue()
                .toString();
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
                "starting delta send to player " + player.getName()
                        .getString(),
                worldId,
                DebugChunkKeys.of(chunkPos),
                null,
                operationId,
                delta.isDirty(),
                null
        );
        final PreparedPayload preparedPayload = preparePayload(chunkPos, worldId, delta, operationId);
        if (preparedPayload == null) {
            return;
        }
        sendPreparedPayload(player, chunkPos, worldId, delta, operationId, preparedPayload);
    }

    /**
     * Serializes one chunk delta once, then fans it out to many players.
     *
     * <p>Use this when the same chunk is being sent to multiple watchers. Re-encoding
     * per player just burns server-thread time for identical bytes.</p>
     *
     * @param players destination players
     * @param chunk   source world chunk
     */
    public static void sendDelta(final List<ServerPlayerEntity> players, final WorldChunk chunk) {
        Objects.requireNonNull(players, "players must not be null");
        Objects.requireNonNull(chunk, "chunk must not be null");

        if (players.isEmpty()) {
            return;
        }

        final ChunkPos chunkPos = chunk.getPos();
        final String worldId = chunk.getWorld()
                .getRegistryKey()
                .getValue()
                .toString();
        final ChunkDelta<?, ?> delta = extractDelta(chunk);

        if (delta == null) {
            return;
        }

        final String operationId = ChunkTraceStore.nextOperationId("sync");
        final PreparedPayload preparedPayload = preparePayload(chunkPos, worldId, delta, operationId);
        if (preparedPayload == null) {
            return;
        }

        for (final ServerPlayerEntity player : players) {
            if (isPlayerUnavailable(player)) {
                continue;
            }
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_TX_START,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    SEND_SOURCE,
                    "starting delta send to player " + player.getName()
                            .getString(),
                    worldId,
                    DebugChunkKeys.of(chunkPos),
                    null,
                    operationId,
                    delta.isDirty(),
                    null
            );
            sendPreparedPayload(player, chunkPos, worldId, delta, operationId, preparedPayload);
        }
    }

    /**
     * Resolves the block delta from a WorldChunk if any.
     *
     * @param chunk source world chunk
     * @return resolved delta instance, or null
     */
    private static ChunkDelta<?, ?> extractDelta(final WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return null;
        }

        final ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();
        return (delta == null || delta.isEmpty()) ? null : delta;
    }

    /**
     * Serializes and writes the payload delta data over the network channel.
     *
     * @param pos         chunk coordinates position
     * @param worldId     target world registry ID string
     * @param delta       target block delta
     * @param operationId active load/save operation ID
     */
    @SuppressWarnings("unchecked")
    private static PreparedPayload preparePayload(
            final ChunkPos pos,
            final String worldId,
            final ChunkDelta<?, ?> delta,
            final String operationId
    ) {
        try {
            final byte[] rawData = ENCODER_POOL.get()
                    .encode(delta);

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
                return null;
            }

            final ChunkDeltaPayload payload = ChunkDeltaPayload.create(rawData, pos.x, pos.z);
            return new PreparedPayload(rawData.length, payload);

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
            return null;
        }
    }

    /**
     * Writes a prepared payload to one player and records the result trace.
     */
    private static void sendPreparedPayload(
            final ServerPlayerEntity player,
            final ChunkPos pos,
            final String worldId,
            final ChunkDelta<?, ?> delta,
            final String operationId,
            final PreparedPayload preparedPayload
    ) {
        ServerPlayNetworking.send(player, preparedPayload.payload());
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CLIENT_SYNC,
                ChunkTraceEventType.CLIENT_SYNC_TX_END,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                SEND_SOURCE,
                describePayloadOutcome(player.getName()
                        .getString(), preparedPayload.rawBytes(), preparedPayload.payload()),
                worldId,
                DebugChunkKeys.of(pos),
                null,
                operationId,
                delta.isDirty(),
                preparedPayload.payload()
                        .data().length
        );
    }

    /**
     * Checks if player is disconnected or removed.
     *
     * @param player player to evaluate
     * @return true if unavailable
     */
    private static boolean isPlayerUnavailable(final ServerPlayerEntity player) {
        return player.isRemoved() || player.isDisconnected();
    }

    /**
     * Checks if payload size violates hard caps.
     *
     * @param data byte array to inspect
     * @return true if violates limits
     */
    private static boolean exceedsSizeLimit(final byte[] data) {
        return data.length > MAX_DELTA_SIZE;
    }

    /**
     * Traces sync errors mapping targets.
     *
     * @param worldId     target world registry ID string
     * @param pos         chunk coordinates position
     * @param operationId active load/save operation ID
     * @param reason      reason code
     * @param message     description detail text
     * @param byteSize    payload size, may be null
     */
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
                DebugChunkKeys.of(pos),
                null,
                operationId,
                null,
                byteSize
        );
    }

    /**
     * Creates human-readable diagnostic message summaries detailing sync transactions.
     *
     * @param playerName target player name
     * @param rawBytes   raw uncompressed byte size
     * @param payload    constructed payload wrapper
     * @return summary text string
     */
    static String describePayloadOutcome(
            final String playerName,
            final int rawBytes,
            final ChunkDeltaPayload payload
    ) {
        return "sent delta to player "
                + playerName
                + " rawBytes="
                + rawBytes
                + " wireBytes="
                + payload.data().length
                + " compressed="
                + payload.compressed();
    }

    /**
     * Immutable prepared send state shared across watcher fan-out.
     */
    private record PreparedPayload(int rawBytes, ChunkDeltaPayload payload) {

    }
}
