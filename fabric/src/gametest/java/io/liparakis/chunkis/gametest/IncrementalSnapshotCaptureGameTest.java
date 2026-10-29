package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.capture.CisSnapshotCapture;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Verifies that a repeated full-baseline snapshot, which re-reads only changed sections, records the
 * same blocks as a fresh full snapshot, including writes that bypass {@code setBlockState}.
 */
@SuppressWarnings("unused")
public final class IncrementalSnapshotCaptureGameTest {

    /**
     * Captures, edits three sections in three different ways, captures again, and compares the result
     * with a fresh capture of the same chunk.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 100)
    public void repeatedSnapshotMatchesFreshSnapshot(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final ChunkPos anchor = new ChunkPos(context.getAbsolutePos(BlockPos.ORIGIN));
        final ChunkPos target = new ChunkPos(anchor.x + 900, anchor.z);
        world.setChunkForced(target.x, target.z, true);
        final WorldChunk chunk = world.getChunk(target.x, target.z);
        try {
            for (int y = 0; y < 64; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final BlockState state = ((x + y + z) & 1) == 0
                                ? Blocks.STONE.getDefaultState()
                                : Blocks.DIRT.getDefaultState();
                        world.setBlockState(target.getBlockPos(x, y, z), state, Block.NOTIFY_LISTENERS);
                    }
                }
            }

            final ChunkDelta<BlockState, NbtCompound> repeated = new ChunkDelta<>(BlockState::isAir);
            repeated.markDirty("IncrementalSnapshotCaptureGameTest");
            final long fullStart = System.nanoTime();
            CisSnapshotCapture.capture(chunk, repeated, null);
            final long fullNanos = System.nanoTime() - fullStart;

            world.setBlockState(target.getBlockPos(3, 5, 7), Blocks.GLASS.getDefaultState(), Block.NOTIFY_LISTENERS);
            world.setBlockState(target.getBlockPos(4, 20, 4), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            // A direct section write never reaches WorldChunk.setBlockState; only the data fingerprint sees it.
            chunk.getSection(chunk.getSectionIndex(40))
                    .setBlockState(9, 40 & 15, 2, Blocks.GOLD_BLOCK.getDefaultState());

            repeated.markDirty("IncrementalSnapshotCaptureGameTest");
            final long incrementalStart = System.nanoTime();
            CisSnapshotCapture.capture(chunk, repeated, null);
            final long incrementalNanos = System.nanoTime() - incrementalStart;

            final ChunkDelta<BlockState, NbtCompound> fresh = new ChunkDelta<>(BlockState::isAir);
            fresh.markDirty("IncrementalSnapshotCaptureGameTest");
            CisSnapshotCapture.capture(chunk, fresh, null);

            Chunkis.LOGGER.info("Chunkis gametest: full snapshot {} us, repeated snapshot {} us",
                    fullNanos / 1000, incrementalNanos / 1000);
            final Long2ObjectMap<BlockState> repeatedBlocks = nonAirBlocks(repeated);
            final Long2ObjectMap<BlockState> freshBlocks = nonAirBlocks(fresh);
            context.assertTrue(repeatedBlocks.equals(freshBlocks),
                    Text.literal("Repeated snapshot differs from a fresh one: " + repeatedBlocks.size() + " vs "
                            + freshBlocks.size() + " non-air blocks"));
            context.assertTrue(freshBlocks.get(BlockInstruction.packPos(9, 40, 2)) == Blocks.GOLD_BLOCK.getDefaultState(),
                    Text.literal("Fresh snapshot is missing the direct section write."));
            // A full read appends sections bottom to top. Reused sections stay in place and re-read ones
            // are appended after them, so Y only goes back down when some sections were skipped.
            context.assertTrue(isOrderedByY(fresh),
                    Text.literal("Fresh snapshot instructions are expected in ascending Y order."));
            context.assertFalse(isOrderedByY(repeated),
                    Text.literal("Repeated snapshot re-read every section instead of reusing unchanged ones."));
            logWarmTimings(world, chunk, target, repeated);
            context.complete();
        } finally {
            world.setChunkForced(target.x, target.z, false);
        }
    }

    /**
     * Reports whether the delta's instructions never go down in Y.
     *
     * @param delta captured delta
     * @return true when instruction Y values are non-decreasing
     */
    private static boolean isOrderedByY(final ChunkDelta<BlockState, NbtCompound> delta) {
        final int[] previousY = {Integer.MIN_VALUE};
        final boolean[] ordered = {true};
        delta.forEachBlock((x, y, z, state) -> {
            if (y < previousY[0]) {
                ordered[0] = false;
            }
            previousY[0] = y;
        });
        return ordered[0];
    }

    /**
     * Logs warm averages of a fresh full snapshot and of a repeated snapshot after a one-block edit.
     *
     * @param world    test world
     * @param chunk    filled chunk
     * @param target   chunk position
     * @param repeated delta already captured once
     */
    private static void logWarmTimings(final ServerWorld world, final WorldChunk chunk, final ChunkPos target,
            final ChunkDelta<BlockState, NbtCompound> repeated) {
        final int rounds = 30;
        long freshNanos = 0;
        long repeatedNanos = 0;
        for (int i = 0; i < rounds; i++) {
            final ChunkDelta<BlockState, NbtCompound> fresh = new ChunkDelta<>(BlockState::isAir);
            fresh.markDirty("IncrementalSnapshotCaptureGameTest");
            long start = System.nanoTime();
            CisSnapshotCapture.capture(chunk, fresh, null);
            freshNanos += System.nanoTime() - start;

            world.setBlockState(target.getBlockPos(i % 16, 10, 0), (i & 1) == 0
                    ? Blocks.GLASS.getDefaultState()
                    : Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
            repeated.markDirty("IncrementalSnapshotCaptureGameTest");
            start = System.nanoTime();
            CisSnapshotCapture.capture(chunk, repeated, null);
            repeatedNanos += System.nanoTime() - start;
        }
        Chunkis.LOGGER.info("Chunkis gametest: warm average over {} rounds, full {} us, after one-block edit {} us",
                rounds, freshNanos / rounds / 1000, repeatedNanos / rounds / 1000);
    }

    /**
     * Collects the non-air blocks a delta records, keyed by packed position; later instructions win.
     *
     * @param delta captured delta
     * @return non-air blocks
     */
    private static Long2ObjectMap<BlockState> nonAirBlocks(final ChunkDelta<BlockState, NbtCompound> delta) {
        final Long2ObjectMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        delta.forEachBlock((x, y, z, state) -> {
            final long pos = BlockInstruction.packPos(x, y, z);
            if (state == null || state.isAir()) {
                blocks.remove(pos);
            } else {
                blocks.put(pos, state);
            }
        });
        return blocks;
    }
}
