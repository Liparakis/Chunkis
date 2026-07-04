package io.liparakis.chunkis.storage.io;

import com.github.luben.zstd.Zstd;
import io.liparakis.chunkis.storage.model.CisConstants;

import java.io.IOException;

/**
 * Thread-local compression context to avoid allocations and synchronization.
 */
final class CompressionContext {

    CompressionContext() {
    }

    /**
     * Compresses data using Zstd.
     *
     * @param data the raw data
     * @return the compressed data
     */
    byte[] compress(byte[] data) {
        return Zstd.compress(data, CisConstants.COMPRESSION_LEVEL);
    }

    /**
     * Decompresses data using Zstd.
     *
     * @param data the compressed data
     * @return the raw data
     * @throws IOException if decompression fails
     */
    byte[] decompress(byte[] data) throws IOException {
        if (data.length > 0 && data[0] == 0x78) {
            try {
                final java.util.zip.Inflater inflater = new java.util.zip.Inflater();
                inflater.setInput(data);
                final byte[] buffer = new byte[8192];
                final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                while (!inflater.finished()) {
                    final int read = inflater.inflate(buffer);
                    if (read == 0 && inflater.needsInput()) {
                        break;
                    }
                    output.write(buffer, 0, read);
                }
                inflater.end();
                return output.toByteArray();
            } catch (final Exception e) {
                throw new IOException("Failed to decompress CIS legacy Zlib payload", e);
            }
        }
        return CisCompression.decompressZstd(data);
    }
}
