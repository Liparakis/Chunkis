package io.liparakis.chunkis.core.compression;

import com.github.luben.zstd.ZstdCompressCtx;
import io.liparakis.chunkis.core.model.CisConstants;

import java.io.IOException;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;

/**
 * Stateless compression entry point (Zstd, plus legacy zlib on read); safe to share across threads.
 */
public final class CompressionContext {

    /**
     * Per-thread reusable Zstd compression context. Allocating native compression state is a large part of
     * compressing one chunk; {@code Zstd.compress(data, level)} does exactly this with a fresh context per call,
     * so frames are identical.
     */
    private static final ThreadLocal<ReusableCompressor> COMPRESS_CTX = ThreadLocal.withInitial(ReusableCompressor::new);

    /**
     * Releases native state when a worker thread and its thread-local compressor become unreachable.
     */
    private static final Cleaner CLEANER = Cleaner.create();

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
            try (final java.util.zip.Inflater inflater = new java.util.zip.Inflater()) {
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
            }
        }
        return CisCompression.decompressZstd(data);
    }

    /**
     * Owns a reusable native context. The cleanup action retains the context but never this owner.
     */
    static final class ReusableCompressor implements AutoCloseable {

        /**
         * Native context owned by the registered cleanup action.
         */
        private final ZstdCompressCtx context;

        /**
         * Idempotent explicit or garbage-collection cleanup.
         */
        private final Cleaner.Cleanable cleanable;

        /**
         * Allocates compression state once for this worker.
         */
        ReusableCompressor() {
            context = new ZstdCompressCtx().setLevel(CisConstants.COMPRESSION_LEVEL);
            cleanable = CLEANER.register(this, context::close);
        }

        /**
         * Compresses without letting automatic cleanup race the native operation.
         *
         * @param data uncompressed payload
         * @return compressed frame
         */
        byte[] compress(final byte[] data) {
            try {
                return context.compress(data);
            } finally {
                Reference.reachabilityFence(this);
            }
        }

        /**
         * Releases the owned native context, once.
         */
        @Override
        public void close() {
            cleanable.clean();
        }
    }
}
