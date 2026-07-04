package io.liparakis.chunkis.storage.io;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import java.io.IOException;

/**
 * Utility methods for decompressing CIS storage payloads.
 *
 * <p>This class currently supports Zstandard-compressed payloads used by the CIS
 * storage format. It is intentionally non-instantiable and keeps one reusable
 * {@link ZstdDecompressCtx} per thread to avoid repeatedly allocating native
 * decompression state.</p>
 *
 * <p>The decompression methods in this class expect payloads whose decompressed
 * size is encoded in the Zstd frame metadata. Payloads with an unknown,
 * invalid, empty, or excessively large decompressed size are rejected before
 * decompression.</p>
 */
public final class CisCompression {

    /**
     * Per-thread reusable Zstd decompression context.
     *
     * <p>{@link ZstdDecompressCtx} holds decompression state and may use native
     * resources internally. Keeping it in a {@link ThreadLocal} allows each
     * thread to reuse its own context safely without sharing mutable
     * decompression state across threads.</p>
     */
    private static final ThreadLocal<ZstdDecompressCtx> DECOMPRESS_CTX =
            ThreadLocal.withInitial(ZstdDecompressCtx::new);

    /**
     * Prevents construction of this utility class.
     *
     * @throws AssertionError always, because this class must not be instantiated
     */
    private CisCompression() {
        throw new AssertionError("Utility class");
    }

    /**
     * Decompresses a CIS Zstandard-compressed payload.
     *
     * <p>The compressed payload must contain a valid Zstd frame content size.
     * This method reads that size first, validates that it can safely fit into
     * a Java byte array, and then decompresses the payload into a newly allocated
     * byte array.</p>
     *
     * @param data the Zstd-compressed CIS payload to decompress
     * @return the decompressed payload bytes
     * @throws IOException if the Zstd frame content size cannot be read, if the
     *                     reported decompressed size is invalid, unknown, empty,
     *                     or larger than {@link Integer#MAX_VALUE}, or if
     *                     decompression fails
     */
    public static byte[] decompressZstd(final byte[] data) throws IOException {
        final long decompressedSize = Zstd.getFrameContentSize(data);
        if (Zstd.isError(decompressedSize)) {
            throw new IOException("Failed to read CIS Zstd size: " + Zstd.getErrorName(decompressedSize));
        }
        if (decompressedSize <= 0L || decompressedSize > Integer.MAX_VALUE) {
            throw new IOException("Invalid CIS Zstd payload size: " + decompressedSize);
        }
        return DECOMPRESS_CTX.get()
                .decompress(data, (int) decompressedSize);
    }
}