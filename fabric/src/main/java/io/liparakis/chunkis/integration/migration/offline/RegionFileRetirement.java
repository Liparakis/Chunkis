package io.liparakis.chunkis.integration.migration.offline;

import io.liparakis.chunkis.Chunkis;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;

/**
 * Retires vanilla source region files to a verified {@code .backup}.
 * 
 * <p>The single place holding the backup invariant: a source file is deleted only after
 * its backup is forced to disk and verified byte for byte. Used by the offline migration
 * and by entity-region cleanup.</p>
 */
public final class RegionFileRetirement {

    /**
     * Logger for this class.
     */
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private RegionFileRetirement() {
        throw new AssertionError("Utility class");
    }

    /**
     * Retires a source region file to {@code <name>.backup}, deleting the source
     * only after the backup is forced to disk and verified byte for byte.
     *
     * <p>0KB placeholders hold no data and are simply removed. An existing backup
     * identical to the source (a crash between copy and delete) completes the
     * retirement; a different existing backup is never overwritten and the source
     * stays in place.</p>
     *
     * @param mcaPath source region file
     * @return {@code true} if the source no longer exists in its live location
     * @throws IOException if copying, verification or deletion fails
     */
    public static boolean retire(final Path mcaPath) throws IOException {
        if (!Files.exists(mcaPath)) {
            return true;
        }
        if (Files.size(mcaPath) == 0) {
            LOGGER.info("Removing 0KB placeholder region {}", mcaPath);
            Files.delete(mcaPath);
            return true;
        }

        final Path backupPath = mcaPath.resolveSibling(mcaPath.getFileName() + ".backup");
        if (Files.exists(backupPath)) {
            if (Files.mismatch(mcaPath, backupPath) != -1L) {
                LOGGER.warn("Keeping {} in place: a different backup already exists at {}", mcaPath, backupPath);
                return false;
            }
        } else {
            final Path tempBackup = mcaPath.resolveSibling(mcaPath.getFileName() + ".backup.tmp");
            Files.copy(mcaPath, tempBackup, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(tempBackup, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (Files.mismatch(mcaPath, tempBackup) != -1L) {
                Files.deleteIfExists(tempBackup);
                throw new IOException("Backup verification failed for " + mcaPath + ": content differs");
            }
            Files.move(tempBackup, backupPath, StandardCopyOption.ATOMIC_MOVE);
        }

        Files.delete(mcaPath);
        LOGGER.info("Verified backup created and source retired: {} -> {}",
                mcaPath.getFileName(),
                backupPath.getFileName());
        return true;
    }

}
