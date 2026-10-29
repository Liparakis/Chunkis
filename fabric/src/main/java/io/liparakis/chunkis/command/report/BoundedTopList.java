package io.liparakis.chunkis.command.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Bounded priority-queue helper that retains only the top-N largest elements.
 *
 * @param <T> list elements type
 */
final class BoundedTopList<T> {

    /**
     * Maximum size of the list.
     */
    final int capacity;

    /**
     * Ordering sorting comparator.
     */
    final Comparator<T> ascendingComparator;

    /**
     * Underlying priority queue.
     */
    final PriorityQueue<T> heap;

    /**
     * Constructor.
     *
     * @param capacity            maximum elements to retain
     * @param ascendingComparator heap sorting comparator
     */
    BoundedTopList(final int capacity, final Comparator<T> ascendingComparator) {
        this.capacity = Math.max(0, capacity);
        this.ascendingComparator = ascendingComparator;
        this.heap = new PriorityQueue<>(Math.max(1, this.capacity), ascendingComparator);
    }

    /**
     * Inserts a value into the top list if it is larger than the smallest element currently retained.
     *
     * @param value elements to add
     */
    void add(final T value) {
        if (capacity == 0) {
            return;
        }

        if (heap.size() < capacity) {
            heap.add(value);
            return;
        }

        final T smallestKept = heap.peek();
        if (smallestKept != null && ascendingComparator.compare(value, smallestKept) > 0) {
            heap.poll();
            heap.add(value);
        }
    }

    /**
     * Adds a list of values to the bounded top list.
     *
     * @param values list of elements to add
     */
    void addAll(final List<T> values) {
        for (final T value : values) {
            add(value);
        }
    }

    /**
     * Returns an immutable copy of the retained elements sorted in descending order.
     *
     * @return descending elements list
     */
    List<T> snapshotDescending() {
        final List<T> values = new ArrayList<>(heap);
        values.sort(ascendingComparator.reversed());
        return List.copyOf(values);
    }
}
