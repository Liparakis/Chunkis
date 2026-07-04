package io.liparakis.chunkis.migrator;

/**
 * Immutable summary of a CIS migration run.
 *
 * @param scannedChunks  total chunk positions inspected
 * @param migratedChunks total chunks rewritten to the target format
 * @param skippedChunks  chunk positions that were valid but needed no migration
 * @param failedChunks   chunk positions that failed to migrate
 *
 */
public record CisMigrationReport(
        int scannedChunks,
        int migratedChunks,
        int skippedChunks,
        int failedChunks) {

    /**
     * Returns an empty migration report with all counters set to zero.
     *
     * @return empty report
     */
    public static CisMigrationReport empty() {
        return new CisMigrationReport(0, 0, 0, 0);
    }

    /**
     * Returns a new report with one additional migrated chunk.
     *
     * @return updated report instance
     */
    public CisMigrationReport addMigrated() {
        return new CisMigrationReport(scannedChunks + 1, migratedChunks + 1, skippedChunks, failedChunks);
    }

    /**
     * Returns a new report with one additional skipped chunk.
     *
     * @return updated report instance
     */
    public CisMigrationReport addSkipped() {
        return new CisMigrationReport(scannedChunks + 1, migratedChunks, skippedChunks + 1, failedChunks);
    }

    /**
     * Returns a new report with one additional failed chunk.
     *
     * @return updated report instance
     */
    public CisMigrationReport addFailure() {
        return new CisMigrationReport(scannedChunks + 1, migratedChunks, skippedChunks, failedChunks + 1);
    }
}
