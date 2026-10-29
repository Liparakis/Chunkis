package io.liparakis.chunkis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests the per-section position index used for block-instruction upserts.
 */
class PositionIndexTest {

    /**
     * Every position maps back to its own index, across sections and negative Y.
     */
    @Test
    void returnsStoredIndexForEveryPosition() {
        final PositionIndex index = new PositionIndex();
        int next = 0;
        for (int y = -64; y < 320; y += 7) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    index.put(BlockInstruction.packPos(x, y, z), next++);
                }
            }
        }
        next = 0;
        for (int y = -64; y < 320; y += 7) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(next++, index.get(BlockInstruction.packPos(x, y, z)));
                }
            }
        }
    }

    /**
     * Unknown positions are missing, a repeated put keeps the latest index, and clear empties it.
     */
    @Test
    void handlesMissingOverwriteAndClear() {
        final PositionIndex index = new PositionIndex();
        final long pos = BlockInstruction.packPos(3, 70, 9);
        assertEquals(PositionIndex.MISSING, index.get(pos));

        index.put(pos, 0);
        index.put(pos, 5);
        assertEquals(5, index.get(pos));
        assertEquals(PositionIndex.MISSING, index.get(BlockInstruction.packPos(3, 71, 9)));

        index.clear();
        assertEquals(PositionIndex.MISSING, index.get(pos));
    }
}
