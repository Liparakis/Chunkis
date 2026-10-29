package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.settle.GenerationSettling;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.Fluids;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;

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
    public void generationFlowDoesNotClaimChunks(final TestContext context) {
        final Border border = Border.create(context, 3300);
        GenerationSettling.run(true, border::placeSource);
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    border.assertFlowedAcross();
                    context.assertFalse(border.isOwned(border.source),
                            Text.literal("Generation settling claimed source chunk " + border.source + "."));
                    context.assertFalse(border.isOwned(border.neighbour),
                            Text.literal("Generation flow claimed neighbour chunk " + border.neighbour + "."));
                })
                .createAndAdd(border::unforce)
                .completeIfSuccessful();
    }

    /**
     * Places water the way a player does, next to an unowned chunk, and expects its flow to claim the
     * neighbour so the water survives the chunk's next reload.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 200)
    public void playerFlowClaimsNeighbourChunk(final TestContext context) {
        final Border border = Border.create(context, 3500);
        border.placeSource();
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    border.assertFlowedAcross();
                    context.assertTrue(border.isOwned(border.neighbour),
                            Text.literal("Water a player released did not claim neighbour chunk "
                                    + border.neighbour + "."));
                })
                .createAndAdd(border::unforce)
                .completeIfSuccessful();
    }

    /**
     * Two fresh, unowned chunks side by side and a spot for water on the source chunk's border.
     */
    private static final class Border {

        /**
         * Test context.
         */
        private final TestContext context;

        /**
         * World of the test.
         */
        private final ServerWorld world;

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
        private Border(final TestContext context, final int offset) {
            this.context = context;
            this.world = context.getWorld();
            final ChunkPos anchor = new ChunkPos(context.getAbsolutePos(BlockPos.ORIGIN));
            this.source = new ChunkPos(anchor.x - offset, anchor.z + offset);
            this.neighbour = new ChunkPos(source.x + 1, source.z);
        }

        /**
         * Loads both chunks and checks Chunkis owns neither yet.
         *
         * @param context test context
         * @param offset  chunk offset from the test structure, distinct per test
         * @return the chunks
         */
        static Border create(final TestContext context, final int offset) {
            final Border border = new Border(context, offset);
            for (final ChunkPos pos : new ChunkPos[]{border.source, border.neighbour}) {
                border.world.setChunkForced(pos.x, pos.z, true);
                border.world.getChunk(pos.x, pos.z);
                context.assertFalse(border.isOwned(pos),
                        Text.literal("Chunk " + pos + " is already owned before the test."));
            }
            final int surfaceY = border.world.getChunk(border.source.x, border.source.z)
                    .sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, 15, 8) + 1;
            border.water = border.source.getBlockPos(15, surfaceY, 8);
            return border;
        }

        /**
         * Places the water source; vanilla schedules its flow.
         */
        void placeSource() {
            world.setBlockState(water, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        }

        /**
         * Asserts the water reached the neighbour chunk.
         */
        void assertFlowedAcross() {
            context.assertTrue(world.getFluidState(water.east()).isOf(Fluids.FLOWING_WATER),
                    Text.literal("Waiting for water to flow from " + water + " into chunk " + neighbour + "."));
        }

        /**
         * Returns whether Chunkis owns a chunk.
         *
         * @param pos chunk position
         * @return {@code true} once claimed
         */
        boolean isOwned(final ChunkPos pos) {
            return ChunkDeltaOwnership.hasChunkisOwnedState(
                    ((ChunkisDeltaDuck) world.getChunk(pos.x, pos.z)).chunkis$getDelta());
        }

        /**
         * Releases both forced tickets.
         */
        void unforce() {
            world.setChunkForced(source.x, source.z, false);
            world.setChunkForced(neighbour.x, neighbour.z, false);
        }
    }
}
