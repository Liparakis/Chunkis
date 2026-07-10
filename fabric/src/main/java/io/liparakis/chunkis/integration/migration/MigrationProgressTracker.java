package io.liparakis.chunkis.integration.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * Publishes immutable MCA-to-CIS migration progress for the client startup screen.
 */
public final class MigrationProgressTracker {

    /**
     * Latest immutable progress snapshot published to the client screen.
     */
    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>(Snapshot.idle());

    private MigrationProgressTracker() {
        throw new AssertionError("Utility class");
    }

    /**
     * Starts a new migration progress session.
     *
     * @param worldId      world identifier shown by the client
     * @param totalRegions number of region tasks
     * @param totalChunks  total present chunks across all tasks
     * @param totalBytes   total region and entity MCA bytes
     * @param workers      number of worker rows
     */
    public static void begin(final String worldId,
            final int totalRegions,
            final long totalChunks,
            final long totalBytes,
            final int workers) {
        final List<WorkerProgress> workerProgress = new ArrayList<>(workers);
        for (int worker = 1; worker <= workers; worker++) {
            workerProgress.add(WorkerProgress.queued(worker));
        }
        SNAPSHOT.set(new Snapshot(worldId,
                totalRegions,
                0,
                totalChunks,
                0,
                totalBytes,
                0,
                0,
                0,
                0,
                System.nanoTime(),
                false,
                List.copyOf(workerProgress)));
    }

    /**
     * Associates a worker with a newly dequeued region.
     *
     * @param worker         one-based worker number
     * @param regionFileName source region filename
     * @param totalChunks    present chunks in the region
     * @param totalBytes     region and optional entity file bytes
     */
    public static void startRegion(final int worker,
            final String regionFileName,
            final int totalChunks,
            final long totalBytes) {
        updateWorker(worker,
                new WorkerProgress(worker,
                        regionFileName,
                        0,
                        totalChunks,
                        0,
                        0,
                        totalBytes,
                        0,
                        System.nanoTime(),
                        WorkerState.CONVERTING));
    }

    /**
     * Publishes chunk, byte, and failure progress for an active worker.
     *
     * @param worker          one-based worker number
     * @param processedChunks chunks visited by the worker
     * @param handledChunks   chunks successfully written
     * @param failedChunks    chunks that failed conversion or validation
     */
    public static void updateRegion(final int worker,
            final int processedChunks,
            final int handledChunks,
            final int failedChunks) {
        SNAPSHOT.updateAndGet(snapshot -> {
            final List<WorkerProgress> workers = new ArrayList<>(snapshot.workers());
            final WorkerProgress current = workers.get(worker - 1);
            final WorkerProgress next = current.withProgress(processedChunks, handledChunks, failedChunks);
            workers.set(worker - 1, next);
            return snapshot.withWorkersAndTotals(List.copyOf(workers),
                    snapshot.processedChunks() + Math.max(0, processedChunks - current.processedChunks()),
                    snapshot.processedBytes() + Math.max(0L, next.processedBytes() - current.processedBytes()),
                    snapshot.failedChunks() + Math.max(0, failedChunks - current.failedChunks()));
        });
    }

    /**
     * Marks a worker's current region complete and records its final bytes.
     */
    public static void completeRegion(final int worker) {
        SNAPSHOT.updateAndGet(snapshot -> {
            final List<WorkerProgress> workers = new ArrayList<>(snapshot.workers());
            final WorkerProgress current = workers.get(worker - 1);
            final long remainingBytes = Math.max(0L, current.totalBytes() - current.processedBytes());
            workers.set(worker - 1, current.withState(WorkerState.COMPLETED));
            final int completedRegions = snapshot.completedRegions() + 1;
            final long processedBytes = snapshot.processedBytes() + remainingBytes;
            final boolean firstBatch = snapshot.sampleBytes() == 0L
                    && completedRegions >= Math.min(snapshot.totalRegions(),
                    snapshot.workers()
                            .size());
            return snapshot.withWorkersAndCompletedRegions(List.copyOf(workers),
                    completedRegions,
                    processedBytes,
                    firstBatch ? processedBytes : snapshot.sampleBytes(),
                    firstBatch ? Math.max(1L, System.nanoTime() - snapshot.startedAtNanos())
                            : snapshot.sampleElapsedNanos());
        });
    }

    /**
     * Marks the current migration as finished.
     */
    public static void finish() {
        SNAPSHOT.updateAndGet(snapshot -> snapshot.withFinished(true));
    }

    /**
     * Clears the published migration state.
     */
    public static void clear() {
        SNAPSHOT.set(Snapshot.idle());
    }

    /**
     * Returns the latest immutable migration snapshot.
     */
    public static Snapshot snapshot() {
        return SNAPSHOT.get();
    }

    /**
     * Compatibility text for the existing splash-overlay hook.
     */
    public static @Nullable String getStatus() {
        final Snapshot snapshot = SNAPSHOT.get();
        if (snapshot.worldId() == null || snapshot.finished()) {
            return null;
        }
        return "Converting " + snapshot.worldId() + " from MCA to CIS (" + snapshot.completedRegions() + "/"
                + snapshot.totalRegions() + " regions)";
    }

    /**
     * Replaces one worker entry while preserving the rest of the snapshot.
     */
    private static void updateWorker(final int worker, final WorkerProgress progress) {
        SNAPSHOT.updateAndGet(snapshot -> {
            final List<WorkerProgress> workers = new ArrayList<>(snapshot.workers());
            workers.set(worker - 1, progress);
            return snapshot.withWorkers(List.copyOf(workers));
        });
    }

    /**
     * Lifecycle state shown for one migration worker.
     */
    public enum WorkerState {
        QUEUED, CONVERTING, COMPLETED
    }

    /**
     * Immutable progress and byte-weighting data for one worker.
     *
     * @param worker          one-based worker number
     * @param regionFileName  current source region filename
     * @param processedChunks visited chunks in the current region
     * @param totalChunks     present chunks in the current region
     * @param handledChunks   successfully migrated chunks
     * @param failedChunks    failed chunks in the current region
     * @param totalBytes      source region and entity bytes
     * @param processedBytes  estimated bytes represented by processed chunks
     * @param startedAtNanos  monotonic start timestamp for the current region
     * @param state           worker lifecycle state
     */
    public record WorkerProgress(int worker, String regionFileName, int processedChunks, int totalChunks,
                                 int handledChunks, int failedChunks, long totalBytes, long processedBytes,
                                 long startedAtNanos, WorkerState state) {

        /**
         * Creates an idle entry for a worker that has not received work yet.
         */
        private static WorkerProgress queued(final int worker) {
            return new WorkerProgress(worker, "-", 0, 0, 0, 0, 0, 0, 0L, WorkerState.QUEUED);
        }

        /**
         * Returns this worker with updated chunk and byte progress.
         */
        private WorkerProgress withProgress(final int processed, final int handled, final int failed) {
            final long nextProcessedBytes = totalChunks <= 0
                    ? totalBytes
                    : Math.min(totalBytes, totalBytes * (long) Math.max(0, processed) / totalChunks);
            return new WorkerProgress(worker,
                    regionFileName,
                    processed,
                    totalChunks,
                    handled,
                    failed,
                    totalBytes,
                    nextProcessedBytes,
                    startedAtNanos,
                    state);
        }

        /**
         * Returns this worker with a new lifecycle state.
         */
        @SuppressWarnings("SameParameterValue")
        private WorkerProgress withState(final WorkerState nextState) {
            return new WorkerProgress(worker,
                    regionFileName,
                    processedChunks,
                    totalChunks,
                    handledChunks,
                    failedChunks,
                    totalBytes,
                    processedBytes,
                    startedAtNanos,
                    nextState);
        }
    }

    /**
     * Immutable aggregate migration state consumed by the client UI.
     *
     * @param worldId            world identifier, or {@code null} while idle
     * @param totalRegions       total region tasks
     * @param completedRegions   completed region tasks
     * @param totalChunks        total present chunks
     * @param processedChunks    globally visited chunks
     * @param totalBytes         total source MCA bytes
     * @param processedBytes     estimated processed source bytes
     * @param sampleBytes        bytes completed by the first worker batch
     * @param sampleElapsedNanos duration of the first worker batch
     * @param failedChunks       globally failed chunks
     * @param startedAtNanos     migration start timestamp
     * @param finished           whether migration has reached its terminal state
     * @param workers            immutable worker rows
     */
    public record Snapshot(@Nullable String worldId, int totalRegions, int completedRegions, long totalChunks,
                           long processedChunks, long totalBytes, long processedBytes, long sampleBytes,
                           long sampleElapsedNanos, int failedChunks, long startedAtNanos, boolean finished,
                           List<WorkerProgress> workers) {

        /**
         * Creates the empty state used when no migration is active.
         */
        private static Snapshot idle() {
            return new Snapshot(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, false, List.of());
        }

        /**
         * Returns this snapshot with replaced worker rows.
         */
        private Snapshot withWorkers(final List<WorkerProgress> nextWorkers) {
            return new Snapshot(worldId,
                    totalRegions,
                    completedRegions,
                    totalChunks,
                    processedChunks,
                    totalBytes,
                    processedBytes,
                    sampleBytes,
                    sampleElapsedNanos,
                    failedChunks,
                    startedAtNanos,
                    finished,
                    nextWorkers);
        }

        /**
         * Returns this snapshot with worker progress and aggregate totals updated.
         */
        private Snapshot withWorkersAndTotals(final List<WorkerProgress> nextWorkers,
                final long nextProcessedChunks,
                final long nextProcessedBytes,
                final int nextFailedChunks) {
            return new Snapshot(worldId,
                    totalRegions,
                    completedRegions,
                    totalChunks,
                    nextProcessedChunks,
                    totalBytes,
                    nextProcessedBytes,
                    sampleBytes,
                    sampleElapsedNanos,
                    nextFailedChunks,
                    startedAtNanos,
                    finished,
                    nextWorkers);
        }

        /**
         * Returns this snapshot after completing a region and possibly sampling the first batch.
         */
        private Snapshot withWorkersAndCompletedRegions(final List<WorkerProgress> nextWorkers,
                final int nextCompletedRegions,
                final long nextProcessedBytes,
                final long nextSampleBytes,
                final long nextSampleElapsedNanos) {
            return new Snapshot(worldId,
                    totalRegions,
                    nextCompletedRegions,
                    totalChunks,
                    processedChunks,
                    totalBytes,
                    nextProcessedBytes,
                    nextSampleBytes,
                    nextSampleElapsedNanos,
                    failedChunks,
                    startedAtNanos,
                    finished,
                    nextWorkers);
        }

        /**
         * Returns this snapshot with its terminal flag changed.
         */
        @SuppressWarnings("SameParameterValue")
        private Snapshot withFinished(final boolean nextFinished) {
            return new Snapshot(worldId,
                    totalRegions,
                    completedRegions,
                    totalChunks,
                    processedChunks,
                    totalBytes,
                    processedBytes,
                    sampleBytes,
                    sampleElapsedNanos,
                    failedChunks,
                    startedAtNanos,
                    nextFinished,
                    workers);
        }
    }
}
