package io.liparakis.chunkis.core.compression;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Covers reuse and native-state release independently of the thread-local cache.
 */
class ReusableCompressorTest {

    /**
     * Repeated compression keeps producing readable, deterministic frames.
     *
     * @throws IOException if the frame cannot be decoded
     */
    @Test
    void reusesContextWithoutChangingFrameContents() throws IOException {
        final byte[] payload = "Chunkis reusable native compression fixture".getBytes(StandardCharsets.UTF_8);
        try (CompressionContext.ReusableCompressor compressor = new CompressionContext.ReusableCompressor()) {
            final byte[] first = compressor.compress(payload);
            assertArrayEquals(first, compressor.compress(payload));
            assertArrayEquals(payload, new CompressionContext().decompress(first));
        }
    }

    /**
     * Cleanup is idempotent and closes the native context rather than just discarding its Java wrapper.
     */
    @Test
    void cleanupReleasesNativeContextExactlyOnce() {
        final CompressionContext.ReusableCompressor compressor = new CompressionContext.ReusableCompressor();
        compressor.close();
        compressor.close();
        assertThrows(IllegalStateException.class, () -> compressor.compress(new byte[]{1, 2, 3}));
    }
}
