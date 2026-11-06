package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

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
    public void pendingFluidTickSurvivesReload(final GameTestHelper context) {
        final ServerLevel world = context.getLevel();
        final ChunkPos anchor = ChunkPos.containing(context.absolutePos(BlockPos.ZERO));
        final ChunkPos target = new ChunkPos(anchor.x() - 3900, anchor.z() + 3900);
        final AtomicInteger unloads = new AtomicInteger();
        ServerChunkEvents.CHUNK_UNLOAD.register((unloadWorld, chunk) -> {
            if (unloadWorld == world && chunk.getPos().equals(target)) {
                unloads.incrementAndGet();
            }
        });
        world.setChunkForced(target.x(), target.z(), true);
        final int surfaceY = world.getChunk(target.x(), target.z()).getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8)
                + 1;
        final BlockPos water = target.getBlockAt(8, surfaceY, 8);
        context.startSequence()
                .thenWaitUntil(() -> {
                    world.setBlock(water, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                    // Stops ticking at once: the flow tick scheduled 5 ticks out stays pending.
                    world.setChunkForced(target.x(), target.z(), false);
                })
                .thenWaitUntil(() -> context.assertTrue(unloads.get() > 0,
                        Component.literal("Waiting for chunk " + target + " to unload.")))
                .thenWaitUntil(() -> {
                    AsyncCisSaveManager.flushAndClose(world);
                    final CompoundTag metadata = FabricCisStorageHelper.getStorage(world)
                            .load(FabricCisStorageHelper.toStoragePos(target))
                            .getChunkMetadata();
                    context.assertTrue(metadata != null && metadata.contains(CisNbtUtil.PENDING_TICKS_KEY),
                            Component.literal("The stored entry of chunk " + target + " lost the water's pending tick."));
                    world.setChunkForced(target.x(), target.z(), true);
                    world.getChunk(target.x(), target.z());
                })
                .thenWaitUntil(() -> context.assertTrue(world.getFluidState(water.east()).is(Fluids.FLOWING_WATER),
                        Component.literal("Water at " + water + " did not resume flowing after its chunk reloaded.")))
                .thenWaitUntil(() -> world.setChunkForced(target.x(), target.z(), false))
                .thenSucceed();
    }
}
