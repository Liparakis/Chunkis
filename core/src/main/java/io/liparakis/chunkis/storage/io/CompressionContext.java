package io.liparakis.chunkis.storage.io;

import com.github.luben.zstd.Zstd;
import io.liparakis.chunkis.storage.model.CisConstants;

import java.io.IOException;

/**
 * Thread-local compression context to avoid allocations and synchronization.
 *
 * @author Liparakis
 * @version 1
 */
final class CompressionContext {
    CompressionContext() {}

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
        final long decompressedSize = Zstd.decompressedSize(data);
        if (Zstd.isError(decompressedSize)) {
            throw new IOException("Failed to read CIS Zstd size: " + Zstd.getErrorName(decompressedSize));
        }
        if (decompressedSize <= 0L || decompressedSize > Integer.MAX_VALUE) {
            throw new IOException("Invalid CIS Zstd payload size: " + decompressedSize);
        }

        return Zstd.decompress(data, (int) decompressedSize);
    }
}
