package io.liparakis.chunkis.core;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * Maps a packed local block position to its instruction index, one {@code int[4096]} per 16-block
 * section.
 *
 * <p>A full-baseline delta holds about 100k instructions. Rebuilding this index after a decode or a
 * snapshot is one array store per instruction, and a section costs 16 KiB, where a hash map over the
 * same positions hashes every entry and needs several megabytes of slots.</p>
 *
 * <p>Positions use the {@link BlockInstruction#packPos} layout: Y in bits 12-31, X in bits 8-11 and
 * Z in bits 4-7. A later {@link #put} for the same position replaces the earlier index.</p>
 */
final class PositionIndex {

    /**
     * Number of block slots in one section.
     */
    private static final int SECTION_VOLUME = 4096;

    /**
     * Value {@link #get} returns for a position with no instruction.
     */
    static final int MISSING = -1;

    /**
     * Per-section slots holding {@code index + 1}, so the zero-filled default means "absent".
     */
    private final Int2ObjectOpenHashMap<int[]> sections = new Int2ObjectOpenHashMap<>();

    /**
     * Key of the most recently used section; consecutive positions usually share it.
     */
    private int lastSectionKey;

    /**
     * Slots of the most recently used section, or {@code null} when nothing is cached.
     */
    private int[] lastSection;

    /**
     * Returns the instruction index stored for a position.
     *
     * @param posKey packed position
     * @return instruction index, or {@link #MISSING}
     */
    int get(final long posKey) {
        final int[] section = section(sectionKey(posKey), false);
        return section == null ? MISSING : section[slot(posKey)] - 1;
    }

    /**
     * Records the instruction index for a position.
     *
     * @param posKey packed position
     * @param index  instruction index
     */
    void put(final long posKey, final int index) {
        section(sectionKey(posKey), true)[slot(posKey)] = index + 1;
    }

    /**
     * Removes every position.
     */
    void clear() {
        sections.clear();
        lastSection = null;
    }

    /**
     * Returns the slots for a section, using the one-entry cache first.
     *
     * @param key    section key
     * @param create whether to allocate a missing section
     * @return section slots, or {@code null} when absent and {@code create} is false
     */
    private int[] section(final int key, final boolean create) {
        if (lastSection != null && lastSectionKey == key) {
            return lastSection;
        }
        int[] section = sections.get(key);
        if (section == null) {
            if (!create) {
                return null;
            }
            section = new int[SECTION_VOLUME];
            sections.put(key, section);
        }
        lastSectionKey = key;
        lastSection = section;
        return section;
    }

    /**
     * Extracts the section key (Y divided by 16, within the 20-bit Y field).
     *
     * @param posKey packed position
     * @return section key
     */
    private static int sectionKey(final long posKey) {
        return (int) (posKey >>> 16);
    }

    /**
     * Extracts the slot of a position inside its section.
     *
     * @param posKey packed position
     * @return slot in {@code [0, 4096)}
     */
    private static int slot(final long posKey) {
        return (int) (((posKey >>> 4) & 0xFF) | (((posKey >>> 12) & 0xF) << 8));
    }
}
