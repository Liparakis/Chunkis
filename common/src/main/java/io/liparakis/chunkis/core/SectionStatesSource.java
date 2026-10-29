package io.liparakis.chunkis.core;

/**
 * A {@link ChunkDeltaView} that can hand its blocks to the encoder a whole section at a time, instead of one
 * {@link ChunkDelta.BlockVisitor} call per block. The encoder writes exactly the same bytes either way.
 *
 * @param <S> block state type
 */
public interface SectionStatesSource<S> {

    /**
     * Visits every section holding at least one block, in ascending section order.
     *
     * @param visitor receives each section's 4096 states (index {@code y << 8 | z << 4 | x}, {@code null} where
     *                the view holds no block); the array is not modified and must not be kept past the encode
     */
    void forEachSectionStates(SectionStatesVisitor visitor);

    /**
     * Receives one section's states.
     */
    @FunctionalInterface
    interface SectionStatesVisitor {

        /**
         * Visits a section.
         *
         * @param sectionY section coordinate ({@code blockY >> 4})
         * @param states   4096 states, {@code null} where the view holds no block
         */
        void visit(int sectionY, Object[] states);
    }
}
