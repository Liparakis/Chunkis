package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.ChunkTraceEvent;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.PayloadWatchTarget;
import io.liparakis.chunkis.storage.BaseChunkCaptureUtil;
import io.liparakis.chunkis.storage.CisNbtUtil;
import io.liparakis.chunkis.storage.FabricCisStorageHelper;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.GlobalChunkTracker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.List;

@SuppressWarnings("unused")
public final class PersistedBaseChunkReloadGameTest {

    private static final int FAR_BLOCK_DISTANCE = 20_000;

    @GameTest(maxTicks = 120)
    public void persistedBaseChunkReloadDoesNotRestoreEmpty(final TestContext context) {
        ChunkTraceStore.clear();
        GlobalChunkTracker.clear();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ServerWorld world = context.getWorld();
        final BlockPos anchor = context.getAbsolutePos(BlockPos.ORIGIN);
        // Move 10,000 blocks away to ensure it's not kept loaded by the gametest structure
        final ChunkPos targetChunk = new ChunkPos(new ChunkPos(anchor).x + 600, new ChunkPos(anchor).z + 600);
        final BlockPos targetArrival = targetChunk.getBlockPos(8, 100, 8);
        final BlockPos farArrival = targetArrival.add(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE);
        final BlockPos primary = targetChunk.getBlockPos(8, 64, 8);
        final BlockPos secondary = targetChunk.getBlockPos(9, 64, 8);
        final BlockPos chestPos = targetChunk.getBlockPos(10, 64, 8);

        ChunkTraceWatchpoints.clear();
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block("minecraft:overworld", primary.getX(), primary.getY(), primary.getZ()));
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block("minecraft:overworld", secondary.getX(), secondary.getY(), secondary.getZ()));
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block("minecraft:overworld", chestPos.getX(), chestPos.getY(), chestPos.getZ()));

        world.setChunkForced(targetChunk.x, targetChunk.z, true);
        final WorldChunk chunk = world.getChunk(targetChunk.x, targetChunk.z);
        placeMarkerPattern(world, primary, secondary, chestPos);

        final ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>(BlockState::isAir);
        BaseChunkCaptureUtil.captureBaseChunk(world, chunk, delta);

        context.assertTrue(
                CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata()),
                Text.literal("Expected persisted base chunk NBT after capture.")
        );
        context.assertTrue(
                delta.getBlockInstructions().isEmpty(),
                Text.literal("Expected base capture to clear sparse block payloads.")
        );

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);
        storage.save(new CisChunkPos(targetChunk.x, targetChunk.z), delta);
        GlobalChunkTracker.clear();

        final ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        teleportPlayer(player, world, targetArrival);
        world.getChunkManager().save(false);
        world.setChunkForced(targetChunk.x, targetChunk.z, false);

        context.runAtTick(20, () -> teleportPlayer(player, world, farArrival));
        context.runAtTick(50, () -> teleportPlayer(player, world, targetArrival));
        context.runAtTick(60, () -> {
            world.getChunk(targetChunk.x, targetChunk.z);

            context.assertTrue(
                    isMarkerPatternPresent(world, primary, secondary, chestPos),
                    Text.literal("Reloaded chunk restored empty instead of from persisted base chunk NBT.")
            );
            context.assertTrue(
                    hasMatchingChestBlockEntity(world, chestPos),
                    Text.literal("Expected chest block entity to match its restored chest block state.")
            );
            context.assertTrue(
                    containsBaseChunkAppliedTrace(targetChunk),
                    Text.literal("Expected BASE_NBT_APPLIED trace event on reload.")
            );

            ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
            context.complete();
        });
    }

    private static void placeMarkerPattern(
            final ServerWorld world,
            final BlockPos primary,
            final BlockPos secondary,
            final BlockPos chestPos
    ) {
        world.setBlockState(primary, Blocks.DIAMOND_BLOCK.getDefaultState());
        world.setBlockState(secondary, Blocks.GOLD_BLOCK.getDefaultState());
        world.setBlockState(chestPos, Blocks.CHEST.getDefaultState());
    }

    private static boolean isMarkerPatternPresent(
            final ServerWorld world,
            final BlockPos primary,
            final BlockPos secondary,
            final BlockPos chestPos
    ) {
        return world.getBlockState(primary).isOf(Blocks.DIAMOND_BLOCK)
                && world.getBlockState(secondary).isOf(Blocks.GOLD_BLOCK)
                && world.getBlockState(chestPos).isOf(Blocks.CHEST);
    }

    private static void teleportPlayer(
            final ServerPlayerEntity player,
            final ServerWorld world,
            final BlockPos destination
    ) {
        player.teleport(
                world,
                destination.getX() + 0.5D,
                destination.getY(),
                destination.getZ() + 0.5D,
                java.util.Set.of(),
                0.0F,
                0.0F,
                true
        );
    }

    private static boolean hasMatchingChestBlockEntity(
            final ServerWorld world,
            final BlockPos chestPos
    ) {
        final BlockEntity blockEntity = world.getBlockEntity(chestPos);
        return world.getBlockState(chestPos).isOf(Blocks.CHEST)
                && blockEntity instanceof ChestBlockEntity
                && blockEntity.getCachedState().isOf(Blocks.CHEST);
    }

    private static boolean containsBaseChunkAppliedTrace(final ChunkPos chunkPos) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                event.chunkKey() != null
                        && event.chunkKey().x() == chunkPos.x
                        && event.chunkKey().z() == chunkPos.z
                        && event.eventType() == ChunkTraceEventType.BASE_NBT_APPLIED
        );
        return !events.isEmpty();
    }
}
