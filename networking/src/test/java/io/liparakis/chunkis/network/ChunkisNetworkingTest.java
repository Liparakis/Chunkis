package io.liparakis.chunkis.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Test class for verifying network communication reporting in {@link ChunkisNetworking}.
 */
class ChunkisNetworkingTest {

    /**
     * Verifies that the string description of an outgoing payload outcome is formatted correctly
     * with the expected player name, compression status, wire bytes, and uncompressed size.
     */
    @Test
    void describesOutgoingPayloadCompressionState() {
        final ChunkDeltaPayload payload = new ChunkDeltaPayload(new byte[128], 3, 4, true, 512);

        assertEquals(
                "sent delta to player Alex rawBytes=512 wireBytes=128 compressed=true encodeMicros=0 wrapMicros=0 players=1",
                ChunkisNetworking.describePayloadOutcome("Alex", 512, payload)
        );
    }
}
