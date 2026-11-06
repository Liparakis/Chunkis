package io.liparakis.chunkis.debug.perf;

import io.liparakis.chunkis.Chunkis;
import java.util.concurrent.atomic.LongAdder;

/**
 * Minimal opt-in server-side counters for chunk restore hot paths.
 *
 * <p>Enable via {@code -Dchunkis.server.hotpath.metrics=true}. When disabled,
 * callers should skip all timing/counter work.</p>
 */
public final class ServerHotpathMetrics {

    /**
     * Global enable switch for server hot-path metrics.
     */
    public static final boolean ENABLED = Boolean.getBoolean("chunkis.server.hotpath.metrics");

    /**
     * Stores restore count.
     */
    private static final LongAdder restoreCount = new LongAdder();
    /**
     * Stores total cursor writes.
     */
    private static final LongAdder totalCursorWrites = new LongAdder();
    /**
     * Stores total cursor rebinds.
     */
    private static final LongAdder totalCursorRebinds = new LongAdder();
    /**
     * Stores total last state hits.
     */
    private static final LongAdder totalLastStateHits = new LongAdder();
    /**
     * Stores total palette hits.
     */
    private static final LongAdder totalPaletteHits = new LongAdder();
    /**
     * Stores total palette misses.
     */
    private static final LongAdder totalPaletteMisses = new LongAdder();
    /**
     * Stores total palette invalidations.
     */
    private static final LongAdder totalPaletteInvalidations = new LongAdder();
    /**
     * Stores total array palette lookups.
     */
    private static final LongAdder totalArrayPaletteLookups = new LongAdder();
    /**
     * Stores total bi map palette lookups.
     */
    private static final LongAdder totalBiMapPaletteLookups = new LongAdder();
    /**
     * Stores total singular palette lookups.
     */
    private static final LongAdder totalSingularPaletteLookups = new LongAdder();

    /**
     * Performs server hotpath metrics.
     */
    private ServerHotpathMetrics() {
        throw new AssertionError("Utility class");
    }

    /**
     * Records one restore cursor summary.
     */
    public static void recordRestoreCursor(
            final long writes,
            final long rebinds,
            final long lastStateHits,
            final long paletteHits,
            final long paletteMisses,
            final long paletteInvalidations,
            final long arrayPaletteLookups,
            final long biMapPaletteLookups,
            final long singularPaletteLookups
    ) {
        restoreCount.increment();
        totalCursorWrites.add(writes);
        totalCursorRebinds.add(rebinds);
        totalLastStateHits.add(lastStateHits);
        totalPaletteHits.add(paletteHits);
        totalPaletteMisses.add(paletteMisses);
        totalPaletteInvalidations.add(paletteInvalidations);
        totalArrayPaletteLookups.add(arrayPaletteLookups);
        totalBiMapPaletteLookups.add(biMapPaletteLookups);
        totalSingularPaletteLookups.add(singularPaletteLookups);
    }

    /**
     * Returns the number of restore cursor summaries recorded so far.
     */
    public static long restoreCount() {
        return restoreCount.sum();
    }

    /**
     * Logs an aggregate summary for restore cursor metrics.
     */
    public static void logRestoreSummary() {
        final long restores = restoreCount.sum();
        if (restores == 0) {
            return;
        }

        Chunkis.LOGGER.info(
                "Chunkis restore cursor metrics: restores={}, avgWrites={}, avgRebinds={}, avgLastStateHits={}, avgPaletteHits={}, avgPaletteMisses={}, avgPaletteInvalidations={}, avgArrayPaletteLookups={}, avgBiMapPaletteLookups={}, avgSingularPaletteLookups={}",
                restores,
                totalCursorWrites.sum() / restores,
                totalCursorRebinds.sum() / restores,
                totalLastStateHits.sum() / restores,
                totalPaletteHits.sum() / restores,
                totalPaletteMisses.sum() / restores,
                totalPaletteInvalidations.sum() / restores,
                totalArrayPaletteLookups.sum() / restores,
                totalBiMapPaletteLookups.sum() / restores,
                totalSingularPaletteLookups.sum() / restores
        );
    }

}
