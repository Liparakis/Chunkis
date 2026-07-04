package io.liparakis.chunkis.core.model;

import java.util.Arrays;

/**
 * Memory-efficient storage for 16x16x16 block states with adaptive compression.
 * Automatically switches between sparse and dense storage modes based on
 * occupancy.
 * Optimized for branch prediction and minimal garbage collection pressure.
 *
 * @param <S> the type representing a block state
 */
public final class CisSection<S> {

    /**
     * Section is empty (default state).
     */
    public static final byte MODE_EMPTY = 0;
    /**
     * Section stores blocks in sparse arrays (key-value pairs).
     */
    public static final byte MODE_SPARSE = 1;
    /**
     * Section stores blocks in a flat array representing the full 16x16x16 volume.
     */
    public static final byte MODE_DENSE = 2;
    /**
     * Number of block slots in a 16x16x16 section.
     */
    private static final int VOLUME = 4096;
    /**
     * Initial sparse-array capacity sized for very small deltas.
     */
    private static final int INITIAL_SPARSE_CAPACITY = 4;
    /**
     * Mask applied when widening a packed coordinate key back to an array index.
     */
    private static final int COORD_MASK = 0xFFFF;
    /**
     * The current storage mode (EMPTY, SPARSE, or DENSE).
     */
    public byte mode = MODE_EMPTY;

    /**
     * Packed coordinate keys (sparse mode).
     */
    public short[] sparseKeys;

    /**
     * Block states associated with keys (sparse mode).
     */
    public Object[] sparseValues;

    /**
     * Number of blocks currently in sparse storage.
     */
    public int sparseSize;

    /**
     * Flat array of block states (dense mode).
     */
    public Object[] denseBlocks;

    /**
     * Creates a new empty section.
     */
    public CisSection() {
    }

    /**
     * Packs local coordinates into a single 16-bit short value.
     * Format: YYYY ZZZZ XXXX (4 bits per dimension)
     *
     * @param x local X
     * @param y local Y
     * @param z local Z
     * @return packed coordinate
     */
    private static short packCoordinate(int x, int y, int z) {
        return (short) ((y << 8) | (z << 4) | x);
    }

    /**
     * Checks if the section is empty (contains no blocks).
     *
     * @return true if empty
     */
    @SuppressWarnings("unused") // used from the fabric module
    public boolean isEmpty() {
        return mode == MODE_EMPTY;
    }

    /**
     * Checks if a state is null (meaning 'not present' in delta).
     *
     * @param state the state to check
     * @return true if null
     */
    private boolean isAirOrNull(S state) {
        return state == null;
    }

    /**
     * Appends a block coming from a {@code ChunkDelta} scan where coordinates are
     * already unique.
     *
     * <p>This path avoids sparse-mode reverse-lookup bookkeeping and update
     * checks. It is only valid when the caller guarantees each local position is
     * visited at most once.</p>
     */
    public void appendDeltaBlock(int x, int y, int z, S state) {
        if (isAirOrNull(state)) {
            return;
        }

        final short key = packCoordinate(x, y, z);

        switch (mode) {
            case MODE_DENSE -> denseBlocks[key & COORD_MASK] = state;
            case MODE_SPARSE -> appendSparseEntry(key, state);
            case MODE_EMPTY -> initializeSparseWithoutIndex(key, state);
            default -> throw new IllegalStateException("Unknown section mode: " + mode);
        }
    }


    /**
     * Initializes sparse storage for append-only encoder assembly without
     * allocating a reverse-lookup map.
     */
    private void initializeSparseWithoutIndex(short key, S state) {
        mode = MODE_SPARSE;
        sparseKeys = new short[INITIAL_SPARSE_CAPACITY];
        sparseValues = new Object[INITIAL_SPARSE_CAPACITY];
        sparseKeys[0] = key;
        sparseValues[0] = state;
        sparseSize = 1;
    }

    /**
     * Appends a new sparse entry when the caller guarantees key uniqueness.
     */
    private void appendSparseEntry(short key, S state) {
        if (sparseSize >= CisConstants.MAX_SPARSE_CAPACITY) {
            convertToDense();
            denseBlocks[key & COORD_MASK] = state;
            return;
        }

        if (sparseSize >= sparseKeys.length) {
            growSparseArrays();
        }

        sparseKeys[sparseSize] = key;
        sparseValues[sparseSize] = state;
        sparseSize++;
    }

    /**
     * Grows the sparse storage arrays up to the maximum sparse capacity.
     */
    private void growSparseArrays() {
        int newCapacity = Math.min(sparseKeys.length << 1, CisConstants.MAX_SPARSE_CAPACITY);
        sparseKeys = Arrays.copyOf(sparseKeys, newCapacity);
        sparseValues = Arrays.copyOf(sparseValues, newCapacity);
    }

    /**
     * Converts the section from sparse to dense storage mode.
     * Occurs when the number of modified blocks exceeds the sparse threshold.
     */
    private void convertToDense() {
        denseBlocks = new Object[VOLUME];

        for (int i = 0; i < sparseSize; i++) {
            denseBlocks[sparseKeys[i] & COORD_MASK] = sparseValues[i];
        }

        sparseKeys = null;
        sparseValues = null;
        mode = MODE_DENSE;
    }
}
