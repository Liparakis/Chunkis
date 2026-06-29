package io.liparakis.chunkis.storage.bits;

/**
 * Non-thread-safe zero-copy bit reader over a byte array.
 * <p>
 * This reader allows for reading arbitrary bit lengths from a raw byte buffer.
 * It reads bits from most significant to least significant (MSB first).
 * It is designed for maximum performance by avoiding array copies and
 * using zero-copy slicing.
 * </p>
 *
 * @author Liparakis
 * @version 1
 */
public final class BitReader {

    /**
     * The raw data buffer being read from
     */
    private byte[] data;
    /**
     * Index of the byte currently being consumed from {@link #data}.
     */
    private int byteIndex;
    /**
     * Bit cursor within the current byte, counted from the most significant bit.
     */
    private int bitIndex;
    /**
     * Exclusive upper bound of the readable slice inside {@link #data}.
     */
    private int endIndex;

    public BitReader(byte[] data) {
        setData(data, 0, data.length);
    }

    /**
     * Configures this reader to read from a byte-array slice without copying.
     *
     * @param data   The source byte array
     * @param offset The starting byte offset
     * @param length The number of bytes to include in the slice
     */
    public void setData(byte[] data, int offset, int length) {
        this.data = data;
        this.byteIndex = offset;
        this.bitIndex = 0;
        this.endIndex = offset + length;
    }

    /**
     * Reads the requested number of bits and returns them as a long value.
     * <p>
     * If the end of the slice is reached before all bits are read, the remaining
     * bits in the result will be zero-padded.
     * </p>
     *
     * @param bits The number of bits to read (0-64)
     * @return The read value as a long
     */
    public long read(int bits) {
        if (bits == 0) {
            return 0;
        }

        long result = 0;

        while (bits > 0) {
            if (byteIndex >= endIndex) {
                return result << bits;
            }

            int b = data[byteIndex] & 0xFF;
            int remaining = 8 - bitIndex;
            int take = Math.min(bits, remaining);
            int chunk = (b >>> (remaining - take)) & ((1 << take) - 1);

            result = (result << take) | chunk;

            bitIndex += take;
            bits -= take;

            if (bitIndex == 8) {
                byteIndex++;
                bitIndex = 0;
            }
        }

        return result;
    }

    /**
     * Reads and decodes a ZigZag-encoded signed integer.
     *
     * @param bits The number of bits to read
     * @return The decoded signed integer
     */
    public int readZigZag(int bits) {
        return BitUtils.decodeZigZag((int) read(bits));
    }
}
