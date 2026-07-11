package io.liparakis.chunkis.core.compression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CompressionContextTest {

    /** Performs round trips payloads with zstd level three. */
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
