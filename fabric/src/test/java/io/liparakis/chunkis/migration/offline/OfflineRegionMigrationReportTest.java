package io.liparakis.chunkis.migration.offline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OfflineRegionMigrationReportTest {

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

        assertTrue(report.toJsonLine().contains("\"handledChunks\":900"));
        assertFalse(report.toJsonLine().contains("convertedChunks"));
    }
}
