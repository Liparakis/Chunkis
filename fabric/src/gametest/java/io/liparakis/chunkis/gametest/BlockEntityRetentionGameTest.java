package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.capture.CisSnapshotCapture;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

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
    private static CompoundTag payload(final String id) {
        final CompoundTag nbt = new CompoundTag();
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
    public void retainsUnsupportedAndUnserializedPayloadsButDropsEdited(final GameTestHelper context) {
        final ServerLevel world = context.getLevel();
        final BlockPos chestPos = context.absolutePos(new BlockPos(1, 2, 1));
        final ChunkPos chunkPos = ChunkPos.containing(chestPos);
        final LevelChunk chunk = world.getChunkAt(chestPos);
        final BlockPos stonePos = chestPos.above();
        final BlockPos unknownPos = chestPos.above(2);

        // A chest block whose live block entity is gone (e.g. failed to serialize).
        world.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        world.removeBlockEntity(chestPos);
        world.setBlockAndUpdate(stonePos, Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(unknownPos, Blocks.STONE.defaultBlockState());

        final Long2ObjectMap<CompoundTag> previous = new Long2ObjectOpenHashMap<>();
        previous.put(packLocal(chunkPos, chestPos), payload("minecraft:chest"));
        previous.put(packLocal(chunkPos, stonePos), payload("minecraft:furnace"));
        previous.put(packLocal(chunkPos, unknownPos), payload("missingmod:machine"));

        final ChunkDelta<BlockState, CompoundTag> target = new ChunkDelta<>(BlockState::isAir);
        CisSnapshotCapture.retainUncapturedBlockEntities(chunk, previous, target);

        final Long2ObjectMap<CompoundTag> kept = target.getBlockEntities();
        context.assertTrue(kept.containsKey(packLocal(chunkPos, chestPos)),
                Component.literal("Expected chest payload kept: its block still wants a block entity."));
        context.assertTrue(kept.containsKey(packLocal(chunkPos, unknownPos)),
                Component.literal("Expected missing-mod payload kept as unsupported data."));
        context.assertFalse(kept.containsKey(packLocal(chunkPos, stonePos)),
                Component.literal("Expected furnace payload dropped: its block was replaced by stone."));
        context.succeed();
    }

    /**
     * Packs a world position into the delta's chunk-local key.
     *
     * @param chunkPos owning chunk
     * @param pos      world position
     * @return packed local position
     */
    private static long packLocal(final ChunkPos chunkPos, final BlockPos pos) {
        return BlockInstruction.packPos(pos.getX() - chunkPos.getMinBlockX(), pos.getY(), pos.getZ() - chunkPos.getMinBlockZ());
    }
}
