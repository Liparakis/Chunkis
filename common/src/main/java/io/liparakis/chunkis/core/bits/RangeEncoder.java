package io.liparakis.chunkis.core.bits;

import java.util.Arrays;

/**
 * Binary adaptive range encoder, the coder LZMA uses: each bit is coded with an 11-bit probability that adapts
 * after every use. Paired with {@link RangeDecoder}; both must update the same probabilities in the same order.
 *
 * <p>Not thread-safe; reuse one instance per thread with {@link #reset()}.</p>
 */
public final class RangeEncoder {

    /**
     * Bits of probability precision.
     */
    static final int PROBABILITY_BITS = 11;

    /**
     * Probability scale; a probability is the chance of a zero bit, out of this.
     */
    static final int PROBABILITY_SCALE = 1 << PROBABILITY_BITS;

    /**
     * Adaptation speed: a probability moves 1/32 of the way toward the coded bit.
     */
    static final int MOVE_BITS = 5;

    /**
     * Mask of the bits that must stay set in the range; below it the range is renormalized.
     */
    static final int TOP_MASK = ~((1 << 24) - 1);

    /**
     * Lower bound of the current interval, with room for a carry into bit 32.
     */
    private long low;

    /**
     * Width of the current interval, as an unsigned 32-bit value.
     */
    private int range;

    /**
     * Pending output byte that a carry may still increment.
     */
    private int cache;

    /**
     * Bytes held back: the cached byte plus any 0xFF bytes a carry would turn to 0x00.
     */
    private int cacheSize;

    /**
     * Encoded output.
     */
    private byte[] buffer = new byte[4096];

    /**
     * Bytes of {@link #buffer} in use.
     */
    private int size;

    /**
     * Creates an encoder ready to code.
     */
    public RangeEncoder() {
        reset();
    }

    /**
     * Sets every probability in {@code [from, to)} to one half.
     *
     * @param probabilities probability table
     * @param from          first index
     * @param to            index after the last
     */
    public static void resetProbabilities(final short[] probabilities, final int from, final int to) {
        Arrays.fill(probabilities, from, to, (short) (PROBABILITY_SCALE >>> 1));
    }

    /**
     * Discards the output and starts a new stream.
     */
    public void reset() {
        low = 0;
        range = -1;
        cache = 0;
        cacheSize = 1;
        size = 0;
    }

    /**
     * Codes one bit with an adaptive probability, then updates it.
     *
     * @param probabilities probability table
     * @param index         probability to use
     * @param bit           0 or 1
     */
    public void encodeBit(final short[] probabilities, final int index, final int bit) {
        final int probability = probabilities[index];
        final int bound = (range >>> PROBABILITY_BITS) * probability;
        if (bit == 0) {
            range = bound;
            probabilities[index] = (short) (probability + ((PROBABILITY_SCALE - probability) >>> MOVE_BITS));
        } else {
            low += bound & 0xFFFFFFFFL;
            range -= bound;
            probabilities[index] = (short) (probability - (probability >>> MOVE_BITS));
        }
        if ((range & TOP_MASK) == 0) {
            range <<= 8;
            shiftLow();
        }
    }

    /**
     * Writes out what the interval's lower bound settled, holding back bytes a carry could still change.
     */
    private void shiftLow() {
        final int lowHigh = (int) (low >>> 32);
        if (lowHigh != 0 || low < 0xFF000000L) {
            int pending = cache;
            do {
                write(pending + lowHigh);
                pending = 0xFF;
            } while (--cacheSize != 0);
            cache = ((int) low) >>> 24;
        }
        cacheSize++;
        low = (low & 0xFFFFFFL) << 8;
    }

    /**
     * Ends the stream: writes every byte the decoder needs to decode all coded bits.
     */
    public void finish() {
        for (int i = 0; i < 5; i++) {
            shiftLow();
        }
    }

    /**
     * Appends one output byte.
     *
     * @param value byte value; only the low 8 bits are kept
     */
    private void write(final int value) {
        if (size == buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        buffer[size++] = (byte) value;
    }

    /**
     * Returns the output buffer; only the first {@link #size()} bytes are output.
     *
     * @return output buffer
     */
    public byte[] buffer() {
        return buffer;
    }

    /**
     * Returns the number of output bytes.
     *
     * @return output size
     */
    public int size() {
        return size;
    }
}
