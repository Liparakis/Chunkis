package io.liparakis.chunkis.core;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

/**
 * Encapsulates the primitive array and fastutil map used to store
 * and query packed block instructions within a ChunkDelta.
 *
 * <p>Two insertion modes are supported:</p>
 * <ul>
 *   <li>{@link #add(long, long)} eagerly updates the position map on every
 *       insert — required by mutation paths that may upsert or look up
 *       positions immediately.</li>
 *   <li>{@link #addAppendOnly(long)} skips the position-map insert entirely
 *       and marks the map dirty. The map is rebuilt lazily on the first call
 *       to {@link #ensurePositionMap()}. Decode paths where positions are
 *       guaranteed unique should prefer this method.</li>
 * </ul>
 */
final class BlockInstructionStorage {

    private static final int INITIAL_CAPACITY = 64;

    /**
     * Mask for the low 32 bits containing the packed local block position.
     */
    private static final long POSITION_MASK = 0xFFFF_FFFFL;

    final Long2IntOpenHashMap positionMap;
    long[] packedInstructions;
    int instructionCount;

    /**
     * When {@code true} the position map is out of date and must be
     * rebuilt via {@link #ensurePositionMap()} before any lookup.
     */
    private boolean positionMapDirty;

    BlockInstructionStorage() {
        this.packedInstructions = new long[INITIAL_CAPACITY];
        this.instructionCount = 0;
        this.positionMap = new Long2IntOpenHashMap(INITIAL_CAPACITY);
        this.positionMap.defaultReturnValue(-1);
    }

    void copyInto(final BlockInstructionStorage target) {
        target.packedInstructions = Arrays.copyOf(this.packedInstructions, this.packedInstructions.length);
        target.instructionCount = this.instructionCount;
        // Propagate dirty state: if source is dirty, target inherits dirty
        // status instead of eagerly rebuilding the map.
        if (this.positionMapDirty) {
            target.positionMapDirty = true;
        } else {
            target.positionMap.clear();
            target.positionMap.putAll(this.positionMap);
            target.positionMapDirty = false;
        }
    }

    void clear() {
        this.packedInstructions = new long[INITIAL_CAPACITY];
        this.instructionCount = 0;
        this.positionMap.clear();
        this.positionMapDirty = false;
    }

    void ensureCapacity() {
        if (instructionCount < packedInstructions.length) {
            return;
        }
        packedInstructions = Arrays.copyOf(packedInstructions, packedInstructions.length << 1);
    }

    void ensureBlockCapacity(final int additionalBlocks) {
        if (additionalBlocks <= 0) {
            return;
        }
        final int requiredCapacity = instructionCount + additionalBlocks;
        if (requiredCapacity > packedInstructions.length) {
            int newCapacity = packedInstructions.length;
            while (newCapacity < requiredCapacity) {
                newCapacity <<= 1;
            }
            packedInstructions = Arrays.copyOf(packedInstructions, newCapacity);
        }
        if (!positionMapDirty) {
            positionMap.ensureCapacity(requiredCapacity);
        }
    }

    /**
     * Inserts a packed instruction and eagerly updates the position map.
     *
     * <p>Use this on mutation paths where subsequent operations may need
     * immediate position-map lookups (e.g. upsert, addBlockChange).</p>
     *
     * @param instruction packed block instruction
     * @param posKey      packed position key
     */
    void add(final long instruction, final long posKey) {
        ensureCapacity();
        packedInstructions[instructionCount] = instruction;
        positionMap.put(posKey, instructionCount);
        instructionCount++;
    }

    /**
     * Appends a packed instruction without updating the position map.
     *
     * <p>This is the fast path for decode operations where positions are
     * guaranteed unique and no immediate lookup is needed. The position
     * map is rebuilt lazily on the first call to {@link #ensurePositionMap()}.</p>
     *
     * @param instruction packed block instruction (high 32 bits = palette id,
     *                    low 32 bits = packed position)
     */
    void addAppendOnly(final long instruction) {
        ensureCapacity();
        packedInstructions[instructionCount] = instruction;
        instructionCount++;
        positionMapDirty = true;
    }

    /**
     * Ensures the position map is up to date by rebuilding it from the
     * packed instruction array if it has been marked dirty.
     *
     * <p>Must be called before any operation that reads from
     * {@link #positionMap} (e.g. upsert lookups, contains checks).</p>
     */
    void ensurePositionMap() {
        if (!positionMapDirty) {
            return;
        }
        positionMap.clear();
        positionMap.ensureCapacity(instructionCount);
        for (int i = 0; i < instructionCount; i++) {
            positionMap.put(packedInstructions[i] & POSITION_MASK, i);
        }
        positionMapDirty = false;
    }

    void update(final int index, final long newInstruction) {
        packedInstructions[index] = newInstruction;
    }
}
