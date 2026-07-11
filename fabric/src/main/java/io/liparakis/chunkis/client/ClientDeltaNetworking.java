package io.liparakis.chunkis.client;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.network.ChunkDeltaPayload;
import io.liparakis.chunkis.network.FabricNetworkCodecFactory;
import io.liparakis.chunkis.core.codec.network.CisNetworkDecoder;
import java.io.IOException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.Property;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Client-side networking handler for applying incoming chunk delta updates.
 *
 * <p>Listens for {@link ChunkDeltaPayload} network packets sent from the server,
 * decodes the contained {@link ChunkDelta} changes, and applies them to the
 * client's world chunk. Thread-locals are utilized for re-using codec decoders
 * and visitors to minimize allocation churn during hot-path chunk updates.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientDeltaNetworking {

    /**
     * Diagnostic trace source string for logging client sync events.
     */
    private static final String APPLY_SOURCE = "ClientDeltaNetworking#processChunkDelta";

    /**
     * Thread-local reusable network decoder for CIS delta formats.
     */
    private static final ThreadLocal<CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound>> DECODER = ThreadLocal.withInitial(
            FabricNetworkCodecFactory::createDecoder);

    /**
     * Thread-local reusable visitor for applying block and entity instructions.
     */
    private static final ThreadLocal<ClientDeltaVisitor> VISITOR = ThreadLocal.withInitial(ClientDeltaVisitor::new);

    /**
     * Cache sentinel to ensure missing duck interface warnings are only logged once.
     */
    private static volatile boolean typeWarningLogged = false;

    /** Performs client delta networking. */
    private ClientDeltaNetworking() {
        throw new AssertionError("Utility class");
    }

    /**
     * Registers global network receivers and connection lifecycle hooks on the client.
     *
     * <p>Subscribes to delta payloads and registers a disconnect callback to clear
     * thread-local buffers and decoders when leaving a server.</p>
     */
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ChunkDeltaPayload.ID, ClientDeltaNetworking::handleIncomingPayload);

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> cleanupThreadLocals());
    }

    /**
     * Handles incoming packet receiver callback for the custom Chunkis channel payload.
     *
     * <p>Runs on the network thread, validates the payload, and schedules the execution
     * on the main client thread to safely modify game states.</p>
     *
     * @param payload the received network payload, must not be null
     * @param context the Fabric networking context, must not be null
     */
    private static void handleIncomingPayload(final ChunkDeltaPayload payload,
            final ClientPlayNetworking.Context context) {
        final byte[] data = payload.data();
        final String operationId = ChunkTraceStore.nextOperationId("client-sync");

        if (isInvalidPayload(data)) {
            ChunkTraceStore.trace(ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_FAILED,
                    ChunkTraceSeverity.WARN,
                    ChunkTraceReason.INVALID_PAYLOAD,
                    APPLY_SOURCE,
                    "rejected incoming " + "payload because it was null or empty",
                    null,
                    new DebugChunkKey(payload.chunkX(), payload.chunkZ()),
                    null,
                    operationId,
                    null,
                    data == null ? null : data.length);
            ClientDeltaMetrics.logErrorThrottled(() -> "Received invalid ChunkDeltaPayload: null or empty data");
            return;
        }

        if (ClientDeltaMetrics.ENABLED) {
            ClientDeltaMetrics.recordPacket(data.length);
        }

        final var client = context.client();
        client.execute(() -> processChunkDelta(payload, client.world, operationId));
    }

    /**
     * Decodes and applies the delta payload to the client world on the client thread.
     *
     * <p>Traces process start and end/errors to diagnostic stores. Reports failures
     * to throttled loggers to prevent chat/log spam in case of corrupted packets.</p>
     *
     * @param payload     the payload containing delta instructions
     * @param world       the target client world, may be null if client is currently disconnecting/switching worlds
     * @param operationId unique ID identifying this sync process sequence
     */
    private static void processChunkDelta(final ChunkDeltaPayload payload,
            final ClientWorld world,
            final String operationId) {
        DebugChunkKey chunkKey = new DebugChunkKey(payload.chunkX(), payload.chunkZ());
        if (world == null) {
            ChunkTraceStore.trace(ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_FAILED,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.CLIENT_WORLD_UNAVAILABLE,
                    APPLY_SOURCE,
                    "skipped " + "payload because client world was unavailable",
                    null,
                    chunkKey,
                    null,
                    operationId,
                    null,
                    payload.data().length);
            return;
        }

        final long startNanos = captureStartTime();
        ChunkTraceStore.trace(ChunkisDebugDomain.CLIENT_SYNC,
                ChunkTraceEventType.CLIENT_SYNC_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                APPLY_SOURCE,
                describeIncomingPayload(payload, "starting client delta apply"),
                world.getRegistryKey()
                        .getValue()
                        .toString(),
                chunkKey,
                null,
                operationId,
                null,
                payload.data().length);

        try {
            final int changedBlockCount = applyPayloadToWorld(payload, world, operationId);
            recordMetricsIfEnabled(changedBlockCount, startNanos);
            ChunkTraceStore.trace(ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_TX_END,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    APPLY_SOURCE,
                    describeIncomingPayload(payload, "completed client delta apply"),
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    chunkKey,
                    null,
                    operationId,
                    null,
                    payload.data().length);

            Chunkis.LOGGER.debug("Applied ChunkDelta ({},{}) - {} bytes, {} blocks",
                    payload.chunkX(),
                    payload.chunkZ(),
                    payload.data().length,
                    changedBlockCount);

        } catch (final IOException e) {
            ChunkTraceStore.trace(ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_FAILED,
                    ChunkTraceSeverity.ERROR,
                    classifyClientSyncFailure(e),
                    APPLY_SOURCE,
                    "client delta decode " + "failed",
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    chunkKey,
                    null,
                    operationId,
                    null,
                    payload.data().length);
            ClientDeltaMetrics.logErrorThrottled(() -> String.format("Decode failed for chunk (%d,%d) - %d bytes",
                    payload.chunkX(),
                    payload.chunkZ(),
                    payload.data().length), e);
        } catch (final Exception e) {
            ChunkTraceStore.trace(ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.IO_EXCEPTION,
                    APPLY_SOURCE,
                    "client delta apply " + "failed" + " with exception",
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    chunkKey,
                    null,
                    operationId,
                    null,
                    payload.data().length);
            ClientDeltaMetrics.logErrorThrottled(() -> String.format("Apply failed for chunk (%d,%d) - %d bytes",
                    payload.chunkX(),
                    payload.chunkZ(),
                    payload.data().length), e);
        }
    }

    /**
     * Retrieves the target world chunk, decodes the network payload, and applies updates.
     *
     * <p>Assumes the chunk implements {@link ChunkisDeltaDuck} to store its delta states.
     * If not, a warning is traced and execution is skipped safely.</p>
     *
     * @param payload     the incoming delta payload
     * @param world       the target client world
     * @param operationId unique ID identifying this sync process
     * @return the number of applied block instructions
     * @throws Exception if decoding or applying the delta fails
     */
    private static int applyPayloadToWorld(final ChunkDeltaPayload payload,
            final ClientWorld world,
            final String operationId) throws Exception {
        final int chunkX = payload.chunkX();
        final int chunkZ = payload.chunkZ();

        final WorldChunk chunk = world.getChunk(chunkX, chunkZ);
        if (!isChunkisDuck(chunk, chunkX, chunkZ)) {
            ChunkTraceStore.trace(ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_FAILED,
                    ChunkTraceSeverity.WARN,
                    ChunkTraceReason.CHUNK_NOT_DELTA_CAPABLE,
                    APPLY_SOURCE,
                    "client chunk " + "did not implement ChunkisDeltaDuck",
                    world.getRegistryKey()
                            .getValue()
                            .toString(),
                    new DebugChunkKey(chunkX, chunkZ),
                    null,
                    operationId,
                    null,
                    payload.data().length);
            return 0;
        }

        final ChunkDelta<BlockState, NbtCompound> receivedDelta = DECODER.get()
                .decode(payload.data());

        @SuppressWarnings("unchecked")
        ChunkDelta<BlockState, NbtCompound> clientDelta =
                (ChunkDelta<BlockState, NbtCompound>) ((ChunkisDeltaDuck) chunk).chunkis$getDelta();
        if (clientDelta == null) {
            ((ChunkisDeltaDuck) chunk).chunkis$setDelta(clientDelta = new ChunkDelta<>(BlockState::isAir));
        }

        applyDelta(clientDelta, receivedDelta, world, chunkX, chunkZ);

        PayloadWatchTracer.traceLiveChunkState(chunk,
                ChunkTraceEventType.WATCH_PRESENT_IN_CLIENT_WORLD,
                "client" + "-world",
                APPLY_SOURCE,
                operationId,
                receivedDelta);

        return receivedDelta.getBlockChangesCount();
    }

    /**
     * Resets the thread-local visitor and accepts the received delta.
     *
     * @param clientDelta   the existing chunk delta of the client
     * @param receivedDelta the new chunk delta received from the server
     * @param world         the target client world
     * @param chunkX        chunk X coordinate
     * @param chunkZ        chunk Z coordinate
     */
    private static void applyDelta(final ChunkDelta<BlockState, NbtCompound> clientDelta,
            final ChunkDelta<BlockState, NbtCompound> receivedDelta,
            final ClientWorld world,
            final int chunkX,
            final int chunkZ) {
        final ClientDeltaVisitor visitor = VISITOR.get();
        visitor.reset(clientDelta, world, chunkX, chunkZ);
        receivedDelta.accept(visitor);
    }

    /**
     * Captures current high-resolution time if metrics are enabled.
     *
     * @return current nanoseconds, or 0 if disabled
     */
    private static long captureStartTime() {
        return ClientDeltaMetrics.ENABLED ? System.nanoTime() : 0L;
    }

    /**
     * Records telemetry metrics for the applied delta packet.
     *
     * @param changedBlockCount number of block updates applied
     * @param startNanos        start time of the decode operation
     */
    private static void recordMetricsIfEnabled(final int changedBlockCount, final long startNanos) {
        if (!ClientDeltaMetrics.ENABLED) {
            return;
        }

        ClientDeltaMetrics.recordDecode(System.nanoTime() - startNanos);
        ClientDeltaMetrics.recordBlocksChanged(changedBlockCount);

        if ((ClientDeltaMetrics.packetCount() & 0x3FF) == 0) {
            ClientDeltaMetrics.logSummary();
        }
    }

    /**
     * Classifies a sync failure exception into a specific trace reason.
     *
     * <p>Package-private to allow tests or related network handlers to classify errors consistently.
     * Detects unknown block registry identifier failures during decode.</p>
     *
     * @param error the caught exception
     * @return the classified trace reason
     */
    static ChunkTraceReason classifyClientSyncFailure(final Exception error) {
        if (!(error instanceof IOException)) {
            return ChunkTraceReason.IO_EXCEPTION;
        }

        final String message = error.getMessage();
        if (message != null && message.contains("Unknown Block ID")) {
            return ChunkTraceReason.MAPPING_LOOKUP_FAILED;
        }
        return ChunkTraceReason.DECODE_FAILED;
    }

    /**
     * Generates a descriptive string for diagnostic trace payloads.
     *
     * @param payload the network payload
     * @param prefix  trace description prefix
     * @return a formatted descriptive string
     */
    static String describeIncomingPayload(final ChunkDeltaPayload payload, final String prefix) {
        return prefix + " compressedOnWire=" + payload.compressed() + " decodedBytes=" + payload.data().length;
    }

    /**
     * Returns true if the payload data byte array is null or empty.
     *
     * @param data payload byte array
     * @return true if data is null or empty
     */
    private static boolean isInvalidPayload(final byte[] data) {
        return data == null || data.length == 0;
    }

    /**
     * Checks if the given chunk implements the required duck interface.
     *
     * @param chunk  the world chunk instance, may be null
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return true if chunk implements {@link ChunkisDeltaDuck}
     */
    private static boolean isChunkisDuck(final WorldChunk chunk, final int chunkX, final int chunkZ) {
        if (chunk instanceof ChunkisDeltaDuck) {
            return true;
        }

        logTypeWarningOnce(chunk, chunkX, chunkZ);
        return false;
    }

    /**
     * Logs a single warning message if a chunk does not implement the required duck interface.
     *
     * @param chunk  the world chunk
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    private static void logTypeWarningOnce(final WorldChunk chunk, final int chunkX, final int chunkZ) {
        if (typeWarningLogged) {
            return;
        }
        typeWarningLogged = true;

        Chunkis.LOGGER.warn(
                "Chunkis: Chunk at ({},{}) does not implement ChunkisDeltaDuck: {}. (Warning shown once " + "only.)",
                chunkX,
                chunkZ,
                chunk.getClass()
                        .getName());
    }

    /**
     * Removes thread-local decoders and visitors to prevent memory leaks.
     */
    private static void cleanupThreadLocals() {
        DECODER.remove();
        VISITOR.remove();
        Chunkis.LOGGER.debug("Chunkis: Cleaned up client thread-local resources");
    }
}
