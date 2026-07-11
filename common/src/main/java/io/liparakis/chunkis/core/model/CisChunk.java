package io.liparakis.chunkis.core.model;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.Arrays;

/**
 * A sparse, memory-efficient representation of a chunk used for delta
 * operations.
 * Uses primitive collections and spatial locality caching for high-performance
 * operations.
 *
 * @param <S> the type representing a block state
 */
public final class CisChunk<S> {

    /**
     * Bit-shift value for world Y to section Y conversion.
     */
    private static final int SECTION_SHIFT = 4;

    /**
     * Map of section Y-indices to their storage objects.
     */
    private final Int2ObjectMap<CisSection<S>> sections;

    /**
     * The Y-index of the last accessed section (for caching).
     */
    private int lastSectionY;

    /**
     * The last accessed section instance (for caching).
     */
    private CisSection<S> lastSection;

    /**
     * Sorted section Y indices present in {@link #sections}.
     */
    private int[] sectionOrder;

    /**
     * Number of valid entries in {@link #sectionOrder}.
     */
    private int sectionOrderSize;

    /**
     * Creates a new empty CisChunk.
     */
    public CisChunk() {
        this.sections = new Int2ObjectOpenHashMap<>();
        this.sectionOrder = new int[4];
    }

    /**
     * Adds a block coming from {@code ChunkDelta} iteration, where each packed
     * position is already unique.
     *
     * <p>This avoids sparse-mode reverse-lookup maintenance during encoder input
     * assembly because duplicate coordinates are impossible on this path.</p>
     */
    public void addUniqueBlock(int x, int y, int z, S state) {
        getOrCreateSection(y >> SECTION_SHIFT)
                .appendDeltaBlock(
                        x & CisConstants.COORD_MASK, y & CisConstants.COORD_MASK, z & CisConstants.COORD_MASK, state);
    }

    /** Performs get or create section. */
    private CisSection<S> getOrCreateSection(final int sectionY) {
        CisSection<S> section;
        if (sectionY == lastSectionY && lastSection != null) {
            section = lastSection;
        } else {
            section = sections.get(sectionY);
            if (section == null) {
                section = new CisSection<>();
                this.sections.put(sectionY, section);
                insertSectionOrder(sectionY);
            }
            lastSectionY = sectionY;
            lastSection = section;
        }
        return section;
    }

    /**
     * Inserts a newly created section Y into {@link #sectionOrder} while keeping
     * the active prefix sorted in ascending order.
     */
    private void insertSectionOrder(final int sectionY) {
        if (sectionOrderSize >= sectionOrder.length) {
            sectionOrder = Arrays.copyOf(sectionOrder, sectionOrder.length << 1);
        }

        int insertAt = sectionOrderSize;
        while (insertAt > 0 && sectionOrder[insertAt - 1] > sectionY) {
            sectionOrder[insertAt] = sectionOrder[insertAt - 1];
            insertAt--;
        }

        sectionOrder[insertAt] = sectionY;
        sectionOrderSize++;
    }

    /**
     * Returns the raw map of section Y indices to CisSection objects.
     * Note: The returned map is not sorted by key.
     *
     * @return the internal sections map
     */
    public Int2ObjectMap<CisSection<S>> getSections() {
        return sections;
    }

    /**
     * Returns the number of sections currently stored in this chunk.
     *
     * @return section count
     */
    public int getSectionCount() {
        return sectionOrderSize;
    }

    /**
     * Returns the sorted section Y at {@code index}.
     *
     * @param index sorted section index
     * @return section Y coordinate
     */
    public int getSortedSectionIndex(final int index) {
        return sectionOrder[index];
    }
}
