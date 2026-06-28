package io.liparakis.chunkis.storage.io.region;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Footer discovery and truncation helpers for region allocation metadata.
 *
 * <p>This keeps the trailing-footer bookkeeping separate from the higher-level
 * region read/write flow in {@link RegionFile}.</p>
 */
final class RegionFooterIO {

    private RegionFooterIO() {
        throw new AssertionError("Utility class");
    }

    /**
     * Locates the start offset of the persisted allocation-metadata payload.
     *
     * <p>The trailing footer stores a payload length and magic marker in the
     * last eight bytes of the file. If those bytes are missing or malformed,
     * this method returns {@code noFooter} instead of throwing.</p>
     *
     * @param channel     region file channel to inspect
     * @param headerSize  minimum valid payload start offset
     * @param footerMagic expected trailing footer magic
     * @param noFooter    sentinel to return when no valid footer is present
     * @return start offset of the footer payload, or {@code noFooter}
     * @throws IOException if the footer bytes cannot be read
     */
    static int discoverFooterStart(
            final FileChannel channel,
            final int headerSize,
            final int footerMagic,
            final int noFooter
    ) throws IOException {
        final long size = channel.size();
        if (size < headerSize + (Integer.BYTES * 2L)) {
            return noFooter;
        }

        final ByteBuffer footer = ByteBuffer.allocate(Integer.BYTES * 2);
        RegionFile.readFully(channel, footer, size - (Integer.BYTES * 2L));
        footer.flip();

        final int payloadLength = footer.getInt();
        final int parsedFooterMagic = footer.getInt();
        if (parsedFooterMagic != footerMagic) {
            return noFooter;
        }

        final long footerStart = size - (Integer.BYTES * 2L) - payloadLength;
        if (payloadLength < 0 || footerStart < headerSize) {
            return noFooter;
        }

        return (int) footerStart;
    }

    /**
     * Reads the raw allocation-metadata payload that precedes the trailing
     * footer marker.
     *
     * @param channel     region file channel to read from
     * @param footerStart start offset of the footer payload
     * @return flipped buffer containing exactly the footer payload bytes
     * @throws IOException if the payload cannot be read fully
     */
    static ByteBuffer readFooterPayload(final FileChannel channel, final int footerStart) throws IOException {
        final long size = channel.size();
        final int payloadLength = (int) (size - footerStart - (Integer.BYTES * 2L));
        final ByteBuffer payload = ByteBuffer.allocate(payloadLength);
        RegionFile.readFully(channel, payload, footerStart);
        payload.flip();
        return payload;
    }

    /**
     * Truncates the trailing footer so subsequent payload writes append at the
     * logical end of live region data.
     *
     * @param channel region file channel to truncate
     * @param dataEnd logical end of live payload bytes
     * @throws IOException if truncation fails
     */
    static void truncateFooter(final FileChannel channel, final int dataEnd) throws IOException {
        if (channel.size() > dataEnd) {
            channel.truncate(dataEnd);
        }
    }
}
