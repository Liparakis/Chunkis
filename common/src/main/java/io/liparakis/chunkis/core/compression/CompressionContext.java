package io.liparakis.chunkis.core.compression;

import com.github.luben.zstd.ZstdCompressCtx;
import io.liparakis.chunkis.core.model.CisConstants;

import java.io.IOException;

/**
 * Stateless compression entry point (Zstd, plus legacy zlib on read); safe to share across threads.
 */
public final class CompressionContext {

    /**
     * Per-thread reusable Zstd compression context. Allocating native compression state is a large part of
     * compressing one chunk; {@code Zstd.compress(data, level)} does exactly this with a fresh context per call,
     * so frames are identical.
     */
    private static final ThreadLocal<ZstdCompressCtx> COMPRESS_CTX = ThreadLocal.withInitial(
            () -> new ZstdCompressCtx().setLevel(CisConstants.COMPRESSION_LEVEL));

    /**
     * Performs compression context.
     */
    public CompressionContext() {
    }

    /**
     * Compresses data using Zstd.
     *
     * @param data the raw data
     * @return the compressed data
     */
    public byte[] compress(byte[] data) {
        return COMPRESS_CTX.get().compress(data);
    }

    /**
     * Decompresses data using Zstd.
     *
     * @param data the compressed data
     * @return the raw data
     * @throws IOException if decompression fails
     */
    public byte[] decompress(byte[] data) throws IOException {
        if (data.length > 0 && data[0] == 0x78) {
            final java.util.zip.Inflater inflater = new java.util.zip.Inflater();
            try {
                inflater.setInput(data);
                final byte[] buffer = new byte[8192];
                final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                while (!inflater.finished()) {
                    final int read = inflater.inflate(buffer);
                    if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                        throw new IOException("Truncated legacy Zlib payload: stream ended after "
                                + output.size() + " bytes");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            } catch (final IOException e) {
                throw e;
            } catch (final Exception e) {
                throw new IOException("Failed to decompress CIS legacy Zlib payload", e);
            } finally {
                inflater.end();
            }
        }
        return CisCompression.decompressZstd(data);
    }
}
