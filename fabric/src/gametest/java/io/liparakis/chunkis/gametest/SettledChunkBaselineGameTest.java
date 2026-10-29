package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Covers chunks nobody changed in an authoritative world: a finished one is stored as a Chunkis entry and an
 * unfinished one is kept as vanilla serializes it, so neither is generated again from scratch on its next load. Runs in the End, which no other game test uses, so marking it
 * authoritative changes nothing for them.
 */
@SuppressWarnings("unused")
public final class SettledChunkBaselineGameTest {

    /**
     * Unloads an untouched End chunk and expects its save to store it and its reload to restore every block from
     * storage.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void untouchedSettledChunkReloadsFromStorage(final TestContext context) {
        final ServerWorld end = context.getWorld().getServer().getWorld(World.END);
        MigrationStateServiceHolder.markAuthoritative(end.getRegistryKey());
        // Far out in the End (outer islands or void, nothing that ticks), distinct for every run like the other
        // tests' chunks, so a kept world never has it stored already.
        final ChunkPos anchor = new ChunkPos(context.getAbsolutePos(BlockPos.ORIGIN));
        final ChunkPos target = new ChunkPos(anchor.x + 3700, anchor.z - 3700);
        final AtomicInteger unloads = new AtomicInteger();
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (world == end && chunk.getPos().equals(target)) {
                unloads.incrementAndGet();
            }
        });
        final BlockState[] before = new BlockState[16 * 16 * 128];
        context.assertFalse(FabricCisStorageHelper.getStorage(end).contains(FabricCisStorageHelper.toStoragePos(target)),
                Text.literal("End chunk " + target + " is already stored before the test."));
        end.setChunkForced(target.x, target.z, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    final WorldChunk chunk = end.getChunk(target.x, target.z);
                    context.assertFalse(ChunkDeltaOwnership.hasChunkisOwnedState(
                                    ((ChunkisDeltaDuck) chunk).chunkis$getDelta()),
                            Text.literal("Untouched End chunk " + target + " is already owned."));
                    for (int i = 0; i < before.length; i++) {
                        before[i] = chunk.getBlockState(at(target, i));
                    }
                    end.setChunkForced(target.x, target.z, false);
                })
                .createAndAdd(() -> context.assertTrue(unloads.get() > 0,
                        Text.literal("Waiting for End chunk " + target + " to unload.")))
                .createAndAdd(() -> {
                    AsyncCisSaveManager.flushAndClose(end);
                    context.assertTrue(FabricCisStorageHelper.getStorage(end)
                                    .contains(FabricCisStorageHelper.toStoragePos(target)),
                            Text.literal("The save of settled End chunk " + target + " stored nothing."));
                    end.setChunkForced(target.x, target.z, true);
                })
                .createAndAdd(() -> {
                    final WorldChunk chunk = end.getChunk(target.x, target.z);
                    context.assertTrue(ChunkDeltaOwnership.hasChunkisOwnedState(
                                    ((ChunkisDeltaDuck) chunk).chunkis$getDelta()),
                            Text.literal("End chunk " + target + " did not reload from Chunkis storage."));
                    for (int i = 0; i < before.length; i++) {
                        final BlockState now = chunk.getBlockState(at(target, i));
                        context.assertTrue(now == before[i], Text.literal("Block at " + at(target, i)
                                + " came back as " + now + " instead of " + before[i] + "."));
                    }
                    end.setChunkForced(target.x, target.z, false);
                })
                .completeIfSuccessful();
    }

    /**
     * Generates an End chunk only part of the way and lets it unload, then expects its save to keep it in the
     * unfinished-chunk store (vanilla's serialized chunk, under Chunkis's directory) and the chunk to load back and finish.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 6000)
    public void partlyGeneratedChunkIsKeptUnfinished(final TestContext context) {
        final ServerWorld end = context.getWorld().getServer().getWorld(World.END);
        MigrationStateServiceHolder.markAuthoritative(end.getRegistryKey());
        final ChunkPos anchor = new ChunkPos(context.getAbsolutePos(BlockPos.ORIGIN));
        final ChunkPos target = new ChunkPos(anchor.x + 4100, anchor.z - 4100);
        final Path region = ChunkisStoragePaths.computeUnfinishedChunksDirectory(
                        end.getServer().getSavePath(WorldSavePath.ROOT), end.getRegistryKey())
                .resolve("r." + target.getRegionX() + "." + target.getRegionZ() + ".cis");
        final AtomicInteger unloads = new AtomicInteger();
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (world == end && chunk.getPos().equals(target)) {
                unloads.incrementAndGet();
            }
        });
        context.assertFalse(Files.exists(region), Text.literal("Unfinished region " + region + " exists already."));
        // No ticket: the chunk drops back out once generated this far, unfinished.
        end.getChunk(target.x, target.z, ChunkStatus.FEATURES, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(waitForSave(end, region),
                        Text.literal("Waiting for unfinished chunk " + target + " to be kept in " + region + ".")))
                .createAndAdd(() -> {
                    end.setChunkForced(target.x, target.z, true);
                    context.assertTrue(end.getChunk(target.x, target.z).getStatus() == ChunkStatus.FULL,
                            Text.literal("Unfinished chunk " + target + " did not load back and finish."));
                    end.setChunkForced(target.x, target.z, false);
                })
                .completeIfSuccessful();
    }

    /**
     * Saves pending chunks and reports whether a region file holds data.
     *
     * @param world  world to save
     * @param region region file
     * @return {@code true} once the file exists and is not empty
     */
    private static boolean waitForSave(final ServerWorld world, final Path region) {
        world.getChunkManager().save(false);
        try {
            return Files.exists(region) && Files.size(region) > 0;
        } catch (final java.io.IOException e) {
            return false;
        }
    }

    /**
     * Position of the {@code i}th checked block: the chunk's columns from y 0 to 127, where End islands lie.
     *
     * @param target chunk
     * @param i      index
     * @return block position
     */
    private static BlockPos at(final ChunkPos target, final int i) {
        return target.getBlockPos(i & 15, i >> 8, (i >> 4) & 15);
    }
}
