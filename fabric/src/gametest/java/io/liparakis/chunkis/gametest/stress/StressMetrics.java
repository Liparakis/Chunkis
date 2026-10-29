package io.liparakis.chunkis.gametest.stress;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Collects server-thread tick times per scenario phase plus CPU, allocation, GC, heap and disk figures,
 * and writes them as a JSON report.
 *
 * <p>Only ticks recorded while {@link #isMeasuring()} is true count; the warm-up round is excluded.
 * Everything here runs on the server thread.</p>
 */
final class StressMetrics {

    /**
     * Scenario phase a tick belongs to.
     */
    enum Phase {
        /** Waiting for every chunk of the area to unload (saves run here). */
        UNLOAD,
        /** Waiting for every chunk to load and restore again. */
        RELOAD,
        /** Applying block, block-entity and entity edits. */
        EDIT,
        /** Ticks right after an autosave pass. */
        SAVE
    }

    /**
     * Tick durations per phase, in nanoseconds.
     */
    private final Map<Phase, LongSamples> phaseTicks = new EnumMap<>(Phase.class);

    /**
     * Tick durations over all measured phases, in nanoseconds.
     */
    private final LongSamples allTicks = new LongSamples();

    /**
     * Server-thread CPU time of each measured tick, by phase: next to {@link #phaseTicks} it tells a tick that
     * computed from one that waited.
     */
    private final Map<Phase, LongSamples> phaseTickCpu = new EnumMap<>(Phase.class);

    /**
     * Server-thread CPU time at the start of the current tick.
     */
    private long tickCpuStart;

    /**
     * Start-to-start tick intervals while measuring, in nanoseconds; includes work run between ticks.
     */
    private final LongSamples tickIntervals = new LongSamples();

    /**
     * Real time taken by each measured unload and reload, in nanoseconds, per phase.
     */
    private final Map<Phase, LongSamples> phaseNanos = new EnumMap<>(Phase.class);

    /**
     * Per-chunk latency from ticket change to unload or to the chunk being usable, in nanoseconds, per phase.
     */
    private final Map<Phase, LongSamples> chunkLatencyNanos = new EnumMap<>(Phase.class);

    /**
     * Bytes-per-chunk comparison with vanilla, added at the end of the run.
     */
    private JsonObject sizePerChunk;

    /**
     * Target tick length in nanoseconds, or 0 to run ticks unthrottled.
     */
    private final long nanosPerTick;

    /**
     * Start of the previous tick, or 0 before the first one.
     */
    private long lastTickStart;

    /**
     * Measured ticks that started more than 10% later than the target rate allows.
     */
    private int lateTicks;

    /**
     * Ticks needed to finish each measured unload and reload, per phase.
     */
    private final Map<Phase, LongSamples> phaseTickCounts = new EnumMap<>(Phase.class);

    /**
     * JVM thread bean with CPU and allocation counters.
     */
    private final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /**
     * OS bean with process CPU time.
     */
    private final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    /**
     * Server thread id, captured on the first measured tick.
     */
    private long serverThreadId = -1;

    /**
     * Whether ticks are currently recorded.
     */
    private boolean measuring;

    /**
     * Start of the current tick, or 0 when none is open.
     */
    private long tickStart;

    /**
     * Phase the next recorded tick belongs to.
     */
    private Phase phase = Phase.UNLOAD;

    /**
     * Counters when measuring started.
     */
    private Counters startCounters;

    /**
     * Counter deltas over the measured window, set when measuring stops.
     */
    private Counters totals;

    /**
     * Chunkis directory size when measuring started.
     */
    private long startDiskBytes;

    /**
     * Vanilla region and entity folders, measured for disk use alongside Chunkis.
     */
    private List<Path> vanillaDirs = List.of();

    /**
     * Vanilla folder sizes when measuring started.
     */
    private long startVanillaBytes;

    /**
     * Highest heap use seen at the end of a measured tick.
     */
    private long peakHeapBytes;

    /**
     * Creates metrics that judge tick lateness against {@code ticksPerSecond}.
     *
     * @param ticksPerSecond target tick rate, or 0 to leave ticks unthrottled
     */
    StressMetrics(final int ticksPerSecond) {
        this.nanosPerTick = ticksPerSecond > 0 ? 1_000_000_000L / ticksPerSecond : 0L;
    }

    /**
     * Starts recording; the disk baseline is taken from {@code chunkisDir}.
     *
     * @param chunkisDir world-level Chunkis directory
     * @param vanillaStorageDirs vanilla region and entity folders of the world
     */
    void start(final Path chunkisDir, final List<Path> vanillaStorageDirs) {
        vanillaDirs = vanillaStorageDirs;
        startVanillaBytes = vanillaDirs.stream().mapToLong(StressMetrics::directorySize).sum();
        serverThreadId = Thread.currentThread().threadId();
        startDiskBytes = directorySize(chunkisDir);
        startCounters = readCounters();
        measuring = true;
    }

    /**
     * Stops recording and captures the totals.
     */
    void stop() {
        measuring = false;
        totals = readCounters().minus(startCounters);
    }

    /**
     * Reads the cumulative CPU, allocation, GC and clock counters.
     *
     * @return current counters
     */
    private Counters readCounters() {
        return new Counters(os.getProcessCpuTime(), threads.getThreadCpuTime(serverThreadId),
                threads.getThreadAllocatedBytes(serverThreadId), gcCount(), gcMillis(), System.nanoTime());
    }

    /**
     * Reports whether ticks are being recorded.
     *
     * @return true while measuring
     */
    boolean isMeasuring() {
        return measuring;
    }

    /**
     * Sets the phase that following ticks are attributed to.
     *
     * @param phase current phase
     */
    void setPhase(final Phase phase) {
        this.phase = phase;
    }

    /**
     * Records how long a measured unload or reload took.
     *
     * @param completed phase that just finished
     * @param ticks     ticks it took
     * @param nanos     real time it took
     */
    void recordPhaseLength(final Phase completed, final long ticks, final long nanos) {
        if (measuring) {
            phaseTickCounts.computeIfAbsent(completed, ignored -> new LongSamples()).add(ticks);
            phaseNanos.computeIfAbsent(completed, ignored -> new LongSamples()).add(nanos);
        }
    }

    /**
     * Records how long one chunk took to unload or to become usable after its ticket changed.
     *
     * @param phase UNLOAD or RELOAD
     * @param nanos latency
     */
    void recordChunkLatency(final Phase phase, final long nanos) {
        if (measuring) {
            chunkLatencyNanos.computeIfAbsent(phase, ignored -> new LongSamples()).add(nanos);
        }
    }

    /**
     * Sets the bytes-per-chunk comparison to include in the report.
     *
     * @param comparison comparison from {@link ChunkSizeComparison}
     */
    void setSizePerChunk(final JsonObject comparison) {
        this.sizePerChunk = comparison;
    }

    /**
     * Marks the start of a server tick and records the interval since the previous one; pacing itself is
     * done by the server (see {@code TestServerMixin}).
     */
    void onTickStart() {
        tickStart = System.nanoTime();
        tickCpuStart = threads.getCurrentThreadCpuTime();
        if (measuring && lastTickStart != 0) {
            final long interval = tickStart - lastTickStart;
            tickIntervals.add(interval);
            if (nanosPerTick > 0 && interval > nanosPerTick + nanosPerTick / 10) {
                lateTicks++;
            }
        }
        lastTickStart = tickStart;
    }

    /**
     * Marks the end of a server tick and records it when measuring.
     */
    void onTickEnd() {
        if (!measuring || tickStart == 0) {
            return;
        }
        final long duration = System.nanoTime() - tickStart;
        tickStart = 0;
        allTicks.add(duration);
        phaseTicks.computeIfAbsent(phase, ignored -> new LongSamples()).add(duration);
        phaseTickCpu.computeIfAbsent(phase, ignored -> new LongSamples())
                .add(threads.getCurrentThreadCpuTime() - tickCpuStart);
        peakHeapBytes = Math.max(peakHeapBytes, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
    }

    /**
     * Builds the report and writes it as {@code stress-<epoch millis>.json} and {@code latest.json}.
     *
     * @param outputDir      report directory
     * @param config         scenario settings to embed
     * @param chunkisDir     world-level Chunkis directory
     * @param retainedHeap   heap in use after a full GC at the end
     * @return path of the timestamped report
     */
    Path writeReport(final Path outputDir, final JsonObject config, final Path chunkisDir, final long retainedHeap) {
        final JsonObject report = new JsonObject();
        report.add("config", config);

        final JsonObject ticks = new JsonObject();
        ticks.add("all", allTicks.summaryMillis());
        for (final Map.Entry<Phase, LongSamples> entry : phaseTicks.entrySet()) {
            ticks.add(entry.getKey().name().toLowerCase(), entry.getValue().summaryMillis());
        }
        report.add("tickMillis", ticks);

        final JsonObject tickCpu = new JsonObject();
        for (final Map.Entry<Phase, LongSamples> entry : phaseTickCpu.entrySet()) {
            tickCpu.add(entry.getKey().name().toLowerCase(), entry.getValue().summaryMillis());
        }
        report.add("tickCpuMillis", tickCpu);

        final JsonObject phaseLengths = new JsonObject();
        for (final Map.Entry<Phase, LongSamples> entry : phaseTickCounts.entrySet()) {
            phaseLengths.add(entry.getKey().name().toLowerCase(), entry.getValue().summaryRaw());
        }
        report.add("phaseLengthTicks", phaseLengths);

        final JsonObject phaseMillis = new JsonObject();
        for (final Map.Entry<Phase, LongSamples> entry : phaseNanos.entrySet()) {
            phaseMillis.add(entry.getKey().name().toLowerCase(), entry.getValue().summaryMillis());
        }
        report.add("phaseLengthMillis", phaseMillis);

        final JsonObject chunkLatency = new JsonObject();
        for (final Map.Entry<Phase, LongSamples> entry : chunkLatencyNanos.entrySet()) {
            chunkLatency.add(entry.getKey().name().toLowerCase(), entry.getValue().summaryMillis());
        }
        report.add("chunkLatencyMillis", chunkLatency);
        if (sizePerChunk != null) {
            report.add("sizePerChunk", sizePerChunk);
        }

        final JsonObject intervals = tickIntervals.summaryMillis();
        intervals.addProperty("lateTicks", lateTicks);
        report.add("tickIntervalMillis", intervals);

        final JsonObject cpu = new JsonObject();
        cpu.addProperty("wallSeconds", totals.nanos() / 1e9);
        cpu.addProperty("processCpuSeconds", totals.processCpuNanos() / 1e9);
        cpu.addProperty("serverThreadCpuSeconds", totals.serverThreadCpuNanos() / 1e9);
        cpu.addProperty("serverThreadAllocatedMb", totals.serverThreadAllocatedBytes() / 1048576.0);
        cpu.addProperty("gcCount", totals.gcCount());
        cpu.addProperty("gcMillis", totals.gcMillis());
        report.add("cpu", cpu);

        final JsonObject memory = new JsonObject();
        memory.addProperty("peakHeapMb", peakHeapBytes / 1048576.0);
        memory.addProperty("retainedHeapMb", retainedHeap / 1048576.0);
        report.add("memory", memory);

        final JsonObject disk = new JsonObject();
        final long diskBytes = directorySize(chunkisDir);
        disk.addProperty("chunkisDirMb", diskBytes / 1048576.0);
        disk.addProperty("growthMb", (diskBytes - startDiskBytes) / 1048576.0);
        final long vanillaBytes = vanillaDirs.stream().mapToLong(StressMetrics::directorySize).sum();
        disk.addProperty("vanillaDirsMb", vanillaBytes / 1048576.0);
        disk.addProperty("vanillaGrowthMb", (vanillaBytes - startVanillaBytes) / 1048576.0);
        report.add("disk", disk);

        final String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
        try {
            Files.createDirectories(outputDir);
            final Path timestamped = outputDir.resolve("stress-" + System.currentTimeMillis() + ".json");
            Files.writeString(timestamped, json, StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve("latest.json"), json, StandardCharsets.UTF_8);
            return timestamped;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed writing stress report to " + outputDir, e);
        }
    }

    /**
     * Sums collections across all collectors.
     *
     * @return total GC count
     */
    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }

    /**
     * Sums collection time across all collectors.
     *
     * @return total GC milliseconds
     */
    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }

    /**
     * Total size of the regular files under {@code dir}, or 0 when it does not exist.
     *
     * @param dir directory
     * @return bytes
     */
    private static long directorySize(final Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (final IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed measuring " + dir, e);
        }
    }

    /**
     * Cumulative counters, or their difference over a window.
     *
     * @param processCpuNanos            process CPU time
     * @param serverThreadCpuNanos       server-thread CPU time
     * @param serverThreadAllocatedBytes bytes allocated by the server thread
     * @param gcCount                    garbage collections
     * @param gcMillis                   time spent in garbage collection
     * @param nanos                      monotonic clock
     */
    private record Counters(long processCpuNanos, long serverThreadCpuNanos, long serverThreadAllocatedBytes,
            long gcCount, long gcMillis, long nanos) {

        /**
         * Subtracts an earlier reading.
         *
         * @param earlier counters read before this one
         * @return difference
         */
        Counters minus(final Counters earlier) {
            return new Counters(processCpuNanos - earlier.processCpuNanos,
                    serverThreadCpuNanos - earlier.serverThreadCpuNanos,
                    serverThreadAllocatedBytes - earlier.serverThreadAllocatedBytes, gcCount - earlier.gcCount,
                    gcMillis - earlier.gcMillis, nanos - earlier.nanos);
        }
    }

    /**
     * Growable list of long samples with percentile summaries.
     */
    private static final class LongSamples {

        /**
         * Sample storage.
         */
        private long[] values = new long[1024];

        /**
         * Number of samples.
         */
        private int size;

        /**
         * Appends a sample.
         *
         * @param value sample
         */
        void add(final long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        /**
         * Summarises nanosecond samples in milliseconds.
         *
         * @return count, mean, p50, p90, p95, p99 and max
         */
        JsonObject summaryMillis() {
            return summary(1e6);
        }

        /**
         * Summarises samples in their own unit.
         *
         * @return count, mean, p50, p90, p95, p99 and max
         */
        JsonObject summaryRaw() {
            return summary(1.0);
        }

        /**
         * Builds a summary, dividing every figure by {@code scale}.
         *
         * @param scale divisor
         * @return summary
         */
        private JsonObject summary(final double scale) {
            final long[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            final JsonObject summary = new JsonObject();
            summary.addProperty("count", size);
            if (size == 0) {
                return summary;
            }
            summary.addProperty("mean", Arrays.stream(sorted).average().orElse(0) / scale);
            summary.addProperty("p50", percentile(sorted, 0.50) / scale);
            summary.addProperty("p90", percentile(sorted, 0.90) / scale);
            summary.addProperty("p95", percentile(sorted, 0.95) / scale);
            summary.addProperty("p99", percentile(sorted, 0.99) / scale);
            summary.addProperty("max", sorted[size - 1] / scale);
            return summary;
        }

        /**
         * Nearest-rank percentile of sorted samples.
         *
         * @param sorted   ascending samples
         * @param fraction percentile as a fraction
         * @return sample at that rank
         */
        private static long percentile(final long[] sorted, final double fraction) {
            final int rank = (int) Math.ceil(fraction * sorted.length) - 1;
            return sorted[Math.clamp(rank, 0, sorted.length - 1)];
        }
    }
}
