package io.liparakis.chunkis.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkisNetworkingTest {

    @Test
    void describesOutgoingPayloadCompressionState() {
        final ChunkDeltaPayload payload = new ChunkDeltaPayload(new byte[128], 3, 4, true, 512);

        assertEquals(
                "sent delta to player Alex rawBytes=512 wireBytes=128 compressed=true",
                ChunkisNetworking.describePayloadOutcome("Alex", 512, payload)
                    );
    }
}
