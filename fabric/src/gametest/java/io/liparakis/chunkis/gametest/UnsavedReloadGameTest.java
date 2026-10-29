package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;

/**
 * Verifies that an edit survives a reload that happens before the chunk's unload save reaches disk: the
 * reloaded chunk carries the unsaved edit and a normal save writes it.
 */
@SuppressWarnings("unused")
public final class UnsavedReloadGameTest {

    /**
     * Edits a chunk, unloads it, reloads it at once, saves normally, and expects the edit in storage.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 300)
    public void editSurvivesReloadBeforeUnloadSaveLands(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final ChunkPos anchor = new ChunkPos(context.getAbsolutePos(BlockPos.ORIGIN));
        final ChunkPos target = new ChunkPos(anchor.x + 1400, anchor.z + 1400);
        world.setChunkForced(target.x, target.z, true);
        final int y = world.getChunk(target.x, target.z).sampleHeightmap(Heightmap.Type.WORLD_SURFACE, 5, 5) + 2;
        final BlockPos marker = target.getBlockPos(5, y, 5);
        final AtomicBoolean unloaded = new AtomicBoolean();
        ServerChunkEvents.CHUNK_UNLOAD.register((unloadWorld, unloadChunk) -> {
            if (unloadWorld == world && unloadChunk.getPos().equals(target)) {
                unloaded.set(true);
            }
        });

        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    world.setBlockState(marker, Blocks.EMERALD_BLOCK.getDefaultState());
                    world.setChunkForced(target.x, target.z, false);
                })
                .createAndAdd(() -> context.assertTrue(unloaded.get(),
                        Text.literal("Waiting for chunk " + target + " to unload.")))
                .createAndAdd(() -> {
                    world.setChunkForced(target.x, target.z, true);
                    world.getChunk(target.x, target.z);
                })
                .createAndAdd(() -> {
                    context.assertTrue(world.getBlockState(marker).isOf(Blocks.EMERALD_BLOCK),
                            Text.literal("Reloaded chunk lost the edit at " + marker + "."));
                    world.getChunkManager().save(false);
                    AsyncCisSaveManager.flushAndClose(world);
                    context.assertTrue(storedBlockIs(world, target, marker, Blocks.EMERALD_BLOCK.getDefaultState()),
                            Text.literal("Storage does not hold the edit at " + marker + " after a normal save."));
                })
                .createAndAdd(() -> world.setChunkForced(target.x, target.z, false))
                .completeIfSuccessful();
    }

    /**
     * Reports whether CIS storage holds {@code expected} at {@code pos}.
     *
     * @param world    test world
     * @param target   chunk position
     * @param pos      block position inside the chunk
     * @param expected expected state
     * @return true when the stored delta has that state at that position
     */
    private static boolean storedBlockIs(final ServerWorld world, final ChunkPos target, final BlockPos pos,
            final BlockState expected) {
        final ChunkDelta<BlockState, NbtCompound> stored = FabricCisStorageHelper.getStorage(world)
                .load(FabricCisStorageHelper.toStoragePos(target));
        final long wanted = BlockInstruction.packPos(pos.getX() & 15, pos.getY(), pos.getZ() & 15);
        final boolean[] found = {false};
        stored.forEachBlock((x, y, z, state) -> {
            if (BlockInstruction.packPos(x, y, z) == wanted) {
                found[0] = state == expected;
            }
        });
        return found[0];
    }
}
