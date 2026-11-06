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
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import java.util.List;
import java.util.UUID;

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
            final ServerLevel world,
            final BlockPos primary,
            final BlockPos secondary,
            final BlockPos chestPos
    ) {
        world.setBlockAndUpdate(primary, Blocks.DIAMOND_BLOCK.defaultBlockState());
        world.setBlockAndUpdate(secondary, Blocks.GOLD_BLOCK.defaultBlockState());
        world.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
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
            final ServerLevel world,
            final BlockPos primary,
            final BlockPos secondary,
            final BlockPos chestPos
    ) {
        return world.getBlockState(primary)
                .is(Blocks.DIAMOND_BLOCK)
                && world.getBlockState(secondary)
                .is(Blocks.GOLD_BLOCK)
                && world.getBlockState(chestPos)
                .is(Blocks.CHEST);
    }

    /**
     * Teleports a player to a specific destination in the server world.
     *
     * @param player      the player entity to teleport
     * @param world       the target server world
     * @param destination the destination block position
     */
    private static void teleportPlayer(
            final ServerPlayer player,
            final ServerLevel world,
            final BlockPos destination
    ) {
        player.teleportTo(
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
    private static void forceAndLoad(final ServerLevel world, final ChunkPos chunkPos) {
        world.setChunkForced(chunkPos.x(), chunkPos.z(), true);
        world.getChunk(chunkPos.x(), chunkPos.z());
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
        final ChunkPos anchorChunk = ChunkPos.containing(anchor);
        return new ChunkPos(anchorChunk.x() + offset, anchorChunk.z() + offset);
    }

    /**
     * Creates an {@link AirDeletionScenario} container at the specified chunk offset from the test anchor.
     *
     * @param context     the game test context
     * @param chunkOffset the chunk offset from the anchor position
     * @return the initialized scenario
     */
    private static AirDeletionScenario createAirDeletionScenario(
            final GameTestHelper context,
            final int chunkOffset
    ) {
        final ServerLevel world = context.getLevel();
        final BlockPos anchor = context.absolutePos(BlockPos.ZERO);
        final ChunkPos targetChunk = getOffsetChunkPos(anchor, chunkOffset);
        final BlockPos targetArrival = targetChunk.getBlockAt(8, 100, 8);
        return new AirDeletionScenario(
                world,
                targetChunk,
                targetArrival,
                targetArrival.offset(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE),
                targetChunk.getBlockAt(8, 64, 8)
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
            final GameTestHelper context,
            final int chunkOffset
    ) {
        final AirDeletionScenario scenario = createAirDeletionScenario(context, chunkOffset);
        watchBlocks(scenario.primary());
        forceAndLoad(scenario.world(), scenario.targetChunk());
        scenario.world()
                .setBlockAndUpdate(scenario.primary(), Blocks.DIAMOND_BLOCK.defaultBlockState());
        return new AirDeletionTestSetup(
                scenario,
                GameTestPlayers.createServerPlayer(context)
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
    private static Pig createConfiguredPig(
            final GameTestHelper context,
            final ServerLevel world,
            final ChunkPos targetChunk
    ) {
        final Pig pig = EntityType.PIG.create(world, EntitySpawnReason.COMMAND);
        if (pig == null) {
            context.fail(Component.literal("Failed to create pig entity."));
            return null;
        }
        pig.snapTo(
                targetChunk.getMinBlockX() + 14.5D,
                115.0D,
                targetChunk.getMinBlockZ() + 4.5D,
                0.0F,
                0.0F
        );
        pig.setNoAi(true);
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
            final ServerLevel world,
            final BlockPos chestPos
    ) {
        final BlockEntity blockEntity = world.getBlockEntity(chestPos);
        return world.getBlockState(chestPos)
                .is(Blocks.CHEST)
                && blockEntity instanceof ChestBlockEntity
                && blockEntity.getBlockState()
                .is(Blocks.CHEST);
    }

    /**
     * Checks if a ChunkDelta has a recorded block instruction at the specified coordinates.
     *
     * @param delta the chunk delta containing instructions
     * @param pos   the target block position
     * @return true if the delta contains a block change for the local coordinates of the position
     */
    private static boolean containsBlockInstructionAt(
            final ChunkDelta<BlockState, CompoundTag> delta,
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
                .x() == chunkPos.x()
                && event.chunkKey()
                .z() == chunkPos.z();
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
     * @param chunkPos     the chunk position
     * @param afterEventId only events with a larger id count
     * @return true if the event exists in the store
     */
    private static boolean containsUnexpectedRealEditTrace(final ChunkPos chunkPos, final long afterEventId) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                isEventForChunk(event, chunkPos)
                        && event.eventId() > afterEventId
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
        return Blocks.JUNGLE_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, false)
                .setValue(LeavesBlock.DISTANCE, distance);
    }

    /**
     * Returns whether the loaded chunk currently carries restorable Chunkis state.
     *
     * @param chunk loaded world chunk
     * @return true if a live Chunkis delta is attached and restorable
     */
    private static boolean hasAttachedRestorableDelta(final LevelChunk chunk) {
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
    private static String describeAttachedDelta(final LevelChunk chunk) {
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
            final ServerLevel world,
            final ChunkPos chunkPos,
            final UUID uuid
    ) {
        for (final var entity : world.getAllEntities()) {
            if (uuid.equals(entity.getUUID()) && entity.chunkPosition()
                    .equals(chunkPos) && entity.isAlive()) {
                return true;
            }
        }
        return !world.getEntities(
                        (net.minecraft.world.entity.Entity) null,
                        new AABB(
                                chunkPos.getMinBlockX(),
                                world.getMinY(),
                                chunkPos.getMinBlockZ(),
                                chunkPos.getMaxBlockX() + 1,
                                world.getMinY() + world.getHeight(),
                                chunkPos.getMaxBlockZ() + 1
                        ),
                        entity -> uuid.equals(entity.getUUID())
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
            final ServerLevel world,
            final ChunkPos chunkPos,
            final Pig pig,
            final UUID uuid
    ) {
        return "removed=" + pig.isRemoved()
                + ", alive=" + pig.isAlive()
                + ", chunk=" + pig.chunkPosition()
                + ", pos=" + pig.blockPosition()
                + ", targetChunkLoaded=" + (world.getChunkSource()
                .getChunk(chunkPos.x(), chunkPos.z(), false)
                != null)
                + ", queryVisible=" + hasEntityWithUuid(world, chunkPos, uuid);
    }

    /**
     * Reports whether CIS storage holds the entity for the chunk.
     *
     * @param world      world whose storage is read
     * @param chunkPos   chunk to load
     * @param entityUuid entity to look for
     * @return true when the stored delta contains the entity
     */
    private static boolean storedDeltaContainsEntity(final ServerLevel world, final ChunkPos chunkPos,
            final UUID entityUuid) {
        return containsEntityUuid(FabricCisStorageHelper.getStorage(world)
                .load(new CisChunkPos(chunkPos.x(), chunkPos.z())), entityUuid);
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
            final GameTestHelper context,
            final ServerLevel world,
            final ChunkPos chunkPos,
            final UUID entityUuid,
            final String stage
    ) {
        final CisStorage<Block, BlockState, Property<?>, CompoundTag> storage =
                FabricCisStorageHelper.getStorage(world);
        final ChunkDelta<BlockState, CompoundTag> storedDelta =
                storage.load(new CisChunkPos(chunkPos.x(), chunkPos.z()));
        context.assertTrue(
                containsEntityUuid(storedDelta, entityUuid),
                Component.literal("Stored delta was missing watched entity after " + stage
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
            final ChunkDelta<BlockState, CompoundTag> delta,
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
                        "ChunkSaveCoordinator#captureLiveEntities".equals(event.source())
                                || "ChunkSaveCoordinator#save".equals(event.source())
                                || "ServerWorldMixin#chunkis$afterSpawnEntity".equals(event.source()))
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
    public void persistedEntityReloadRestoresPig(final GameTestHelper context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ServerLevel world = context.getLevel();
        final BlockPos anchor = context.absolutePos(BlockPos.ZERO);
        final ChunkPos targetChunk = getOffsetChunkPos(anchor, 660);
        final BlockPos targetArrival = targetChunk.getBlockAt(8, 115, 8);
        final BlockPos farArrival = targetArrival.offset(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE);

        forceAndLoad(world, targetChunk);
        final Pig pig = createConfiguredPig(context, world, targetChunk);
        if (pig == null) {
            return;
        }
        final UUID pigUuid = pig.getUUID();
        watchEntity(pigUuid);
        final ServerPlayer player = GameTestPlayers.createServerPlayer(context);
        context.assertTrue(world.addFreshEntity(pig), Component.literal("Expected pig spawn to succeed."));

        context.runAtTickTime(60, () -> {
            context.assertTrue(
                    pig.isAlive() && !pig.isRemoved() && pig.chunkPosition()
                            .equals(targetChunk),
                    Component.literal("Spawned pig was not alive in the target chunk before save. State: "
                            + describeEntityState(world, targetChunk, pig, pigUuid))
            );
            teleportPlayer(player, world, targetArrival);
        });
        // Vanilla skips saving a chunk whose holder still has pending work (neighbour generation under
        // concurrent tests), so retry the save each tick instead of assuming one pass writes it.
        final java.util.concurrent.atomic.AtomicBoolean saved = new java.util.concurrent.atomic.AtomicBoolean();
        for (long tick = 60L; tick <= 80L; tick++) {
            context.runAtTickTime(tick, () -> {
                if (saved.get()) {
                    return;
                }
                world.getChunkSource()
                        .save(false);
                AsyncCisSaveManager.flushAndClose(world);
                if (storedDeltaContainsEntity(world, targetChunk, pigUuid)) {
                    saved.set(true);
                    world.setChunkForced(targetChunk.x(), targetChunk.z(), false);
                }
            });
        }
        context.runAtTickTime(81L, () -> assertStoredDeltaContainsEntity(context, world, targetChunk, pigUuid,
                "initial persisted pig save"));

        context.runAtTickTime(85, () -> teleportPlayer(player, world, farArrival));
        context.runAtTickTime(110, () -> context.assertTrue(
                world.getChunkSource()
                        .getChunk(targetChunk.x(), targetChunk.z(), false) == null,
                Component.literal("Target chunk never unloaded before reload attempt. Load trace: "
                        + describeLoadTrace(targetChunk))
        ));
        context.runAtTickTime(130, () -> {
            teleportPlayer(player, world, targetArrival);
            world.setChunkForced(targetChunk.x(), targetChunk.z(), true);
            world.getChunk(targetChunk.x(), targetChunk.z());
        });
        context.runAtTickTime(170, () -> {
            context.assertTrue(
                    hasEntityWithUuid(world, targetChunk, pigUuid),
                    Component.literal("Reloaded chunk did not restore the persisted pig entity. Timeline: "
                            + describeEntityTimeline(pigUuid)
                            + ". State: " + describeEntityState(world, targetChunk, pig, pigUuid)
                            + ". Load trace: " + describeLoadTrace(targetChunk))
            );
            world.setChunkForced(targetChunk.x(), targetChunk.z(), false);
            context.succeed();
        });
    }

    /**
     * Verifies that deleting a block to air inside a restored chunk survives a subsequent chunk reload cycle.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 220)
    public void restoredChunkAirDeletionSurvivesSecondReload(final GameTestHelper context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final AirDeletionTestSetup setup = createAirDeletionTestSetup(context, 640);
        final AirDeletionScenario scenario = setup.scenario();
        final ServerLevel world = scenario.world();
        final ChunkPos targetChunk = scenario.targetChunk();
        final BlockPos targetArrival = scenario.targetArrival();
        final BlockPos farArrival = scenario.farArrival();
        final BlockPos primary = scenario.primary();
        final ServerPlayer player = setup.player();
        teleportPlayer(player, world, targetArrival);
        world.getChunkSource()
                .save(false);
        AsyncCisSaveManager.flushAndClose(world);
        world.setChunkForced(targetChunk.x(), targetChunk.z(), false);

        context.runAtTickTime(20, () -> teleportPlayer(player, world, farArrival));
        context.runAtTickTime(50, () -> {
            teleportPlayer(player, world, targetArrival);
            world.getChunk(targetChunk.x(), targetChunk.z());
            context.assertTrue(
                    world.getBlockState(primary)
                            .is(Blocks.DIAMOND_BLOCK),
                    Component.literal("Expected first reload to restore the original block before deletion.")
            );
            world.setBlockAndUpdate(primary, Blocks.AIR.defaultBlockState());
            context.assertTrue(
                    world.getBlockState(primary)
                            .isAir(),
                    Component.literal("Expected watched block to be air immediately after deletion on restored chunk.")
            );
            world.getChunkSource()
                    .save(false);
            AsyncCisSaveManager.flushAndClose(world);
            world.setChunkForced(targetChunk.x(), targetChunk.z(), false);
        });
        context.runAtTickTime(90, () -> teleportPlayer(player, world, farArrival));
        context.runAtTickTime(130, () -> {
            teleportPlayer(player, world, targetArrival);
            world.getChunk(targetChunk.x(), targetChunk.z());
        });
        context.runAtTickTime(150, () -> {
            context.assertTrue(
                    world.getBlockState(primary)
                            .isAir(),
                    Component.literal("Reloaded restored chunk resurrected a block that had been deleted to air.")
            );
            context.succeed();
        });
    }

    /**
     * Verifies that deleting a block to air in a persisted base chunk reload preserves the air deletion.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 120)
    public void persistedBaseChunkReloadPreservesAirDeletion(final GameTestHelper context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final AirDeletionTestSetup setup = createAirDeletionTestSetup(context, 620);
        final AirDeletionScenario scenario = setup.scenario();

        scenario.world()
                .setBlockAndUpdate(scenario.primary(), Blocks.AIR.defaultBlockState());

        context.assertTrue(
                scenario.world()
                        .getBlockState(scenario.primary())
                        .isAir(),
                Component.literal("Expected watched block to be air immediately after deletion.")
        );

        teleportPlayer(setup.player(), scenario.world(), scenario.targetArrival());
        scenario.world()
                .getChunkSource()
                .save(false);
        AsyncCisSaveManager.flushAndClose(scenario.world());

        final CisStorage<Block, BlockState, Property<?>, CompoundTag> storage =
                FabricCisStorageHelper.getStorage(scenario.world());
        final ChunkDelta<BlockState, CompoundTag> storedDelta =
                storage.load(new CisChunkPos(scenario.targetChunk().x(), scenario.targetChunk().z()));

        context.assertFalse(
                CisNbtUtil.hasPersistedBaseChunkNbt(storedDelta.getChunkMetadata())
                        && CisNbtUtil.hasFullBlockBaseline(storedDelta.getChunkMetadata())
                        && !containsBlockInstructionAt(storedDelta, scenario.primary()),
                Component.literal(
                        "Stored full-baseline delta kept base snapshot metadata while omitting the watched air deletion.")
        );

        scenario.world()
                .setChunkForced(scenario.targetChunk().x(), scenario.targetChunk().z(), false);

        context.runAtTickTime(20, () -> teleportPlayer(setup.player(), scenario.world(), scenario.farArrival()));
        context.runAtTickTime(50, () -> teleportPlayer(setup.player(), scenario.world(), scenario.targetArrival()));
        context.runAtTickTime(60, () -> {
            scenario.world()
                    .getChunk(scenario.targetChunk().x(), scenario.targetChunk().z());

            context.assertTrue(
                    scenario.world()
                            .getBlockState(scenario.primary())
                            .isAir(),
                    Component.literal("Reloaded chunk resurrected a block that had been deleted to air.")
            );

            context.succeed();
        });
    }

    /**
     * Verifies that reloading a persisted base chunk does not restore empty state but recovers correctly.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 300)
    public void persistedBaseChunkReloadDoesNotRestoreEmpty(final GameTestHelper context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ServerLevel world = context.getLevel();
        final BlockPos anchor = context.absolutePos(BlockPos.ZERO);
        // Move 10,000 blocks away to ensure it's not kept loaded by the gametest structure
        final ChunkPos targetChunk = getOffsetChunkPos(anchor, 600);
        final BlockPos targetArrival = targetChunk.getBlockAt(8, 100, 8);
        final BlockPos farArrival = targetArrival.offset(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE);
        final BlockPos primary = targetChunk.getBlockAt(8, 64, 8);
        final BlockPos secondary = targetChunk.getBlockAt(9, 64, 8);
        final BlockPos chestPos = targetChunk.getBlockAt(10, 64, 8);

        watchBlocks(primary, secondary, chestPos);

        world.setChunkForced(targetChunk.x(), targetChunk.z(), true);
        final LevelChunk chunk = world.getChunk(targetChunk.x(), targetChunk.z());
        placeMarkerPattern(world, primary, secondary, chestPos);

        final ChunkDelta<BlockState, CompoundTag> delta = new ChunkDelta<>(BlockState::isAir);
        BaseChunkCaptureUtil.captureBaseChunk(world, chunk, delta);

        context.assertTrue(
                CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata()),
                Component.literal("Expected persisted base chunk NBT after capture.")
        );

        context.assertTrue(
                delta.getBlockInstructions()
                        .isEmpty(),
                Component.literal("Expected base capture to clear sparse block payloads.")
        );

        final ServerPlayer player = GameTestPlayers.createServerPlayer(context);
        teleportPlayer(player, world, targetArrival);
        world.setChunkForced(targetChunk.x(), targetChunk.z(), false);

        // Wait for vanilla to really unload the chunk instead of assuming a tick count. A chunk that only
        // dropped below FULL is still in memory and returning re-promotes it without a storage load, so the
        // BASE_NBT_APPLIED check would test nothing. The legacy base-chunk entry is written only after the
        // unload: the unload save itself stores the chunk in the current full-baseline form.
        final java.util.concurrent.atomic.AtomicBoolean unloaded = new java.util.concurrent.atomic.AtomicBoolean();
        ServerChunkEvents.CHUNK_UNLOAD.register((unloadWorld, unloadChunk) -> {
            if (unloadWorld == world && unloadChunk.getPos().equals(targetChunk)) {
                unloaded.set(true);
            }
        });
        context.runAtTickTime(20, () -> teleportPlayer(player, world, farArrival));
        context.startSequence()
                .thenWaitUntil(() -> context.assertTrue(unloaded.get(),
                        Component.literal("Waiting for target chunk " + targetChunk + " to unload.")))
                .thenWaitUntil(() -> {
                    // The unload's own save stores the chunk in the current form; wait for it to land so it
                    // cannot overwrite the legacy entry written next.
                    AsyncCisSaveManager.flushAndClose(world);
                    // flushAndClose closes the storage instance; look it up again rather than reuse one.
                    context.assertTrue(CisNbtUtil.hasFullBlockBaseline(FabricCisStorageHelper.getStorage(world)
                                    .load(new CisChunkPos(targetChunk.x(), targetChunk.z())).getChunkMetadata()),
                            Component.literal("Waiting for the unload save of " + targetChunk + "."));
                })
                .thenWaitUntil(() -> {
                    context.assertTrue(FabricCisStorageHelper.getStorage(world)
                                    .save(new CisChunkPos(targetChunk.x(), targetChunk.z()), delta),
                            Component.literal("Failed writing the legacy base-chunk entry."));
                    GlobalChunkTracker.forgetChunk(world, targetChunk);
                    teleportPlayer(player, world, targetArrival);
                })
                .thenWaitUntil(() -> {
                    world.getChunk(targetChunk.x(), targetChunk.z());

                    context.assertTrue(
                            isMarkerPatternPresent(world, primary, secondary, chestPos),
                            Component.literal("Reloaded chunk restored empty instead of from persisted base chunk NBT.")
                    );
                    context.assertTrue(
                            hasMatchingChestBlockEntity(world, chestPos),
                            Component.literal("Expected chest block entity to match its restored chest block state.")
                    );
                    context.assertTrue(
                            containsBaseChunkAppliedTrace(targetChunk),
                            Component.literal("Expected BASE_NBT_APPLIED trace event on reload.")
                    );
                })
                .thenSucceed();
    }

    /**
     * Verifies that repeated full baseline reload churn does not accept unexpected edits and preserves the pig entity.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 300)
    public void fullBaselineReloadChurnDoesNotAcceptUnexpectedRealEditsOrLosePig(final GameTestHelper context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ServerLevel world = context.getLevel();
        final BlockPos anchor = context.absolutePos(BlockPos.ZERO);
        final ChunkPos targetChunk = getOffsetChunkPos(anchor, 700);
        final BlockPos targetArrival = targetChunk.getBlockAt(8, 115, 8);
        final BlockPos farArrival = targetArrival.offset(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE);
        final BlockPos primary = targetChunk.getBlockAt(8, 64, 8);
        final BlockPos secondary = targetChunk.getBlockAt(9, 64, 8);
        final int churnCycles = 12;

        forceAndLoad(world, targetChunk);
        world.setBlockAndUpdate(primary, Blocks.DIAMOND_BLOCK.defaultBlockState());
        world.setBlockAndUpdate(secondary, Blocks.GOLD_BLOCK.defaultBlockState());

        final Pig pig = createConfiguredPig(context, world, targetChunk);
        if (pig == null) {
            return;
        }
        final UUID pigUuid = pig.getUUID();
        watchBlocks(primary, secondary);
        watchEntity(pigUuid);
        final ServerPlayer player = GameTestPlayers.createServerPlayer(context);
        context.assertTrue(world.addFreshEntity(pig), Component.literal("Expected pig spawn to succeed."));

        context.runAtTickTime(5, () -> {
            context.assertTrue(
                    pig.isAlive() && !pig.isRemoved() && pig.chunkPosition()
                            .equals(targetChunk),
                    Component.literal("Spawned pig was not alive in the target chunk before churn seed save. State: "
                            + describeEntityState(world, targetChunk, pig, pigUuid))
            );
            teleportPlayer(player, world, targetArrival);
        });

        // Vanilla skips saving a chunk whose holder still has pending work (neighbour generation under
        // concurrent tests), so retry the seed save each tick instead of assuming one pass writes it.
        final java.util.concurrent.atomic.AtomicBoolean seeded = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicLong seedEventId = new java.util.concurrent.atomic.AtomicLong();
        for (long tick = 5L; tick <= 60L; tick++) {
            context.runAtTickTime(tick, () -> {
                if (seeded.get()) {
                    return;
                }
                // A world save, which also saves entities: an autosave of a loaded chunk leaves its entities to
                // vanilla's entity saves, as vanilla does.
                world.save(null, false, false);
                AsyncCisSaveManager.flushAndClose(world);
                if (storedDeltaContainsEntity(world, targetChunk, pigUuid)) {
                    seeded.set(true);
                    // The seed edits above are real edits by design; only edits after the seed save count.
                    seedEventId.set(ChunkTraceStore.snapshotMatching(event -> true).stream()
                            .mapToLong(ChunkTraceEvent::eventId).max().orElse(-1L));
                    world.setChunkForced(targetChunk.x(), targetChunk.z(), false);
                }
            });
        }
        context.runAtTickTime(61L, () -> assertStoredDeltaContainsEntity(context, world, targetChunk, pigUuid,
                "full-baseline churn seed save"));

        for (int i = 0; i < churnCycles; i++) {
            final long cycleStart = 65L + (long) i * 14L;
            context.runAtTickTime(cycleStart, () -> teleportPlayer(player, world, farArrival));
            context.runAtTickTime(cycleStart + 4L, () -> teleportPlayer(player, world, targetArrival));
            context.runAtTickTime(cycleStart + 8L, () -> {
                world.getChunk(targetChunk.x(), targetChunk.z());
                world.getChunkSource()
                        .save(false);
            });
        }

        context.runAtTickTime(65L + churnCycles * 14L + 30L, () -> {
            world.getChunk(targetChunk.x(), targetChunk.z());
            context.assertTrue(
                    world.getBlockState(primary)
                            .is(Blocks.DIAMOND_BLOCK)
                            && world.getBlockState(secondary)
                            .is(Blocks.GOLD_BLOCK),
                    Component.literal("Repeated full-baseline churn changed restored marker blocks.")
            );
            if (world.canSpawnEntitiesInChunk(targetChunk)) {
                context.assertTrue(
                        hasEntityWithUuid(world, targetChunk, pigUuid),
                        Component.literal("Repeated full-baseline churn lost the persisted pig entity. Timeline: "
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
                    containsUnexpectedRealEditTrace(targetChunk, seedEventId.get()),
                    Component.literal("Repeated full-baseline churn accepted post-restore real edits for the restored chunk.")
            );

            context.succeed();
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
    public void mixedBaselineNeighborReloadPreservesCrossChunkBorderFixture(final GameTestHelper context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ServerLevel world = context.getLevel();
        final BlockPos anchor = context.absolutePos(BlockPos.ZERO);
        final ChunkPos leftChunkPos = getOffsetChunkPos(anchor, 760);
        final ChunkPos rightChunkPos = new ChunkPos(leftChunkPos.x() + 1, leftChunkPos.z());
        final BlockPos leftArrival = leftChunkPos.getBlockAt(14, 100, 8);
        final BlockPos farArrival = leftArrival.offset(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE);
        final BlockPos leftLogBase = leftChunkPos.getBlockAt(15, 64, 8);
        final BlockPos leftLogTop = leftChunkPos.getBlockAt(15, 65, 8);
        final BlockPos leftLeaf = leftChunkPos.getBlockAt(15, 66, 8);
        final BlockPos rightLeafNear = rightChunkPos.getBlockAt(0, 66, 8);
        final BlockPos rightLeafFar = rightChunkPos.getBlockAt(1, 66, 8);

        watchBlocks(leftLogBase, leftLogTop, leftLeaf, rightLeafNear, rightLeafFar);
        forceAndLoad(world, leftChunkPos);
        forceAndLoad(world, rightChunkPos);

        world.setBlockAndUpdate(leftLogBase, Blocks.JUNGLE_LOG.defaultBlockState());
        world.setBlockAndUpdate(leftLogTop, Blocks.JUNGLE_LOG.defaultBlockState());
        world.setBlockAndUpdate(leftLeaf, naturalJungleLeaves(1));
        world.setBlockAndUpdate(rightLeafNear, naturalJungleLeaves(2));
        world.setBlockAndUpdate(rightLeafFar, naturalJungleLeaves(2));

        final CisStorage<Block, BlockState, Property<?>, CompoundTag> storage =
                FabricCisStorageHelper.getStorage(world);
        final LevelChunk leftChunk = world.getChunk(leftChunkPos.x(), leftChunkPos.z());
        final LevelChunk rightChunk = world.getChunk(rightChunkPos.x(), rightChunkPos.z());

        final ChunkDelta<BlockState, CompoundTag> leftDelta = new ChunkDelta<>(BlockState::isAir);
        BaseChunkCaptureUtil.captureBaseChunk(world, leftChunk, leftDelta);
        CisSnapshotCapture.capture(leftChunk, leftDelta, "gametest-mixed-border-left");
        storage.save(new CisChunkPos(leftChunkPos.x(), leftChunkPos.z()), leftDelta);

        final ChunkDelta<BlockState, CompoundTag> rightDelta = new ChunkDelta<>(BlockState::isAir);
        BaseChunkCaptureUtil.captureBaseChunk(world, rightChunk, rightDelta);
        storage.save(new CisChunkPos(rightChunkPos.x(), rightChunkPos.z()), rightDelta);
        final ChunkDelta<BlockState, CompoundTag> storedRightDelta =
                storage.load(new CisChunkPos(rightChunkPos.x(), rightChunkPos.z()), "gametest-mixed-border-right-readback");

        context.assertTrue(
                !CisNbtUtil.hasPersistedBaseChunkNbt(leftDelta.getChunkMetadata())
                        && CisNbtUtil.hasFullBlockBaseline(leftDelta.getChunkMetadata()),
                Component.literal("Expected left chunk to persist as full-baseline snapshot.")
        );
        context.assertTrue(
                CisNbtUtil.hasPersistedBaseChunkNbt(rightDelta.getChunkMetadata())
                        && !CisNbtUtil.hasFullBlockBaseline(rightDelta.getChunkMetadata()),
                Component.literal("Expected right chunk to persist as base-backed sparse chunk.")
        );
        context.assertTrue(
                ChunkDeltaOwnership.hasRestorableChunkisState(storedRightDelta)
                        && CisNbtUtil.hasPersistedBaseChunkNbt(storedRightDelta.getChunkMetadata())
                        && !CisNbtUtil.hasFullBlockBaseline(storedRightDelta.getChunkMetadata()),
                Component.literal("Expected right base-backed sparse chunk to round-trip through storage: "
                        + describeDelta(storedRightDelta))
        );

        GlobalChunkTracker.forgetChunk(world, leftChunkPos);
        GlobalChunkTracker.forgetChunk(world, rightChunkPos);

        final ServerPlayer player = GameTestPlayers.createServerPlayer(context);
        teleportPlayer(player, world, leftArrival);
        world.setChunkForced(leftChunkPos.x(), leftChunkPos.z(), false);
        world.setChunkForced(rightChunkPos.x(), rightChunkPos.z(), false);

        context.runAtTickTime(20, () -> teleportPlayer(player, world, farArrival));

        context.runAtTickTime(50, () -> context.assertTrue(
                world.getChunkSource()
                        .getChunk(leftChunkPos.x(), leftChunkPos.z(), false) == null
                        && world.getChunkSource()
                        .getChunk(rightChunkPos.x(), rightChunkPos.z(), false) == null,
                Component.literal("Expected both border chunks to unload before reload.")
        ));

        context.runAtTickTime(70, () -> {
            world.setChunkForced(rightChunkPos.x(), rightChunkPos.z(), true);
            world.getChunk(rightChunkPos.x(), rightChunkPos.z());
            world.scheduleTick(rightLeafNear, Blocks.JUNGLE_LEAVES, 1);
        });

        context.runAtTickTime(90, () -> {
            final BlockState state = world.getBlockState(rightLeafNear);
            context.assertTrue(
                    state.is(Blocks.JUNGLE_LEAVES)
                            && state.getValue(LeavesBlock.DISTANCE) < LeavesBlock.DECAY_DISTANCE,
                    Component.literal("Sparse-side natural leaf lost its log distance before neighbor reload: " + state)
            );
            teleportPlayer(player, world, leftArrival);
            world.setChunkForced(leftChunkPos.x(), leftChunkPos.z(), true);
            world.getChunk(leftChunkPos.x(), leftChunkPos.z());
        });
        context.runAtTickTime(110, () -> {
            context.assertTrue(
                    world.getBlockState(leftLogBase)
                            .is(Blocks.JUNGLE_LOG)
                            && world.getBlockState(leftLogTop)
                            .is(Blocks.JUNGLE_LOG)
                            && world.getBlockState(leftLeaf)
                            .is(Blocks.JUNGLE_LEAVES)
                            && world.getBlockState(rightLeafNear)
                            .is(Blocks.JUNGLE_LEAVES)
                            && world.getBlockState(rightLeafFar)
                            .is(Blocks.JUNGLE_LEAVES),
                    Component.literal("Cross-chunk border fixture was cut at reload.")
            );

            final LevelChunk reloadedLeft = world.getChunkSource()
                    .getChunk(leftChunkPos.x(), leftChunkPos.z(), false);
            final LevelChunk reloadedRight = world.getChunkSource()
                    .getChunk(rightChunkPos.x(), rightChunkPos.z(), false);
            context.assertTrue(
                    hasAttachedRestorableDelta(reloadedLeft),
                    Component.literal("Reloaded left full-baseline chunk did not keep a live Chunkis delta attached: "
                            + describeAttachedDelta(reloadedLeft))
            );
            context.assertTrue(
                    hasAttachedRestorableDelta(reloadedRight),
                    Component.literal("Reloaded right base-backed chunk did not keep a live Chunkis delta attached: "
                            + describeAttachedDelta(reloadedRight))
            );

            world.setChunkForced(leftChunkPos.x(), leftChunkPos.z(), false);
            world.setChunkForced(rightChunkPos.x(), rightChunkPos.z(), false);
            context.succeed();
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
            ServerLevel world,
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
            ServerPlayer player
    ) {

    }
}
