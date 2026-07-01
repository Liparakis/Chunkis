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
 *
 * <p>The position map itself is lazily initialised: it is {@code null} until
 * the first call to {@link #add(long, long)} or {@link #ensurePositionMap()}.
 * Decode paths that exclusively use {@link #addAppendOnly(long)} and never
 * perform position lookups therefore never pay the map allocation cost.</p>
 */
final class BlockInstructionStorage {

    private static final int INITIAL_CAPACITY = 64;

    /**
     * Mask for the low 32 bits containing the packed local block position.
     */
    private static final long POSITION_MASK = 0xFFFF_FFFFL;

    /**
     * Position map, lazily created on the first call to {@link #add(long, long)}
     * or {@link #ensurePositionMap()}. {@code null} on decode-only instances that
     * have only ever received {@link #addAppendOnly(long)} calls.
     */
    Long2IntOpenHashMap positionMap;
    long[] packedInstructions;
    int instructionCount;

    /**
     * When {@code true} the position map is out of date and must be
     * rebuilt via {@link #ensurePositionMap()} before any lookup.
     * Irrelevant when {@link #positionMap} is {@code null}.
     */
    private boolean positionMapDirty;

    BlockInstructionStorage() {
        this.packedInstructions = new long[INITIAL_CAPACITY];
        this.instructionCount = 0;
        // positionMap intentionally left null — created lazily on first lookup/add.
    }

    void copyInto(final BlockInstructionStorage target) {
        target.packedInstructions = Arrays.copyOf(this.packedInstructions, this.packedInstructions.length);
        target.instructionCount = this.instructionCount;
        // Propagate dirty state: if source is dirty or has no map, target inherits
        // dirty status instead of eagerly rebuilding the map.
        if (this.positionMapDirty || this.positionMap == null) {
            target.positionMapDirty = true;
            if (target.positionMap != null) {
                target.positionMap.clear();
            }
        } else {
            if (target.positionMap == null) {
                target.positionMap = new Long2IntOpenHashMap(this.positionMap.size());
                target.positionMap.defaultReturnValue(-1);
            } else {
                target.positionMap.clear();
            }
            target.positionMap.putAll(this.positionMap);
            target.positionMapDirty = false;
        }
    }

    void clear() {
        this.packedInstructions = new long[INITIAL_CAPACITY];
        this.instructionCount = 0;
        if (this.positionMap != null) {
            this.positionMap.clear();
        }
        this.positionMapDirty = false;
    }

    void ensureCapacity() {
        if (instructionCount < packedInstructions.length) {
            return;
        }
        packedInstructions = Arrays.copyOf(packedInstructions, packedInstructions.length << 1);
    }

    /**
     * Pre-sizes the instruction array for bulk decode paths.
     *
     * <p>The position map is intentionally <em>not</em> pre-sized here.
     * Decode paths use {@link #addAppendOnly(long)} and never consult the map,
     * so growing it upfront would waste ~0.5ms per chunk on a table that is
     * never needed. The map is sized correctly inside {@link #ensurePositionMap()}
     * the first time a lookup is actually required.</p>
     *
     * @param additionalBlocks number of additional block instructions expected
     */
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
        // Only pre-size the map if it already exists and is being actively maintained
        // (not dirty). When dirty or null, ensurePositionMap() will size it correctly
        // at rebuild time.
        if (positionMap != null && !positionMapDirty) {
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
        if (positionMap == null) {
            positionMap = new Long2IntOpenHashMap(INITIAL_CAPACITY);
            positionMap.defaultReturnValue(-1);
            // Map was null: any prior addAppendOnly entries are not in the map yet.
            // Rebuild from scratch so this new keyed insert is consistent.
            if (positionMapDirty) {
                positionMap.ensureCapacity(instructionCount + 1);
                for (int i = 0; i < instructionCount; i++) {
                    positionMap.put(packedInstructions[i] & POSITION_MASK, i);
                }
                positionMapDirty = false;
            }
        }
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
     * packed instruction array if it has been marked dirty or was never created.
     *
     * <p>Must be called before any operation that reads from
     * {@link #positionMap} (e.g. upsert lookups, contains checks).</p>
     */
    void ensurePositionMap() {
        if (positionMap != null && !positionMapDirty) {
            return;
        }
        if (positionMap == null) {
            // First-time creation: size the map precisely to avoid any rehash.
            positionMap = new Long2IntOpenHashMap(instructionCount);
            positionMap.defaultReturnValue(-1);
        } else {
            positionMap.clear();
            positionMap.ensureCapacity(instructionCount);
        }
        for (int i = 0; i < instructionCount; i++) {
            positionMap.put(packedInstructions[i] & POSITION_MASK, i);
        }
        positionMapDirty = false;
    }

    /**
     * Returns whether {@code index} refers to an existing instruction slot.
     *
     * <p>Used as a precondition check before any direct index-addressed
     * read or write to avoid silent out-of-bounds corruption.</p>
     *
     * @param index the instruction slot to test
     * @return {@code true} if {@code index} is in {@code [0, instructionCount)}
     */
    boolean hasIndex(final int index) {
        return index >= 0 && index < instructionCount;
    }

    /**
     * Overwrites the palette ID of the instruction at {@code index} while
     * preserving its packed position bits unchanged.
     *
     * <p>Each packed instruction is a {@code long} with the following layout:</p>
     * <pre>
     *   bits 63–32  palette ID   (block state reference)
     *   bits 31–0   position mask (packed x/y/z section coordinates)
     * </pre>
     *
     * <p>This method replaces only the upper 32 bits, leaving the lower
     * 32-bit position mask intact. It is the core primitive used by the
     * decoder optimisation path to patch exception blocks in-place after a
     * {@code fillSection} without consulting or rebuilding the position map.</p>
     *
     * <p><strong>Invariant:</strong> {@code index} must satisfy
     * {@link #hasIndex(int)}. Violations are caught by the {@code assert}
     * guard and will throw {@link AssertionError} when assertions are enabled.</p>
     *
     * @param index     the instruction slot to update; must be in
     *                  {@code [0, instructionCount)}
     * @param paletteId the new palette ID to store in the upper 32 bits
     */
    void updatePalette(final int index, final int paletteId) {
        assert hasIndex(index) : "Index " + index + " out of bounds for instructionCount " + instructionCount;
        packedInstructions[index] = ((long) paletteId << 32) | (packedInstructions[index] & POSITION_MASK);
    }
}
