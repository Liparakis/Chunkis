package io.liparakis.chunkis.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public final class ChunkTraceStore {

    private static final int DEFAULT_CAPACITY = 50_000;

    private static final AtomicLong EVENT_IDS = new AtomicLong();
    private static final AtomicLong OPERATION_IDS = new AtomicLong();

    private static final Object MONITOR = new Object();

    private static volatile int capacity = DEFAULT_CAPACITY;
    private static volatile ChunkTraceEvent[] ring = new ChunkTraceEvent[DEFAULT_CAPACITY];
    private static volatile int size = 0;
    private static volatile int writeIndex = 0;

    private ChunkTraceStore() {
        throw new AssertionError("Utility class");
    }

    public static ChunkTraceEvent record(final ChunkTraceEvent event) {
        Objects.requireNonNull(event, "event");

        final ChunkTraceEvent stored = event.eventId() > 0
                ? event
                : event.withEventId(EVENT_IDS.incrementAndGet());

        synchronized (MONITOR) {
            ring[writeIndex] = stored;
            writeIndex = (writeIndex + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        }

        return stored;
    }

    public static String nextOperationId(final String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return prefix + '-' + OPERATION_IDS.incrementAndGet();
    }

    public static void trace(
            final ChunkisDebugDomain domain,
            final ChunkTraceEventType eventType,
            final ChunkTraceSeverity severity,
            final ChunkTraceReason reason,
            final String source,
            final String message,
            final String worldId,
            final DebugChunkKey chunkKey,
            final DebugRegionKey regionKey,
            final String operationId,
            final Boolean dirtyState,
            final Integer byteSize
    ) {
        if (!ChunkisDebugConfig.allows(domain, severity)) {
            return;
        }

        record(new ChunkTraceEvent(
                0L,
                System.currentTimeMillis(),
                Thread.currentThread().getName(),
                domain,
                eventType,
                severity,
                reason,
                source,
                message,
                worldId,
                chunkKey,
                regionKey,
                operationId,
                dirtyState,
                byteSize
        ));
    }

    public static List<ChunkTraceEvent> latest(final int count) {
        return latestMatching(count, event -> true);
    }

    public static List<ChunkTraceEvent> latestMatching(
            final int count,
            final Predicate<ChunkTraceEvent> predicate
    ) {
        if (count <= 0) {
            return List.of();
        }
        Objects.requireNonNull(predicate, "predicate");

        synchronized (MONITOR) {
            final List<ChunkTraceEvent> events = new ArrayList<>(Math.min(count, size));
            for (int i = 0; i < size && events.size() < count; i++) {
                final int index = (writeIndex - 1 - i + capacity) % capacity;
                final ChunkTraceEvent event = ring[index];
                if (event != null && predicate.test(event)) {
                    events.add(event);
                }
            }
            return events;
        }
    }

    public static void clear() {
        synchronized (MONITOR) {
            ring = new ChunkTraceEvent[capacity];
            size = 0;
            writeIndex = 0;
        }
    }

    static void setCapacityForTests(final int newCapacity) {
        if (newCapacity <= 0) {
            throw new IllegalArgumentException("newCapacity must be > 0");
        }

        synchronized (MONITOR) {
            capacity = newCapacity;
            ring = new ChunkTraceEvent[newCapacity];
            size = 0;
            writeIndex = 0;
            EVENT_IDS.set(0L);
            OPERATION_IDS.set(0L);
        }
    }

    public static void resetForTests() {
        setCapacityForTests(DEFAULT_CAPACITY);
    }
}
