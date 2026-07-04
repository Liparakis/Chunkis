package io.liparakis.chunkis.migrator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CisMigrationReport}, verifying that the report
 * correctly accumulates migration metrics such as scanned, migrated,
 * skipped, and failed chunk counts.
 */
class CisMigrationReportTest {

    /**
     * Verifies that the report object accumulates each counter accurately
     * in an immutable fashion.
     */
    @Test
    void accumulatesCountersImmutably() {
        final CisMigrationReport report = CisMigrationReport.empty()
                .addMigrated()
                .addSkipped()
                .addFailure();

        assertEquals(3, report.scannedChunks());
        assertEquals(1, report.migratedChunks());
        assertEquals(1, report.skippedChunks());
        assertEquals(1, report.failedChunks());
    }
}
