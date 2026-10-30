package io.liparakis.chunkis.storage.io.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.CisChunkPos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that compacting a region keeps every live entry byte for byte and gives back the space rewrites left.
 */
class RegionFileCompactionTest {

    /**
     * Temporary filesystem sandbox for each test case.
     */
    @TempDir
    Path tempDir;

    /**
     * Rewrites and deletes entries of a region until it holds freed space, compacts it, and expects every entry
     * unchanged before and after reopening, no free space left, and the file writable again.
     */
    @Test
    void compactionKeepsEveryEntryAndDropsFreedSpace() throws Exception {
        final Path regions = Files.createDirectories(tempDir.resolve("compact"));
        final Random random = new Random(42);
        final Map<CisChunkPos, byte[]> expected = new HashMap<>();
        final CisChunkPos deleted = new CisChunkPos(3, 3);

        try (RegionFile region = new RegionFile(regions, 0, 0)) {
            for (int i = 0; i < 64; i++) {
                final CisChunkPos pos = new CisChunkPos(i & 31, i >> 5);
                expected.put(pos, bytes(random, 500 + random.nextInt(6000)));
                region.write(pos, expected.get(pos));
            }
            region.flush();
            for (int i = 0; i < 64; i += 2) {
                final CisChunkPos pos = new CisChunkPos(i & 31, i >> 5);
                expected.put(pos, bytes(random, 500 + random.nextInt(6000)));
                region.write(pos, expected.get(pos));
            }
            region.write(deleted, bytes(random, 4000));
            region.write(deleted, null);
            region.flush();
            final RegionFile.RegionSpaceStats before = region.spaceStats();
            assertTrue(before.physicalBytes() - RegionFile.HEADER_SIZE - before.liveBytes() > 50_000,
                    "rewrites left freed space to compact");

            final RegionFile.RegionCompactReport report = region.compactWithReport();

            final RegionFile.RegionSpaceStats after = region.spaceStats();
            assertEquals(before.liveBytes(), after.liveBytes());
            assertEquals(0, after.reusableBytes());
            assertTrue(report.physicalBytesAfter() - RegionFile.HEADER_SIZE - after.liveBytes() < 1024,
                    "only the header, the entries and an empty footer are left");
            assertEntries(region, expected);
            assertNull(region.read(deleted));

            final CisChunkPos added = new CisChunkPos(31, 31);
            expected.put(added, bytes(random, 3000));
            region.write(added, expected.get(added));
            region.flush();
        }
        try (RegionFile reopened = new RegionFile(regions, 0, 0)) {
            assertEntries(reopened, expected);
            assertNull(reopened.read(deleted));
        }
    }

    /**
     * Expects a region to hold exactly the given entries.
     *
     * @param region   region
     * @param expected entries by position
     * @throws Exception when reading fails
     */
    private static void assertEntries(final RegionFile region, final Map<CisChunkPos, byte[]> expected)
            throws Exception {
        for (final Map.Entry<CisChunkPos, byte[]> entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(), region.read(entry.getKey()), "entry " + entry.getKey());
        }
    }

    /**
     * Returns random bytes.
     *
     * @param random source
     * @param length length
     * @return bytes
     */
    private static byte[] bytes(final Random random, final int length) {
        final byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
