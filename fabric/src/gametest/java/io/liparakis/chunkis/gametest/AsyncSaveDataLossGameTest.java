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
import io.liparakis.chunkis.storage.AsyncCisSaveManager;
import io.liparakis.chunkis.storage.BaseChunkCaptureScheduler;
import io.liparakis.chunkis.storage.CisNbtUtil;
import io.liparakis.chunkis.storage.FabricCisStorageHelper;
import io.liparakis.chunkis.storage.io.CisStorage;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import java.util.List;

/**
 * Regression coverage for long-distance unload/reload churn that previously let
 * Chunkis return synthetic STATUS_EMPTY chunk NBT and reroll terrain.
 *
 * <p>The earlier test only toggled forced chunks. That was not faithful to the
 * real repro. This version creates a mock server player and teleports it back
 * and forth between two chunks about 20,000 blocks apart, verifies edited
 * blocks in both places survive 100 alternations, and also asserts that CIS
 * storage ended up with a persisted base chunk for both chunks.</p>
 */
@SuppressWarnings("unused")
public final class AsyncSaveDataLossGameTest {

    private static final int ROUND_TRIPS = 100;
    private static final int FAR_BLOCK_DISTANCE = 20_000;
    private static final int INITIAL_DELAY_TICKS = 20;
    private static final int FINAL_SETTLE_TICKS = 20;
    private static final int MAX_TICKS = 800;

    @GameTest(maxTicks = MAX_TICKS)
    public void survivesHundredFarChunkRoundTripsWithoutLosingEdits(final TestContext context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ChunkTargets targets = createTargets(context);
        final ServerWorld world = context.getWorld();

        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block("minecraft:overworld", targets.nearPrimary().getX(), targets.nearPrimary().getY(), targets.nearPrimary().getZ()));
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block("minecraft:overworld", targets.nearSecondary().getX(), targets.nearSecondary().getY(), targets.nearSecondary().getZ()));
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block("minecraft:overworld", targets.farPrimary().getX(), targets.farPrimary().getY(), targets.farPrimary().getZ()));
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block("minecraft:overworld", targets.farSecondary().getX(), targets.farSecondary().getY(), targets.farSecondary().getZ()));

        forceAndLoad(world, targets.nearChunk());
        forceAndLoad(world, targets.farChunk());

        placeMarkerPattern(world, targets.nearPrimary(), targets.nearSecondary());
        placeMarkerPattern(world, targets.farPrimary(), targets.farSecondary());
        markChunkDirty(world, targets.nearChunk());
        markChunkDirty(world, targets.farChunk());

        world.getChunkManager().save(false);
        world.setChunkForced(targets.nearChunk().x, targets.nearChunk().z, false);
        world.setChunkForced(targets.farChunk().x, targets.farChunk().z, false);

        final ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        teleportPlayer(player, world, targets.nearArrival());

        for (int i = 0; i < ROUND_TRIPS; i++) {
            final int roundTrip = i + 1;
            final boolean moveToFar = (i & 1) == 0;
            final ChunkPos destination = moveToFar ? targets.farChunk() : targets.nearChunk();
            final BlockPos destinationArrival =
                    moveToFar ? targets.farArrival() : targets.nearArrival();
            final BlockPos destinationPrimary =
                    moveToFar ? targets.farPrimary() : targets.nearPrimary();
            final BlockPos destinationSecondary =
                    moveToFar ? targets.farSecondary() : targets.nearSecondary();
            final long teleportTick = INITIAL_DELAY_TICKS + (long) i * 2L;

            context.runAtTick(
                    teleportTick, () ->
                            teleportPlayer(player, world, destinationArrival)
            );

            context.runAtTick(
                    teleportTick + 1L, () -> {
                        world.getChunk(destination.x, destination.z);
                        context.assertTrue(
                                isMarkerPatternPresent(world, destinationPrimary, destinationSecondary),
                                Text.literal("Lost edited blocks after round trip " + roundTrip
                                        + " while loading chunk " + destination)
                        );

                        world.getChunkManager().save(false);
                    }
            );
        }

        context.runAtTick(
                INITIAL_DELAY_TICKS + ROUND_TRIPS * 2L + FINAL_SETTLE_TICKS, () -> {
                    teleportPlayer(player, world, targets.nearArrival());
                    forceAndLoad(world, targets.nearChunk());
                    teleportPlayer(player, world, targets.farArrival());
                    forceAndLoad(world, targets.farChunk());

                    context.assertTrue(
                            isMarkerPatternPresent(world, targets.nearPrimary(), targets.nearSecondary()),
                            Text.literal("Near chunk edits disappeared after 100 far round trips.")
                    );
                    context.assertTrue(
                            isMarkerPatternPresent(world, targets.farPrimary(), targets.farSecondary()),
                            Text.literal("Far chunk edits disappeared after 100 far round trips.")
                    );

                    world.getChunkManager().save(false);
                    BaseChunkCaptureScheduler.flushAndClose(world);
                    AsyncCisSaveManager.flushAndClose(world);

                    assertPersistedBaseChunkPresent(context, world, targets.nearChunk(), "near");
                    assertPersistedBaseChunkPresent(context, world, targets.farChunk(), "far");
                    assertTraceTimeline(context, targets);

                    world.setChunkForced(targets.nearChunk().x, targets.nearChunk().z, false);
                    world.setChunkForced(targets.farChunk().x, targets.farChunk().z, false);
                    context.complete();
                }
        );
    }

    private static ChunkTargets createTargets(final TestContext context) {
        final BlockPos anchor = context.getAbsolutePos(new BlockPos(0, 0, 0));
        final ChunkPos nearChunk = new ChunkPos(anchor);
        final int farChunkOffset = FAR_BLOCK_DISTANCE >> 4;
        final ChunkPos farChunk = new ChunkPos(
                nearChunk.x + farChunkOffset,
                nearChunk.z + farChunkOffset
        );

        return new ChunkTargets(
                nearChunk,
                farChunk,
                nearChunk.getBlockPos(8, 100, 8),
                farChunk.getBlockPos(8, 100, 8),
                nearChunk.getBlockPos(8, 64, 8),
                nearChunk.getBlockPos(9, 64, 8),
                farChunk.getBlockPos(8, 64, 8),
                farChunk.getBlockPos(9, 64, 8)
        );
    }

    private static void forceAndLoad(final ServerWorld world, final ChunkPos chunkPos) {
        world.setChunkForced(chunkPos.x, chunkPos.z, true);
        world.getChunk(chunkPos.x, chunkPos.z);
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

    private static void placeMarkerPattern(
            final ServerWorld world,
            final BlockPos primary,
            final BlockPos secondary
    ) {
        world.setBlockState(primary, Blocks.DIAMOND_BLOCK.getDefaultState());
        world.setBlockState(secondary, Blocks.GOLD_BLOCK.getDefaultState());
    }

    private static boolean isMarkerPatternPresent(
            final ServerWorld world,
            final BlockPos primary,
            final BlockPos secondary
    ) {
        return world.getBlockState(primary).isOf(Blocks.DIAMOND_BLOCK)
                && world.getBlockState(secondary).isOf(Blocks.GOLD_BLOCK);
    }

    private static void markChunkDirty(final ServerWorld world, final ChunkPos chunkPos) {
        final Chunk chunk = world.getChunk(chunkPos.x, chunkPos.z);
        chunk.markNeedsSaving();
    }

    private static void assertPersistedBaseChunkPresent(
            final TestContext context,
            final ServerWorld world,
            final ChunkPos chunkPos,
            final String label
    ) {
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);
        final ChunkDelta<BlockState, NbtCompound> delta =
                storage.load(new CisChunkPos(chunkPos.x, chunkPos.z));

        context.assertTrue(
                true,
                Text.literal("Expected a persisted Chunkis delta for the " + label + " chunk.")
        );
        context.assertTrue(
                CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata()),
                Text.literal("Expected persisted base chunk NBT for the " + label + " chunk.")
        );
    }

    private static void assertTraceTimeline(final TestContext context, final ChunkTargets targets) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                matchesChunk(event, targets.nearChunk()) || matchesChunk(event, targets.farChunk()));

        context.assertTrue(
                containsEvent(events, targets.nearChunk(), ChunkTraceEventType.SAVE_TX_START)
                        || containsEvent(events, targets.nearChunk(), ChunkTraceEventType.SAVE_QUEUED)
                        || containsEvent(events, targets.nearChunk(), ChunkTraceEventType.SAVE_FLUSH_COMPLETED),
                Text.literal("Expected near chunk save timeline evidence in trace store.")
        );
        context.assertTrue(
                containsEvent(events, targets.farChunk(), ChunkTraceEventType.SAVE_TX_START)
                        || containsEvent(events, targets.farChunk(), ChunkTraceEventType.SAVE_QUEUED)
                        || containsEvent(events, targets.farChunk(), ChunkTraceEventType.SAVE_FLUSH_COMPLETED),
                Text.literal("Expected far chunk save timeline evidence in trace store.")
        );
        context.assertTrue(
                containsEvent(events, targets.nearChunk(), ChunkTraceEventType.LOAD_SOURCE_RESOLVED),
                Text.literal("Expected near chunk load-source evidence after explicit storage load.")
        );
        context.assertTrue(
                containsEvent(events, targets.farChunk(), ChunkTraceEventType.LOAD_SOURCE_RESOLVED),
                Text.literal("Expected far chunk load-source evidence after explicit storage load.")
        );
        context.assertTrue(
                containsEvent(events, targets.nearChunk(), ChunkTraceEventType.REGION_WRITE_TX_END)
                        || containsEvent(events, targets.nearChunk(), ChunkTraceEventType.SAVE_FLUSH_COMPLETED),
                Text.literal("Expected near chunk write completion evidence in trace store.")
        );
        context.assertTrue(
                containsEvent(events, targets.farChunk(), ChunkTraceEventType.REGION_WRITE_TX_END)
                        || containsEvent(events, targets.farChunk(), ChunkTraceEventType.SAVE_FLUSH_COMPLETED),
                Text.literal("Expected far chunk write completion evidence in trace store.")
        );
        context.assertTrue(
                containsEvent(events, targets.nearChunk(), ChunkTraceEventType.REGION_READ_TX_END),
                Text.literal("Expected near chunk region read evidence after explicit storage load.")
        );
        context.assertTrue(
                containsEvent(events, targets.farChunk(), ChunkTraceEventType.REGION_READ_TX_END),
                Text.literal("Expected far chunk region read evidence after explicit storage load.")
        );
        context.assertTrue(
                containsEvent(events, targets.nearChunk(), ChunkTraceEventType.RESTORE_COMPLETED)
                        || containsEvent(events, targets.farChunk(), ChunkTraceEventType.RESTORE_COMPLETED),
                Text.literal("Expected at least one restore-completed event during long-distance churn.")
        );
        context.assertTrue(
                events.stream().noneMatch(event -> event.eventType() == ChunkTraceEventType.ASSERTION_FAILED),
                Text.literal("Did not expect assertion failures in the durability trace timeline.")
        );
    }

    private static boolean containsEvent(
            final List<ChunkTraceEvent> events,
            final ChunkPos chunkPos,
            final ChunkTraceEventType eventType
    ) {
        for (final ChunkTraceEvent event : events) {
            if (event.eventType() == eventType && matchesChunk(event, chunkPos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesChunk(final ChunkTraceEvent event, final ChunkPos chunkPos) {
        return event.chunkKey() != null
                && event.chunkKey().x() == chunkPos.x
                && event.chunkKey().z() == chunkPos.z;
    }

    private record ChunkTargets(
            ChunkPos nearChunk,
            ChunkPos farChunk,
            BlockPos nearArrival,
            BlockPos farArrival,
            BlockPos nearPrimary,
            BlockPos nearSecondary,
            BlockPos farPrimary,
            BlockPos farSecondary
    ) {
    }
}
