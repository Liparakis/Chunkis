package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.settle.GenerationSettling;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * Covers {@link GenerationSettling}: block changes that replay when a chunk is regenerated from the seed must
 * not claim a chunk Chunkis does not own, while a change a player started must, even when it spreads into the
 * neighbouring chunk through scheduled ticks. Both tests let a water source flow across a chunk border.
 */
@SuppressWarnings("unused")
public final class GenerationSettlingGameTest {

    /**
     * Places water as generation would, next to an unowned chunk, and expects its flow to leave both chunks
     * unclaimed.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 200)
    public void generationFlowDoesNotClaimChunks(final GameTestHelper context) {
        final Border border = Border.create(context, 3300);
        GenerationSettling.run(true, border::placeSource);
        context.startSequence()
                .thenWaitUntil(() -> {
                    border.assertFlowedAcross();
                    context.assertFalse(border.isOwned(border.source),
                            Component.literal("Generation settling claimed source chunk " + border.source + "."));
                    context.assertFalse(border.isOwned(border.neighbour),
                            Component.literal("Generation flow claimed neighbour chunk " + border.neighbour + "."));
                })
                .thenWaitUntil(border::unforce)
                .thenSucceed();
    }

    /**
     * Places water the way a player does, next to an unowned chunk, and expects its flow to claim the
     * neighbour so the water survives the chunk's next reload.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 200)
    public void playerFlowClaimsNeighbourChunk(final GameTestHelper context) {
        final Border border = Border.create(context, 3500);
        border.placeSource();
        context.startSequence()
                .thenWaitUntil(() -> {
                    border.assertFlowedAcross();
                    context.assertTrue(border.isOwned(border.neighbour),
                            Component.literal("Water a player released did not claim neighbour chunk "
                                    + border.neighbour + "."));
                })
                .thenWaitUntil(border::unforce)
                .thenSucceed();
    }

    /**
     * Two fresh, unowned chunks side by side and a spot for water on the source chunk's border.
     */
    private static final class Border {

        /**
         * Test context.
         */
        private final GameTestHelper context;

        /**
         * World of the test.
         */
        private final ServerLevel world;

        /**
         * Chunk the water is placed in.
         */
        private final ChunkPos source;

        /**
         * Chunk east of it, which the water flows into.
         */
        private final ChunkPos neighbour;

        /**
         * Water source on the source chunk's east edge, on the surface.
         */
        private BlockPos water;

        /**
         * Creates the chunks.
         *
         * @param context test context
         * @param offset  chunk offset from the test structure, distinct per test
         */
        private Border(final GameTestHelper context, final int offset) {
            this.context = context;
            this.world = context.getLevel();
            final ChunkPos anchor = ChunkPos.containing(context.absolutePos(BlockPos.ZERO));
            this.source = new ChunkPos(anchor.x() - offset, anchor.z() + offset);
            this.neighbour = new ChunkPos(source.x() + 1, source.z());
        }

        /**
         * Loads both chunks and checks Chunkis owns neither yet.
         *
         * @param context test context
         * @param offset  chunk offset from the test structure, distinct per test
         * @return the chunks
         */
        static Border create(final GameTestHelper context, final int offset) {
            final Border border = new Border(context, offset);
            for (final ChunkPos pos : new ChunkPos[]{border.source, border.neighbour}) {
                border.world.setChunkForced(pos.x(), pos.z(), true);
                border.world.getChunk(pos.x(), pos.z());
                context.assertFalse(border.isOwned(pos),
                        Component.literal("Chunk " + pos + " is already owned before the test."));
            }
            final int surfaceY = border.world.getChunk(border.source.x(), border.source.z())
                    .getHeight(Heightmap.Types.MOTION_BLOCKING, 15, 8) + 1;
            border.water = border.source.getBlockAt(15, surfaceY, 8);
            return border;
        }

        /**
         * Places the water source; vanilla schedules its flow.
         */
        void placeSource() {
            world.setBlock(water, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        }

        /**
         * Asserts the water reached the neighbour chunk.
         */
        void assertFlowedAcross() {
            context.assertTrue(world.getFluidState(water.east()).is(Fluids.FLOWING_WATER),
                    Component.literal("Waiting for water to flow from " + water + " into chunk " + neighbour + "."));
        }

        /**
         * Returns whether Chunkis owns a chunk.
         *
         * @param pos chunk position
         * @return {@code true} once claimed
         */
        boolean isOwned(final ChunkPos pos) {
            return ChunkDeltaOwnership.hasChunkisOwnedState(
                    ((ChunkisDeltaDuck) world.getChunk(pos.x(), pos.z())).chunkis$getDelta());
        }

        /**
         * Releases both forced tickets.
         */
        void unforce() {
            world.setChunkForced(source.x(), source.z(), false);
            world.setChunkForced(neighbour.x(), neighbour.z(), false);
        }
    }
}
