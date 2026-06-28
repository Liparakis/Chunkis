package io.liparakis.chunkis.storage.io.region;

import io.liparakis.chunkis.Chunkis;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Swap and recovery helpers for region-file compaction.
 *
 * <p>This keeps the post-compaction file replacement and recovery path out of the
 * main read/write logic in {@link RegionFile}.</p>
 */
final class RegionCompactionRecovery {

    private RegionCompactionRecovery() {
        throw new AssertionError("Utility class");
    }

    /**
     * Replaces the live region file with a compacted temp file and returns a
     * freshly opened channel for the new on-disk file.
     */
    static FileChannel swapCompactedFile(final FileChannel channel, final Path tempPath, final Path regionPath) throws IOException {
        channel.close();
        try {
            Files.move(tempPath, regionPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException atomicFailure) {
            Files.move(tempPath, regionPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return openChannel(regionPath);
    }

    /**
     * Best-effort reopens a region channel after a failed compaction attempt.
     *
     * <p>If the original channel never closed, it is returned unchanged. If
     * reopening fails, the original closed channel is returned so the caller can
     * decide how to proceed.</p>
     */
    static FileChannel recoverChannel(final FileChannel channel, final Path regionPath) {
        if (channel.isOpen()) {
            return channel;
        }
        try {
            return openChannel(regionPath);
        } catch (final IOException ex) {
            Chunkis.LOGGER.error("CRITICAL: Failed to reopen region after failed compaction: {}", regionPath, ex);
            return channel;
        }
    }

    /**
     * Opens a region file channel with the same semantics as the live region.
     */
    private static FileChannel openChannel(final Path regionPath) throws IOException {
        return FileChannel.open(regionPath, StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE);
    }
}
