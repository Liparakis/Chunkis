package io.liparakis.chunkis.network;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaSnapshotView;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.perf.ServerHotpathMetrics;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.storage.codec.network.CisNetworkEncoder;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
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
     * Shared background encoder thread for restore-triggered full-chunk resends.
     */
    private static final ExecutorService ASYNC_DELTA_ENCODER = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "Chunkis-DeltaEncode");
        thread.setDaemon(true);
        return thread;
    });

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
        final PreparedPayload preparedPayload = preparePayload(chunkPos, worldId, delta, operationId, 1);
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
        final PreparedPayload preparedPayload = preparePayload(chunkPos, worldId, delta, operationId, players.size());
        if (preparedPayload == null) {
            return;
        }

        fanOutPreparedPayload(
                players,
                chunkPos,
                worldId,
                delta,
                operationId,
                preparedPayload,
                () -> true
        );
    }

    /**
     * Snapshots and encodes a chunk delta off-thread, then sends it back on the server thread.
     *
     * <p>Used by restore-time full chunk resends where payload preparation dominates the
     * server-thread slice. Watcher lookup and packet send stay on the server thread.</p>
     *
     * @param world   source world owning the chunk
     * @param players destination players captured on the server thread
     * @param chunk   source world chunk
     */
    public static void sendDeltaAsync(
            final ServerWorld world,
            final List<ServerPlayerEntity> players,
            final WorldChunk chunk
    ) {
        Objects.requireNonNull(world, "world must not be null");
        Objects.requireNonNull(players, "players must not be null");
        Objects.requireNonNull(chunk, "chunk must not be null");

        if (players.isEmpty()) {
            return;
        }
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return;
        }
        if (!(deltaDuck.chunkis$getDelta() instanceof ChunkDelta<?, ?> rawDelta)) {
            return;
        }

        @SuppressWarnings("unchecked") final ChunkDelta<BlockState, NbtCompound> liveDelta =
                (ChunkDelta<BlockState, NbtCompound>) rawDelta;
        if (liveDelta.isEmpty()) {
            return;
        }

        final ChunkPos chunkPos = chunk.getPos();
        final String worldId = world.getRegistryKey()
                .getValue()
                .toString();
        final String operationId = ChunkTraceStore.nextOperationId("sync");
        final long generation = liveDelta.getMutationGeneration();
        final ChunkDeltaSnapshotView<BlockState, NbtCompound> snapshot = liveDelta.snapshotView(NbtCompound::copy);
        final List<ServerPlayerEntity> playerSnapshot = List.copyOf(players);

        ASYNC_DELTA_ENCODER.execute(() -> {
            final PreparedPayload preparedPayload = preparePayload(
                    chunkPos,
                    worldId,
                    snapshot,
                    operationId,
                    playerSnapshot.size()
            );
            if (preparedPayload == null) {
                return;
            }

            Objects.requireNonNull(world.getServer())
                    .execute(() -> sendPreparedPayloadIfCurrent(
                            playerSnapshot,
                            chunkPos,
                            worldId,
                            liveDelta,
                            generation,
                            operationId,
                            preparedPayload
                    ));
        });
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
            final ChunkDeltaView<?, ?> delta,
            final String operationId,
            final int playerCount
    ) {
        try {
            final long encodeStartNanos = ServerHotpathMetrics.startTimer();
            final byte[] rawData = ENCODER_POOL.get()
                    .encode(delta);
            final long encodeNanos = ServerHotpathMetrics.ENABLED ? System.nanoTime() - encodeStartNanos : 0L;

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

            final long wrapStartNanos = ServerHotpathMetrics.startTimer();
            final ChunkDeltaPayload payload = ChunkDeltaPayload.create(rawData, pos.x, pos.z);
            final long wrapNanos = ServerHotpathMetrics.ENABLED ? System.nanoTime() - wrapStartNanos : 0L;
            if (ServerHotpathMetrics.ENABLED) {
                ServerHotpathMetrics.recordPreparedPayload(
                        encodeNanos,
                        wrapNanos,
                        rawData.length,
                        payload.data().length,
                        playerCount
                );
                if ((ServerHotpathMetrics.payloadCount() & 0x7F) == 0) {
                    // ponytail: piggyback a cheap periodic summary instead of another scheduler.
                    ServerHotpathMetrics.logPayloadSummary();
                }
            }
            return new PreparedPayload(rawData.length, payload, encodeNanos, wrapNanos, playerCount);

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
            final ChunkDeltaView<?, ?> delta,
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
                        .getString(), preparedPayload),
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
     * Sends a prepared payload only if the captured live delta has not been superseded.
     */
    private static void sendPreparedPayloadIfCurrent(
            final List<ServerPlayerEntity> players,
            final ChunkPos pos,
            final String worldId,
            final ChunkDelta<BlockState, NbtCompound> liveDelta,
            final long generation,
            final String operationId,
            final PreparedPayload preparedPayload
    ) {
        fanOutPreparedPayload(
                players,
                pos,
                worldId,
                liveDelta,
                operationId,
                preparedPayload,
                () -> liveDelta.getMutationGeneration() == generation
        );
    }

    /**
     * Fans one prepared payload out to many players with an optional freshness guard.
     */
    private static void fanOutPreparedPayload(
            final List<ServerPlayerEntity> players,
            final ChunkPos pos,
            final String worldId,
            final ChunkDeltaView<?, ?> delta,
            final String operationId,
            final PreparedPayload preparedPayload,
            final BooleanSupplier shouldSend
    ) {
        if (!shouldSend.getAsBoolean()) {
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
                    DebugChunkKeys.of(pos),
                    null,
                    operationId,
                    delta.isDirty(),
                    null
            );
            sendPreparedPayload(player, pos, worldId, delta, operationId, preparedPayload);
        }
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
     * @return summary text string
     */
    static String describePayloadOutcome(
            final String playerName,
            final PreparedPayload preparedPayload
    ) {
        return "sent delta to player "
                + playerName
                + " rawBytes="
                + preparedPayload.rawBytes()
                + " wireBytes="
                + preparedPayload.payload()
                .data().length
                + " compressed="
                + preparedPayload.payload()
                .compressed()
                + " encodeMicros="
                + ServerHotpathMetrics.nanosToMicros(preparedPayload.encodeNanos())
                + " wrapMicros="
                + ServerHotpathMetrics.nanosToMicros(preparedPayload.wrapNanos())
                + " players="
                + preparedPayload.playerCount();
    }

    /**
     * Backward-compatible summary helper retained for lightweight unit tests.
     */
    @SuppressWarnings("SameParameterValue")
    static String describePayloadOutcome(
            final String playerName,
            final int rawBytes,
            final ChunkDeltaPayload payload
    ) {
        return describePayloadOutcome(
                playerName,
                new PreparedPayload(rawBytes, payload, 0L, 0L, 1)
        );
    }

    /**
     * Immutable prepared send state shared across watcher fan-out.
     */
    private record PreparedPayload(
            int rawBytes,
            ChunkDeltaPayload payload,
            long encodeNanos,
            long wrapNanos,
            int playerCount
    ) {

    }
}
