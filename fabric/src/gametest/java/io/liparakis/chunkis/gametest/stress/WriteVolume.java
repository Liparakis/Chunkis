package io.liparakis.chunkis.gametest.stress;

import io.liparakis.chunkis.Chunkis;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bytes written to region files over a whole run, by store, for both storage formats: the payload bytes and the
 * bytes they occupy on disk (vanilla rounds every chunk up to 4 KiB sectors). With {@code
 * -Dchunkis.stress.countWrites=true}; logged when the server stops.
 */
public final class WriteVolume {

    /**
     * Whether writes are counted.
     */
    public static final boolean ENABLED = Boolean.getBoolean("chunkis.stress.countWrites");

    /**
     * Writes, payload bytes and occupied bytes by store.
     */
    private static final Map<String, LongAdder[]> STORES = new ConcurrentHashMap<>();

    /**
     * Not instantiated.
     */
    private WriteVolume() {
    }

    /**
     * Counts one chunk write.
     *
     * @param store    store name, such as the region directory
     * @param payload  bytes of the chunk's payload
     * @param occupied bytes the write occupies on disk
     */
    public static void record(final String store, final long payload, final long occupied) {
        final LongAdder[] counters = STORES.computeIfAbsent(store,
                ignored -> new LongAdder[]{new LongAdder(), new LongAdder(), new LongAdder()});
        counters[0].increment();
        counters[1].add(payload);
        counters[2].add(occupied);
    }

    /**
     * Logs the totals.
     */
    static void log() {
        if (!ENABLED) {
            return;
        }
        new TreeMap<>(STORES).forEach((store, counters) -> Chunkis.LOGGER.info(
                "Chunkis stress: writes store={} count={} payloadBytes={} occupiedBytes={}", store,
                counters[0].sum(), counters[1].sum(), counters[2].sum()));
    }
}
