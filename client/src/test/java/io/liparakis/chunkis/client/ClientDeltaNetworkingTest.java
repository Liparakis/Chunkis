package io.liparakis.chunkis.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.network.ChunkDeltaPayload;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Tests for client-side delta networking functionality and utility methods.
 */
class ClientDeltaNetworkingTest {

    /**
     * Tests that an IOException containing "Unknown Block ID" is classified
     * as a mapping lookup failure.
     */
    @Test
    void classifiesUnknownBlockIdAsMappingLookupFailure() {
        assertEquals(
                ChunkTraceReason.MAPPING_LOOKUP_FAILED,
                ClientDeltaNetworking.classifyClientSyncFailure(
                        new IOException("Unknown Block ID 42 - stream desync detected"))
        );
    }

    /**
     * Tests that other IOExceptions are classified as decode failures.
     */
    @Test
    void classifiesOtherIoFailuresAsDecodeFailures() {
        assertEquals(
                ChunkTraceReason.DECODE_FAILED,
                ClientDeltaNetworking.classifyClientSyncFailure(
                        new IOException("Invalid property data length: -1"))
        );
    }

    /**
     * Tests that non-IOException failures are classified as generic IO exception trace reasons.
     */
    @Test
    void leavesNonIoFailuresAsGenericApplyFailures() {
        assertEquals(
                ChunkTraceReason.IO_EXCEPTION,
                ClientDeltaNetworking.classifyClientSyncFailure(new IllegalStateException("boom"))
        );
    }

    /**
     * Tests that incoming payload compression state is correctly described.
     */
    @Test
    void describesIncomingPayloadCompressionState() {
        final ChunkDeltaPayload payload = new ChunkDeltaPayload(new byte[512], 7, -2, true, 2048);

        assertEquals(
                "completed client delta apply compressedOnWire=true decodedBytes=512",
                ClientDeltaNetworking.describeIncomingPayload(payload, "completed client delta apply")
        );
    }
}
