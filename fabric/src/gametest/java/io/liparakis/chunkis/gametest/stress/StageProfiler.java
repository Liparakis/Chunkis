package io.liparakis.chunkis.gametest.stress;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.management.ManagementFactory;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import io.liparakis.chunkis.gametest.stress.StressMetrics.LongSamples;

/**
 * Benchmark-only instrumentation of the chunk load pipeline, fed by the {@code profile} game test mixins: wall time
 * and allocated bytes of each stage, a per-chunk timeline from ticket to usable chunk, generation work, and the
 * recent-chunk cache's traffic. Off unless {@code -Dchunkis.stress.stages=true}; while off every hook returns after
 * one field read.
 *
 * <p>Stage times are measured on the thread that runs the stage, so they are work, not waiting: the gaps between
 * timeline events minus the work inside them are queueing. CPU per stage is not measured here, since thread CPU
 * time on Windows only advances in 15.6 ms steps; it comes from the JFR execution samples of the same run.</p>
 */
public final class StageProfiler {

    /**
     * Whether profiling is on.
     */
    public static final boolean ENABLED = Boolean.getBoolean("chunkis.stress.stages");

    /**
     * A timed stage of loading a chunk.
     */
    public enum Stage {
        /** Chunkis's whole synthetic NBT provider: delta resolution, storage load, NBT build. */
        CHUNKIS_PROVIDE,
        /** Reading the compressed entry from the region file. */
        IO,
        /** Zstd decompression of an entry. */
        DECOMPRESS,
        /** Looking a chunk up in the recent-chunk cache, verifying a copy's fingerprint included. */
        CACHE_LOOKUP,
        /** Decoding a full CIS entry into a delta. */
        DECODE,
        /** Decoding an entry except its blocks, when the cache holds them. */
        DECODE_WITHOUT_BLOCKS,
        /** Filling a delta from cached sections. */
        CACHE_FILL,
        /** Building the chunk NBT vanilla parses. */
        NBT_BUILD,
        /** Attaching pre-built sections to that NBT. */
        EMBED,
        /** Vanilla {@code SerializedChunk.fromNbt}: NBT parse and section construction. */
        PARSE,
        /** Vanilla {@code SerializedChunk.convert} into a proto chunk, Chunkis's restore hooks included. */
        CONVERT,
        /** Vanilla's full-chunk conversion on the server thread, sub-steps below included. */
        FULL_CHUNK,
        /** {@code WorldChunk.loadEntities}. */
        LOAD_ENTITIES,
        /** {@code WorldChunk.updateAllBlockEntities}. */
        BLOCK_ENTITIES,
        /** {@code WorldChunk.addChunkTickSchedulers}. */
        TICK_SCHEDULERS
    }

    /**
     * Thread context a stage ran in, since storage reads also happen for saves.
     */
    public enum Context {
        /** Any thread but Chunkis's save workers. */
        LOAD,
        /** A Chunkis async save worker, reading stored blocks back for a save. */
        SAVE
    }

    /**
     * Events of one chunk's way from ticket to usable, in order.
     */
    public enum Event {
        /** The benchmark forced the chunk. */
        TICKET,
        /** Vanilla asked for the chunk's NBT. */
        REQUESTED,
        /** The NBT future completed. */
        NBT_READY,
        /** {@code fromNbt} finished. */
        PARSED,
        /** {@code convert} finished. */
        CONVERTED,
        /** The load finished, converted or found nothing stored. */
        LOAD_DONE,
        /** Full-chunk conversion finished. */
        FULL,
        /** The chunk load event fired. */
        LOADED_EVENT,
        /** The benchmark first saw the chunk usable. */
        ACCESSIBLE
    }

    /**
     * Generation work counted by step.
     */
    public enum Generation {
        /** {@code populateNoise}: terrain generated from scratch. */
        NOISE,
        /** {@code generateFeatures}. */
        FEATURES,
        /** {@code initializeLight}. */
        INITIALIZE_LIGHT,
        /** {@code light}. */
        LIGHT,
        /** {@code convertToFullChunk}. */
        FULL
    }

    /**
     * Calls, wall nanos and allocated bytes per context and stage.
     */
    private static final LongAdder[][][] STAGES = new LongAdder[Context.values().length][Stage.values().length][3];

    /**
     * Longest single call per context and stage, in nanos.
     */
    private static final AtomicLongArray MAX = new AtomicLongArray(Context.values().length * Stage.values().length);

    /**
     * Start wall nanos and allocated bytes of the stages open on this thread.
     */
    private static final ThreadLocal<long[]> STARTS = ThreadLocal.withInitial(() -> new long[Stage.values().length * 2]);

    /**
     * Generation calls by step.
     */
    private static final LongAdder[] GENERATION = new LongAdder[Generation.values().length];

    /**
     * Event times of chunks on their way, by packed position.
     */
    private static final Long2ObjectMap<long[]> TIMELINES = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());

    /**
     * Intervals between consecutive events of finished timelines, by the later event.
     */
    private static final Map<Event, LongSamples> INTERVALS = new EnumMap<>(Event.class);

    /**
     * Request to usable, of chunks that became usable.
     */
    private static final LongSamples REQUESTED_TO_ACCESSIBLE = new LongSamples();

    /**
     * How long before its ticket a chunk was already requested, by neighbouring tickets.
     */
    private static final LongSamples TICKET_LEAD = new LongSamples();

    /**
     * Ticket to usable, of chunks not yet requested when their ticket came: the loads a player waits for.
     */
    private static final LongSamples COLD_TICKET_TO_ACCESSIBLE = new LongSamples();

    /**
     * Tickets that came before their chunk was requested.
     */
    private static final LongAdder COLD_TICKETS = new LongAdder();

    /**
     * Tickets for chunks already usable.
     */
    private static final LongAdder RESIDENT_TICKETS = new LongAdder();

    /**
     * Loads requested and not yet converted: the loader's backlog.
     */
    private static final java.util.concurrent.atomic.AtomicLong IN_FLIGHT = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Recent-chunk cache traffic.
     */
    public enum CacheCounter {
        /** Lookups that returned usable blocks. */
        HITS,
        /** Lookups with no entry. */
        MISSES,
        /** Lookups that found an entry and dropped it as stale. */
        INVALIDATED,
        /** Entries stored from an unload save. */
        PUTS,
        /** Entries stored from an unload without a save, verified on first use. */
        PUTS_TO_VERIFY,
        /** Entries evicted over budget. */
        EVICTIONS,
        /** Estimated bytes evicted. */
        EVICTED_BYTES,
        /** Highest estimated bytes held. */
        PEAK_BYTES,
        /** Highest entry count held. */
        PEAK_ENTRIES
    }

    /**
     * Cache counters.
     */
    private static final AtomicLongArray CACHE = new AtomicLongArray(CacheCounter.values().length);

    static {
        for (final LongAdder[][] context : STAGES) {
            for (final LongAdder[] stage : context) {
                for (int i = 0; i < stage.length; i++) {
                    stage[i] = new LongAdder();
                }
            }
        }
        for (int i = 0; i < GENERATION.length; i++) {
            GENERATION[i] = new LongAdder();
        }
    }

    /**
     * Allocation counter of the JVM's threads.
     */
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /**
     * Not instantiated.
     */
    private StageProfiler() {
    }

    /**
     * Opens a stage on the calling thread.
     *
     * @param stage stage
     */
    public static void begin(final Stage stage) {
        if (!ENABLED) {
            return;
        }
        final long[] starts = STARTS.get();
        starts[stage.ordinal() * 2] = System.nanoTime();
        starts[stage.ordinal() * 2 + 1] = THREADS.getCurrentThreadAllocatedBytes();
    }

    /**
     * Closes a stage opened on the calling thread; a stage whose body threw is never closed, and its next opening
     * replaces the start.
     *
     * @param stage stage
     */
    public static void end(final Stage stage) {
        if (!ENABLED) {
            return;
        }
        final long[] starts = STARTS.get();
        final long wall = System.nanoTime() - starts[stage.ordinal() * 2];
        final long allocated = THREADS.getCurrentThreadAllocatedBytes() - starts[stage.ordinal() * 2 + 1];
        final int context = Thread.currentThread().getName().startsWith("Chunkis-AsyncSave")
                ? Context.SAVE.ordinal() : Context.LOAD.ordinal();
        final LongAdder[] stats = STAGES[context][stage.ordinal()];
        stats[0].increment();
        stats[1].add(wall);
        stats[2].add(allocated);
        MAX.accumulateAndGet(context * Stage.values().length + stage.ordinal(), wall, Math::max);
    }

    /**
     * Counts a generation step.
     *
     * @param step step
     */
    public static void generated(final Generation step) {
        if (ENABLED) {
            GENERATION[step.ordinal()].increment();
        }
    }

    /**
     * Records an event of a chunk's timeline. A timeline lives from the chunk's load request until it unloads, so
     * it also tells whether a chunk is resident. Every stage event records the time since the previous one. A ticket
     * is cold when its chunk has no timeline, so nothing loads it yet; for a chunk still loading it records how far
     * ahead the request came instead.
     *
     * @param event event
     * @param x     chunk X
     * @param z     chunk Z
     */
    public static void event(final Event event, final int x, final int z) {
        if (!ENABLED) {
            return;
        }
        final long now = System.nanoTime();
        final long key = net.minecraft.util.math.ChunkPos.toLong(x, z);
        final int requested = Event.REQUESTED.ordinal();
        final int ticket = Event.TICKET.ordinal();
        final int accessible = Event.ACCESSIBLE.ordinal();
        synchronized (TIMELINES) {
            long[] times = TIMELINES.get(key);
            if (event == Event.TICKET) {
                if (times == null) {
                    times = new long[Event.values().length];
                    times[ticket] = now;
                    TIMELINES.put(key, times);
                    COLD_TICKETS.increment();
                } else if (times[accessible] != 0L) {
                    RESIDENT_TICKETS.increment();
                } else if (times[requested] != 0L) {
                    TICKET_LEAD.add(now - times[requested]);
                }
                return;
            }
            if (event == Event.REQUESTED) {
                if (times != null && times[requested] != 0L && times[Event.LOAD_DONE.ordinal()] == 0L) {
                    IN_FLIGHT.decrementAndGet();
                }
                final long coldTicket = times != null && times[requested] == 0L ? times[ticket] : 0L;
                times = new long[Event.values().length];
                times[ticket] = coldTicket;
                times[requested] = now;
                TIMELINES.put(key, times);
                IN_FLIGHT.incrementAndGet();
                return;
            }
            if (times == null || times[requested] == 0L || times[event.ordinal()] != 0L) {
                return;
            }
            times[event.ordinal()] = now;
            long previous = times[requested];
            for (int i = requested + 1; i < event.ordinal(); i++) {
                previous = Math.max(previous, times[i]);
            }
            synchronized (INTERVALS) {
                INTERVALS.computeIfAbsent(event, ignored -> new LongSamples()).add(now - previous);
                if (event == Event.ACCESSIBLE) {
                    REQUESTED_TO_ACCESSIBLE.add(now - times[requested]);
                    if (times[ticket] != 0L) {
                        COLD_TICKET_TO_ACCESSIBLE.add(now - times[ticket]);
                    }
                }
            }
            if (event == Event.LOAD_DONE) {
                IN_FLIGHT.decrementAndGet();
            }
        }
    }

    /**
     * Ends a chunk's timeline when it unloads.
     *
     * @param x chunk X
     * @param z chunk Z
     */
    public static void unloaded(final int x, final int z) {
        if (!ENABLED) {
            return;
        }
        synchronized (TIMELINES) {
            final long[] times = TIMELINES.remove(net.minecraft.util.math.ChunkPos.toLong(x, z));
            if (times != null && times[Event.REQUESTED.ordinal()] != 0L
                    && times[Event.LOAD_DONE.ordinal()] == 0L) {
                IN_FLIGHT.decrementAndGet();
            }
        }
    }

    /**
     * Returns the loads requested and not yet finished.
     *
     * @return loads in flight
     */
    public static long inFlight() {
        return IN_FLIGHT.get();
    }

    /**
     * Returns whether a chunk is known and not usable yet.
     *
     * @param x chunk X
     * @param z chunk Z
     * @return true while the chunk is loading or waiting to become usable
     */
    public static boolean pending(final int x, final int z) {
        if (!ENABLED) {
            return false;
        }
        final long[] times = TIMELINES.get(net.minecraft.util.math.ChunkPos.toLong(x, z));
        return times != null && times[Event.ACCESSIBLE.ordinal()] == 0L;
    }

    /**
     * Adds to a cache counter.
     *
     * @param counter counter
     * @param amount  amount
     */
    public static void cache(final CacheCounter counter, final long amount) {
        if (ENABLED) {
            CACHE.addAndGet(counter.ordinal(), amount);
        }
    }

    /**
     * Raises a cache peak.
     *
     * @param counter peak counter
     * @param value   current value
     */
    public static void cachePeak(final CacheCounter counter, final long value) {
        if (ENABLED) {
            CACHE.accumulateAndGet(counter.ordinal(), value, Math::max);
        }
    }

    /**
     * How a region write relates to the entry it replaces.
     */
    public enum WriteKind {
        /** No entry stored before. */
        NEW,
        /** The same bytes as the stored entry. */
        IDENTICAL,
        /** Different bytes. */
        CHANGED
    }

    /**
     * Writes and bytes by store (chunks, unfinished chunks) and kind.
     */
    private static final LongAdder[][][] WRITES = new LongAdder[2][WriteKind.values().length][2];

    static {
        for (final LongAdder[][] store : WRITES) {
            for (final LongAdder[] kind : store) {
                kind[0] = new LongAdder();
                kind[1] = new LongAdder();
            }
        }
    }

    /**
     * Counts a region write by how it relates to the stored entry.
     *
     * @param unfinished whether it went to the unfinished-chunk store
     * @param kind       relation to the stored entry
     * @param bytes      bytes written
     */
    public static void recordWrite(final boolean unfinished, final WriteKind kind, final long bytes) {
        final LongAdder[] counters = WRITES[unfinished ? 1 : 0][kind.ordinal()];
        counters[0].increment();
        counters[1].add(bytes);
    }

    /**
     * Returns the write counts recorded so far.
     *
     * @return counts and bytes by store and kind
     */
    static JsonObject writesJson() {
        final JsonObject json = new JsonObject();
        for (int store = 0; store < 2; store++) {
            final JsonObject kinds = new JsonObject();
            for (final WriteKind kind : WriteKind.values()) {
                final JsonObject entry = new JsonObject();
                entry.addProperty("writes", WRITES[store][kind.ordinal()][0].sum());
                entry.addProperty("mb", WRITES[store][kind.ordinal()][1].sum() / 1048576.0);
                kinds.add(kind.name().toLowerCase(), entry);
            }
            json.add(store == 0 ? "chunks" : "unfinished", kinds);
        }
        return json;
    }

    /**
     * Where each delta last went from clean to dirty: the first frames outside the delta, and when.
     */
    private static final Map<Object, String> DIRTY_SITES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Records where a clean delta is becoming dirty.
     *
     * @param delta the delta
     */
    public static void recordDirtySite(final Object delta) {
        DIRTY_SITES.put(delta, callSite());
    }

    /**
     * Where each delta was last marked saved.
     */
    private static final Map<Object, String> CLEAN_SITES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Records where a dirty delta is being marked saved.
     *
     * @param delta the delta
     */
    public static void recordCleanSite(final Object delta) {
        CLEAN_SITES.put(delta, callSite());
    }

    /**
     * Where each delta was last put into the dirty tracker while clean.
     */
    private static final Map<Object, String> CLEAN_PUTS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Records where a clean delta entered the dirty tracker.
     *
     * @param delta  the delta
     * @param source the tracker call's source label
     */
    public static void recordCleanPut(final Object delta, final String source) {
        CLEAN_PUTS.put(delta, source + " | " + callSite());
    }

    /**
     * Returns where a delta last entered the dirty tracker while clean.
     *
     * @param delta the delta
     * @return source and call site, or {@code "never put clean"}
     */
    static String cleanPut(final Object delta) {
        return CLEAN_PUTS.getOrDefault(delta, "never put clean");
    }

    /**
     * Returns where a delta was last marked saved.
     *
     * @param delta the delta
     * @return call site, or {@code "never marked saved"}
     */
    static String cleanSite(final Object delta) {
        return CLEAN_SITES.getOrDefault(delta, "never marked saved");
    }

    /**
     * Returns the first frames of the current stack outside the delta and this instrumentation.
     *
     * @return frames, innermost first
     */
    private static String callSite() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().startsWith("io.liparakis.chunkis.core.ChunkDelta")
                        && !frame.getClassName().contains("ChunkDeltaProbeMixin")
                        && !frame.getClassName().endsWith("StageProfiler"))
                .limit(5)
                .map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)
                        + "." + frame.getMethodName())
                .collect(java.util.stream.Collectors.joining(" < ")));
    }

    /**
     * Returns where a delta last went from clean to dirty.
     *
     * @param delta the delta
     * @return call site, or {@code "never seen clean"}
     */
    static String dirtySite(final Object delta) {
        return DIRTY_SITES.getOrDefault(delta, "never seen clean");
    }

    /**
     * Starts measuring afresh, keeping open timelines and the cache counters, which callers diff per leg.
     */
    static void reset() {
        for (final LongAdder[][] context : STAGES) {
            for (final LongAdder[] stage : context) {
                for (final LongAdder counter : stage) {
                    counter.reset();
                }
            }
        }
        for (int i = 0; i < MAX.length(); i++) {
            MAX.set(i, 0L);
        }
        for (final LongAdder step : GENERATION) {
            step.reset();
        }
        for (final LongAdder[][] store : WRITES) {
            for (final LongAdder[] kind : store) {
                kind[0].reset();
                kind[1].reset();
            }
        }
        synchronized (INTERVALS) {
            INTERVALS.clear();
            REQUESTED_TO_ACCESSIBLE.clear();
            TICKET_LEAD.clear();
            COLD_TICKET_TO_ACCESSIBLE.clear();
        }
        COLD_TICKETS.reset();
        RESIDENT_TICKETS.reset();
    }

    /**
     * Snapshots the cache counters.
     *
     * @return counter values by name
     */
    static JsonObject cacheSnapshot() {
        final JsonObject json = new JsonObject();
        for (final CacheCounter counter : CacheCounter.values()) {
            json.addProperty(counter.name().toLowerCase(), CACHE.get(counter.ordinal()));
        }
        return json;
    }

    /**
     * Writes everything measured so far.
     *
     * @return the measurements
     */
    static JsonObject toJson() {
        final JsonObject json = new JsonObject();
        for (final Context context : Context.values()) {
            final JsonObject stages = new JsonObject();
            for (final Stage stage : Stage.values()) {
                final LongAdder[] stats = STAGES[context.ordinal()][stage.ordinal()];
                final long calls = stats[0].sum();
                if (calls == 0) {
                    continue;
                }
                final JsonObject entry = new JsonObject();
                entry.addProperty("calls", calls);
                entry.addProperty("wallMs", stats[1].sum() / 1e6);
                entry.addProperty("meanWallUs", stats[1].sum() / 1e3 / calls);
                entry.addProperty("maxWallMs", MAX.get(context.ordinal() * Stage.values().length + stage.ordinal()) / 1e6);
                entry.addProperty("allocatedMb", stats[2].sum() / 1048576.0);
                entry.addProperty("meanAllocatedKb", stats[2].sum() / 1024.0 / calls);
                stages.add(stage.name().toLowerCase(), entry);
            }
            json.add("stages" + (context == Context.LOAD ? "" : "OnSaveThreads"), stages);
        }
        final JsonObject generation = new JsonObject();
        for (final Generation step : Generation.values()) {
            generation.addProperty(step.name().toLowerCase(), GENERATION[step.ordinal()].sum());
        }
        json.add("generationCalls", generation);
        final JsonObject intervals = new JsonObject();
        synchronized (INTERVALS) {
            for (final Map.Entry<Event, LongSamples> entry : INTERVALS.entrySet()) {
                intervals.add("to" + entry.getKey().name().toLowerCase(), entry.getValue().summaryMillis());
            }
            intervals.add("requestedToAccessible", REQUESTED_TO_ACCESSIBLE.summaryMillis());
            intervals.add("requestedBeforeTicket", TICKET_LEAD.summaryMillis());
            intervals.add("coldTicketToAccessible", COLD_TICKET_TO_ACCESSIBLE.summaryMillis());
        }
        json.add("timelineMillis", intervals);
        json.addProperty("residentTimelines", TIMELINES.size());
        json.addProperty("coldTickets", COLD_TICKETS.sum());
        json.addProperty("residentTickets", RESIDENT_TICKETS.sum());
        json.addProperty("loadsInFlightAtEnd", IN_FLIGHT.get());
        json.add("cache", cacheSnapshot());
        json.add("writes", writesJson());
        return json;
    }
}
