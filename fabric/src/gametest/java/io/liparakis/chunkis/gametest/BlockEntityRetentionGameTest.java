package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.capture.CisSnapshotCapture;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
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
 * Verifies which stored block-entity payloads a full snapshot keeps when the live
 * sweep did not capture them.
 */
@SuppressWarnings("unused")
public final class BlockEntityRetentionGameTest {

    /**
     * Builds a payload with the given block-entity type id.
     *
     * @param id block-entity type id
     * @return payload compound
     */
    private static NbtCompound payload(final String id) {
        final NbtCompound nbt = new NbtCompound();
        nbt.putString("id", id);
        nbt.putString("marker", id);
        return nbt;
    }

    /**
     * Keeps unknown-type payloads and payloads whose block still wants a block entity,
     * and drops payloads whose block was replaced by one without a block entity.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 40)
    public void retainsUnsupportedAndUnserializedPayloadsButDropsEdited(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final BlockPos chestPos = context.getAbsolutePos(new BlockPos(1, 2, 1));
        final ChunkPos chunkPos = new ChunkPos(chestPos);
        final WorldChunk chunk = world.getWorldChunk(chestPos);
        final BlockPos stonePos = chestPos.up();
        final BlockPos unknownPos = chestPos.up(2);

        // A chest block whose live block entity is gone (e.g. failed to serialize).
        world.setBlockState(chestPos, Blocks.CHEST.getDefaultState());
        world.removeBlockEntity(chestPos);
        world.setBlockState(stonePos, Blocks.STONE.getDefaultState());
        world.setBlockState(unknownPos, Blocks.STONE.getDefaultState());

        final Long2ObjectMap<NbtCompound> previous = new Long2ObjectOpenHashMap<>();
        previous.put(packLocal(chunkPos, chestPos), payload("minecraft:chest"));
        previous.put(packLocal(chunkPos, stonePos), payload("minecraft:furnace"));
        previous.put(packLocal(chunkPos, unknownPos), payload("missingmod:machine"));

        final ChunkDelta<BlockState, NbtCompound> target = new ChunkDelta<>(BlockState::isAir);
        CisSnapshotCapture.retainUncapturedBlockEntities(chunk, previous, target);

        final Long2ObjectMap<NbtCompound> kept = target.getBlockEntities();
        context.assertTrue(kept.containsKey(packLocal(chunkPos, chestPos)),
                Text.literal("Expected chest payload kept: its block still wants a block entity."));
        context.assertTrue(kept.containsKey(packLocal(chunkPos, unknownPos)),
                Text.literal("Expected missing-mod payload kept as unsupported data."));
        context.assertFalse(kept.containsKey(packLocal(chunkPos, stonePos)),
                Text.literal("Expected furnace payload dropped: its block was replaced by stone."));
        context.complete();
    }

    /**
     * Packs a world position into the delta's chunk-local key.
     *
     * @param chunkPos owning chunk
     * @param pos      world position
     * @return packed local position
     */
    private static long packLocal(final ChunkPos chunkPos, final BlockPos pos) {
        return BlockInstruction.packPos(pos.getX() - chunkPos.getStartX(), pos.getY(), pos.getZ() - chunkPos.getStartZ());
    }
}
