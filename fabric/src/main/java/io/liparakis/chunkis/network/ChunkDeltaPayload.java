package io.liparakis.chunkis.network;

import io.liparakis.chunkis.Chunkis;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Network payload carrying a serialised Chunkis chunk delta, with optional
 * zlib compression for large payloads.
 *
 * <h2>Wire format</h2>
 * <pre>
 *   [chunkX      : int  ]
 *   [chunkZ      : int  ]
 *   [flags       : byte ]  bit 0 = compressed
 *   [dataLength  : int  ]  byte length of the following data field
 *   [data        : bytes]  compressed bytes (if compressed flag set) or raw bytes
 *   [origLength  : int  ]  ONLY present when compressed flag is set;
 *                          byte length of the uncompressed payload
 * </pre>
 *
 * <h2>Compression policy</h2>
 * <p>
 * Compression is attempted only when the raw payload is at least
 * {@value #COMPRESSION_THRESHOLD} bytes. The compressed form is used only when
 * it is at least 10% smaller than the
 * original — below that, the decompression overhead is not worthwhile.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * {@link Deflater} and {@link Inflater} instances are pooled per-thread to avoid
 * construction cost and synchronization overhead on the Netty I/O threads.
 * </p>
 *
 * @param data             raw or compressed serialised block delta byte data
 * @param chunkX           chunk X position
 * @param chunkZ           chunk Z position
 * @param compressed       true if byte data is compressed
 * @param uncompressedSize size of raw byte data when uncompressed
 */
public record ChunkDeltaPayload(
        byte[] data,
        int chunkX,
        int chunkZ,
        boolean compressed,
        int uncompressedSize) implements CustomPayload {

    /**
     * Packet channel identifier: {@code <modId>:chunk_delta}.
     */
    public static final CustomPayload.Id<ChunkDeltaPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Chunkis.MOD_ID, "chunk_delta"));

    /**
     * Hard cap on incoming payload data length to guard against malformed packets.
     */
    private static final int MAX_PAYLOAD_SIZE = 1024 * 1024; // 1 MB

    /**
     * Minimum raw payload size in bytes before compression is attempted.
     */
    private static final int COMPRESSION_THRESHOLD = 4096; // 4 KB

    /**
     * Compression ratio threshold factor.
     */
    private static final double COMPRESSION_RATIO_THRESHOLD = 0.9;

    /**
     * Wire flag indicating the payload data is zlib-compressed.
     */
    private static final byte FLAG_COMPRESSED = (byte) 0x01;

    /**
     * Wire flag indicating the payload data is uncompressed.
     */
    private static final byte FLAG_UNCOMPRESSED = (byte) 0x00;

    /**
     * Scratch buffer size for deflate/inflate loops.
     * 64 KB reduces loop iterations for large chunk payloads (200–400 KB).
     */
    private static final int COMPRESSION_BUFFER_SIZE = 65536;

    /**
     * Per-thread {@link Deflater} pool.
     */
    private static final ThreadLocal<Deflater> DEFLATER_POOL =
            ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED));

    /**
     * Per-thread {@link Inflater} pool.
     */
    private static final ThreadLocal<Inflater> INFLATER_POOL =
            ThreadLocal.withInitial(Inflater::new);

    /**
     * Per-thread scratch buffer reused across deflate/inflate calls.
     * Eliminates a 64 KB heap allocation per compress/decompress invocation.
     */
    private static final ThreadLocal<byte[]> COMPRESSION_BUFFER =
            ThreadLocal.withInitial(() -> new byte[COMPRESSION_BUFFER_SIZE]);

    /**
     * Codec wiring the static {@link #read} and instance {@link #write} methods.
     */
    public static final PacketCodec<RegistryByteBuf, ChunkDeltaPayload> CODEC =
            PacketCodec.of(ChunkDeltaPayload::write, ChunkDeltaPayload::read);

    /**
     * Creates a payload from raw (uncompressed) delta bytes, compressing
     * automatically when the result is meaningfully smaller.
     *
     * <p>
     * Compression is applied when both:
     * <ul>
     * <li>the raw size is at least {@value #COMPRESSION_THRESHOLD} bytes, and</li>
     * <li>the compressed size is less than 10%
     *     of the raw size.</li>
     * </ul>
     * Otherwise a defensive copy of the raw bytes is stored uncompressed.
     * </p>
     *
     * @param rawData raw serialised delta bytes (must not be null)
     * @param chunkX  chunk X coordinate
     * @param chunkZ  chunk Z coordinate
     * @return a ready-to-send payload
     */
    public static ChunkDeltaPayload create(final byte[] rawData, final int chunkX, final int chunkZ) {
        Objects.requireNonNull(rawData, "rawData must not be null");

        if (shouldAttemptCompression(rawData)) {
            final byte[] compressed = compress(rawData);
            if (isCompressionBeneficial(compressed, rawData)) {
                return new ChunkDeltaPayload(compressed, chunkX, chunkZ, true, rawData.length);
            }
        }

        // Defensive copy so the caller cannot mutate the payload after creation.
        return new ChunkDeltaPayload(Arrays.copyOf(rawData, rawData.length), chunkX, chunkZ, false, 0);
    }

    /**
     * Deserializes a {@link ChunkDeltaPayload} from the given packet buffer.
     *
     * <p>Wire order: {@code chunkX | chunkZ | flags | dataLength | data | [origLength if compressed]}.</p>
     *
     * @param buf the buffer to read from
     * @return the deserialized payload
     * @throws IllegalArgumentException if the buffer contains invalid data
     */
    private static ChunkDeltaPayload read(final RegistryByteBuf buf) {
        try {
            final int chunkX = buf.readInt();
            final int chunkZ = buf.readInt();
            final byte flags = buf.readByte();
            final boolean isCompressed = isCompressedFlag(flags);

            final int dataLength = buf.readInt();
            validatePayloadLength(dataLength, "data length");

            final byte[] data = new byte[dataLength];
            buf.readBytes(data);

            if (isCompressed) {
                final int originalSize = buf.readInt();
                validatePayloadLength(originalSize, "decompressed size");
                final byte[] decompressed = decompress(data, originalSize);
                return new ChunkDeltaPayload(decompressed, chunkX, chunkZ, true, originalSize);
            }

            return new ChunkDeltaPayload(data, chunkX, chunkZ, false, 0);

        } catch (final Exception e) {
            throw new IllegalArgumentException("Failed to read ChunkDeltaPayload", e);
        }
    }

    /**
     * Compresses {@code data} using the thread-local {@link Deflater}.
     * The deflater is reset before use to ensure clean state.
     *
     * @param data the bytes to compress (must not be null or empty)
     * @return the compressed bytes
     */
    private static byte[] compress(final byte[] data) {
        final Deflater deflater = DEFLATER_POOL.get();
        deflater.reset();
        deflater.setInput(data);
        deflater.finish();

        // Pre-size at half the input length as a heuristic for typical compression ratios.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length / 2);
        // Reuse the thread-local scratch buffer; avoids a 64 KB allocation per call.
        final byte[] buf = COMPRESSION_BUFFER.get();

        while (!deflater.finished()) {
            baos.write(buf, 0, deflater.deflate(buf));
        }

        return baos.toByteArray();
    }

    /**
     * Decompresses {@code compressed} using the thread-local {@link Inflater}.
     *
     * <p>Uses streaming inflate so that a mismatch between {@code originalSize}
     * and the true inflated length is detected and reported rather than causing
     * a buffer overflow or silent data truncation.</p>
     *
     * @param compressed   the compressed bytes to inflate
     * @param originalSize the expected decompressed byte count
     * @return the inflated bytes
     * @throws IOException         if the inflated length does not match {@code originalSize}
     * @throws DataFormatException if the compressed data is corrupted
     */
    private static byte[] decompress(final byte[] compressed, final int originalSize)
            throws IOException, DataFormatException {

        final Inflater inflater = INFLATER_POOL.get();
        inflater.reset();
        inflater.setInput(compressed);

        final ByteArrayOutputStream baos = new ByteArrayOutputStream(originalSize);
        final byte[] buf = COMPRESSION_BUFFER.get();

        while (!inflater.finished() && !inflater.needsInput()) {
            baos.write(buf, 0, inflater.inflate(buf));
        }

        final byte[] result = baos.toByteArray();
        validateDecompressedSize(result.length, originalSize);
        return result;
    }

    /**
     * Throws {@link IllegalArgumentException} if the given length is negative or
     * exceeds {@link #MAX_PAYLOAD_SIZE}.
     *
     * @param length    the length to validate
     * @param fieldName a human-readable field name used in the error message
     */
    private static void validatePayloadLength(final int length, final String fieldName) {
        if (length < 0 || length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException(
                    "Payload " + fieldName + " out of range: " + length);
        }
    }

    /**
     * Throws {@link IOException} if the actual decompressed size does not match
     * the expected size declared in the wire format.
     *
     * @param actual   the actual number of bytes produced by inflation
     * @param expected the expected number declared in the packet
     * @throws IOException if the sizes differ
     */
    private static void validateDecompressedSize(final int actual, final int expected) throws IOException {
        if (actual != expected) {
            throw new IOException(String.format(
                    "Decompression size mismatch: expected %d bytes, got %d bytes", expected, actual));
        }
    }

    /**
     * Returns true if the raw data is large enough to make compression worthwhile.
     *
     * @param rawData the uncompressed payload bytes
     * @return true if compression should be attempted
     */
    private static boolean shouldAttemptCompression(final byte[] rawData) {
        return rawData.length >= COMPRESSION_THRESHOLD;
    }

    /**
     * Returns true if the compressed form is small enough relative to the original
     * to justify the decompression overhead on the receiving end.
     *
     * @param compressed the compressed bytes
     * @param raw        the original uncompressed bytes
     * @return true if the compressed form should be used
     */
    private static boolean isCompressionBeneficial(final byte[] compressed, final byte[] raw) {
        return compressed.length < raw.length * COMPRESSION_RATIO_THRESHOLD;
    }

    /**
     * Returns true if the compressed flag bit is set in the given flags byte.
     *
     * @param flags the flags byte read from the wire
     * @return true if the payload is compressed
     */
    private static boolean isCompressedFlag(final byte flags) {
        return (flags & FLAG_COMPRESSED) != 0;
    }

    /**
     * Serializes this payload into the given packet buffer.
     *
     * <p>Wire order: {@code chunkX | chunkZ | flags | dataLength | data | [origLength if compressed]}.</p>
     *
     * @param buf the buffer to write to
     */
    private void write(final RegistryByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeByte(compressed ? FLAG_COMPRESSED : FLAG_UNCOMPRESSED);
        buf.writeInt(data.length);
        buf.writeBytes(data);

        if (compressed) {
            // Receiver needs the original size to pre-allocate the decompression buffer.
            buf.writeInt(uncompressedSize);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}