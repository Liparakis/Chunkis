package io.liparakis.chunkis.gametest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Builds section patterns and compares complete block snapshots for persistence game tests.
 */
final class SectionTestSupport {

    /**
     * Static helpers only.
     */
    private SectionTestSupport() {
    }

    /**
     * Places a whole section using a repeating list of states without neighbour updates.
     *
     * @param world fixture world
     * @param target chunk containing the pattern
     * @param baseY bottom block Y of the section
     * @param states nonempty pattern of block states
     */
    static void placePattern(final ServerLevel world, final ChunkPos target, final int baseY,
            final List<BlockState> states) {
        if (states.isEmpty()) {
            throw new IllegalArgumentException("Section fixture needs at least one block state");
        }
        for (int i = 0; i < 16 * 16 * 16; i++) {
            final BlockPos pos = target.getBlockAt(i & 15, baseY + (i >> 8), (i >> 4) & 15);
            world.setBlock(pos, states.get(i % states.size()), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Copies all sections so later edits cannot mutate the expected snapshot.
     *
     * @param chunk chunk being snapshotted
     * @return block container copies in section order
     */
    static List<PalettedContainer<BlockState>> copySections(final LevelChunk chunk) {
        final List<PalettedContainer<BlockState>> copies = new ArrayList<>();
        for (final LevelChunkSection section : chunk.getSections()) {
            copies.add(section.getStates().copy());
        }
        return copies;
    }

    /**
     * Compares every block in every section after a reload.
     *
     * @param context test assertions
     * @param expected stored snapshot
     * @param chunk reloaded chunk
     */
    static void assertSectionsMatch(final GameTestHelper context,
            final List<PalettedContainer<BlockState>> expected, final LevelChunk chunk) {
        final List<PalettedContainer<BlockState>> actual = copySections(chunk);
        context.assertTrue(expected.size() == actual.size(), Component.literal("Reloaded section count differs."));
        for (int section = 0; section < expected.size(); section++) {
            for (int i = 0; i < 16 * 16 * 16; i++) {
                final BlockState want = expected.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                final BlockState got = actual.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                if (want != got) {
                    throw context.assertionException(Component.literal("Section " + section + " position " + i
                            + " reloaded as " + got + ", stored as " + want + "."));
                }
            }
        }
    }
}
