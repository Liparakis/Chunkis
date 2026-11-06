package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Verifies that a chunk restored from a full block baseline is lit for the restored blocks, not for
 * the terrain vanilla generated and lit before the restore replaced it.
 */
@SuppressWarnings("unused")
public final class RestoredChunkLightGameTest {

    /**
     * Lowest corner of the sealed box, in chunk-local coordinates.
     */
    private static final int BOX_MIN = 4;

    /**
     * Highest corner of the sealed box, in chunk-local coordinates.
     */
    private static final int BOX_MAX = 10;

    /**
     * Stores a chunk holding a sealed stone box with its light, reloads it, and expects no sky light inside.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 400)
    public void restoredSealedBoxHasNoSkyLight(final GameTestHelper context) {
        runSealedBoxScenario(context, 1100, false);
    }

    /**
     * Same as {@link #restoredSealedBoxHasNoSkyLight}, for an entry stored without light: vanilla lights the
     * chunk from its restored blocks and Chunkis pulls the light of already-lit neighbours in.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 400)
    public void restoredUnlitSealedBoxHasNoSkyLight(final GameTestHelper context) {
        runSealedBoxScenario(context, 1200, true);
    }

    /**
     * Stores a chunk holding a sealed stone box under a roof, reloads it, and checks its sky light.
     *
     * @param context         the game test context
     * @param offset          chunk offset from the test structure, distinct per test
     * @param dropStoredLight whether to store the entry as if captured unlit
     */
    private static void runSealedBoxScenario(final GameTestHelper context, final int offset,
            final boolean dropStoredLight) {
        final ServerLevel world = context.getLevel();
        final ChunkPos anchor = ChunkPos.containing(context.absolutePos(BlockPos.ZERO));
        final ChunkPos target = new ChunkPos(anchor.x() + offset, anchor.z() - offset);
        world.setChunkForced(target.x(), target.z(), true);
        // Sit on the generated surface, in a section vanilla already lit while generating the terrain.
        final int boxBottomY = world.getChunk(target.x(), target.z())
                .getHeight(Heightmap.Types.MOTION_BLOCKING, 7, 7) + 1;
        final BlockPos inside = target.getBlockAt(7, boxBottomY + 3, 7);
        final AtomicBoolean unloaded = new AtomicBoolean();
        ServerChunkEvents.CHUNK_UNLOAD.register((unloadWorld, unloadChunk) -> {
            if (unloadWorld == world && unloadChunk.getPos().equals(target)) {
                unloaded.set(true);
            }
        });

        for (int y = boxBottomY; y <= boxBottomY + BOX_MAX - BOX_MIN; y++) {
            for (int z = BOX_MIN; z <= BOX_MAX; z++) {
                for (int x = BOX_MIN; x <= BOX_MAX; x++) {
                    final boolean shell = x == BOX_MIN || x == BOX_MAX || z == BOX_MIN || z == BOX_MAX
                            || y == boxBottomY || y == boxBottomY + BOX_MAX - BOX_MIN;
                    if (shell) {
                        world.setBlockAndUpdate(target.getBlockAt(x, y, z), Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }

        // A roof over the whole chunk: below it, sky light only arrives sideways from the open neighbours.
        final int roofY = boxBottomY + BOX_MAX - BOX_MIN + 3;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                world.setBlockAndUpdate(target.getBlockAt(x, roofY, z), Blocks.STONE.defaultBlockState());
            }
        }

        context.startSequence()
                .thenWaitUntil(() -> {
                    world.getChunk(target.x(), target.z())
                            .markUnsaved();
                    world.getChunkSource()
                            .save(false);
                    AsyncCisSaveManager.flushAndClose(world);
                    context.assertTrue(storedBoxPresent(world, target, boxBottomY),
                            Component.literal("Waiting for the box chunk to be stored."));
                })
                .thenWaitUntil(() -> world.setChunkForced(target.x(), target.z(), false))
                .thenWaitUntil(() -> context.assertTrue(unloaded.get(),
                        Component.literal("Waiting for the box chunk to unload.")))
                .thenWaitUntil(() -> {
                    if (dropStoredLight) {
                        // After the unload, whose own save would store the light again.
                        AsyncCisSaveManager.flushAndClose(world);
                        dropStoredLight(world, target);
                    }
                    world.setChunkForced(target.x(), target.z(), true);
                    world.getChunk(target.x(), target.z());
                })
                .thenWaitUntil(() -> {
                    context.assertTrue(world.getBlockState(inside.below(3)).is(Blocks.STONE),
                            Component.literal("Reloaded chunk lost the box floor."));
                    final int skyLight = world.getBrightness(LightLayer.SKY, inside);
                    context.assertTrue(skyLight == 0,
                            Component.literal("Sealed box in a restored chunk has sky light " + skyLight + ", expected 0."));
                    final int openSkyLight = world.getBrightness(LightLayer.SKY, inside.above(8));
                    context.assertTrue(openSkyLight == 15,
                            Component.literal("Open air above the box has sky light " + openSkyLight + ", expected 15."));
                    final int highSkyLight = world.getBrightness(LightLayer.SKY, inside.above(64));
                    context.assertTrue(highSkyLight == 15,
                            Component.literal("Open air high above the terrain has sky light " + highSkyLight + ", expected 15."));
                    final int underRoofEdge = world.getBrightness(LightLayer.SKY, target.getBlockAt(0, roofY - 1, 7));
                    final int underRoofInner = world.getBrightness(LightLayer.SKY, target.getBlockAt(3, roofY - 1, 7));
                    context.assertTrue(underRoofEdge == 14 && underRoofInner == 11,
                            Component.literal("Under the roof, sky light from the open west neighbour is " + underRoofEdge
                                    + " at the edge and " + underRoofInner + " three blocks in; expected 14 and 11."));
                })
                .thenWaitUntil(() -> world.setChunkForced(target.x(), target.z(), false))
                .thenSucceed();
    }

    /**
     * Rewrites the stored entry as if it had been captured while the chunk was unlit.
     *
     * @param world  test world
     * @param target chunk position
     */
    private static void dropStoredLight(final ServerLevel world, final ChunkPos target) {
        final var storage = FabricCisStorageHelper.getStorage(world);
        final ChunkDelta<BlockState, CompoundTag> stored = storage.load(FabricCisStorageHelper.toStoragePos(target));
        final CompoundTag metadata = stored.getChunkMetadata().copy();
        metadata.putBoolean(CisNbtUtil.STORED_LIGHT_KEY, false);
        stored.setChunkMetadata(metadata, false);
        storage.save(FabricCisStorageHelper.toStoragePos(target), stored);
    }

    /**
     * Reports whether CIS storage holds the box floor block for the chunk.
     *
     * @param world  test world
     * @param target chunk position
     * @param boxBottomY bottom Y of the box
     * @return true when the stored delta contains stone at the box floor corner
     */
    private static boolean storedBoxPresent(final ServerLevel world, final ChunkPos target, final int boxBottomY) {
        final ChunkDelta<BlockState, CompoundTag> stored = FabricCisStorageHelper.getStorage(world)
                .load(FabricCisStorageHelper.toStoragePos(target));
        final long floorCorner = BlockInstruction.packPos(BOX_MIN, boxBottomY, BOX_MIN);
        final boolean[] found = {false};
        stored.forEachBlock((x, y, z, state) -> {
            if (BlockInstruction.packPos(x, y, z) == floorCorner && state != null && state.is(Blocks.STONE)) {
                found[0] = true;
            }
        });
        return found[0];
    }
}
