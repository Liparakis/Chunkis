package io.liparakis.chunkis.client.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.integration.migration.MigrationProgressTracker;
import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationScreenTest {

    /** Ensures global ETA never underreports the slowest active worker. */
    @Test
    void globalEtaNeverUndercutsSlowestActiveWorker() {
        final long started = System.nanoTime() - 10_000_000_000L;
        final MigrationProgressTracker.WorkerProgress worker = new MigrationProgressTracker.WorkerProgress(
                1, "r.0.0.mca", 1, 100, 1, 0, 100, 1, started,
                MigrationProgressTracker.WorkerState.CONVERTING);
        final MigrationProgressTracker.Snapshot snapshot = new MigrationProgressTracker.Snapshot(
                "minecraft:overworld", 10, 0, 1_000, 10, 1_000, 10, 10, 10_000_000_000L, 0, started, false,
                List.of(worker));

        assertTrue(MigrationScreen.etaSeconds(snapshot) >= 990L);
    }

    /** Ensures queued regions are included in global ETA. */
    @Test
    void globalEtaIncludesWorkWaitingBehindActiveWorkers() {
        final long started = System.nanoTime() - 10_000_000_000L;
        final MigrationProgressTracker.WorkerProgress workerOne = new MigrationProgressTracker.WorkerProgress(
                1, "r.0.0.mca", 50, 100, 50, 0, 100, 50, started,
                MigrationProgressTracker.WorkerState.CONVERTING);
        final MigrationProgressTracker.WorkerProgress workerTwo = new MigrationProgressTracker.WorkerProgress(
                2, "r.1.0.mca", 50, 100, 50, 0, 100, 50, started,
                MigrationProgressTracker.WorkerState.CONVERTING);
        final MigrationProgressTracker.Snapshot snapshot = new MigrationProgressTracker.Snapshot(
                "minecraft:overworld", 12, 0, 1_200, 100, 1_200, 100, 100, 10_000_000_000L, 0, started, false,
                List.of(workerOne, workerTwo));

        assertTrue(MigrationScreen.etaSeconds(snapshot) >= 100L);
    }

    /** Ensures global ETA is weighted by MCA byte size rather than chunk count alone. */
    @Test
    void globalEtaWeightsMcaFileBytes() {
        final long started = System.nanoTime() - 10_000_000_000L;
        final MigrationProgressTracker.WorkerProgress worker = new MigrationProgressTracker.WorkerProgress(
                1, "r.0.0.mca", 50, 100, 50, 0, 1_000, 100, started,
                MigrationProgressTracker.WorkerState.CONVERTING);
        final MigrationProgressTracker.Snapshot snapshot = new MigrationProgressTracker.Snapshot(
                "minecraft:overworld", 11, 0, 1_000, 500, 11_000, 100, 100, 10_000_000_000L, 0, started, false,
                List.of(worker));

        assertTrue(MigrationScreen.etaSeconds(snapshot) >= 1_000L);
    }
}
