package io.liparakis.chunkis.world.entity.replay;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Retains each dimension's next replay entry across time-budget cuts. An idle dimension must not reset
 * another dimension's progress, and resumption must distinguish entities in the same chunk.
 * Used only on the server thread.
 *
 * @param <K> complete queue key in replay order
 */
final class EntityReplayProgress<K extends Comparable<K>> {
    /** Next unprocessed entry for each dimension. */
    private final Map<String, K> cursors = new HashMap<>();

    /**
     * Finds the first remaining entry at or after this dimension's cursor, wrapping if it disappeared
     * beyond the end of the current snapshot.
     *
     * @param dimension dimension being drained
     * @param entries sorted queue snapshot
     * @return starting index, or zero for an empty snapshot
     */
    int start(final String dimension, final List<? extends Map.Entry<K, ?>> entries) {
        final K cursor = cursors.get(dimension);
        if (cursor != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).getKey().compareTo(cursor) >= 0) {
                    return i;
                }
            }
        }
        return 0;
    }

    /**
     * Records the first entry not processed when this dimension exhausted its budget.
     *
     * @param dimension dimension being drained
     * @param nextEntry first unprocessed complete queue key
     */
    void pause(final String dimension, final K nextEntry) {
        cursors.put(dimension, nextEntry);
    }

    /**
     * Restarts only this dimension after a complete traversal, including when it had no entries.
     *
     * @param dimension dimension whose traversal finished
     */
    void complete(final String dimension) {
        cursors.remove(dimension);
    }

    /** Discards all progress when the server stops. */
    void clear() {
        cursors.clear();
    }
}
