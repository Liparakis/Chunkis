package io.liparakis.chunkis.client;

import io.liparakis.chunkis.Chunkis;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;

/**
 * Thread-safe performance metrics and rate-limited error logging for the
 * Chunkis client packet pipeline.
 *
 * <p>
 * All counters use {@link LongAdder} which outperforms
 * {@link java.util.concurrent.atomic.AtomicLong}
 * under contention by reducing false sharing. Enable via
 * {@code -Dchunkis.client.metrics=true}.
 *
 * <p>
 * This class is purely additive - it never retains world, chunk, or block
 * references.
 */
@Environment(EnvType.CLIENT)
public final class ClientDeltaMetrics {

    /**
     * Enable/disable metrics collection. Zero overhead when disabled.
     */
    public static final boolean ENABLED = Boolean.getBoolean("chunkis.client.metrics");

    /**
     * Only the 1st error and every {@value}th thereafter
     * are logged, preventing log spam from a flood of bad packets.
     */
    private static final int ERROR_LOG_INTERVAL = 100;

    /** Stores packets received. */
    private static final LongAdder packetsReceived = new LongAdder();
    /** Stores total bytes received. */
    private static final LongAdder totalBytesReceived = new LongAdder();
    /** Stores total decode nanos. */
    private static final LongAdder totalDecodeNanos = new LongAdder();
    /** Stores total blocks changed. */
    private static final LongAdder totalBlocksChanged = new LongAdder();

    /** Stores error count. */
    private static final AtomicInteger errorCount = new AtomicInteger(0);

    /** Performs client delta metrics. */
    private ClientDeltaMetrics() {
        throw new AssertionError("Utility class");
    }

    /**
     * Records receipt of one packet of the given byte length.
     *
     * @param bytes the raw (pre-decompression) payload size in bytes
     */
    public static void recordPacket(final int bytes) {
        packetsReceived.increment();
        totalBytesReceived.add(bytes);
    }

    /**
     * Records elapsed decode time for one packet.
     *
     * @param elapsedNanos wall-clock nanoseconds spent decoding
     */
    public static void recordDecode(final long elapsedNanos) {
        totalDecodeNanos.add(elapsedNanos);
    }

    /**
     * Records the number of block-state changes applied from one packet.
     *
     * @param count number of block instructions applied
     */
    public static void recordBlocksChanged(final int count) {
        totalBlocksChanged.add(count);
    }

    /**
     * Returns the current total packet count.
     * Used externally to decide when to emit periodic metric summaries.
     *
     * @return cumulative packets received since startup
     */
    public static long packetCount() {
        return packetsReceived.sum();
    }

    /**
     * Logs an error with rate limiting. Only logs the 1st and every
     * {@value #ERROR_LOG_INTERVAL}th error to avoid log spam.
     *
     * @param messageSupplier lazily evaluated message (only called when logging)
     */
    public static void logErrorThrottled(final Supplier<String> messageSupplier) {
        logErrorThrottled(messageSupplier, null);
    }

    /**
     * Logs an error with rate limiting and an optional cause.
     *
     * @param messageSupplier lazily evaluated message (only called when logging)
     * @param cause           optional exception, may be null
     */
    public static void logErrorThrottled(final Supplier<String> messageSupplier, final Throwable cause) {
        final int errors = errorCount.incrementAndGet();
        if (!shouldLogError(errors)) {
            return;
        }

        final String message = messageSupplier.get();
        if (cause != null) {
            Chunkis.LOGGER.error("{} - error #{} (logging every {}th)",
                    message, errors, ERROR_LOG_INTERVAL, cause);
        } else {
            Chunkis.LOGGER.error("{} - error #{} (logging every {}th)",
                    message, errors, ERROR_LOG_INTERVAL);
        }
    }

    /**
     * Logs a human-readable summary of all metrics collected since startup.
     * Intended to be called periodically (e.g., every 1024 packets).
     * Skips logging if no packets have been received.
     */
    public static void logSummary() {
        final long packets = packetsReceived.sum();
        if (packets == 0) {
            return;
        }

        final double avgBytes = averagePerPacket(totalBytesReceived.sum(), packets);
        final double avgMicros = averagePerPacket(totalDecodeNanos.sum(), packets) / 1_000.0;
        final double avgBlocks = averagePerPacket(totalBlocksChanged.sum(), packets);

        Chunkis.LOGGER.info(String.format(
                "Chunk Delta Metrics - Packets: %d, Avg: %.1f bytes, %.1f blocks, %.2fus decode",
                packets, avgBytes, avgBlocks, avgMicros));
    }

    /**
     * Returns an immutable snapshot of the current metric values.
     * Useful for testing and external reporting without exposing the live counters.
     *
     * @return a point-in-time snapshot
     */
    @SuppressWarnings("unused")
    public static MetricsSnapshot snapshot() {
        return new MetricsSnapshot(
                packetsReceived.sum(),
                totalBytesReceived.sum(),
                totalDecodeNanos.sum(),
                totalBlocksChanged.sum(),
                errorCount.get());
    }

    /**
     * Returns true if the given error count should trigger a log entry.
     * Always logs the first error; then throttles to every
     * {@value #ERROR_LOG_INTERVAL}th.
     *
     * @param errors the current total error count
     * @return true if this error should be logged
     */
    private static boolean shouldLogError(final int errors) {
        return errors == 1 || errors % ERROR_LOG_INTERVAL == 0;
    }

    /**
     * Computes the per-packet average of the given total.
     * Returns 0 if packets is zero to avoid division by zero.
     *
     * @param total   the cumulative counter value
     * @param packets the total packet count
     * @return the per-packet average as a double
     */
    private static double averagePerPacket(final long total, final long packets) {
        return packets > 0 ? (double) total / packets : 0.0;
    }

    /**
     * Immutable snapshot of all metric counters at a single point in time.
     *
     * @param packets     total packets received
     * @param bytes       total bytes received (pre-decompression)
     * @param decodeNanos cumulative decode time in nanoseconds
     * @param blocks      total block-state changes applied
     * @param errors      total errors encountered
     */
    public record MetricsSnapshot(
            long packets,
            long bytes,
            long decodeNanos,
            long blocks,
            int errors) {

        /**
         * Returns the average byte length per packet, or 0 if no packets recorded.
         */
        public double avgBytes() {
            return packets > 0 ? (double) bytes / packets : 0;
        }

        /**
         * Returns the average block changes per packet, or 0 if no packets recorded.
         */
        public double avgBlocks() {
            return packets > 0 ? (double) blocks / packets : 0;
        }

        /**
         * Returns the average decode time per packet in microseconds,
         * or 0 if no packets recorded.
         */
        public double avgDecodeMicros() {
            return packets > 0 ? (double) decodeNanos / packets / 1_000 : 0;
        }

        @Override
        public @NotNull String toString() {
            return String.format("Packets: %d, Avg: %.1f bytes, %.1f blocks, %.2fus, Errors: %d",
                    packets, avgBytes(), avgBlocks(), avgDecodeMicros(), errors);
        }
    }
}
