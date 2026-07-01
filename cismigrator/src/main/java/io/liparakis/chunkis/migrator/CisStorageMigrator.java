package io.liparakis.chunkis.migrator;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.io.CisStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * Generic migrator for existing Chunkis CIS region storage.
 *
 * <p>The migrator is storage-backed rather than codec-backed: it uses the
 * caller's configured {@link CisStorage} so the same adapters and mappings
 * used by the game are also used during migration. This makes it safe to run
 * alongside the MCA import path, while keeping CIS version upgrades in a
 * dedicated project.
 *
 * <p><b>Threading:</b> All methods must be called from the background migration
 * thread. No Minecraft world state is accessed; only CIS region files on disk
 * are read and rewritten via the provided {@link CisStorage}.
 *
 * @param <S> block state type
 * @param <N> chunk NBT type
 * @author Liparakis
 * @version 1.0
 */
public final class CisStorageMigrator<S, N> {

    /**
     * Compiled pattern for validating and parsing {@code r.<x>.<z>.cis} filenames.
     */
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cis");
    private static final String VERSION_MARKER_FILE = ".chunkis-cis-version";

    /**
     * Number of chunk slots along each axis in one region file.
     */
    private static final int REGION_SIZE = 32;

    private final CisStorage<?, S, ?, N> storage;
    private final Logger logger;

    /**
     * Target CIS version all chunks are upgraded toward.
     * Chunks already at or above this version are skipped.
     */
    private final int targetVersion;

    /**
     * Creates a migrator that upgrades CIS data to the runtime's latest supported
     * version.
     *
     * @param storage storage facade used to load and re-save chunk deltas
     * @param logger  logger used for progress and failure reporting
     */
    public CisStorageMigrator(final CisStorage<?, S, ?, N> storage, final Logger logger) {
        this(storage, logger, CisVersionMap.latestVersion());
    }

    /**
     * Creates a migrator that upgrades CIS data to a specific target version.
     *
     * @param storage       storage facade used to load and re-save chunk deltas
     * @param logger        logger used for progress and failure reporting
     * @param targetVersion CIS version to migrate chunks toward
     */
    public CisStorageMigrator(
            final CisStorage<?, S, ?, N> storage,
            final Logger logger,
            final int targetVersion) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.targetVersion = targetVersion;
    }

    /**
     * Scans the given storage directory for Chunkis region files and upgrades any
     * chunk whose encoded CIS version is older than the configured target.
     *
     * <p>Returns immediately with an empty report if {@code storageDir} does not
     * exist. Non-matching filenames are silently ignored.
     *
     * @param storageDir directory containing {@code r.<x>.<z>.cis} files
     * @return aggregated report for the migration run
     */
    public CisMigrationReport migrateStorage(final Path storageDir) {
        if (!Files.exists(storageDir)) {
            return CisMigrationReport.empty();
        }

        if (isStorageMarkedAtVersion(storageDir, targetVersion)) {
            return CisMigrationReport.empty();
        }

        CisMigrationReport report = CisMigrationReport.empty();
        boolean completed = true;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir, "r.*.*.cis")) {
            for (final Path path : stream) {
                report = migrateRegionFileIfMatched(path, report);
            }
        } catch (final IOException e) {
            completed = false;
            logger.error("Failed to iterate CIS storage directory {}", storageDir, e);
        }

        if (completed && report.failedChunks() == 0) {
            writeTargetVersionMarker(storageDir);
        }

        return report;
    }

    /**
     * Returns {@code true} when a prior successful migration already verified
     * this storage directory at the current target version.
     *
     * @param storageDir directory containing CIS region files
     * @return {@code true} when a clean migration marker matches {@link #targetVersion}
     */
    public static boolean isStorageMarkedAtVersion(final Path storageDir, final int targetVersion) {
        final Path markerPath = storageDir.resolve(VERSION_MARKER_FILE);
        if (!Files.isRegularFile(markerPath)) {
            return false;
        }

        try {
            final int recordedVersion = Integer.parseInt(Files.readString(markerPath, StandardCharsets.UTF_8).trim());
            return recordedVersion == targetVersion;
        } catch (final IOException | NumberFormatException e) {
            return false;
        }
    }

    /**
     * Persists the target version marker after a clean full-directory scan.
     *
     * @param storageDir directory containing CIS region files
     */
    private void writeTargetVersionMarker(final Path storageDir) {
        final Path markerPath = storageDir.resolve(VERSION_MARKER_FILE);
        try {
            Files.writeString(markerPath, Integer.toString(targetVersion), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            logger.warn("Failed to write CIS migration marker {}", markerPath, e);
        }
    }

    /**
     * Migrates a single region file when the filename matches the Chunkis region
     * naming convention.
     *
     * @param path   candidate region file path
     * @param report accumulated report so far
     * @return updated migration report
     */
    private CisMigrationReport migrateRegionFileIfMatched(final Path path, final CisMigrationReport report) {
        final Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return report;
        }

        final int rx = Integer.parseInt(matcher.group(1));
        final int rz = Integer.parseInt(matcher.group(2));
        return migrateRegion(rx, rz, report);
    }

    /**
     * Iterates every chunk slot within one 32x32 region and delegates each to
     * {@link #migrateChunk}.
     *
     * @param rx     region X coordinate
     * @param rz     region Z coordinate
     * @param report accumulated report so far
     * @return updated migration report
     */
    private CisMigrationReport migrateRegion(final int rx, final int rz, CisMigrationReport report) {
        for (int x = 0; x < REGION_SIZE; x++) {
            for (int z = 0; z < REGION_SIZE; z++) {
                report = migrateChunk((rx << 5) + x, (rz << 5) + z, report);
            }
        }
        return report;
    }

    /**
     * Loads one chunk delta, checks whether it needs upgrading, and re-saves it
     * through the configured storage when the source version is below the target.
     *
     * <p>Empty deltas and up-to-date chunks are counted as skipped. Any exception
     * during load or save is caught, logged, and counted as a failure so that one
     * corrupt chunk does not abort the entire migration run.
     *
     * @param chunkX absolute chunk X coordinate
     * @param chunkZ absolute chunk Z coordinate
     * @param report accumulated report so far
     * @return updated migration report
     */
    private CisMigrationReport migrateChunk(final int chunkX, final int chunkZ, final CisMigrationReport report) {
        final CisChunkPos pos = new CisChunkPos(chunkX, chunkZ);

        try {
            final ChunkDelta<S, N> delta = storage.loadWithoutClearing(pos);

            if (delta == null || delta.isEmpty()) {
                return report.addSkipped();
            }

            final int sourceVersion = delta.getSourceVersion();
            if (sourceVersion >= targetVersion) {
                return report.addSkipped();
            }

            final CisVersionPath path = CisVersionMap.plan(sourceVersion, targetVersion);
            if (path.isNoOp()) {
                return report.addSkipped();
            }

            storage.save(pos, delta);
            logger.info("Migrated CIS chunk {} from v{} to v{} via {} step(s)",
                        pos, sourceVersion, targetVersion, path.steps().size());
            return report.addMigrated();

        } catch (final Exception e) {
            logger.error("Failed to migrate CIS chunk {}", pos, e);
            return report.addFailure();
        }
    }
}
