package io.liparakis.chunkis.debug.perf;

import io.liparakis.chunkis.Chunkis;
import java.util.concurrent.atomic.LongAdder;

/**
 * Minimal opt-in server-side counters for restore and sync hot paths.
 *
 * <p>Enable via {@code -Dchunkis.server.hotpath.metrics=true}. When disabled,
 * callers should skip all timing/counter work.</p>
 */
public final class ServerHotpathMetrics {

    /**
     * Global enable switch for server hot-path metrics.
     */
    public static final boolean ENABLED = Boolean.getBoolean("chunkis.server.hotpath.metrics");

    private static final LongAdder payloadCount = new LongAdder();
    private static final LongAdder totalEncodeNanos = new LongAdder();
    private static final LongAdder totalWrapNanos = new LongAdder();
    private static final LongAdder totalRawBytes = new LongAdder();
    private static final LongAdder totalWireBytes = new LongAdder();
    private static final LongAdder totalFanoutPlayers = new LongAdder();

    private static final LongAdder restoreCount = new LongAdder();
    private static final LongAdder totalCursorWrites = new LongAdder();
    private static final LongAdder totalCursorRebinds = new LongAdder();
    private static final LongAdder totalPaletteHits = new LongAdder();
    private static final LongAdder totalPaletteMisses = new LongAdder();
    private static final LongAdder totalPaletteInvalidations = new LongAdder();

    private ServerHotpathMetrics() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns a start timestamp when metrics are enabled.
     */
    public static long startTimer() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /**
     * Records one prepared outgoing delta payload.
     */
    public static void recordPreparedPayload(
            final long encodeNanos,
            final long wrapNanos,
            final int rawBytes,
            final int wireBytes,
            final int playerCount
    ) {
        payloadCount.increment();
        totalEncodeNanos.add(encodeNanos);
        totalWrapNanos.add(wrapNanos);
        totalRawBytes.add(rawBytes);
        totalWireBytes.add(wireBytes);
        totalFanoutPlayers.add(playerCount);
    }

    /**
     * Returns the number of prepared payloads recorded so far.
     */
    public static long payloadCount() {
        return payloadCount.sum();
    }

    /**
     * Records one restore cursor summary.
     */
    public static void recordRestoreCursor(
            final long writes,
            final long rebinds,
            final long paletteHits,
            final long paletteMisses,
            final long paletteInvalidations
    ) {
        restoreCount.increment();
        totalCursorWrites.add(writes);
        totalCursorRebinds.add(rebinds);
        totalPaletteHits.add(paletteHits);
        totalPaletteMisses.add(paletteMisses);
        totalPaletteInvalidations.add(paletteInvalidations);
    }

    /**
     * Returns the number of restore cursor summaries recorded so far.
     */
    public static long restoreCount() {
        return restoreCount.sum();
    }

    /**
     * Logs an aggregate summary for sync payload metrics.
     */
    public static void logPayloadSummary() {
        final long payloads = payloadCount.sum();
        if (payloads == 0) {
            return;
        }

        Chunkis.LOGGER.info(
                "Chunkis server sync metrics: payloads={}, avgEncodeMicros={}, avgWrapMicros={}, avgRawBytes={}, avgWireBytes={}, avgPlayers={}",
                payloads,
                nanosToMicros(totalEncodeNanos.sum() / payloads),
                nanosToMicros(totalWrapNanos.sum() / payloads),
                totalRawBytes.sum() / payloads,
                totalWireBytes.sum() / payloads,
                totalFanoutPlayers.sum() / payloads
        );
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
                "Chunkis restore cursor metrics: restores={}, avgWrites={}, avgRebinds={}, avgPaletteHits={}, avgPaletteMisses={}, avgPaletteInvalidations={}",
                restores,
                totalCursorWrites.sum() / restores,
                totalCursorRebinds.sum() / restores,
                totalPaletteHits.sum() / restores,
                totalPaletteMisses.sum() / restores,
                totalPaletteInvalidations.sum() / restores
        );
    }

    /**
     * Formats nanoseconds as microseconds for trace messages.
     */
    public static long nanosToMicros(final long nanos) {
        return nanos / 1_000L;
    }
}
