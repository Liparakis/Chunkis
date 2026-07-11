package io.liparakis.chunkis.core.bits;

import java.util.Arrays;

/**
 * Non-thread-safe bit writer backed by a reusable byte array.
 * <p>
 * This writer allows for writing arbitrary bit lengths into a dynamic byte
 * buffer.
 * It writes bits from most significant to least significant (MSB first).
 * The underlying buffer grows automatically as needed, and the writer can be
 * reset for reuse to minimize garbage collection overhead.
 * </p>
 */
public final class BitWriter {

    /**
     * The raw buffer storing written data
     */
    private byte[] buffer;
    /**
     * Index of the byte currently being written into {@link #buffer}.
     */
    private int index;
    /**
     * Bit cursor within the current output byte, counted from the most significant bit.
     */
    private int bitIndex;

    /** Performs bit writer. */
    public BitWriter(int initialCapacity) {
        this.buffer = new byte[initialCapacity];
    }

    /**
     * Resets the writer for reuse without reallocating the underlying buffer.
     * <p>
     * This is critical for minimizing garbage collection pressure in
     * performance-sensitive
     * loops by allowing the same buffer to be used for multiple write operations.
     * </p>
     */
    public void reset() {
        this.index = 0;
        this.bitIndex = 0;
    }

    /**
     * Ensures the buffer has capacity for the specified number of additional bytes.
     * <p>
     * Grows the buffer exponentially (2x) when needed to amortize resizing costs.
     * </p>
     *
     * @param bytesNeeded The number of additional bytes required
     */
    private void ensureCapacity(int bytesNeeded) {
        int required = index + bytesNeeded;
        if (required >= buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length << 1, required + 128));
        }
    }

    /**
     * Writes up to 64 bits to the stream.
     *
     * @param value The value to write
     * @param bits  The number of bits to write (0-64)
     */
    public void write(long value, int bits) {
        if (bits == 0) {
            return;
        }

        ensureCapacity((bits >>> 3) + 2);

        if (bits != 64) {
            value &= (1L << bits) - 1;
        }

        while (bits > 0) {
            int space = 8 - bitIndex;
            int take = Math.min(bits, space);
            long chunk = (value >>> (bits - take)) & ((1L << take) - 1);

            if (bitIndex == 0) {
                buffer[index] = (byte) (chunk << (space - take));
            } else {
                buffer[index] |= (byte) (chunk << (space - take));
            }

            bitIndex += take;
            bits -= take;

            if (bitIndex == 8) {
                index++;
                bitIndex = 0;
            }
        }
    }

    /**
     * Writes a ZigZag-encoded signed integer.
     *
     * @param value The signed integer to encode and write
     * @param bits  The number of bits to write
     */
    public void writeZigZag(int value, int bits) {
        write(BitUtils.encodeZigZag(value), bits);
    }

    /**
     * Advances to the next byte boundary if a partial byte has been written.
     * <p>
     * Any unused bits in the current byte will remain unchanged (typically zero
     * from initialization or clearing).
     * </p>
     */
    public void flush() {
        if (bitIndex > 0) {
            index++;
            bitIndex = 0;
        }
    }

    /**
     * Returns a right-sized copy of the written bytes.
     *
     * @return A byte array containing all written data, sized exactly to the data
     *         length
     */
    public byte[] toByteArray() {
        int length = bitIndex > 0 ? index + 1 : index;
        return Arrays.copyOf(buffer, length);
    }
}
