package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

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
    public void reloadedFullBaselineMatchesEveryBlockAndHeightmap(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final ChunkPos anchor = new ChunkPos(context.getAbsolutePos(BlockPos.ORIGIN));
        final ChunkPos target = new ChunkPos(anchor.x - 1300, anchor.z + 1300);
        world.setChunkForced(target.x, target.z, true);
        final WorldChunk chunk = world.getChunk(target.x, target.z);
        final int baseY = (chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, 8, 8) + 20) & ~15;
        final List<BlockState> states = simpleStates();
        for (int i = 0; i < 16 * 16 * 16; i++) {
            final BlockPos pos = target.getBlockPos(i & 15, baseY + (i >> 8), (i >> 4) & 15);
            world.setBlockState(pos, states.get(i % states.size()), Block.NOTIFY_LISTENERS);
        }
        final List<PalettedContainer<BlockState>> stored = copySections(chunk);
        final Map<Heightmap.Type, long[]> storedHeightmaps = heightmaps(chunk);
        final AtomicBoolean unloaded = new AtomicBoolean();
        ServerChunkEvents.CHUNK_UNLOAD.register((unloadWorld, unloadChunk) -> {
            if (unloadWorld == world && unloadChunk.getPos().equals(target)) {
                unloaded.set(true);
            }
        });

        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    chunk.markNeedsSaving();
                    world.getChunkManager().save(false);
                    AsyncCisSaveManager.flushAndClose(world);
                    world.setChunkForced(target.x, target.z, false);
                })
                .createAndAdd(() -> context.assertTrue(unloaded.get(),
                        Text.literal("Waiting for chunk " + target + " to unload.")))
                .createAndAdd(() -> {
                    world.setChunkForced(target.x, target.z, true);
                    world.getChunk(target.x, target.z);
                })
                .createAndAdd(() -> {
                    final WorldChunk reloaded = world.getChunk(target.x, target.z);
                    context.assertTrue(reloaded != chunk, Text.literal("Chunk " + target + " was not reloaded."));
                    final List<PalettedContainer<BlockState>> restored = copySections(reloaded);
                    for (int section = 0; section < stored.size(); section++) {
                        for (int i = 0; i < 16 * 16 * 16; i++) {
                            final BlockState expected = stored.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                            final BlockState actual = restored.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                            if (expected != actual) {
                                throw context.createError(Text.literal("Section " + section + " position " + i
                                        + " reloaded as " + actual + ", stored as " + expected + "."));
                            }
                        }
                    }
                    final Map<Heightmap.Type, long[]> restoredHeightmaps = heightmaps(reloaded);
                    storedHeightmaps.forEach((type, values) -> context.assertTrue(
                            java.util.Arrays.equals(values, restoredHeightmaps.get(type)),
                            Text.literal("Heightmap " + type + " differs after reload.")));
                })
                .createAndAdd(() -> world.setChunkForced(target.x, target.z, false))
                .completeIfSuccessful();
    }

    /**
     * Reloads a stored chunk, whose delta then releases its block instructions, edits it, saves, reloads it
     * again, and expects every block: the next save captures the blocks back from the loaded chunk.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 500)
    public void editAfterReleasedReloadKeepsEveryBlock(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final ChunkPos anchor = new ChunkPos(context.getAbsolutePos(BlockPos.ORIGIN));
        final ChunkPos target = new ChunkPos(anchor.x - 1500, anchor.z + 1500);
        world.setChunkForced(target.x, target.z, true);
        final WorldChunk chunk = world.getChunk(target.x, target.z);
        final int baseY = (chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, 8, 8) + 20) & ~15;
        final List<BlockState> states = simpleStates();
        for (int i = 0; i < 16 * 16 * 16; i++) {
            final BlockPos pos = target.getBlockPos(i & 15, baseY + (i >> 8), (i >> 4) & 15);
            world.setBlockState(pos, states.get(i % states.size()), Block.NOTIFY_LISTENERS);
        }
        final BlockPos edited = target.getBlockPos(3, baseY + 5, 7);
        final List<List<PalettedContainer<BlockState>>> expected = new ArrayList<>();
        final int[] unloads = {0};
        ServerChunkEvents.CHUNK_UNLOAD.register((unloadWorld, unloadChunk) -> {
            if (unloadWorld == world && unloadChunk.getPos().equals(target)) {
                unloads[0]++;
            }
        });

        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    chunk.markNeedsSaving();
                    world.getChunkManager().save(false);
                    AsyncCisSaveManager.flushAndClose(world);
                    world.setChunkForced(target.x, target.z, false);
                })
                .createAndAdd(() -> context.assertTrue(unloads[0] >= 1,
                        Text.literal("Waiting for chunk " + target + " to unload.")))
                .createAndAdd(() -> {
                    world.setChunkForced(target.x, target.z, true);
                    world.getChunk(target.x, target.z);
                })
                .createAndAdd(() -> {
                    final WorldChunk reloaded = world.getChunk(target.x, target.z);
                    final var delta = ((io.liparakis.chunkis.api.ChunkisDeltaDuck) reloaded).chunkis$getDelta();
                    context.assertTrue(delta != null && delta.isBlockPayloadReleased(),
                            Text.literal("Reloaded chunk " + target + " kept a copy of its block instructions."));
                    world.setBlockState(edited, net.minecraft.block.Blocks.GOLD_BLOCK.getDefaultState());
                    expected.add(copySections(reloaded));
                    world.getChunkManager().save(false);
                    AsyncCisSaveManager.flushAndClose(world);
                    world.setChunkForced(target.x, target.z, false);
                })
                .createAndAdd(() -> context.assertTrue(unloads[0] >= 2,
                        Text.literal("Waiting for chunk " + target + " to unload again.")))
                .createAndAdd(() -> {
                    world.setChunkForced(target.x, target.z, true);
                    world.getChunk(target.x, target.z);
                })
                .createAndAdd(() -> {
                    final List<PalettedContainer<BlockState>> restored = copySections(world.getChunk(target.x,
                            target.z));
                    final List<PalettedContainer<BlockState>> stored = expected.get(0);
                    for (int section = 0; section < stored.size(); section++) {
                        for (int i = 0; i < 16 * 16 * 16; i++) {
                            final BlockState want = stored.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                            final BlockState got = restored.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                            if (want != got) {
                                throw context.createError(Text.literal("Section " + section + " position " + i
                                        + " reloaded as " + got + ", saved as " + want + "."));
                            }
                        }
                    }
                })
                .createAndAdd(() -> world.setChunkForced(target.x, target.z, false))
                .completeIfSuccessful();
    }

    /**
     * Picks block states that stay as placed without neighbour updates: no block entity, no fluid.
     *
     * @return {@value #DISTINCT_STATES} distinct states
     */
    private static List<BlockState> simpleStates() {
        final List<BlockState> states = new ArrayList<>();
        for (final Block block : Registries.BLOCK) {
            for (final BlockState state : block.getStateManager().getStates()) {
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
    private static List<PalettedContainer<BlockState>> copySections(final WorldChunk chunk) {
        final List<PalettedContainer<BlockState>> copies = new ArrayList<>();
        for (final ChunkSection section : chunk.getSectionArray()) {
            copies.add(section.getBlockStateContainer().copy());
        }
        return copies;
    }

    /**
     * Copies the chunk's heightmaps.
     *
     * @param chunk chunk
     * @return heightmap data by type
     */
    private static Map<Heightmap.Type, long[]> heightmaps(final WorldChunk chunk) {
        final Map<Heightmap.Type, long[]> heightmaps = new EnumMap<>(Heightmap.Type.class);
        for (final Map.Entry<Heightmap.Type, Heightmap> entry : chunk.getHeightmaps()) {
            heightmaps.put(entry.getKey(), entry.getValue().asLongArray().clone());
        }
        return heightmaps;
    }
}
