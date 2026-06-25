package io.liparakis.chunkis.client;

import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.network.ChunkDeltaPayload;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientDeltaNetworkingTest {

    @Test
    void classifiesUnknownBlockIdAsMappingLookupFailure() {
        assertEquals(
                ChunkTraceReason.MAPPING_LOOKUP_FAILED,
                ClientDeltaNetworking.classifyClientSyncFailure(
                        new IOException("Unknown Block ID 42 - stream desync detected"))
        );
    }

    @Test
    void classifiesOtherIoFailuresAsDecodeFailures() {
        assertEquals(
                ChunkTraceReason.DECODE_FAILED,
                ClientDeltaNetworking.classifyClientSyncFailure(
                        new IOException("Invalid property data length: -1"))
        );
    }

    @Test
    void leavesNonIoFailuresAsGenericApplyFailures() {
        assertEquals(
                ChunkTraceReason.IO_EXCEPTION,
                ClientDeltaNetworking.classifyClientSyncFailure(new IllegalStateException("boom"))
        );
    }

    @Test
    void describesIncomingPayloadCompressionState() {
        final ChunkDeltaPayload payload = new ChunkDeltaPayload(new byte[512], 7, -2, true, 2048);

        assertEquals(
                "completed client delta apply compressedOnWire=true decodedBytes=512",
                ClientDeltaNetworking.describeIncomingPayload(payload, "completed client delta apply")
        );
    }
}
