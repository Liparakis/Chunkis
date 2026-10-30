package io.liparakis.chunkis.storage.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.io.region.RegionFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies when the region cache compacts: a large slack on eviction, a much smaller one when the storage closes.
 */
class RegionFileCacheCompactionTest {

    /**
     * Temporary filesystem sandbox for each test case.
     */
    @TempDir
    Path tempDir;

    /**
     * Closing the storage compacts at 10 % and 64 KiB reclaimable, header excluded, and not below either.
     */
    @Test
    void storageCloseCompactsAtTenPercentAndSixtyFourKibibytes() {
        assertTrue(RegionFileCache.shouldCompactOnStorageClose(stats(1_000_000, 1_000_000 - 8192 - 100_000)));
        assertFalse(RegionFileCache.shouldCompactOnStorageClose(stats(1_000_000, 1_000_000 - 8192 - 99_000)),
                "under 10 %");
        assertFalse(RegionFileCache.shouldCompactOnStorageClose(stats(100_000, 100_000 - 8192 - 60_000)),
                "under 64 KiB");
        assertFalse(RegionFileCache.shouldCompactOnStorageClose(stats(8192 + 500_000, 500_000)),
                "the header alone is not reclaimable");
    }

    /**
     * Eviction during play keeps its large threshold: 1 MiB and half the file.
     */
    @Test
    void evictionStillNeedsLargeSlack() {
        assertFalse(RegionFileCache.shouldCompactOnClose(stats(4_000_000, 3_000_000)), "1 MB but a quarter");
        assertTrue(RegionFileCache.shouldCompactOnClose(stats(4_000_000, 1_900_000)));
    }

    /**
     * Closing the cache compacts a region whose rewrites left a fifth of it free, keeping every entry.
     */
    @Test
    void closeAllCompactsFragmentedRegion() throws Exception {
        final Path dir = Files.createDirectories(tempDir.resolve("close"));
        final RegionFileCache cache = new RegionFileCache(dir);
        final Random random = new Random(7);
        final byte[][] entries = new byte[200][];
        final RegionFile region = cache.get(new CisChunkPos(0, 0), true);
        for (int i = 0; i < entries.length; i++) {
            entries[i] = bytes(random, 2000 + random.nextInt(4000));
            region.write(new CisChunkPos(i & 31, i >> 5), entries[i]);
        }
        region.flush();
        for (int i = 0; i < entries.length; i += 4) {
            entries[i] = bytes(random, 2000 + random.nextInt(4000));
            region.write(new CisChunkPos(i & 31, i >> 5), entries[i]);
        }
        region.flush();
        final RegionFile.RegionSpaceStats before = region.spaceStats();
        assertTrue(RegionFileCache.shouldCompactOnStorageClose(before), "test region is fragmented enough");
        assertFalse(RegionFileCache.shouldCompactOnClose(before), "but not enough for an eviction");

        cache.closeAll();

        final Path file = dir.resolve("r.0.0.cis");
        assertTrue(Files.size(file) < RegionFile.HEADER_SIZE + before.liveBytes() + 1024, "compacted on close");
        try (RegionFile reopened = new RegionFile(dir, 0, 0)) {
            for (int i = 0; i < entries.length; i++) {
                assertArrayEquals(entries[i], reopened.read(new CisChunkPos(i & 31, i >> 5)), "entry " + i);
            }
        }
    }

    /**
     * Builds space accounting with the given sizes.
     *
     * @param physical file size
     * @param live     live entry bytes
     * @return stats
     */
    private static RegionFile.RegionSpaceStats stats(final long physical, final long live) {
        return new RegionFile.RegionSpaceStats(Path.of("r.0.0.cis"), physical, live, 0, 0, 0, 0, 0, 0);
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
