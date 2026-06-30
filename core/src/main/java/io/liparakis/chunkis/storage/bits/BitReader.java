package io.liparakis.chunkis.storage.bits;

import java.util.Arrays;

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
     * Reads a batch of values of a fixed bit width into the provided output array.
     * <p>
     * This avoids loop overhead and intermediate method calls, providing high-performance
     * reading for dense palettes.
     * </p>
     *
     * @param bits   The bit width of each entry (0-32)
     * @param output The array to write the read values to
     */
    public void readBatch(int bits, int[] output) {
        if (bits == 0) {
            Arrays.fill(output, 0);
            return;
        }

        int count = output.length;
        long acc = 0;
        int accBits = 0;
        long mask = (1L << bits) - 1;

        // Populate initial accumulator if we have a current partial byte
        if (bitIndex > 0 && byteIndex < endIndex) {
            int remaining = 8 - bitIndex;
            acc = data[byteIndex] & ((1L << remaining) - 1);
            accBits = remaining;
            byteIndex++;
            bitIndex = 0;
        }

        for (int i = 0; i < count; i++) {
            while (accBits < bits && byteIndex < endIndex) {
                acc = (acc << 8) | (data[byteIndex++] & 0xFF);
                accBits += 8;
            }
            if (accBits >= bits) {
                output[i] = (int) ((acc >>> (accBits - bits)) & mask);
                accBits -= bits;
            } else {
                // End of stream, pad with zero
                output[i] = (int) ((acc << (bits - accBits)) & mask);
                accBits = 0;
            }
        }

        // Restore bitIndex and byteIndex from remaining accBits
        if (accBits > 0) {
            int fullBytes = accBits / 8;
            byteIndex -= fullBytes;

            int remainingBits = accBits % 8;
            if (remainingBits > 0) {
                byteIndex--;
                bitIndex = 8 - remainingBits;
            } else {
                bitIndex = 0;
            }
        } else {
            bitIndex = 0;
        }
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
