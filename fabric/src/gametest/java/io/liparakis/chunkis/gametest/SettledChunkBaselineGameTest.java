package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Covers chunks nobody changed in an authoritative world: a finished one is stored as a Chunkis entry and an
 * unfinished one is kept as vanilla serializes it, so neither is generated again from scratch on its next load. Runs in
 * the End, which no other game test uses, so marking it
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
    public void untouchedSettledChunkReloadsFromStorage(final GameTestHelper context) {
        final ServerLevel end = Objects.requireNonNull(context.getLevel()
                .getServer()
                .getLevel(Level.END), "Test dimension is missing");
        MigrationStateServiceHolder.markAuthoritative(end.dimension());
        // Far out in the End (outer islands or void, nothing that ticks), distinct for every run like the other
        // tests' chunks, so a kept world never has it stored already.
        final ChunkPos anchor = ChunkPos.containing(context.absolutePos(BlockPos.ZERO));
        final ChunkPos target = new ChunkPos(anchor.x() + 3700, anchor.z() - 3700);
        final AtomicInteger unloads = new AtomicInteger();
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (world == end && chunk.getPos()
                    .equals(target)) {
                unloads.incrementAndGet();
            }
        });
        final BlockState[] before = new BlockState[16 * 16 * 128];
        context.assertFalse(FabricCisStorageHelper.getStorage(end)
                        .contains(FabricCisStorageHelper.toStoragePos(target)),
                Component.literal("End chunk " + target + " is already stored before the test."));
        end.setChunkForced(target.x(), target.z(), true);
        context.startSequence()
                .thenWaitUntil(() -> {
                    final LevelChunk chunk = end.getChunk(target.x(), target.z());
                    context.assertFalse(ChunkDeltaOwnership.hasChunkisOwnedState(((ChunkisDeltaDuck) chunk).chunkis$getDelta()),
                            Component.literal("Untouched End chunk " + target + " is already owned."));
                    for (int i = 0; i < before.length; i++) {
                        before[i] = chunk.getBlockState(at(target, i));
                    }
                    end.setChunkForced(target.x(), target.z(), false);
                })
                .thenWaitUntil(() -> context.assertTrue(unloads.get() > 0,
                        Component.literal("Waiting for End chunk " + target + " to unload.")))
                .thenWaitUntil(() -> {
                    AsyncCisSaveManager.flushAndClose(end);
                    context.assertTrue(FabricCisStorageHelper.getStorage(end)
                                    .contains(FabricCisStorageHelper.toStoragePos(target)),
                            Component.literal("The save of settled End chunk " + target + " stored nothing."));
                    end.setChunkForced(target.x(), target.z(), true);
                })
                .thenWaitUntil(() -> {
                    final LevelChunk chunk = end.getChunk(target.x(), target.z());
                    context.assertTrue(ChunkDeltaOwnership.hasChunkisOwnedState(((ChunkisDeltaDuck) chunk).chunkis$getDelta()),
                            Component.literal("End chunk " + target + " did not reload from Chunkis storage."));
                    for (int i = 0; i < before.length; i++) {
                        final BlockState now = chunk.getBlockState(at(target, i));
                        context.assertTrue(now == before[i],
                                Component.literal("Block at " + at(target, i) + " came back as " + now + " instead of "
                                        + before[i] + "."));
                    }
                    end.setChunkForced(target.x(), target.z(), false);
                })
                .thenSucceed();
    }

    /**
     * Generates an End chunk only part of the way and lets it unload, then expects its save to keep it in the
     * unfinished-chunk store (vanilla's serialized chunk, under Chunkis's directory) and the chunk to load back and
     * finish.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 6000)
    public void partlyGeneratedChunkIsKeptUnfinished(final GameTestHelper context) {
        final ServerLevel end = java.util.Objects.requireNonNull(context.getLevel()
                .getServer()
                .getLevel(Level.END), "Test dimension is missing");
        MigrationStateServiceHolder.markAuthoritative(end.dimension());
        final ChunkPos anchor = ChunkPos.containing(context.absolutePos(BlockPos.ZERO));
        final ChunkPos target = new ChunkPos(anchor.x() + 4100, anchor.z() - 4100);
        final Path region = ChunkisStoragePaths.computeUnfinishedChunksDirectory(end.getServer()
                        .getWorldPath(LevelResource.ROOT), end.dimension())
                .resolve("r." + target.getRegionX() + "." + target.getRegionZ() + ".cis");
        final AtomicInteger unloads = new AtomicInteger();
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (world == end && chunk.getPos()
                    .equals(target)) {
                unloads.incrementAndGet();
            }
        });
        context.assertFalse(Files.exists(region),
                Component.literal("Unfinished region " + region + " exists already."));
        // No ticket: the chunk drops back out once generated this far, unfinished.
        end.getChunk(target.x(), target.z(), ChunkStatus.FEATURES, true);
        context.startSequence()
                .thenWaitUntil(() -> context.assertTrue(waitForSave(end, region),
                        Component.literal("Waiting for unfinished chunk " + target + " to be kept in " + region + ".")))
                .thenWaitUntil(() -> {
                    end.setChunkForced(target.x(), target.z(), true);
                    context.assertTrue(end.getChunk(target.x(), target.z())
                                    .getPersistedStatus() == ChunkStatus.FULL,
                            Component.literal("Unfinished chunk " + target + " did not load back and finish."));
                    end.setChunkForced(target.x(), target.z(), false);
                })
                .thenSucceed();
    }

    /**
     * Saves pending chunks and reports whether a region file holds data.
     *
     * @param world  world to save
     * @param region region file
     * @return {@code true} once the file exists and is not empty
     */
    private static boolean waitForSave(final ServerLevel world, final Path region) {
        world.getChunkSource()
                .save(false);
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
        return target.getBlockAt(i & 15, i >> 8, (i >> 4) & 15);
    }
}
