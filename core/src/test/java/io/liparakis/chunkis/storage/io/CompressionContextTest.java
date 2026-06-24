package io.liparakis.chunkis.storage.io;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CompressionContextTest {

    @Test
    void roundTripsPayloadsWithZstdLevelThree() throws Exception {
        final CompressionContext context = new CompressionContext();
        final byte[] smallPayload = new byte[256];
        final byte[] largePayload = new byte[16 * 1024];

        for (int i = 0; i < smallPayload.length; i++) {
            smallPayload[i] = (byte) i;
        }
        Arrays.fill(largePayload, (byte) 42);

        assertThat(context.decompress(context.compress(smallPayload))).isEqualTo(smallPayload);
        assertThat(context.decompress(context.compress(largePayload))).isEqualTo(largePayload);
        assertThat(context.decompress(context.compress(smallPayload))).isEqualTo(smallPayload);
    }
}
