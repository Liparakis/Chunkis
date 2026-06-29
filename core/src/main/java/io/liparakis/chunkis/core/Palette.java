package io.liparakis.chunkis.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bidirectional mapping between objects of type T and compact integer IDs.
 * <p>
 * Used as a palette for compressing repeated values (e.g. BlockStates in chunks),
 * where many entries share the same value and can be represented by a small ID.
 * <p>
 * Instead of storing the full object multiple times, we store:
 * - one unique instance in the palette
 * - many integer references to it
 * <p>
 * Typical use case:
 * - Chunk sections where most blocks repeat (stone, air, etc.)
 * - IDs can later be bit-packed for further compression
 * <p>
 * Characteristics:
 * - IDs are assigned sequentially starting from 0
 * - O(1) lookup by object (HashMap)
 * - O(1) lookup by ID (ArrayList)
 * - IDs are stable once assigned
 * - Not thread-safe
 * <p>
 * Memory:
 * - Initial capacity: 32
 * - Grows dynamically
 * - ~2 references per unique entry (map + list)
 *
 * @param <T> type stored in the palette
 * @author Liparakis
 * @version 1
 * @see ChunkDelta
 * @see BlockInstruction
 */
public class Palette<T> {

    /**
     * Initial capacity optimized for typical chunk block diversity
     */
    private static final int INITIAL_CAPACITY = 32;

    /**
     * Sequential list mapping IDs to entries (ID → Entry)
     */
    private final List<T> idToEntry = new ArrayList<>(INITIAL_CAPACITY);

    /**
     * Reverse lookup mapping entries to their assigned IDs (Entry → ID)
     */
    private final Map<T, Integer> entryToId = new HashMap<>(INITIAL_CAPACITY);

    /**
     * Retrieves the ID for the given entry, adding it to the palette if not present.
     * <p>
     * This method ensures that each unique object gets a consistent ID. If the object
     * is already in the palette, its existing ID is returned. Otherwise, a new ID is
     * assigned sequentially.
     * </p>
     * <p>
     * Example usage:
     * <pre>{@code
     * Palette<BlockState> palette = new Palette<>();
     * int stoneId = palette.getOrAdd(Blocks.STONE.getDefaultState());  // Returns 0
     * int dirtId = palette.getOrAdd(Blocks.DIRT.getDefaultState());    // Returns 1
     * int stoneId2 = palette.getOrAdd(Blocks.STONE.getDefaultState()); // Returns 0 (same as before)
     * }</pre>
     * </p>
     *
     * @param entry the object to look up or add to the palette
     * @return the ID associated with this entry (existing or newly assigned)
     * @throws IllegalArgumentException if entry is null
     */
    public int getOrAdd(T entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Cannot add null to Palette");
        }

        Integer existingId = entryToId.get(entry);
        if (existingId != null) {
            return existingId;
        }

        int id = idToEntry.size();
        idToEntry.add(entry);
        entryToId.put(entry, id);
        return id;
    }

    /**
     * Retrieves the entry associated with the given ID.
     * <p>
     * This is a fast O(1) lookup operation. If the ID is out of bounds,
     * {@code null} is returned instead of throwing an exception for performance reasons.
     * </p>
     *
     * @param id the palette ID to look up
     * @return the entry associated with this ID, or {@code null} if the ID is invalid
     */
    public T get(int id) {
        return (id >= 0 && id < idToEntry.size()) ? idToEntry.get(id) : null;
    }

    /**
     * Creates a shallow copy of this palette.
     * <p>
     * The new palette contains the same entries and ID mappings as this one,
     * but its internal collections are independent. Subsequent modifications to
     * the original palette will not affect the copy.
     * </p>
     *
     * @return a new Palette instance with the same state
     */
    public Palette<T> copy() {
        Palette<T> copy = new Palette<>();
        copy.idToEntry.addAll(this.idToEntry);
        copy.entryToId.putAll(this.entryToId);
        return copy;
    }
}
