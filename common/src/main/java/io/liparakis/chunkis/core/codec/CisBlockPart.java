package io.liparakis.chunkis.core.codec;

import io.liparakis.chunkis.core.model.CisConstants;

/**
 * The block part of a CIS payload: the header, the global palette and every section with its raw and coded block
 * streams, everything before the block entities. A save whose blocks are exactly the stored entry's can copy it
 * as is ({@link CisEncoder#encodeWithBlockPart}) instead of encoding the blocks again.
 */
public final class CisBlockPart {

    /**
     * Magic and version.
     */
    private static final int HEADER_SIZE = 8;

    /**
     * Not instantiable.
     */
    private CisBlockPart() {
        throw new AssertionError("Utility class");
    }

    /**
     * Finds where a payload's block part ends, by its length prefixes alone.
     *
     * @param payload decompressed CIS payload
     * @return the block part's length, or -1 when the payload is not current-version or its lengths do not fit
     */
    public static int length(final byte[] payload) {
        if (payload.length < HEADER_SIZE || readInt(payload, 0) != CisConstants.MAGIC
                || readInt(payload, 4) != CisConstants.VERSION) {
            return -1;
        }
        long offset = HEADER_SIZE;
        if (offset + 4 > payload.length) {
            return -1;
        }
        final int paletteSize = readInt(payload, (int) offset);
        offset += 4 + 2L * paletteSize;
        offset = skipPrefixed(payload, offset);
        if (offset < 0 || offset + 2 > payload.length) {
            return -1;
        }
        offset += 2;
        for (int stream = 0; stream < 3 && offset >= 0; stream++) {
            offset = skipPrefixed(payload, offset);
        }
        return offset < 0 ? -1 : (int) offset;
    }

    /**
     * Hashes a payload's block part.
     *
     * @param payload decompressed CIS payload
     * @return its block part's hash ({@link #hash}), or {@code null} when it has none
     */
    public static long[] hashOf(final byte[] payload) {
        final int length = length(payload);
        return length < 0 ? null : hash(payload, 0, length);
    }

    /**
     * Hashes bytes into 128 bits (two independently seeded 64-bit mixes), to tell whether a stored block part is
     * the one that was written.
     *
     * @param data bytes
     * @param from first byte
     * @param to   end, exclusive
     * @return two 64-bit hashes
     */
    public static long[] hash(final byte[] data, final int from, final int to) {
        long a = 0x9E3779B97F4A7C15L ^ (to - from);
        long b = 0xC2B2AE3D27D4EB4FL ^ (to - from);
        int i = from;
        for (; i + 8 <= to; i += 8) {
            long word = 0;
            for (int k = 0; k < 8; k++) {
                word = (word << 8) | (data[i + k] & 0xFFL);
            }
            a = mix(a ^ word);
            b = mix(b + word * 0xFF51AFD7ED558CCDL);
        }
        long tail = 0;
        for (; i < to; i++) {
            tail = (tail << 8) | (data[i] & 0xFFL);
        }
        return new long[]{mix(a ^ tail), mix(b + tail)};
    }

    /**
     * SplitMix64 finaliser.
     *
     * @param value input
     * @return mixed value
     */
    public static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /**
     * Skips a 4-byte length prefix and the bytes it counts.
     *
     * @param payload payload
     * @param offset  offset of the prefix
     * @return offset after the counted bytes, or -1 when they do not fit
     */
    private static long skipPrefixed(final byte[] payload, final long offset) {
        if (offset < 0 || offset + 4 > payload.length) {
            return -1;
        }
        final int prefix = readInt(payload, (int) offset);
        // A negative prefix marks the coded stream's per-section layout; its magnitude is the length.
        final long length = prefix < 0 ? -(long) prefix - 1 : prefix;
        final long end = offset + 4 + length;
        return end > payload.length ? -1 : end;
    }

    /**
     * Reads a big-endian int.
     *
     * @param data   bytes
     * @param offset offset
     * @return the int
     */
    private static int readInt(final byte[] data, final int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
