package io.liparakis.chunkis.core.bits;

/**
 * Binary adaptive range decoder matching {@link RangeEncoder}.
 *
 * <p>Reading past the end of its input yields zero bytes and sets {@link #hasOverrun()}; callers check it
 * before trusting what they decoded, as a truncated stream decodes to garbage rather than failing on its own.</p>
 *
 * <p>Not thread-safe; reuse one instance per thread with {@link #setData}.</p>
 */
public final class RangeDecoder {

    /**
     * Input.
     */
    private byte[] data;

    /**
     * Next input offset.
     */
    private int position;

    /**
     * End of the input, exclusive.
     */
    private int end;

    /**
     * Width of the current interval, as an unsigned 32-bit value.
     */
    private int range;

    /**
     * Offset of the coded value inside the current interval.
     */
    private int code;

    /**
     * Whether a read went past {@link #end}.
     */
    private boolean overrun;

    /**
     * Creates a decoder with no input.
     */
    public RangeDecoder() {
        setData(new byte[0], 0, 0);
    }

    /**
     * Points the decoder at a stream and reads its first bytes.
     *
     * @param source input
     * @param offset first byte of the stream
     * @param length stream length
     */
    public void setData(final byte[] source, final int offset, final int length) {
        data = source;
        position = offset;
        end = offset + length;
        overrun = false;
        range = -1;
        code = 0;
        if (length > 0) {
            for (int i = 0; i < 5; i++) {
                code = (code << 8) | next();
            }
        }
    }

    /**
     * Decodes one bit with an adaptive probability, then updates it exactly as the encoder did.
     *
     * @param probabilities probability table
     * @param index         probability to use
     * @return 0 or 1
     */
    public int decodeBit(final short[] probabilities, final int index) {
        final int probability = probabilities[index];
        final int bound = (range >>> RangeEncoder.PROBABILITY_BITS) * probability;
        final int bit;
        if ((code ^ 0x80000000) < (bound ^ 0x80000000)) {
            range = bound;
            probabilities[index] = (short) (probability
                    + ((RangeEncoder.PROBABILITY_SCALE - probability) >>> RangeEncoder.MOVE_BITS));
            bit = 0;
        } else {
            range -= bound;
            code -= bound;
            probabilities[index] = (short) (probability - (probability >>> RangeEncoder.MOVE_BITS));
            bit = 1;
        }
        if ((range & RangeEncoder.TOP_MASK) == 0) {
            code = (code << 8) | next();
            range <<= 8;
        }
        return bit;
    }

    /**
     * Reads the next input byte, or zero past the end.
     *
     * @return unsigned byte value
     */
    private int next() {
        if (position >= end) {
            overrun = true;
            return 0;
        }
        return data[position++] & 0xFF;
    }

    /**
     * Reports whether decoding needed more input than the stream holds, so its output is not trustworthy.
     *
     * @return true after a read past the end
     */
    public boolean hasOverrun() {
        return overrun;
    }
}
