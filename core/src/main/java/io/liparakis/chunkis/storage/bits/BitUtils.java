package io.liparakis.chunkis.storage.bits;

/**
 * Stateless bit-level numeric helpers.
 * <p>
 * This class provides utility methods for bit manipulation, specifically ZigZag
 * encoding
 * which is used to efficiently store signed integers by mapping them to
 * unsigned space.
 * Streaming bit-level I/O is handled separately by {@link BitReader} and
 * {@link BitWriter}.
 * </p>
 *
 * @author Liparakis
 * @version 1
 */
public final class BitUtils {

    private BitUtils() {
    }

    /**
     * Encodes a signed integer using ZigZag encoding.
     * <p>
     * ZigZag encoding maps signed integers to unsigned integers such that
     * values with small absolute magnitudes (like -1, 1, 2, -2) result in
     * small unsigned values. This is ideal for variable-length bit encoding.
     * </p>
     *
     * @param n The signed integer to encode
     * @return The ZigZag-encoded unsigned integer
     */
    public static int encodeZigZag(int n) {
        return (n << 1) ^ (n >> 31);
    }

    /**
     * Decodes a ZigZag-encoded integer back to its original signed value.
     *
     * @param n The ZigZag-encoded unsigned integer
     * @return The original signed integer
     */
    public static int decodeZigZag(int n) {
        return (n >>> 1) ^ -(n & 1);
    }
}
