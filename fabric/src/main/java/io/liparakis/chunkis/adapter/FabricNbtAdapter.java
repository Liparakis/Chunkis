package io.liparakis.chunkis.adapter;

import io.liparakis.chunkis.spi.NbtAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

/**
 * NBT adapter for Chunkis' length-prefixed NBT payload format.
 *
 * <p>Both compressed and raw payloads are stored as:</p>
 * <ol>
 *   <li>an {@code int} byte length</li>
 *   <li>exactly that many payload bytes</li>
 * </ol>
 *
 * <p>Writes serialize into a reusable thread-local byte buffer first because the
 * payload length must be known before the final output receives the length prefix.
 * The valid range of the reusable backing array is then written directly, avoiding
 * {@link ByteArrayOutputStream#toByteArray()} copies.</p>
 *
 * <p>Reads stream directly from {@link DataInputStream} through a bounded wrapper.
 * Generic {@link DataInput} sources are copied into a reusable thread-local read
 * buffer first because they do not expose an {@link InputStream}.</p>
 *
 * <p><b>Thread safety:</b> buffers are stored in {@link ThreadLocal}. No world,
 * chunk, registry, or mutable NBT references are retained by this adapter.</p>
 */
public final class FabricNbtAdapter implements NbtAdapter<NbtCompound> {

    /**
     * Maximum accepted payload size in bytes.
     *
     * <p>For compressed NBT, this is the compressed byte count. Parsed NBT size is
     * still guarded separately by {@link NbtSizeTracker}.</p>
     */
    private static final int MAX_NBT_SIZE = 16 * 1024 * 1024;

    /**
     * Initial reusable buffer size.
     */
    private static final int BUFFER_SIZE = 8 * 1024;

    /**
     * Maximum buffer capacity retained in a thread-local holder.
     *
     * <p>This prevents a rare huge NBT payload from making the server thread retain
     * a multi-megabyte byte array forever. Normal chunk payloads still reuse buffers
     * without allocation churn.</p>
     */
    private static final int MAX_RETAINED_BUFFER_SIZE = 1024 * 1024;

    /**
     * Per-thread reusable buffers.
     */
    private static final ThreadLocal<BufferHolder> BUFFER_POOL =
            ThreadLocal.withInitial(BufferHolder::new);

    /**
     * Writes a serialized payload as {@code int length + bytes}.
     *
     * <p>The payload is written directly from the reusable buffer's backing array.
     * Only bytes in {@code [0, size)} are valid.</p>
     *
     * @param output target output
     * @param buffer serialized payload buffer
     * @throws IOException if output fails or the payload exceeds the size limit
     */
    private static void writeLengthPrefixedPayload(
            final DataOutput output,
            final ReusableByteArrayOutputStream buffer
    ) throws IOException {
        final int size = buffer.size();

        validatePayloadSize(size);

        output.writeInt(size);
        output.write(buffer.array(), 0, size);
    }

    /**
     * Reads and validates the length prefix.
     *
     * @param input source input
     * @return validated payload length
     * @throws IOException if the length is invalid
     */
    private static int readAndValidateLength(final DataInput input) throws IOException {
        final int length = input.readInt();
        validateReadLength(length);
        return length;
    }

    /**
     * Reads compressed NBT from a generic {@link DataInput}.
     *
     * @param input  source input
     * @param length exact payload byte length
     * @return parsed NBT compound
     * @throws IOException if reading or parsing fails
     */
    private static NbtCompound readCompressedBuffered(
            final DataInput input,
            final int length
    ) throws IOException {
        final BufferHolder holder = BUFFER_POOL.get();
        final byte[] buffer = holder.getReadBuffer(length);

        try {
            input.readFully(buffer, 0, length);

            try (ByteArrayInputStream stream = new ByteArrayInputStream(buffer, 0, length)) {
                return NbtIo.readCompressed(stream, NbtSizeTracker.of(MAX_NBT_SIZE));
            }
        } finally {
            holder.releaseReadBufferIfOversized();
        }
    }

    /**
     * Reads raw NBT from a generic {@link DataInput}.
     *
     * @param input  source input
     * @param length exact payload byte length
     * @return parsed NBT compound
     * @throws IOException if reading or parsing fails
     */
    private static NbtCompound readRawBuffered(
            final DataInput input,
            final int length
    ) throws IOException {
        final BufferHolder holder = BUFFER_POOL.get();
        final byte[] buffer = holder.getReadBuffer(length);

        try {
            input.readFully(buffer, 0, length);

            try (ByteArrayInputStream byteInput = new ByteArrayInputStream(buffer, 0, length);
                    DataInputStream nbtInput = new DataInputStream(byteInput)) {
                return NbtIo.readCompound(nbtInput, NbtSizeTracker.of(MAX_NBT_SIZE));
            }
        } finally {
            holder.releaseReadBufferIfOversized();
        }
    }

    /**
     * Validates serialized payload size before writing.
     *
     * @param size serialized payload size
     * @throws IOException if the size is invalid
     */
    private static void validatePayloadSize(final int size) throws IOException {
        if (size <= 0 || size > MAX_NBT_SIZE) {
            throw new IOException(
                    "Invalid NBT payload size: "
                            + size
                            + " bytes (expected 1-"
                            + MAX_NBT_SIZE
                            + ")"
            );
        }
    }

    /**
     * Validates a payload length read from storage.
     *
     * @param length declared payload length
     * @throws IOException if the length is invalid
     */
    private static void validateReadLength(final int length) throws IOException {
        if (length <= 0 || length > MAX_NBT_SIZE) {
            throw new IOException(
                    "Invalid NBT payload size: "
                            + length
                            + " bytes (expected 1-"
                            + MAX_NBT_SIZE
                            + ")"
            );
        }
    }

    /**
     * Writes compressed NBT.
     *
     * @param nbt    NBT compound to write
     * @param output target output
     * @throws IOException if serialization or output fails
     */
    @Override
    public void write(final NbtCompound nbt, final DataOutput output) throws IOException {
        writeCompressed(nbt, output);
    }

    /**
     * Writes a compressed, length-prefixed NBT compound.
     *
     * @param nbt    NBT compound to write
     * @param output target output
     * @throws IOException if serialization fails or the payload exceeds the size limit
     */
    @Override
    public void writeCompressed(
            final NbtCompound nbt,
            final DataOutput output
    ) throws IOException {
        Objects.requireNonNull(nbt, "nbt");
        Objects.requireNonNull(output, "output");

        final BufferHolder holder = BUFFER_POOL.get();
        final ReusableByteArrayOutputStream buffer = holder.getWriteBuffer();

        try {
            NbtIo.writeCompressed(nbt, buffer);
            writeLengthPrefixedPayload(output, buffer);
        } finally {
            holder.releaseWriteBufferIfOversized();
        }
    }

    /**
     * Reads compressed NBT.
     *
     * @param input source input
     * @return parsed NBT compound
     * @throws IOException if reading or parsing fails
     */
    @Override
    public NbtCompound read(final DataInput input) throws IOException {
        return readCompressed(input);
    }

    /**
     * Reads a compressed, length-prefixed NBT compound.
     *
     * @param input source input
     * @return parsed NBT compound
     * @throws IOException if the declared length is invalid or parsing fails
     */
    @Override
    public NbtCompound readCompressed(final DataInput input) throws IOException {
        Objects.requireNonNull(input, "input");

        final int length = readAndValidateLength(input);

        return readCompressedBuffered(input, length);
    }

    /**
     * Writes a raw, uncompressed, length-prefixed NBT compound.
     *
     * @param nbt    NBT compound to write
     * @param output target output
     * @throws IOException if serialization fails or the payload exceeds the size limit
     */
    @Override
    public void writeRaw(
            final NbtCompound nbt,
            final DataOutput output
    ) throws IOException {
        Objects.requireNonNull(nbt, "nbt");
        Objects.requireNonNull(output, "output");

        final BufferHolder holder = BUFFER_POOL.get();
        final ReusableByteArrayOutputStream buffer = holder.getWriteBuffer();

        try {
            final DataOutputStream nbtOutput = new DataOutputStream(buffer);
            NbtIo.writeCompound(nbt, nbtOutput);
            nbtOutput.flush();

            writeLengthPrefixedPayload(output, buffer);
        } finally {
            holder.releaseWriteBufferIfOversized();
        }
    }

    /**
     * Reads a raw, uncompressed, length-prefixed NBT compound.
     *
     * @param input source input
     * @return parsed NBT compound
     * @throws IOException if the declared length is invalid or parsing fails
     */
    @Override
    public NbtCompound readRaw(final DataInput input) throws IOException {
        Objects.requireNonNull(input, "input");

        final int length = readAndValidateLength(input);

        return readRawBuffered(input, length);
    }


    /**
     * Per-thread reusable buffer holder.
     */
    private static final class BufferHolder {

        /**
         * Reusable serialized output buffer.
         */
        private ReusableByteArrayOutputStream writeBuffer =
                new ReusableByteArrayOutputStream(BUFFER_SIZE);
        /**
         * Reusable generic-input read buffer.
         */
        private byte[] readBuffer = new byte[BUFFER_SIZE];

        /**
         * Returns a reset write buffer.
         *
         * @return reusable output buffer
         */
        private ReusableByteArrayOutputStream getWriteBuffer() {
            writeBuffer.reset();
            return writeBuffer;
        }

        /**
         * Returns a read buffer large enough for {@code length} bytes.
         *
         * @param length required capacity
         * @return reusable read buffer
         */
        private byte[] getReadBuffer(final int length) {
            if (length > readBuffer.length) {
                readBuffer = new byte[length];
            }

            return readBuffer;
        }

        /**
         * Drops oversized write buffers so rare huge payloads are not retained forever.
         */
        private void releaseWriteBufferIfOversized() {
            if (writeBuffer.capacity() > MAX_RETAINED_BUFFER_SIZE) {
                writeBuffer = new ReusableByteArrayOutputStream(BUFFER_SIZE);
                return;
            }

            writeBuffer.reset();
        }

        /**
         * Drops oversized read buffers so rare huge payloads are not retained forever.
         */
        private void releaseReadBufferIfOversized() {
            if (readBuffer.length > MAX_RETAINED_BUFFER_SIZE) {
                readBuffer = new byte[BUFFER_SIZE];
            }
        }
    }

    /**
     * {@link ByteArrayOutputStream} with safe backing-array access.
     *
     * <p>Only bytes in {@code [0, size())} are valid payload bytes.</p>
     */
    private static final class ReusableByteArrayOutputStream extends ByteArrayOutputStream {

        /**
         * @param size initial capacity
         */
        private ReusableByteArrayOutputStream(final int size) {
            super(size);
        }

        /**
         * Returns the backing byte array.
         *
         * @return backing array
         */
        private byte[] array() {
            return buf;
        }

        /**
         * Returns current backing-array capacity.
         *
         * @return buffer capacity
         */
        private int capacity() {
            return buf.length;
        }
    }

}