package io.liparakis.chunkis.migration.offline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link OfflineRegionMigrationReport} class, validating condition checks
 * for source region retirement and JSON serialization properties.
 */
class OfflineRegionMigrationReportTest {

    /**
     * Tests that a source region with complete coverage and no failures can be retired.
     */
    @Test
    void completeCoverageWithoutFailuresCanRetireSourceRegion() {
        final OfflineRegionMigrationReport report = new OfflineRegionMigrationReport(
                "minecraft:overworld",
                "r.0.0.mca",
                1024,
                1024,
                0,
                true,
                OfflineMcaCisTranslator.TRANSLATOR_VERSION
        );

        assertTrue(report.canRetireSourceRegion());
    }

    /**
     * Tests that a source region with partial coverage can still be retired if every
     * present chunk was successfully converted.
     */
    @Test
    void partialCoverageCanRetireWhenEveryPresentChunkConverted() {
        final OfflineRegionMigrationReport partial = new OfflineRegionMigrationReport(
                "minecraft:overworld",
                "r.0.0.mca",
                12,
                12,
                0,
                true,
                OfflineMcaCisTranslator.TRANSLATOR_VERSION
        );

        assertTrue(partial.canRetireSourceRegion());
    }

    /**
     * Tests that a source region cannot be retired if there are failed chunk conversions
     * or deferred coverage.
     */
    @Test
    void failedOrDeferredCoverageCannotRetireSourceRegion() {
        final OfflineRegionMigrationReport failed = new OfflineRegionMigrationReport(
                "minecraft:overworld",
                "r.0.0.mca",
                12,
                12,
                1,
                false,
                OfflineMcaCisTranslator.TRANSLATOR_VERSION
        );

        assertFalse(failed.canRetireSourceRegion());
    }

    /**
     * Tests that the serialized JSON line contains the correct key and value for handled chunk counts.
     */
    @Test
    void jsonLineUsesHandledChunkCount() {
        final OfflineRegionMigrationReport report = new OfflineRegionMigrationReport(
                "minecraft:overworld",
                "r.0.0.mca",
                1024,
                900,
                0,
                false,
                OfflineMcaCisTranslator.TRANSLATOR_VERSION
        );

        assertTrue(report.toJsonLine()
                .contains("\"handledChunks\":900"));
        assertFalse(report.toJsonLine()
                .contains("convertedChunks"));
    }
}
