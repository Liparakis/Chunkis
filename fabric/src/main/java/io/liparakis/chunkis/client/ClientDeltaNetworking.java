package io.liparakis.chunkis.client;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.network.ChunkDeltaPayload;
import io.liparakis.chunkis.network.FabricNetworkCodecFactory;
import io.liparakis.chunkis.storage.codec.network.CisNetworkDecoder;
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

import java.io.IOException;

@Environment(EnvType.CLIENT)
public final class ClientDeltaNetworking {

    private static final String APPLY_SOURCE = "ClientDeltaNetworking#processChunkDelta";

    private static final ThreadLocal<CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound>> DECODER =
            ThreadLocal.withInitial(FabricNetworkCodecFactory::createDecoder);

    private static final ThreadLocal<ClientDeltaVisitor> VISITOR =
            ThreadLocal.withInitial(ClientDeltaVisitor::new);

    private static volatile boolean typeWarningLogged = false;

    private ClientDeltaNetworking() {
        throw new AssertionError("Utility class");
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                ChunkDeltaPayload.ID,
                ClientDeltaNetworking::handleIncomingPayload);

        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> cleanupThreadLocals());
    }

    private static void handleIncomingPayload(
            final ChunkDeltaPayload payload,
            final ClientPlayNetworking.Context context
    ) {
        final byte[] data = payload.data();
        final String operationId = ChunkTraceStore.nextOperationId("client-sync");

        if (isInvalidPayload(data)) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_FAILED,
                    ChunkTraceSeverity.WARN,
                    ChunkTraceReason.INVALID_PAYLOAD,
                    APPLY_SOURCE,
                    "rejected incoming payload because it was null or empty",
                    null,
                    new DebugChunkKey(payload.chunkX(), payload.chunkZ()),
                    null,
                    operationId,
                    null,
                    data == null ? null : data.length
            );
            ClientDeltaMetrics.logErrorThrottled(
                    () -> "Received invalid ChunkDeltaPayload: null or empty data");
            return;
        }

        if (ClientDeltaMetrics.ENABLED) {
            ClientDeltaMetrics.recordPacket(data.length);
        }

        final var client = context.client();
        client.execute(() -> processChunkDelta(payload, client.world, operationId));
    }

    private static void processChunkDelta(
            final ChunkDeltaPayload payload,
            final ClientWorld world,
            final String operationId
    ) {
        if (world == null) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_FAILED,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.CLIENT_WORLD_UNAVAILABLE,
                    APPLY_SOURCE,
                    "skipped payload because client world was unavailable",
                    null,
                    new DebugChunkKey(payload.chunkX(), payload.chunkZ()),
                    null,
                    operationId,
                    null,
                    payload.data().length
            );
            return;
        }

        final long startNanos = captureStartTime();
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CLIENT_SYNC,
                ChunkTraceEventType.CLIENT_SYNC_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                APPLY_SOURCE,
                describeIncomingPayload(payload, "starting client delta apply"),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(payload.chunkX(), payload.chunkZ()),
                null,
                operationId,
                null,
                payload.data().length
        );

        try {
            final int changedBlockCount = applyPayloadToWorld(payload, world, operationId);
            recordMetricsIfEnabled(changedBlockCount, startNanos);
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_TX_END,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    APPLY_SOURCE,
                    describeIncomingPayload(payload, "completed client delta apply"),
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(payload.chunkX(), payload.chunkZ()),
                    null,
                    operationId,
                    null,
                    payload.data().length
            );

            Chunkis.LOGGER.debug("Applied ChunkDelta ({},{}) - {} bytes, {} blocks",
                    payload.chunkX(), payload.chunkZ(),
                    payload.data().length,
                    changedBlockCount);

        } catch (final IOException e) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_FAILED,
                    ChunkTraceSeverity.ERROR,
                    classifyClientSyncFailure(e),
                    APPLY_SOURCE,
                    "client delta decode failed",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(payload.chunkX(), payload.chunkZ()),
                    null,
                    operationId,
                    null,
                    payload.data().length
            );
            ClientDeltaMetrics.logErrorThrottled(() -> String.format(
                    "Decode failed for chunk (%d,%d) - %d bytes",
                    payload.chunkX(), payload.chunkZ(),
                    payload.data().length), e);
        } catch (final Exception e) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.IO_EXCEPTION,
                    APPLY_SOURCE,
                    "client delta apply failed with exception",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(payload.chunkX(), payload.chunkZ()),
                    null,
                    operationId,
                    null,
                    payload.data().length
            );
            ClientDeltaMetrics.logErrorThrottled(() -> String.format(
                    "Apply failed for chunk (%d,%d) - %d bytes",
                    payload.chunkX(), payload.chunkZ(),
                    payload.data().length), e);
        }
    }

    private static int applyPayloadToWorld(
            final ChunkDeltaPayload payload,
            final ClientWorld world,
            final String operationId
    ) throws Exception {
        final int chunkX = payload.chunkX();
        final int chunkZ = payload.chunkZ();

        final WorldChunk chunk = world.getChunk(chunkX, chunkZ);
        if (!isChunkisDuck(chunk, chunkX, chunkZ)) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CLIENT_SYNC,
                    ChunkTraceEventType.CLIENT_SYNC_FAILED,
                    ChunkTraceSeverity.WARN,
                    ChunkTraceReason.CHUNK_NOT_DELTA_CAPABLE,
                    APPLY_SOURCE,
                    "client chunk did not implement ChunkisDeltaDuck",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkX, chunkZ),
                    null,
                    operationId,
                    null,
                    payload.data().length
            );
            return 0;
        }

        final ChunkDelta<BlockState, NbtCompound> receivedDelta = DECODER.get().decode(payload.data());

        @SuppressWarnings("unchecked")
        final ChunkDelta<BlockState, NbtCompound> clientDelta =
                (ChunkDelta<BlockState, NbtCompound>) ((ChunkisDeltaDuck) chunk).chunkis$getDelta();

        applyDelta(clientDelta, receivedDelta, world, chunkX, chunkZ);
        return receivedDelta.getBlockInstructions().size();
    }

    private static void applyDelta(
            final ChunkDelta<BlockState, NbtCompound> clientDelta,
            final ChunkDelta<BlockState, NbtCompound> receivedDelta,
            final ClientWorld world,
            final int chunkX,
            final int chunkZ
    ) {
        final ClientDeltaVisitor visitor = VISITOR.get();
        visitor.reset(clientDelta, world, chunkX, chunkZ);
        receivedDelta.accept(visitor);
    }

    private static long captureStartTime() {
        return ClientDeltaMetrics.ENABLED ? System.nanoTime() : 0L;
    }

    private static void recordMetricsIfEnabled(
            final int changedBlockCount,
            final long startNanos
    ) {
        if (!ClientDeltaMetrics.ENABLED) {
            return;
        }

        ClientDeltaMetrics.recordDecode(System.nanoTime() - startNanos);
        ClientDeltaMetrics.recordBlocksChanged(changedBlockCount);

        if ((ClientDeltaMetrics.packetCount() & 0x3FF) == 0) {
            ClientDeltaMetrics.logSummary();
        }
    }

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

    static String describeIncomingPayload(
            final ChunkDeltaPayload payload,
            final String prefix
    ) {
        return prefix
                + " compressedOnWire="
                + payload.compressed()
                + " decodedBytes="
                + payload.data().length;
    }

    private static boolean isInvalidPayload(final byte[] data) {
        return data == null || data.length == 0;
    }

    private static boolean isChunkisDuck(
            final WorldChunk chunk,
            final int chunkX,
            final int chunkZ
    ) {
        if (chunk instanceof ChunkisDeltaDuck) {
            return true;
        }

        logTypeWarningOnce(chunk, chunkX, chunkZ);
        return false;
    }

    private static void logTypeWarningOnce(
            final WorldChunk chunk,
            final int chunkX,
            final int chunkZ
    ) {
        if (typeWarningLogged) {
            return;
        }
        typeWarningLogged = true;

        Chunkis.LOGGER.warn(
                "Chunkis: Chunk at ({},{}) does not implement ChunkisDeltaDuck: {}. (Warning shown once only.)",
                chunkX, chunkZ, chunk.getClass().getName());
    }

    private static void cleanupThreadLocals() {
        DECODER.remove();
        VISITOR.remove();
        Chunkis.LOGGER.debug("Chunkis: Cleaned up client thread-local resources");
    }
}
