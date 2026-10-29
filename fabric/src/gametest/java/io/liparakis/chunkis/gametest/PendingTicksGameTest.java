package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.Fluids;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;

/**
 * Covers {@link io.liparakis.chunkis.world.restoration.nbt.PendingTicksNbt}: a chunk that unloads with a tick
 * still pending stores it and resumes it after reload, instead of freezing.
 */
@SuppressWarnings("unused")
public final class PendingTicksGameTest {

    /**
     * Places water and unloads its chunk before the water's first flow tick, then expects the stored entry to hold
     * the pending tick and the reloaded water to flow.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void pendingFluidTickSurvivesReload(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final ChunkPos anchor = new ChunkPos(context.getAbsolutePos(BlockPos.ORIGIN));
        final ChunkPos target = new ChunkPos(anchor.x - 3900, anchor.z + 3900);
        final AtomicInteger unloads = new AtomicInteger();
        ServerChunkEvents.CHUNK_UNLOAD.register((unloadWorld, chunk) -> {
            if (unloadWorld == world && chunk.getPos().equals(target)) {
                unloads.incrementAndGet();
            }
        });
        world.setChunkForced(target.x, target.z, true);
        final int surfaceY = world.getChunk(target.x, target.z).sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, 8, 8)
                + 1;
        final BlockPos water = target.getBlockPos(8, surfaceY, 8);
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    world.setBlockState(water, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
                    // Stops ticking at once: the flow tick scheduled 5 ticks out stays pending.
                    world.setChunkForced(target.x, target.z, false);
                })
                .createAndAdd(() -> context.assertTrue(unloads.get() > 0,
                        Text.literal("Waiting for chunk " + target + " to unload.")))
                .createAndAdd(() -> {
                    AsyncCisSaveManager.flushAndClose(world);
                    final NbtCompound metadata = FabricCisStorageHelper.getStorage(world)
                            .load(FabricCisStorageHelper.toStoragePos(target))
                            .getChunkMetadata();
                    context.assertTrue(metadata != null && metadata.contains(CisNbtUtil.PENDING_TICKS_KEY),
                            Text.literal("The stored entry of chunk " + target + " lost the water's pending tick."));
                    world.setChunkForced(target.x, target.z, true);
                    world.getChunk(target.x, target.z);
                })
                .createAndAdd(() -> context.assertTrue(world.getFluidState(water.east()).isOf(Fluids.FLOWING_WATER),
                        Text.literal("Water at " + water + " did not resume flowing after its chunk reloaded.")))
                .createAndAdd(() -> world.setChunkForced(target.x, target.z, false))
                .completeIfSuccessful();
    }
}
