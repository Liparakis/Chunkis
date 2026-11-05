package io.liparakis.chunkis.storage.io.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.liparakis.chunkis.core.CisChunkPos;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Freed slots released while a compaction rewrote the file must not be handed out again.
 */
class RegionFileReleaseRaceTest {

    /**
     * Folder for the region file.
     */
    @TempDir
    Path tempDir;

    /**
     * Frees enough slots to release them, compacts the file while the release has the lock dropped, then writes
     * new chunks and expects every chunk the compaction kept to read back unchanged: the old slots point into the
     * compacted file's live entries.
     */
    @Test
    void compactionWhileFreesAreReleasedNeverReusesOldSlots() throws IOException {
        final Path regions = Files.createDirectories(tempDir.resolve("regions"));
        try (RegionFile region = new RegionFile(regions, 0, 0)) {
            for (int i = 0; i < 200; i++) {
                region.write(new CisChunkPos(i % 32, i / 32), payload(i, 0));
            }
            // Rewriting each chunk larger frees its old slot; keep the frees pending.
            for (int i = 0; i < 100; i++) {
                region.write(new CisChunkPos(i % 32, i / 32), payload(i, 3000));
            }
            region.releaseFreesIfMany(() -> {
                try {
                    region.compactWithReport();
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            // The next save and flush make any frees still pending reusable.
            region.write(new CisChunkPos(31, 31), payload(1023, 0));
            region.flush();
            // Small enough to fit the old slots, so an allocator holding them would place these there.
            for (int i = 200; i < 300; i++) {
                region.write(new CisChunkPos(i % 32, i / 32), payload(i, -800));
            }
            assertArrayEquals(payload(1023, 0), region.read(new CisChunkPos(31, 31)), "chunk 1023");
            for (int i = 0; i < 300; i++) {
                final int extra = i < 100 ? 3000 : i < 200 ? 0 : -800;
                assertArrayEquals(payload(i, extra), region.read(new CisChunkPos(i % 32, i / 32)), "chunk " + i);
            }
        }
    }

    /**
     * Returns a recognisable payload for a chunk.
     *
     * @param chunk chunk index
     * @param extra bytes added to the base size
     * @return payload bytes
     */
    private static byte[] payload(final int chunk, final int extra) {
        final byte[] bytes = new byte[1000 + extra + chunk];
        Arrays.fill(bytes, (byte) (chunk * 31 + extra));
        return bytes;
    }
}
