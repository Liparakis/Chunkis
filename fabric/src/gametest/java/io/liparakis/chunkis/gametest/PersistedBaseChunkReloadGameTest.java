package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureUtil;
import io.liparakis.chunkis.world.restoration.capture.CisSnapshotCapture;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.chunk.WorldChunk;

@SuppressWarnings("unused")
public final class PersistedBaseChunkReloadGameTest {

    /**
     * Bounding box offset used to teleport mock players and unload target chunks.
     * Moving 20,000 blocks away guarantees the chunk is completely out of player render distance
     * and eligible for unload.
     */
    private static final int FAR_BLOCK_DISTANCE = 20_000;

    /**
     * Identifier for the Minecraft overworld dimension.
     */
    private static final String OVERWORLD_ID = "minecraft:overworld";

    /**
     * Seeds the specified positions with a distinct marker block pattern (Diamond, Gold, Chest).
     * This pattern is used to verify that chunk restoration successfully reconstructs block grid state.
     *
     * @param world     the server world
     * @param primary   the position for the diamond block
     * @param secondary the position for the gold block
     * @param chestPos  the position for the chest
     */
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

    /**
     * Checks if the marker block pattern (Diamond, Gold, Chest) is present at the specified positions.
     *
     * @param world     the server world
     * @param primary   the expected position of the diamond block
     * @param secondary the expected position of the gold block
     * @param chestPos  the expected position of the chest
     * @return true if the blocks match the expected pattern
     */
    private static boolean isMarkerPatternPresent(
            final ServerWorld world,
            final BlockPos primary,
            final BlockPos secondary,
            final BlockPos chestPos
    ) {
        return world.getBlockState(primary)
                .isOf(Blocks.DIAMOND_BLOCK)
                && world.getBlockState(secondary)
                .isOf(Blocks.GOLD_BLOCK)
                && world.getBlockState(chestPos)
                .isOf(Blocks.CHEST);
    }

    /**
     * Teleports a player to a specific destination in the server world.
     *
     * @param player      the player entity to teleport
     * @param world       the target server world
     * @param destination the destination block position
     */
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

    /**
     * Forces a chunk to remain loaded and requests it from the world chunk manager.
     *
     * @param world    the server world
     * @param chunkPos the position of the chunk to force load
     */
    private static void forceAndLoad(final ServerWorld world, final ChunkPos chunkPos) {
        world.setChunkForced(chunkPos.x, chunkPos.z, true);
        world.getChunk(chunkPos.x, chunkPos.z);
    }

    /**
     * Computes a ChunkPos offset from an anchor block position.
     * This helper avoids duplicate creation of ChunkPos objects when calculating target test coordinates.
     *
     * @param anchor the starting anchor block position
     * @param offset the chunk offset to apply to both X and Z coordinates
     * @return the offset ChunkPos
     */
    private static ChunkPos getOffsetChunkPos(final BlockPos anchor, final int offset) {
        final ChunkPos anchorChunk = new ChunkPos(anchor);
        return new ChunkPos(anchorChunk.x + offset, anchorChunk.z + offset);
    }

    /**
     * Creates an {@link AirDeletionScenario} container at the specified chunk offset from the test anchor.
     *
     * @param context     the game test context
     * @param chunkOffset the chunk offset from the anchor position
     * @return the initialized scenario
     */
    private static AirDeletionScenario createAirDeletionScenario(
            final TestContext context,
            final int chunkOffset
    ) {
        final ServerWorld world = context.getWorld();
        final BlockPos anchor = context.getAbsolutePos(BlockPos.ORIGIN);
        final ChunkPos targetChunk = getOffsetChunkPos(anchor, chunkOffset);
        final BlockPos targetArrival = targetChunk.getBlockPos(8, 100, 8);
        return new AirDeletionScenario(
                world,
                targetChunk,
                targetArrival,
                targetArrival.add(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE),
                targetChunk.getBlockPos(8, 64, 8)
        );
    }

    /**
     * Initializes an {@link AirDeletionTestSetup} by preparing the scenario, registering block watchpoints,
     * force-loading the chunk, and seeding the primary position with a diamond block.
     *
     * @param context     the game test context
     * @param chunkOffset the chunk offset from the anchor position
     * @return the prepared test setup
     */
    private static AirDeletionTestSetup createAirDeletionTestSetup(
            final TestContext context,
            final int chunkOffset
    ) {
        final AirDeletionScenario scenario = createAirDeletionScenario(context, chunkOffset);
        watchBlocks(scenario.primary());
        forceAndLoad(scenario.world(), scenario.targetChunk());
        scenario.world()
                .setBlockState(scenario.primary(), Blocks.DIAMOND_BLOCK.getDefaultState());
        return new AirDeletionTestSetup(
                scenario,
                (ServerPlayerEntity) context.createMockPlayer(GameMode.CREATIVE)
        );
    }

    /**
     * Registers trace watchpoints for the specified block positions in the overworld.
     *
     * @param positions the block positions to watch
     */
    private static void watchBlocks(final BlockPos... positions) {
        for (final BlockPos pos : positions) {
            ChunkTraceWatchpoints.watchPayload(
                    PayloadWatchTarget.block(OVERWORLD_ID, pos.getX(), pos.getY(), pos.getZ())
            );
        }
    }

    /**
     * Registers a trace watchpoint for the specified entity UUID in the overworld.
     *
     * @param entityUuid the UUID of the entity to watch
     */
    private static void watchEntity(final UUID entityUuid) {
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.entity(OVERWORLD_ID, entityUuid.toString()));
    }

    /**
     * Spawns a test pig entity with configured gravity, AI, and invulnerability settings.
     *
     * @param context     the game test context
     * @param world       the server world
     * @param targetChunk the chunk position where the pig should be placed
     * @return the configured PigEntity, or null if creation fails
     */
    private static PigEntity createConfiguredPig(
            final TestContext context,
            final ServerWorld world,
            final ChunkPos targetChunk
    ) {
        final PigEntity pig = EntityType.PIG.create(world, SpawnReason.COMMAND);
        if (pig == null) {
            context.throwGameTestException(Text.literal("Failed to create pig entity."));
            return null;
        }
        pig.refreshPositionAndAngles(
                targetChunk.getStartX() + 14.5D,
                115.0D,
                targetChunk.getStartZ() + 4.5D,
                0.0F,
                0.0F
        );
        pig.setAiDisabled(true);
        pig.setNoGravity(true);
        pig.setInvulnerable(true);
        return pig;
    }

    /**
     * Checks if there is a valid chest block entity cached and present at the specified position.
     *
     * @param world    the server world
     * @param chestPos the chest block position
     * @return true if a chest block entity matches and is present
     */
    private static boolean hasMatchingChestBlockEntity(
            final ServerWorld world,
            final BlockPos chestPos
    ) {
        final BlockEntity blockEntity = world.getBlockEntity(chestPos);
        return world.getBlockState(chestPos)
                .isOf(Blocks.CHEST)
                && blockEntity instanceof ChestBlockEntity
                && blockEntity.getCachedState()
                .isOf(Blocks.CHEST);
    }

    /**
     * Checks if a ChunkDelta has a recorded block instruction at the specified coordinates.
     *
     * @param delta the chunk delta containing instructions
     * @param pos   the target block position
     * @return true if the delta contains a block change for the local coordinates of the position
     */
    private static boolean containsBlockInstructionAt(
            final ChunkDelta<BlockState, NbtCompound> delta,
            final BlockPos pos
    ) {
        final int localX = pos.getX() & 15;
        final int localZ = pos.getZ() & 15;
        final boolean[] found = {false};
        delta.forEachBlock((x, y, z, state) -> {
            if (x == localX && y == pos.getY() && z == localZ) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * Helper to verify if a ChunkTraceEvent is associated with the given ChunkPos.
     *
     * @param event    the trace event to check
     * @param chunkPos the target chunk position
     * @return true if the event's chunk key matches the target chunk position
     */
    private static boolean isEventForChunk(final ChunkTraceEvent event, final ChunkPos chunkPos) {
        return event.chunkKey() != null
                && event.chunkKey()
                .x() == chunkPos.x
                && event.chunkKey()
                .z() == chunkPos.z;
    }

    /**
     * Checks if the trace store contains a BASE_NBT_APPLIED event for the given chunk.
     *
     * @param chunkPos the chunk position
     * @return true if the event exists in the store
     */
    private static boolean containsBaseChunkAppliedTrace(final ChunkPos chunkPos) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                isEventForChunk(event, chunkPos)
                        && event.eventType()
                        == ChunkTraceEventType.BASE_NBT_APPLIED
        );
        return !events.isEmpty();
    }

    /**
     * Checks if the trace store contains a MUTATION_ACCEPTED_REAL_EDIT event for the given chunk.
     *
     * @param chunkPos the chunk position
     * @return true if the event exists in the store
     */
    private static boolean containsUnexpectedRealEditTrace(final ChunkPos chunkPos) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                isEventForChunk(event, chunkPos)
                        && event.eventType()
                        == ChunkTraceEventType.MUTATION_ACCEPTED_REAL_EDIT
        );
        return !events.isEmpty();
    }

    /**
     * Returns a natural jungle leaves state with a stable distance value.
     *
     * <p>Real generated jungle leaves are not persistent. Using natural leaves
     * keeps this test on the same decay path as worldgen trees.</p>
     *
     * @param distance leaf distance to nearest log
     * @return configured jungle leaves block state
     */
    private static BlockState naturalJungleLeaves(final int distance) {
        return Blocks.JUNGLE_LEAVES.getDefaultState()
                .with(LeavesBlock.PERSISTENT, false)
                .with(LeavesBlock.DISTANCE, distance);
    }

    /**
     * Returns whether the loaded chunk currently carries restorable Chunkis state.
     *
     * @param chunk loaded world chunk
     * @return true if a live Chunkis delta is attached and restorable
     */
    private static boolean hasAttachedRestorableDelta(final WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck duck)
                || !(duck.chunkis$getDelta() instanceof ChunkDelta<?, ?> delta)) {
            return false;
        }
        return ChunkDeltaOwnership.hasRestorableChunkisState(delta);
    }

    /**
     * Describes the live delta shape for reload assertions.
     *
     * @param chunk loaded world chunk, may be {@code null}
     * @return compact live delta description
     */
    private static String describeAttachedDelta(final WorldChunk chunk) {
        if (chunk == null) {
            return "chunk=null";
        }
        if (!(chunk instanceof ChunkisDeltaDuck duck)) {
            return "duck=false";
        }
        if (!(duck.chunkis$getDelta() instanceof ChunkDelta<?, ?> delta)) {
            return "delta=null";
        }
        return "restorable=" + ChunkDeltaOwnership.hasRestorableChunkisState(delta)
                + ", owned=" + ChunkDeltaOwnership.hasChunkisOwnedState(delta)
                + ", blocks=" + delta.getBlockChangesCount()
                + ", hasBase=" + CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata())
                + ", full=" + CisNbtUtil.hasFullBlockBaseline(delta.getChunkMetadata());
    }

    /**
     * Describes a standalone delta for storage round-trip assertions.
     *
     * @param delta chunk delta, may be {@code null}
     * @return compact delta description
     */
    private static String describeDelta(final ChunkDelta<?, ?> delta) {
        if (delta == null) {
            return "delta=null";
        }
        return "restorable=" + ChunkDeltaOwnership.hasRestorableChunkisState(delta)
                + ", owned=" + ChunkDeltaOwnership.hasChunkisOwnedState(delta)
                + ", blocks=" + delta.getBlockChangesCount()
                + ", hasBase=" + CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata())
                + ", full=" + CisNbtUtil.hasFullBlockBaseline(delta.getChunkMetadata());
    }

    /**
     * Checks if an entity with the specified UUID is currently alive and active in the chunk.
     * Checks both the world's entities list and a localized boundary search box.
     *
     * @param world    the server world
     * @param chunkPos the chunk position
     * @param uuid     the entity UUID
     * @return true if the entity is present and active
     */
    private static boolean hasEntityWithUuid(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final UUID uuid
    ) {
        for (final var entity : world.iterateEntities()) {
            if (uuid.equals(entity.getUuid()) && entity.getChunkPos()
                    .equals(chunkPos) && entity.isAlive()) {
                return true;
            }
        }
        return !world.getOtherEntities(
                        null,
                        new Box(
                                chunkPos.getStartX(),
                                world.getBottomY(),
                                chunkPos.getStartZ(),
                                chunkPos.getEndX() + 1,
                                world.getBottomY() + world.getHeight(),
                                chunkPos.getEndZ() + 1
                        ),
                        entity -> uuid.equals(entity.getUuid())
                )
                .isEmpty();
    }

    /**
     * Constructs a string description of a pig entity's current state for debugging.
     *
     * @param world    the server world
     * @param chunkPos the chunk position
     * @param pig      the pig entity
     * @param uuid     the target UUID
     * @return the string state description
     */
    private static String describeEntityState(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final PigEntity pig,
            final UUID uuid
    ) {
        return "removed=" + pig.isRemoved()
                + ", alive=" + pig.isAlive()
                + ", chunk=" + pig.getChunkPos()
                + ", pos=" + pig.getBlockPos()
                + ", targetChunkLoaded=" + (world.getChunkManager()
                .getWorldChunk(chunkPos.x, chunkPos.z, false)
                != null)
                + ", queryVisible=" + hasEntityWithUuid(world, chunkPos, uuid);
    }

    /**
     * Asserts that the stored ChunkDelta in the CIS database contains the entity with the specified UUID.
     *
     * @param context    the game test context
     * @param world      the server world
     * @param chunkPos   the chunk position
     * @param entityUuid the entity UUID to verify
     * @param stage      description of the current test stage for logging
     */
    private static void assertStoredDeltaContainsEntity(
            final TestContext context,
            final ServerWorld world,
            final ChunkPos chunkPos,
            final UUID entityUuid,
            final String stage
    ) {
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);
        final ChunkDelta<BlockState, NbtCompound> storedDelta =
                storage.load(new CisChunkPos(chunkPos.x, chunkPos.z));
        context.assertTrue(
                containsEntityUuid(storedDelta, entityUuid),
                Text.literal("Stored delta was missing watched entity after " + stage
                        + ". Save trace: " + describeSaveTrace(chunkPos))
        );
    }

    /**
     * Checks if the given ChunkDelta contains an entity payload matching the specified UUID.
     * Leverages {@link EntityPayloadNbt#findUuid} to safely read the UUID field.
     *
     * @param delta      the chunk delta to inspect
     * @param entityUuid the entity UUID to match
     * @return true if the delta contains a matching entity payload
     */
    private static boolean containsEntityUuid(
            final ChunkDelta<BlockState, NbtCompound> delta,
            final UUID entityUuid
    ) {
        final boolean[] found = {false};
        delta.forEachEntity(entityNbt -> {
            if (!found[0] && entityNbt != null) {
                found[0] = EntityPayloadNbt.findUuid(entityNbt)
                        .map(entityUuid::equals)
                        .orElse(false);
            }
        });
        return found[0];
    }

    /**
     * Formats a {@link ChunkTraceEvent} into a human-readable string for debugging and assertions.
     *
     * @param event the trace event
     * @return formatted string
     */
    private static String formatTraceEvent(final ChunkTraceEvent event) {
        final StringBuilder sb = new StringBuilder();
        sb.append(event.eventType()
                .name());
        if (event.payloadWatchStage() != null) {
            sb.append('@')
                    .append(event.payloadWatchStage());
        }
        if (event.reason() != null) {
            sb.append(" reason=")
                    .append(event.reason());
        }
        if (event.source() != null) {
            sb.append(" src=")
                    .append(event.source());
        }
        if (event.message() != null) {
            sb.append(" msg=")
                    .append(event.message());
        }
        return sb.toString();
    }

    /**
     * Compiles a debug timeline string of all trace events matching the watched entity UUID.
     *
     * @param entityUuid the UUID of the watched entity
     * @return a formatted timeline string
     */
    private static String describeEntityTimeline(final UUID entityUuid) {
        final String uuidStr = entityUuid.toString();
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                event.payloadWatchTarget() != null
                        && uuidStr
                        .equals(event.payloadWatchTarget()
                                .entityUuid())
        );
        if (events.isEmpty()) {
            return "no watched entity events";
        }

        final StringBuilder builder = new StringBuilder();
        for (final ChunkTraceEvent event : events) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(formatTraceEvent(event));
        }
        return builder.toString();
    }

    /**
     * Compiles a debug timeline of save-related trace events for the given chunk.
     *
     * @param chunkPos the chunk position
     * @return a formatted timeline string
     */
    private static String describeSaveTrace(final ChunkPos chunkPos) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                isEventForChunk(event, chunkPos)
                        && (
                        "ThreadedAnvilChunkStorageMixin#chunkis$captureLiveEntities".equals(event.source())
                                || "ThreadedAnvilChunkStorageMixin#chunkis$onSave".equals(event.source())
                                || "ServerWorldMixin#chunkis$afterSpawnEntity".equals(event.source())
                                || "WorldChunkMixin#addEntity".equals(event.source()))
        );
        if (events.isEmpty()) {
            return "no save trace events";
        }

        final StringBuilder builder = new StringBuilder();
        for (final ChunkTraceEvent event : events) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(formatTraceEvent(event));
        }
        return builder.toString();
    }

    /**
     * Compiles a debug timeline of load-related trace events for the given chunk.
     *
     * @param chunkPos the chunk position
     * @return a formatted timeline string
     */
    private static String describeLoadTrace(final ChunkPos chunkPos) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                isEventForChunk(event, chunkPos)
                        && (event.eventType()
                        == ChunkTraceEventType.LOAD_SOURCE_RESOLVED
                        || event.eventType()
                        == ChunkTraceEventType.LOAD_TX_END
                        || event.eventType()
                        == ChunkTraceEventType.RESTORE_COMPLETED
                        || event.eventType()
                        == ChunkTraceEventType.BASE_NBT_APPLIED
                        || event.eventType()
                        == ChunkTraceEventType.CHUNKIS_OWNERSHIP_DECISION)
        );
        if (events.isEmpty()) {
            return "no load trace events";
        }

        final StringBuilder builder = new StringBuilder();
        for (final ChunkTraceEvent event : events) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(formatTraceEvent(event));
        }
        return builder.toString();
    }

    /**
     * Verifies that reloading a persisted entity chunk successfully restores the saved pig entity.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 320)
    public void persistedEntityReloadRestoresPig(final TestContext context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ServerWorld world = context.getWorld();
        final BlockPos anchor = context.getAbsolutePos(BlockPos.ORIGIN);
        final ChunkPos targetChunk = getOffsetChunkPos(anchor, 660);
        final BlockPos targetArrival = targetChunk.getBlockPos(8, 115, 8);
        final BlockPos farArrival = targetArrival.add(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE);

        forceAndLoad(world, targetChunk);
        final PigEntity pig = createConfiguredPig(context, world, targetChunk);
        if (pig == null) {
            return;
        }
        final UUID pigUuid = pig.getUuid();
        watchEntity(pigUuid);
        final ServerPlayerEntity player = (ServerPlayerEntity) context.createMockPlayer(GameMode.CREATIVE);
        context.assertTrue(world.spawnEntity(pig), Text.literal("Expected pig spawn to succeed."));

        context.runAtTick(60, () -> {
            context.assertTrue(
                    pig.isAlive() && !pig.isRemoved() && pig.getChunkPos()
                            .equals(targetChunk),
                    Text.literal("Spawned pig was not alive in the target chunk before save. State: "
                            + describeEntityState(world, targetChunk, pig, pigUuid))
            );
            teleportPlayer(player, world, targetArrival);
            world.getChunkManager()
                    .save(false);
            AsyncCisSaveManager.flushAndClose(world);
            assertStoredDeltaContainsEntity(context, world, targetChunk, pigUuid, "initial persisted pig save");
            world.setChunkForced(targetChunk.x, targetChunk.z, false);
        });

        context.runAtTick(85, () -> teleportPlayer(player, world, farArrival));
        context.runAtTick(110, () -> context.assertTrue(
                world.getChunkManager()
                        .getWorldChunk(targetChunk.x, targetChunk.z, false) == null,
                Text.literal("Target chunk never unloaded before reload attempt. Load trace: "
                        + describeLoadTrace(targetChunk))
        ));
        context.runAtTick(130, () -> {
            teleportPlayer(player, world, targetArrival);
            world.setChunkForced(targetChunk.x, targetChunk.z, true);
            world.getChunk(targetChunk.x, targetChunk.z);
        });
        context.runAtTick(170, () -> {
            context.assertTrue(
                    hasEntityWithUuid(world, targetChunk, pigUuid),
                    Text.literal("Reloaded chunk did not restore the persisted pig entity. Timeline: "
                            + describeEntityTimeline(pigUuid)
                            + ". State: " + describeEntityState(world, targetChunk, pig, pigUuid)
                            + ". Load trace: " + describeLoadTrace(targetChunk))
            );
            world.setChunkForced(targetChunk.x, targetChunk.z, false);
            context.complete();
        });
    }

    /**
     * Verifies that deleting a block to air inside a restored chunk survives a subsequent chunk reload cycle.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 220)
    public void restoredChunkAirDeletionSurvivesSecondReload(final TestContext context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final AirDeletionTestSetup setup = createAirDeletionTestSetup(context, 640);
        final AirDeletionScenario scenario = setup.scenario();
        final ServerWorld world = scenario.world();
        final ChunkPos targetChunk = scenario.targetChunk();
        final BlockPos targetArrival = scenario.targetArrival();
        final BlockPos farArrival = scenario.farArrival();
        final BlockPos primary = scenario.primary();
        final ServerPlayerEntity player = setup.player();
        teleportPlayer(player, world, targetArrival);
        world.getChunkManager()
                .save(false);
        AsyncCisSaveManager.flushAndClose(world);
        world.setChunkForced(targetChunk.x, targetChunk.z, false);

        context.runAtTick(20, () -> teleportPlayer(player, world, farArrival));
        context.runAtTick(50, () -> {
            teleportPlayer(player, world, targetArrival);
            world.getChunk(targetChunk.x, targetChunk.z);
            context.assertTrue(
                    world.getBlockState(primary)
                            .isOf(Blocks.DIAMOND_BLOCK),
                    Text.literal("Expected first reload to restore the original block before deletion.")
            );
            world.setBlockState(primary, Blocks.AIR.getDefaultState());
            context.assertTrue(
                    world.getBlockState(primary)
                            .isAir(),
                    Text.literal("Expected watched block to be air immediately after deletion on restored chunk.")
            );
            world.getChunkManager()
                    .save(false);
            AsyncCisSaveManager.flushAndClose(world);
            world.setChunkForced(targetChunk.x, targetChunk.z, false);
        });
        context.runAtTick(90, () -> teleportPlayer(player, world, farArrival));
        context.runAtTick(130, () -> {
            teleportPlayer(player, world, targetArrival);
            world.getChunk(targetChunk.x, targetChunk.z);
        });
        context.runAtTick(150, () -> {
            context.assertTrue(
                    world.getBlockState(primary)
                            .isAir(),
                    Text.literal("Reloaded restored chunk resurrected a block that had been deleted to air.")
            );
            context.complete();
        });
    }

    /**
     * Verifies that deleting a block to air in a persisted base chunk reload preserves the air deletion.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 120)
    public void persistedBaseChunkReloadPreservesAirDeletion(final TestContext context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final AirDeletionTestSetup setup = createAirDeletionTestSetup(context, 620);
        final AirDeletionScenario scenario = setup.scenario();

        scenario.world()
                .setBlockState(scenario.primary(), Blocks.AIR.getDefaultState());

        context.assertTrue(
                scenario.world()
                        .getBlockState(scenario.primary())
                        .isAir(),
                Text.literal("Expected watched block to be air immediately after deletion.")
        );

        teleportPlayer(setup.player(), scenario.world(), scenario.targetArrival());
        scenario.world()
                .getChunkManager()
                .save(false);
        AsyncCisSaveManager.flushAndClose(scenario.world());

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(scenario.world());
        final ChunkDelta<BlockState, NbtCompound> storedDelta =
                storage.load(new CisChunkPos(scenario.targetChunk().x, scenario.targetChunk().z));

        context.assertFalse(
                CisNbtUtil.hasPersistedBaseChunkNbt(storedDelta.getChunkMetadata())
                        && CisNbtUtil.hasFullBlockBaseline(storedDelta.getChunkMetadata())
                        && !containsBlockInstructionAt(storedDelta, scenario.primary()),
                Text.literal(
                        "Stored full-baseline delta kept base snapshot metadata while omitting the watched air deletion.")
        );

        scenario.world()
                .setChunkForced(scenario.targetChunk().x, scenario.targetChunk().z, false);

        context.runAtTick(20, () -> teleportPlayer(setup.player(), scenario.world(), scenario.farArrival()));
        context.runAtTick(50, () -> teleportPlayer(setup.player(), scenario.world(), scenario.targetArrival()));
        context.runAtTick(60, () -> {
            scenario.world()
                    .getChunk(scenario.targetChunk().x, scenario.targetChunk().z);

            context.assertTrue(
                    scenario.world()
                            .getBlockState(scenario.primary())
                            .isAir(),
                    Text.literal("Reloaded chunk resurrected a block that had been deleted to air.")
            );

            context.complete();
        });
    }

    /**
     * Verifies that reloading a persisted base chunk does not restore empty state but recovers correctly.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 120)
    public void persistedBaseChunkReloadDoesNotRestoreEmpty(final TestContext context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ServerWorld world = context.getWorld();
        final BlockPos anchor = context.getAbsolutePos(BlockPos.ORIGIN);
        // Move 10,000 blocks away to ensure it's not kept loaded by the gametest structure
        final ChunkPos targetChunk = getOffsetChunkPos(anchor, 600);
        final BlockPos targetArrival = targetChunk.getBlockPos(8, 100, 8);
        final BlockPos farArrival = targetArrival.add(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE);
        final BlockPos primary = targetChunk.getBlockPos(8, 64, 8);
        final BlockPos secondary = targetChunk.getBlockPos(9, 64, 8);
        final BlockPos chestPos = targetChunk.getBlockPos(10, 64, 8);

        watchBlocks(primary, secondary, chestPos);

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
                delta.getBlockInstructions()
                        .isEmpty(),
                Text.literal("Expected base capture to clear sparse block payloads.")
        );

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);
        storage.save(new CisChunkPos(targetChunk.x, targetChunk.z), delta);
        GlobalChunkTracker.forgetChunk(world, targetChunk);

        final ServerPlayerEntity player = (ServerPlayerEntity) context.createMockPlayer(GameMode.CREATIVE);
        teleportPlayer(player, world, targetArrival);
        world.getChunkManager()
                .save(false);
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

            context.complete();
        });
    }

    /**
     * Verifies that repeated full baseline reload churn does not accept unexpected edits and preserves the pig entity.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 300)
    public void fullBaselineReloadChurnDoesNotAcceptUnexpectedRealEditsOrLosePig(final TestContext context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ServerWorld world = context.getWorld();
        final BlockPos anchor = context.getAbsolutePos(BlockPos.ORIGIN);
        final ChunkPos targetChunk = getOffsetChunkPos(anchor, 700);
        final BlockPos targetArrival = targetChunk.getBlockPos(8, 115, 8);
        final BlockPos farArrival = targetArrival.add(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE);
        final BlockPos primary = targetChunk.getBlockPos(8, 64, 8);
        final BlockPos secondary = targetChunk.getBlockPos(9, 64, 8);
        final int churnCycles = 12;

        forceAndLoad(world, targetChunk);
        world.setBlockState(primary, Blocks.DIAMOND_BLOCK.getDefaultState());
        world.setBlockState(secondary, Blocks.GOLD_BLOCK.getDefaultState());

        final PigEntity pig = createConfiguredPig(context, world, targetChunk);
        if (pig == null) {
            return;
        }
        final UUID pigUuid = pig.getUuid();
        watchBlocks(primary, secondary);
        watchEntity(pigUuid);
        final ServerPlayerEntity player = (ServerPlayerEntity) context.createMockPlayer(GameMode.CREATIVE);
        context.assertTrue(world.spawnEntity(pig), Text.literal("Expected pig spawn to succeed."));

        context.runAtTick(5, () -> {
            context.assertTrue(
                    pig.isAlive() && !pig.isRemoved() && pig.getChunkPos()
                            .equals(targetChunk),
                    Text.literal("Spawned pig was not alive in the target chunk before churn seed save. State: "
                            + describeEntityState(world, targetChunk, pig, pigUuid))
            );
            teleportPlayer(player, world, targetArrival);
            world.getChunkManager()
                    .save(false);
            AsyncCisSaveManager.flushAndClose(world);
            assertStoredDeltaContainsEntity(context, world, targetChunk, pigUuid, "full-baseline churn seed save");
            world.setChunkForced(targetChunk.x, targetChunk.z, false);
        });

        for (int i = 0; i < churnCycles; i++) {
            final long cycleStart = 30L + (long) i * 14L;
            context.runAtTick(cycleStart, () -> teleportPlayer(player, world, farArrival));
            context.runAtTick(cycleStart + 4L, () -> teleportPlayer(player, world, targetArrival));
            context.runAtTick(cycleStart + 8L, () -> {
                world.getChunk(targetChunk.x, targetChunk.z);
                world.getChunkManager()
                        .save(false);
            });
        }

        context.runAtTick(30L + churnCycles * 14L + 30L, () -> {
            world.getChunk(targetChunk.x, targetChunk.z);
            context.assertTrue(
                    world.getBlockState(primary)
                            .isOf(Blocks.DIAMOND_BLOCK)
                            && world.getBlockState(secondary)
                            .isOf(Blocks.GOLD_BLOCK),
                    Text.literal("Repeated full-baseline churn changed restored marker blocks.")
            );
            if (world.canSpawnEntitiesAt(targetChunk)) {
                context.assertTrue(
                        hasEntityWithUuid(world, targetChunk, pigUuid),
                        Text.literal("Repeated full-baseline churn lost the persisted pig entity. Timeline: "
                                + describeEntityTimeline(pigUuid)
                                + ". State: " + describeEntityState(world, targetChunk, pig, pigUuid))
                );
            } else {
                assertStoredDeltaContainsEntity(
                        context,
                        world,
                        targetChunk,
                        pigUuid,
                        "full-baseline churn final non-entity-ready reload"
                );
            }
            context.assertFalse(
                    containsUnexpectedRealEditTrace(targetChunk),
                    Text.literal("Repeated full-baseline churn accepted post-restore real edits for the restored chunk.")
            );

            context.complete();
        });
    }

    /**
     * Verifies that a cross-chunk border fixture survives reload when one chunk
     * is stored as a full baseline snapshot and its neighbor is stored as a
     * base-backed sparse chunk.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 160)
    public void mixedBaselineNeighborReloadPreservesCrossChunkBorderFixture(final TestContext context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ServerWorld world = context.getWorld();
        final BlockPos anchor = context.getAbsolutePos(BlockPos.ORIGIN);
        final ChunkPos leftChunkPos = getOffsetChunkPos(anchor, 760);
        final ChunkPos rightChunkPos = new ChunkPos(leftChunkPos.x + 1, leftChunkPos.z);
        final BlockPos leftArrival = leftChunkPos.getBlockPos(14, 100, 8);
        final BlockPos farArrival = leftArrival.add(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE);
        final BlockPos leftLogBase = leftChunkPos.getBlockPos(15, 64, 8);
        final BlockPos leftLogTop = leftChunkPos.getBlockPos(15, 65, 8);
        final BlockPos leftLeaf = leftChunkPos.getBlockPos(15, 66, 8);
        final BlockPos rightLeafNear = rightChunkPos.getBlockPos(0, 66, 8);
        final BlockPos rightLeafFar = rightChunkPos.getBlockPos(1, 66, 8);

        watchBlocks(leftLogBase, leftLogTop, leftLeaf, rightLeafNear, rightLeafFar);
        forceAndLoad(world, leftChunkPos);
        forceAndLoad(world, rightChunkPos);

        world.setBlockState(leftLogBase, Blocks.JUNGLE_LOG.getDefaultState());
        world.setBlockState(leftLogTop, Blocks.JUNGLE_LOG.getDefaultState());
        world.setBlockState(leftLeaf, naturalJungleLeaves(1));
        world.setBlockState(rightLeafNear, naturalJungleLeaves(2));
        world.setBlockState(rightLeafFar, naturalJungleLeaves(2));

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);
        final WorldChunk leftChunk = world.getChunk(leftChunkPos.x, leftChunkPos.z);
        final WorldChunk rightChunk = world.getChunk(rightChunkPos.x, rightChunkPos.z);

        final ChunkDelta<BlockState, NbtCompound> leftDelta = new ChunkDelta<>(BlockState::isAir);
        BaseChunkCaptureUtil.captureBaseChunk(world, leftChunk, leftDelta);
        CisSnapshotCapture.capture(leftChunk, leftDelta, "gametest-mixed-border-left");
        storage.save(new CisChunkPos(leftChunkPos.x, leftChunkPos.z), leftDelta);

        final ChunkDelta<BlockState, NbtCompound> rightDelta = new ChunkDelta<>(BlockState::isAir);
        BaseChunkCaptureUtil.captureBaseChunk(world, rightChunk, rightDelta);
        storage.save(new CisChunkPos(rightChunkPos.x, rightChunkPos.z), rightDelta);
        final ChunkDelta<BlockState, NbtCompound> storedRightDelta =
                storage.load(new CisChunkPos(rightChunkPos.x, rightChunkPos.z), "gametest-mixed-border-right-readback");

        context.assertTrue(
                CisNbtUtil.hasPersistedBaseChunkNbt(leftDelta.getChunkMetadata())
                        && CisNbtUtil.hasFullBlockBaseline(leftDelta.getChunkMetadata()),
                Text.literal("Expected left chunk to persist as full-baseline snapshot.")
        );
        context.assertTrue(
                CisNbtUtil.hasPersistedBaseChunkNbt(rightDelta.getChunkMetadata())
                        && !CisNbtUtil.hasFullBlockBaseline(rightDelta.getChunkMetadata()),
                Text.literal("Expected right chunk to persist as base-backed sparse chunk.")
        );
        context.assertTrue(
                ChunkDeltaOwnership.hasRestorableChunkisState(storedRightDelta)
                        && CisNbtUtil.hasPersistedBaseChunkNbt(storedRightDelta.getChunkMetadata())
                        && !CisNbtUtil.hasFullBlockBaseline(storedRightDelta.getChunkMetadata()),
                Text.literal("Expected right base-backed sparse chunk to round-trip through storage: "
                        + describeDelta(storedRightDelta))
        );

        GlobalChunkTracker.forgetChunk(world, leftChunkPos);
        GlobalChunkTracker.forgetChunk(world, rightChunkPos);

        final ServerPlayerEntity player = (ServerPlayerEntity) context.createMockPlayer(GameMode.CREATIVE);
        teleportPlayer(player, world, leftArrival);
        world.setChunkForced(leftChunkPos.x, leftChunkPos.z, false);
        world.setChunkForced(rightChunkPos.x, rightChunkPos.z, false);

        context.runAtTick(20, () -> teleportPlayer(player, world, farArrival));

        context.runAtTick(50, () -> context.assertTrue(
                world.getChunkManager()
                        .getWorldChunk(leftChunkPos.x, leftChunkPos.z, false) == null
                        && world.getChunkManager()
                        .getWorldChunk(rightChunkPos.x, rightChunkPos.z, false) == null,
                Text.literal("Expected both border chunks to unload before reload.")
        ));

        context.runAtTick(70, () -> {
            world.setChunkForced(rightChunkPos.x, rightChunkPos.z, true);
            world.getChunk(rightChunkPos.x, rightChunkPos.z);
            world.scheduleBlockTick(rightLeafNear, Blocks.JUNGLE_LEAVES, 1);
        });

        context.runAtTick(90, () -> {
            final BlockState state = world.getBlockState(rightLeafNear);
            context.assertTrue(
                    state.isOf(Blocks.JUNGLE_LEAVES)
                            && state.get(LeavesBlock.DISTANCE) < LeavesBlock.MAX_DISTANCE,
                    Text.literal("Sparse-side natural leaf lost its log distance before neighbor reload: " + state)
            );
            teleportPlayer(player, world, leftArrival);
            world.setChunkForced(leftChunkPos.x, leftChunkPos.z, true);
            world.getChunk(leftChunkPos.x, leftChunkPos.z);
        });
        context.runAtTick(110, () -> {
            context.assertTrue(
                    world.getBlockState(leftLogBase)
                            .isOf(Blocks.JUNGLE_LOG)
                            && world.getBlockState(leftLogTop)
                            .isOf(Blocks.JUNGLE_LOG)
                            && world.getBlockState(leftLeaf)
                            .isOf(Blocks.JUNGLE_LEAVES)
                            && world.getBlockState(rightLeafNear)
                            .isOf(Blocks.JUNGLE_LEAVES)
                            && world.getBlockState(rightLeafFar)
                            .isOf(Blocks.JUNGLE_LEAVES),
                    Text.literal("Cross-chunk border fixture was cut at reload.")
            );

            final WorldChunk reloadedLeft = world.getChunkManager()
                    .getWorldChunk(leftChunkPos.x, leftChunkPos.z, false);
            final WorldChunk reloadedRight = world.getChunkManager()
                    .getWorldChunk(rightChunkPos.x, rightChunkPos.z, false);
            context.assertTrue(
                    hasAttachedRestorableDelta(reloadedLeft),
                    Text.literal("Reloaded left full-baseline chunk did not keep a live Chunkis delta attached: "
                            + describeAttachedDelta(reloadedLeft))
            );
            context.assertTrue(
                    hasAttachedRestorableDelta(reloadedRight),
                    Text.literal("Reloaded right base-backed chunk did not keep a live Chunkis delta attached: "
                            + describeAttachedDelta(reloadedRight))
            );

            world.setChunkForced(leftChunkPos.x, leftChunkPos.z, false);
            world.setChunkForced(rightChunkPos.x, rightChunkPos.z, false);
            context.complete();
        });
    }

    /**
     * Immutable container representing target chunk layout coordinates and arrival parameters for air deletion tests.
     *
     * @param world         the server world
     * @param targetChunk   the chunk under test
     * @param targetArrival arrival location inside the target chunk
     * @param farArrival    location far away from target chunk
     * @param primary       target block pos to watch and modify
     */
    private record AirDeletionScenario(
            ServerWorld world,
            ChunkPos targetChunk,
            BlockPos targetArrival,
            BlockPos farArrival,
            BlockPos primary
    ) {

    }

    /**
     * Immutable setup container combining an air deletion scenario with a mock player instance.
     *
     * @param scenario the air deletion scenario parameters
     * @param player   the spawned mock server player
     */
    private record AirDeletionTestSetup(
            AirDeletionScenario scenario,
            ServerPlayerEntity player
    ) {

    }
}
