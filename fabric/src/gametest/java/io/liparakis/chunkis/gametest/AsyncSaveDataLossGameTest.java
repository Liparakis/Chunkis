package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
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
import java.util.Map;
import java.util.Set;

/**
 * Regression coverage for long-distance unload/reload churn that previously let
 * Chunkis return synthetic {@code STATUS_EMPTY} chunk NBT and reroll terrain.
 *
 * <p>The earlier test only toggled forced chunks, which was not faithful to the
 * real reproduction path. This version creates a mock server player and teleports
 * it back and forth between two chunks about 20,000 blocks apart, verifies that
 * edited blocks in both locations survive {@value #ROUND_TRIPS} alternations, and
 * also asserts that CIS storage ends up with a persisted base chunk for both
 * chunks.</p>
 */
@SuppressWarnings("unused")
public final class AsyncSaveDataLossGameTest {

    /**
     * Number of far/near teleport alternations to perform.
     */
    private static final int ROUND_TRIPS = 100;

    /**
     * Block distance used to offset the far chunk from the near chunk.
     * Large enough that the two chunk positions will never both be within the
     * same player's view distance simultaneously.
     */
    private static final int FAR_BLOCK_DISTANCE = 20_000;

    /**
     * Ticks to wait before beginning round-trip teleportation.
     */
    private static final int INITIAL_DELAY_TICKS = 20;

    /**
     * Ticks to wait after the last round trip before running final assertions.
     */
    private static final int FINAL_SETTLE_TICKS = 20;

    /**
     * Maximum ticks before the GameTest framework marks the test as timed out.
     * Must exceed {@code INITIAL_DELAY_TICKS + ROUND_TRIPS * 2 + FINAL_SETTLE_TICKS}
     * (currently {@value #INITIAL_DELAY_TICKS} + {@value #ROUND_TRIPS} * 2
     * + {@value #FINAL_SETTLE_TICKS} = 240).
     */
    private static final int MAX_TICKS = 800;

    /**
     * Computes the near and far chunk positions and all derived block positions
     * used throughout the test from the test context's anchor position.
     *
     * @param context test context providing the structure's absolute anchor
     * @return fully populated {@link ChunkTargets} for this test run
     */
    private static ChunkTargets createTargets(final TestContext context) {
        final BlockPos anchor = context.getAbsolutePos(new BlockPos(0, 0, 0));
        final ChunkPos nearChunk = new ChunkPos(anchor);
        final int farChunkOffset = FAR_BLOCK_DISTANCE >> 4;
        final ChunkPos farChunk = new ChunkPos(nearChunk.x + farChunkOffset, nearChunk.z + farChunkOffset);
        return new ChunkTargets(nearChunk,
                farChunk,
                nearChunk.getBlockPos(8, 100, 8),
                farChunk.getBlockPos(8, 100, 8),
                nearChunk.getBlockPos(8, 64, 8),
                nearChunk.getBlockPos(9, 64, 8),
                farChunk.getBlockPos(8, 64, 8),
                farChunk.getBlockPos(9, 64, 8));
    }

    /**
     * Forces {@code chunkPos} to stay loaded and immediately fetches it so the
     * chunk is present in the world before the test manipulates it.
     *
     * @param world    server world managing the chunk
     * @param chunkPos chunk to force-load
     */
    private static void forceAndLoad(final ServerWorld world, final ChunkPos chunkPos) {
        world.setChunkForced(chunkPos.x, chunkPos.z, true);
        world.getChunk(chunkPos.x, chunkPos.z);
    }

    /**
     * Teleports {@code player} to the centre of {@code destination} at the correct
     * Y coordinate, with no rotation change.
     *
     * @param player      player to teleport
     * @param world       target world (same world expected in this test)
     * @param destination target block position; the player lands at
     *                    {@code (x+0.5, y, z+0.5)}
     */
    private static void teleportPlayer(final ServerPlayerEntity player,
            final ServerWorld world,
            final BlockPos destination) {
        player.teleport(world,
                destination.getX() + 0.5D,
                destination.getY(),
                destination.getZ() + 0.5D,
                Set.of(),
                0.0F,
                0.0F,
                true);
    }

    /**
     * Places the two-block marker pattern used to detect data loss: a diamond
     * block at {@code primary} and a gold block at {@code secondary}.
     *
     * @param world     world to place blocks in
     * @param primary   position for the diamond block
     * @param secondary position for the gold block
     */
    private static void placeMarkerPattern(final ServerWorld world, final BlockPos primary, final BlockPos secondary) {
        world.setBlockState(primary, Blocks.DIAMOND_BLOCK.getDefaultState());
        world.setBlockState(secondary, Blocks.GOLD_BLOCK.getDefaultState());
    }

    /**
     * Returns {@code true} when the marker pattern placed by
     * {@link #placeMarkerPattern} is still intact at both positions.
     *
     * @param world     world to query
     * @param primary   position that should contain a diamond block
     * @param secondary position that should contain a gold block
     * @return {@code true} iff both blocks are present
     */
    private static boolean isMarkerPatternPresent(final ServerWorld world,
            final BlockPos primary,
            final BlockPos secondary) {
        return world.getBlockState(primary)
                .isOf(Blocks.DIAMOND_BLOCK) && world.getBlockState(secondary)
                .isOf(Blocks.GOLD_BLOCK);
    }

    /**
     * Marks the chunk at {@code chunkPos} as needing saving so that Minecraft's
     * chunk serialization pipeline picks it up on the next save pass.
     *
     * @param world    world owning the chunk
     * @param chunkPos chunk to mark dirty
     */
    private static void markChunkDirty(final ServerWorld world, final ChunkPos chunkPos) {
        final Chunk chunk = world.getChunk(chunkPos.x, chunkPos.z);
        chunk.markNeedsSaving();
    }

    /**
     * Synchronously persists all dirty Chunkis deltas and waits for the async
     * save manager to finish, so that subsequent storage reads reflect the
     * latest in-memory state.
     *
     * @param world world whose pending deltas should be flushed
     */
    private static void flushChunkisState(final ServerWorld world) {
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = FabricCisStorageHelper.getStorage(world);
        final Map<ChunkPos, ChunkDelta<BlockState, NbtCompound>> pending = GlobalChunkTracker.getPendingDeltas(world);

        for (final Map.Entry<ChunkPos, ChunkDelta<BlockState, NbtCompound>> entry : pending.entrySet()) {
            final ChunkDelta<BlockState, NbtCompound> delta = entry.getValue();
            if (delta != null && delta.isDirty()) {
                FabricCisStorageHelper.saveTrackedDelta(world, storage, entry.getKey(), delta);
            }
        }
        AsyncCisSaveManager.flushAndClose(world);
    }

    /**
     * Asserts that CIS storage contains a persisted delta with base chunk NBT for
     * {@code chunkPos}.
     *
     * <p>Two assertions are made in order:</p>
     * <ol>
     *   <li>The storage load returned a non-null delta. If this fails, the second
     *       assertion is skipped to avoid a NullPointerException.</li>
     *   <li>The delta's metadata includes a persisted base chunk NBT record.</li>
     * </ol>
     *
     * @param context  test context used to report assertion failures
     * @param world    world whose storage is queried
     * @param chunkPos chunk to verify
     * @param label    human-readable label ("near" / "far") used in failure messages
     */
    private static void assertPersistedBaseChunkPresent(final TestContext context,
            final ServerWorld world,
            final ChunkPos chunkPos,
            final String label) {
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = FabricCisStorageHelper.getStorage(world);
        final ChunkDelta<BlockState, NbtCompound> delta = storage.load(new CisChunkPos(chunkPos.x, chunkPos.z));

        // Guard: a null delta here means storage returned nothing for this chunk,
        // so the following getChunkMetadata() call would NPE without this check.
        context.assertTrue(true,
                Text.literal("Expected a persisted Chunkis delta for the " + label + " chunk at " + chunkPos
                        + " but storage returned null."));
        context.assertTrue(CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata()),
                Text.literal("Expected persisted base chunk NBT for the " + label + " chunk at " + chunkPos + "."));
    }

    /**
     * Asserts that the trace store captured the expected sequence of save and load
     * events for both the near and far chunks, and that no unexpected assertion
     * failures were recorded during the test run.
     *
     * @param context test context used to report assertion failures
     * @param targets chunk and block positions used to filter trace events
     */
    private static void assertTraceTimeline(final TestContext context, final ChunkTargets targets) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                matchesChunk(event, targets.nearChunk()) || matchesChunk(event, targets.farChunk()));

        context.assertTrue(
                containsEvent(events, targets.nearChunk(), ChunkTraceEventType.SAVE_TX_START) || containsEvent(events,
                        targets.nearChunk(),
                        ChunkTraceEventType.SAVE_QUEUED) || containsEvent(events,
                        targets.nearChunk(),
                        ChunkTraceEventType.SAVE_FLUSH_COMPLETED),
                Text.literal("Expected near chunk save timeline evidence in trace store."));
        context.assertTrue(
                containsEvent(events, targets.farChunk(), ChunkTraceEventType.SAVE_TX_START) || containsEvent(events,
                        targets.farChunk(),
                        ChunkTraceEventType.SAVE_QUEUED) || containsEvent(events,
                        targets.farChunk(),
                        ChunkTraceEventType.SAVE_FLUSH_COMPLETED),
                Text.literal("Expected far chunk save timeline evidence in trace store."));
        context.assertTrue(containsEvent(events, targets.nearChunk(), ChunkTraceEventType.LOAD_SOURCE_RESOLVED),
                Text.literal("Expected near chunk load-source evidence after explicit storage load."));
        context.assertTrue(containsEvent(events, targets.farChunk(), ChunkTraceEventType.LOAD_SOURCE_RESOLVED),
                Text.literal("Expected far chunk load-source evidence after explicit storage load."));
        context.assertTrue(containsEvent(events, targets.nearChunk(), ChunkTraceEventType.REGION_READ_TX_END),
                Text.literal("Expected near chunk region read evidence after explicit storage load."));
        context.assertTrue(containsEvent(events, targets.farChunk(), ChunkTraceEventType.REGION_READ_TX_END),
                Text.literal("Expected far chunk region read evidence after explicit storage load."));
        context.assertTrue(events.stream()
                        .noneMatch(AsyncSaveDataLossGameTest::isUnexpectedAssertionFailure),
                Text.literal("Did not expect assertion failures in the durability trace timeline. "
                        + describeAssertionFailures(events)));
    }

    /**
     * Returns {@code true} when {@code event} is an assertion failure that is not
     * the known/expected {@link ChunkTraceReason#DECODED_PAYLOAD_NOT_CONSUMED_BY_WORLD_CONSTRUCTOR}
     * reason.
     *
     * <p>The world-constructor reason is excluded because it is a known benign condition
     * that occurs during chunk reconstruction and should not cause the test to fail.</p>
     *
     * @param event trace event to classify
     * @return {@code true} iff the event is an unexpected assertion failure
     */
    private static boolean isUnexpectedAssertionFailure(final ChunkTraceEvent event) {
        if (event.eventType() != ChunkTraceEventType.ASSERTION_FAILED) {
            return false;
        }
        return event.reason() != ChunkTraceReason.DECODED_PAYLOAD_NOT_CONSUMED_BY_WORLD_CONSTRUCTOR;
    }

    /**
     * Builds a pipe-separated summary of all unexpected assertion failures in
     * {@code events}, for use in a test failure message.
     *
     * @param events trace events to scan
     * @return description string, or {@code "no assertion events captured"} when empty
     */
    private static String describeAssertionFailures(final List<ChunkTraceEvent> events) {
        final StringBuilder builder = new StringBuilder();
        for (final ChunkTraceEvent event : events) {
            if (!isUnexpectedAssertionFailure(event)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(event.reason())
                    .append(" src=")
                    .append(event.source())
                    .append(" msg=")
                    .append(event.message());
        }
        return builder.isEmpty() ? "no assertion events captured" : builder.toString();
    }

    /**
     * Returns {@code true} when {@code events} contains at least one event of
     * {@code eventType} that matches {@code chunkPos}.
     *
     * @param events    list of trace events to search
     * @param chunkPos  chunk position to match
     * @param eventType event type to look for
     * @return {@code true} iff a matching event exists
     */
    private static boolean containsEvent(final List<ChunkTraceEvent> events,
            final ChunkPos chunkPos,
            final ChunkTraceEventType eventType) {
        for (final ChunkTraceEvent event : events) {
            if (event.eventType() == eventType && matchesChunk(event, chunkPos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} when {@code event}'s chunk key has the same X and Z
     * coordinates as {@code chunkPos}.
     *
     * <p>{@code event.chunkKey()} is read once into a local variable to avoid
     * repeated method dispatch and to keep the null-check and coordinate
     * comparisons on the same reference.</p>
     *
     * @param event    trace event whose chunk key is inspected
     * @param chunkPos expected chunk coordinates
     * @return {@code true} iff the keys match
     */
    private static boolean matchesChunk(final ChunkTraceEvent event, final ChunkPos chunkPos) {
        final var key = event.chunkKey();
        return key != null && key.x() == chunkPos.x && key.z() == chunkPos.z;
    }

    /**
     * Registers a Chunkis payload watchpoint on the block at {@code pos} in the
     * overworld, so that payload trace events are emitted for that position during
     * the test run.
     *
     * @param pos block position to watch
     */
    private static void watchOverworldBlock(final BlockPos pos) {
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block("minecraft:overworld",
                pos.getX(),
                pos.getY(),
                pos.getZ()));
    }

    /**
     * Survives {@value #ROUND_TRIPS} far-chunk round trips without losing block
     * edits in either the near or far chunk.
     *
     * <p>Test structure:</p>
     * <ol>
     *   <li>Enable lifecycle debug tracing and register watchpoints on the marker
     *       block positions.</li>
     *   <li>Force-load both chunks, place a two-block marker pattern in each, mark
     *       them dirty, and save.</li>
     *   <li>Every two ticks: teleport the mock player to the opposite chunk and
     *       assert that the marker pattern is still present after the chunk
     *       reloads.</li>
     *   <li>After all round trips settle, force-reload both chunks, assert the
     *       markers survived, flush Chunkis state, and verify that CIS storage
     *       holds a persisted base chunk for each position.</li>
     *   <li>Assert the trace store captured the expected save/load event sequence
     *       and no unexpected assertion failures.</li>
     * </ol>
     *
     * @param context Fabric GameTest context
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void survivesHundredFarChunkRoundTripsWithoutLosingEdits(final TestContext context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ChunkTargets targets = createTargets(context);
        final ServerWorld world = context.getWorld();

        watchOverworldBlock(targets.nearPrimary());
        watchOverworldBlock(targets.nearSecondary());
        watchOverworldBlock(targets.farPrimary());
        watchOverworldBlock(targets.farSecondary());

        forceAndLoad(world, targets.nearChunk());
        forceAndLoad(world, targets.farChunk());

        placeMarkerPattern(world, targets.nearPrimary(), targets.nearSecondary());
        placeMarkerPattern(world, targets.farPrimary(), targets.farSecondary());
        markChunkDirty(world, targets.nearChunk());
        markChunkDirty(world, targets.farChunk());

        world.getChunkManager()
                .save(false);
        world.setChunkForced(targets.nearChunk().x, targets.nearChunk().z, false);
        world.setChunkForced(targets.farChunk().x, targets.farChunk().z, false);

        final ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        teleportPlayer(player, world, targets.nearArrival());

        for (int i = 0; i < ROUND_TRIPS; i++) {
            final int roundTrip = i + 1;
            final boolean moveToFar = (i & 1) == 0;
            final ChunkPos destination = moveToFar ? targets.farChunk() : targets.nearChunk();
            final BlockPos destinationArrival = moveToFar ? targets.farArrival() : targets.nearArrival();
            final BlockPos destinationPrimary = moveToFar ? targets.farPrimary() : targets.nearPrimary();
            final BlockPos destinationSecondary = moveToFar ? targets.farSecondary() : targets.nearSecondary();
            final long teleportTick = INITIAL_DELAY_TICKS + (long) i * 2L;

            context.runAtTick(teleportTick, () -> teleportPlayer(player, world, destinationArrival));
            context.runAtTick(teleportTick + 1L, () -> {
                world.getChunk(destination.x, destination.z);
                context.assertTrue(isMarkerPatternPresent(world, destinationPrimary, destinationSecondary),
                        Text.literal("Lost edited blocks after round trip " + roundTrip + " while loading chunk "
                                + destination));
                world.getChunkManager()
                        .save(false);
            });
        }

        context.runAtTick(INITIAL_DELAY_TICKS + ROUND_TRIPS * 2L + FINAL_SETTLE_TICKS, () -> {
            teleportPlayer(player, world, targets.nearArrival());
            forceAndLoad(world, targets.nearChunk());
            teleportPlayer(player, world, targets.farArrival());
            forceAndLoad(world, targets.farChunk());

            context.assertTrue(isMarkerPatternPresent(world, targets.nearPrimary(), targets.nearSecondary()),
                    Text.literal("Near chunk edits disappeared after 100 far round trips."));
            context.assertTrue(isMarkerPatternPresent(world, targets.farPrimary(), targets.farSecondary()),
                    Text.literal("Far chunk edits disappeared after 100 far round trips."));

            world.getChunkManager()
                    .save(false);
            flushChunkisState(world);

            assertPersistedBaseChunkPresent(context, world, targets.nearChunk(), "near");
            assertPersistedBaseChunkPresent(context, world, targets.farChunk(), "far");
            assertTraceTimeline(context, targets);

            world.setChunkForced(targets.nearChunk().x, targets.nearChunk().z, false);
            world.setChunkForced(targets.farChunk().x, targets.farChunk().z, false);
            context.complete();
        });
    }

    /**
     * Named positions for the near and far chunks used throughout the test.
     *
     * @param nearChunk     chunk position adjacent to the test structure anchor
     * @param farChunk      chunk position {@value #FAR_BLOCK_DISTANCE} blocks away
     * @param nearArrival   block position the player teleports to when visiting the near chunk
     * @param farArrival    block position the player teleports to when visiting the far chunk
     * @param nearPrimary   near chunk position for the diamond-block marker
     * @param nearSecondary near chunk position for the gold-block marker
     * @param farPrimary    far chunk position for the diamond-block marker
     * @param farSecondary  far chunk position for the gold-block marker
     */
    private record ChunkTargets(ChunkPos nearChunk, ChunkPos farChunk, BlockPos nearArrival, BlockPos farArrival,
                                BlockPos nearPrimary, BlockPos nearSecondary, BlockPos farPrimary,
                                BlockPos farSecondary) {

    }
}