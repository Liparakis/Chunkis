package io.liparakis.chunkis.storage.io.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.CisChunkPos;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies free-list persistence, legacy-gap reconstruction, and corruption
 * fallback behavior for {@link RegionFile}.
 */
class RegionFileFreeListTest {

    /**
     * Temporary filesystem sandbox for each test case.
     */
    @TempDir
    Path tempDir;

    /**
     * Creates a legacy region file with no footer metadata so the new reader can
     * infer reusable gaps from indexed live payloads alone.
     */
    private static void writeLegacyRegion(
            final Path regionPath,
            final CisChunkPos first,
            final byte[] firstBytes,
            final CisChunkPos second,
            final byte[] secondBytes,
            final int secondOffset
                                         ) throws IOException {
        final ByteBuffer header = ByteBuffer.allocate(RegionFile.HEADER_SIZE);
        writeHeaderEntry(header, first, RegionFile.HEADER_SIZE, firstBytes.length);
        writeHeaderEntry(header, second, secondOffset, secondBytes.length);
        header.position(0);

        try (FileChannel channel = FileChannel.open(
                regionPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
                                                   )) {
            channel.write(header, 0L);
            channel.write(ByteBuffer.wrap(firstBytes), RegionFile.HEADER_SIZE);
            channel.write(ByteBuffer.wrap(secondBytes), secondOffset);
        }
    }

    /**
     * Corrupts the first free-list entry so region open has to reject the footer
     * and fall back to append-only safety.
     */
    private static void corruptFooterFreeOffset(final Path path, final int corruptedOffset) throws Exception {
        final byte[] bytes = Files.readAllBytes(path);
        final int footerMagicOffset = bytes.length - Integer.BYTES;
        final int footerMagic = readInt(bytes, footerMagicOffset);
        assertEquals(RegionFile.FOOTER_MAGIC, footerMagic);

        final int metadataLength = readInt(bytes, footerMagicOffset - Integer.BYTES);
        final int metadataStart = bytes.length - (Integer.BYTES * 2) - metadataLength;
        final int freeListOffset = metadataStart
                + Integer.BYTES
                + Integer.BYTES
                + Integer.BYTES
                + Long.BYTES
                + Long.BYTES;
        writeInt(bytes, freeListOffset, corruptedOffset);
        Files.write(path, bytes);
    }

    /**
     * Writes one raw header slot in the legacy region test fixture.
     */
    private static void writeHeaderEntry(
            final ByteBuffer header,
            final CisChunkPos pos,
            final int offset,
            final int length
                                        ) {
        final int index = indexOf(pos);
        header.putInt(index * 8, offset);
        header.putInt(index * 8 + 4, length);
    }

    /**
     * Computes the local region header slot for a chunk position.
     */
    private static int indexOf(final CisChunkPos pos) {
        return (pos.x() & 31) + (pos.z() & 31) * 32;
    }

    /**
     * Builds deterministic pseudo-payload bytes for region-file tests.
     */
    private static byte[] bytes(final int length, final int seed) {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }

    /**
     * Reads a big-endian integer from a raw byte array.
     */
    private static int readInt(final byte[] data, final int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /**
     * Writes a big-endian integer into a raw byte array.
     */
    private static void writeInt(final byte[] data, final int offset, final int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    @Test
    void reusesBestFitFreeBlockAfterReopen() throws Exception {
        final Path regions = Files.createDirectories(tempDir.resolve("regions"));
        final CisChunkPos smallHole = new CisChunkPos(0, 0);
        final CisChunkPos separator = new CisChunkPos(1, 0);
        final CisChunkPos largeHole = new CisChunkPos(2, 0);
        final CisChunkPos target = new CisChunkPos(3, 0);

        final int smallOffset;
        final int largeOffset;
        final long sizeBeforeReuse;

        try (RegionFile region = new RegionFile(regions, 0, 0)) {
            region.write(smallHole, bytes(220, 1));
            region.write(separator, bytes(32, 2));
            region.write(largeHole, bytes(340, 3));
            smallOffset = region.offsets[indexOf(smallHole)];
            largeOffset = region.offsets[indexOf(largeHole)];
            region.write(smallHole, null);
            region.write(largeHole, null);
            sizeBeforeReuse = Files.size(region.path());
        }

        try (RegionFile reopened = new RegionFile(regions, 0, 0)) {
            reopened.write(target, bytes(180, 4));
            assertEquals(smallOffset, reopened.offsets[indexOf(target)]);
            assertNotEquals(largeOffset, reopened.offsets[indexOf(target)]);
            assertTrue(Files.size(reopened.path()) <= sizeBeforeReuse);
        }
    }

    @Test
    void reusesGapFromLegacyFooterlessRegion() throws Exception {
        final Path regions = Files.createDirectories(tempDir.resolve("legacy-regions"));
        final Path regionPath = regions.resolve("r.0.0.cis");
        final CisChunkPos first = new CisChunkPos(0, 0);
        final CisChunkPos second = new CisChunkPos(1, 0);
        final CisChunkPos target = new CisChunkPos(2, 0);
        final byte[] firstBytes = bytes(100, 1);
        final byte[] secondBytes = bytes(100, 2);
        final int gapOffset = RegionFile.HEADER_SIZE + firstBytes.length;

        writeLegacyRegion(regionPath, first, firstBytes, second, secondBytes, gapOffset + 200);

        try (RegionFile region = new RegionFile(regions, 0, 0)) {
            region.write(target, bytes(150, 3));
            assertEquals(gapOffset, region.offsets[indexOf(target)]);
        }
    }

    @Test
    void corruptFreeListFallsBackToAppendOnly() throws Exception {
        final Path regions = Files.createDirectories(tempDir.resolve("corrupt-regions"));
        final CisChunkPos hole = new CisChunkPos(0, 0);
        final CisChunkPos live = new CisChunkPos(1, 0);
        final CisChunkPos target = new CisChunkPos(2, 0);

        final int holeOffset;
        final int liveOffset;
        final byte[] liveBytes = bytes(64, 7);

        try (RegionFile region = new RegionFile(regions, 0, 0)) {
            region.write(hole, bytes(160, 5));
            region.write(live, liveBytes);
            holeOffset = region.offsets[indexOf(hole)];
            liveOffset = region.offsets[indexOf(live)];
            region.write(hole, null);
        }

        corruptFooterFreeOffset(regions.resolve("r.0.0.cis"), liveOffset);

        try (RegionFile reopened = new RegionFile(regions, 0, 0)) {
            reopened.write(target, bytes(120, 8));
            assertNotEquals(holeOffset, reopened.offsets[indexOf(target)]);
            assertArrayEquals(liveBytes, reopened.read(live));
        }
    }
}
