package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Verifies that a full-baseline chunk, whose sections Chunkis builds in vanilla's serialized form when it
 * loads, comes back with exactly the blocks and heightmaps it was stored with.
 */
@SuppressWarnings("unused")
public final class PreloadedSectionsReloadGameTest {

    /**
     * Distinct block states placed in one section: more than 256, so that section needs vanilla's widest
     * palette width, while other sections use the narrow ones.
     */
    private static final int DISTINCT_STATES = 300;

    /**
     * Stores a chunk holding hundreds of distinct block states, reloads it, and compares every block.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 400)
    public void reloadedFullBaselineMatchesEveryBlockAndHeightmap(final GameTestHelper context) {
        final ServerLevel world = context.getLevel();
        final ChunkPos anchor = ChunkPos.containing(context.absolutePos(BlockPos.ZERO));
        final ChunkPos target = new ChunkPos(anchor.x() - 1300, anchor.z() + 1300);
        world.setChunkForced(target.x(), target.z(), true);
        final LevelChunk chunk = world.getChunk(target.x(), target.z());
        final int baseY = (chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8) + 20) & ~15;
        final List<BlockState> states = simpleStates();
        for (int i = 0; i < 16 * 16 * 16; i++) {
            final BlockPos pos = target.getBlockAt(i & 15, baseY + (i >> 8), (i >> 4) & 15);
            world.setBlock(pos, states.get(i % states.size()), Block.UPDATE_CLIENTS);
        }
        final List<PalettedContainer<BlockState>> stored = copySections(chunk);
        final Map<Heightmap.Types, long[]> storedHeightmaps = heightmaps(chunk);
        final AtomicBoolean unloaded = new AtomicBoolean();
        ServerChunkEvents.CHUNK_UNLOAD.register((unloadWorld, unloadChunk) -> {
            if (unloadWorld == world && unloadChunk.getPos().equals(target)) {
                unloaded.set(true);
            }
        });

        context.startSequence()
                .thenWaitUntil(() -> {
                    chunk.markUnsaved();
                    world.getChunkSource().save(false);
                    AsyncCisSaveManager.flushAndClose(world);
                    world.setChunkForced(target.x(), target.z(), false);
                })
                .thenWaitUntil(() -> context.assertTrue(unloaded.get(),
                        Component.literal("Waiting for chunk " + target + " to unload.")))
                .thenWaitUntil(() -> {
                    world.setChunkForced(target.x(), target.z(), true);
                    world.getChunk(target.x(), target.z());
                })
                .thenWaitUntil(() -> {
                    final LevelChunk reloaded = world.getChunk(target.x(), target.z());
                    context.assertTrue(reloaded != chunk, Component.literal("Chunk " + target + " was not reloaded."));
                    final List<PalettedContainer<BlockState>> restored = copySections(reloaded);
                    for (int section = 0; section < stored.size(); section++) {
                        for (int i = 0; i < 16 * 16 * 16; i++) {
                            final BlockState expected = stored.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                            final BlockState actual = restored.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                            if (expected != actual) {
                                throw context.assertionException(Component.literal("Section " + section + " position " + i
                                        + " reloaded as " + actual + ", stored as " + expected + "."));
                            }
                        }
                    }
                    final Map<Heightmap.Types, long[]> restoredHeightmaps = heightmaps(reloaded);
                    storedHeightmaps.forEach((type, values) -> context.assertTrue(
                            java.util.Arrays.equals(values, restoredHeightmaps.get(type)),
                            Component.literal("Heightmap " + type + " differs after reload.")));
                })
                .thenWaitUntil(() -> world.setChunkForced(target.x(), target.z(), false))
                .thenSucceed();
    }

    /**
     * Reloads a stored chunk, whose delta then releases its block instructions, edits it, saves, reloads it
     * again, and expects every block: the next save captures the blocks back from the loaded chunk.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 500)
    public void editAfterReleasedReloadKeepsEveryBlock(final GameTestHelper context) {
        final ServerLevel world = context.getLevel();
        final ChunkPos anchor = ChunkPos.containing(context.absolutePos(BlockPos.ZERO));
        final ChunkPos target = new ChunkPos(anchor.x() - 1500, anchor.z() + 1500);
        world.setChunkForced(target.x(), target.z(), true);
        final LevelChunk chunk = world.getChunk(target.x(), target.z());
        final int baseY = (chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8) + 20) & ~15;
        final List<BlockState> states = simpleStates();
        for (int i = 0; i < 16 * 16 * 16; i++) {
            final BlockPos pos = target.getBlockAt(i & 15, baseY + (i >> 8), (i >> 4) & 15);
            world.setBlock(pos, states.get(i % states.size()), Block.UPDATE_CLIENTS);
        }
        final BlockPos edited = target.getBlockAt(3, baseY + 5, 7);
        final List<List<PalettedContainer<BlockState>>> expected = new ArrayList<>();
        final int[] unloads = {0};
        ServerChunkEvents.CHUNK_UNLOAD.register((unloadWorld, unloadChunk) -> {
            if (unloadWorld == world && unloadChunk.getPos().equals(target)) {
                unloads[0]++;
            }
        });

        context.startSequence()
                .thenWaitUntil(() -> {
                    chunk.markUnsaved();
                    world.getChunkSource().save(false);
                    AsyncCisSaveManager.flushAndClose(world);
                    world.setChunkForced(target.x(), target.z(), false);
                })
                .thenWaitUntil(() -> context.assertTrue(unloads[0] >= 1,
                        Component.literal("Waiting for chunk " + target + " to unload.")))
                .thenWaitUntil(() -> {
                    world.setChunkForced(target.x(), target.z(), true);
                    world.getChunk(target.x(), target.z());
                })
                .thenWaitUntil(() -> {
                    final LevelChunk reloaded = world.getChunk(target.x(), target.z());
                    final var delta = ((io.liparakis.chunkis.api.ChunkisDeltaDuck) reloaded).chunkis$getDelta();
                    context.assertTrue(delta != null && delta.isBlockPayloadReleased(),
                            Component.literal("Reloaded chunk " + target + " kept a copy of its block instructions."));
                    world.setBlockAndUpdate(edited, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
                    expected.add(copySections(reloaded));
                    world.getChunkSource().save(false);
                    AsyncCisSaveManager.flushAndClose(world);
                    world.setChunkForced(target.x(), target.z(), false);
                })
                .thenWaitUntil(() -> context.assertTrue(unloads[0] >= 2,
                        Component.literal("Waiting for chunk " + target + " to unload again.")))
                .thenWaitUntil(() -> {
                    world.setChunkForced(target.x(), target.z(), true);
                    world.getChunk(target.x(), target.z());
                })
                .thenWaitUntil(() -> {
                    final List<PalettedContainer<BlockState>> restored = copySections(world.getChunk(target.x(),
                            target.z()));
                    final List<PalettedContainer<BlockState>> stored = expected.get(0);
                    for (int section = 0; section < stored.size(); section++) {
                        for (int i = 0; i < 16 * 16 * 16; i++) {
                            final BlockState want = stored.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                            final BlockState got = restored.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                            if (want != got) {
                                throw context.assertionException(Component.literal("Section " + section + " position " + i
                                        + " reloaded as " + got + ", saved as " + want + "."));
                            }
                        }
                    }
                })
                .thenWaitUntil(() -> world.setChunkForced(target.x(), target.z(), false))
                .thenSucceed();
    }

    /**
     * Picks block states that stay as placed without neighbour updates: no block entity, no fluid.
     *
     * @return {@value #DISTINCT_STATES} distinct states
     */
    private static List<BlockState> simpleStates() {
        final List<BlockState> states = new ArrayList<>();
        for (final Block block : BuiltInRegistries.BLOCK) {
            for (final BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (!state.isAir() && !state.hasBlockEntity() && state.getFluidState().isEmpty()) {
                    states.add(state);
                    if (states.size() == DISTINCT_STATES) {
                        return states;
                    }
                }
            }
        }
        return states;
    }

    /**
     * Copies every section's blocks.
     *
     * @param chunk chunk
     * @return per-section copies
     */
    private static List<PalettedContainer<BlockState>> copySections(final LevelChunk chunk) {
        final List<PalettedContainer<BlockState>> copies = new ArrayList<>();
        for (final LevelChunkSection section : chunk.getSections()) {
            copies.add(section.getStates().copy());
        }
        return copies;
    }

    /**
     * Copies the chunk's heightmaps.
     *
     * @param chunk chunk
     * @return heightmap data by type
     */
    private static Map<Heightmap.Types, long[]> heightmaps(final LevelChunk chunk) {
        final Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        for (final Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            heightmaps.put(entry.getKey(), entry.getValue().getRawData().clone());
        }
        return heightmaps;
    }
}
