package io.liparakis.chunkis.gametest;

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
import io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureUtil;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.world.chunk.WorldChunk;

@SuppressWarnings("unused")
public final class PersistedBaseChunkReloadGameTest {

    private static final int FAR_BLOCK_DISTANCE = 20_000;

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
        return world.getBlockState(primary)
                .isOf(Blocks.DIAMOND_BLOCK)
                && world.getBlockState(secondary)
                .isOf(Blocks.GOLD_BLOCK)
                && world.getBlockState(chestPos)
                .isOf(Blocks.CHEST);
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

    private static void forceAndLoad(final ServerWorld world, final ChunkPos chunkPos) {
        world.setChunkForced(chunkPos.x, chunkPos.z, true);
        world.getChunk(chunkPos.x, chunkPos.z);
    }

    private static AirDeletionScenario createAirDeletionScenario(
            final TestContext context,
            final int chunkOffset
    ) {
        final ServerWorld world = context.getWorld();
        final BlockPos anchor = context.getAbsolutePos(BlockPos.ORIGIN);
        final ChunkPos targetChunk = new ChunkPos(
                new ChunkPos(anchor).x + chunkOffset,
                new ChunkPos(anchor).z + chunkOffset
        );
        final BlockPos targetArrival = targetChunk.getBlockPos(8, 100, 8);
        return new AirDeletionScenario(
                world,
                targetChunk,
                targetArrival,
                targetArrival.add(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE),
                targetChunk.getBlockPos(8, 64, 8)
        );
    }

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
                context.createMockCreativeServerPlayerInWorld()
        );
    }

    private static void watchBlocks(final BlockPos... positions) {
        for (final BlockPos pos : positions) {
            ChunkTraceWatchpoints.watchPayload(
                    PayloadWatchTarget.block("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ())
            );
        }
    }

    private static void watchEntity(final UUID entityUuid) {
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.entity("minecraft:overworld", entityUuid.toString()));
    }

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

    private static boolean containsBaseChunkAppliedTrace(final ChunkPos chunkPos) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                event.chunkKey() != null
                        && event.chunkKey()
                        .x()
                        == chunkPos.x
                        && event.chunkKey()
                        .z()
                        == chunkPos.z
                        && event.eventType()
                        == ChunkTraceEventType.BASE_NBT_APPLIED
        );
        return !events.isEmpty();
    }

    private static boolean containsUnexpectedRealEditTrace(final ChunkPos chunkPos) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                event.chunkKey() != null
                        && event.chunkKey()
                        .x()
                        == chunkPos.x
                        && event.chunkKey()
                        .z()
                        == chunkPos.z
                        && event.eventType()
                        == ChunkTraceEventType.MUTATION_ACCEPTED_REAL_EDIT
        );
        return !events.isEmpty();
    }

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

    private static boolean containsEntityUuid(
            final ChunkDelta<BlockState, NbtCompound> delta,
            final UUID entityUuid
    ) {
        final boolean[] found = {false};
        delta.forEachEntity(entityNbt -> {
            if (found[0] || entityNbt == null) {
                return;
            }
            found[0] = entityNbt.getIntArray("UUID")
                    .map(net.minecraft.util.Uuids::toUuid)
                    .map(entityUuid::equals)
                    .orElse(false);
        });
        return found[0];
    }

    private static String describeEntityTimeline(final UUID entityUuid) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                event.payloadWatchTarget() != null
                        && entityUuid.toString()
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
            builder.append(event.eventType()
                    .name());
            if (event.payloadWatchStage() != null) {
                builder.append('@')
                        .append(event.payloadWatchStage());
            }
            if (event.source() != null) {
                builder.append(" src=")
                        .append(event.source());
            }
            if (event.message() != null) {
                builder.append(" msg=")
                        .append(event.message());
            }
        }
        return builder.toString();
    }

    private static String describeSaveTrace(final ChunkPos chunkPos) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                event.chunkKey() != null
                        && event.chunkKey()
                        .x()
                        == chunkPos.x
                        && event.chunkKey()
                        .z()
                        == chunkPos.z
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
            builder.append(event.eventType()
                            .name())
                    .append(" src=")
                    .append(event.source())
                    .append(" msg=")
                    .append(event.message());
        }
        return builder.toString();
    }

    private static String describeLoadTrace(final ChunkPos chunkPos) {
        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                event.chunkKey() != null
                        && event.chunkKey()
                        .x()
                        == chunkPos.x
                        && event.chunkKey()
                        .z()
                        == chunkPos.z
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
            builder.append(event.eventType()
                            .name())
                    .append(" reason=")
                    .append(event.reason())
                    .append(" src=")
                    .append(event.source())
                    .append(" msg=")
                    .append(event.message());
        }
        return builder.toString();
    }

    @GameTest(maxTicks = 320)
    public void persistedEntityReloadRestoresPig(final TestContext context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ServerWorld world = context.getWorld();
        final BlockPos anchor = context.getAbsolutePos(BlockPos.ORIGIN);
        final ChunkPos targetChunk = new ChunkPos(new ChunkPos(anchor).x + 660, new ChunkPos(anchor).z + 660);
        final BlockPos targetArrival = targetChunk.getBlockPos(8, 115, 8);
        final BlockPos farArrival = targetArrival.add(FAR_BLOCK_DISTANCE, 0, FAR_BLOCK_DISTANCE);

        forceAndLoad(world, targetChunk);
        final PigEntity pig = createConfiguredPig(context, world, targetChunk);
        if (pig == null) {
            return;
        }
        final UUID pigUuid = pig.getUuid();
        watchEntity(pigUuid);
        final ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
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

    @GameTest(maxTicks = 120)
    public void persistedBaseChunkReloadPreservesAirDeletion(final TestContext context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final AirDeletionTestSetup setup = createAirDeletionTestSetup(context, 620);
        final AirDeletionScenario scenario = setup.scenario();
        final ServerWorld world = scenario.world();
        final ChunkPos targetChunk = scenario.targetChunk();
        final BlockPos targetArrival = scenario.targetArrival();
        final BlockPos farArrival = scenario.farArrival();
        final BlockPos primary = scenario.primary();
        final ServerPlayerEntity player = setup.player();

        world.setBlockState(primary, Blocks.AIR.getDefaultState());

        context.assertTrue(
                world.getBlockState(primary)
                        .isAir(),
                Text.literal("Expected watched block to be air immediately after deletion.")
        );

        teleportPlayer(player, world, targetArrival);
        world.getChunkManager()
                .save(false);
        AsyncCisSaveManager.flushAndClose(world);

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);
        final ChunkDelta<BlockState, NbtCompound> storedDelta =
                storage.load(new CisChunkPos(targetChunk.x, targetChunk.z));

        context.assertFalse(
                CisNbtUtil.hasPersistedBaseChunkNbt(storedDelta.getChunkMetadata())
                        && CisNbtUtil.hasFullBlockBaseline(storedDelta.getChunkMetadata())
                        && !containsBlockInstructionAt(storedDelta, primary),
                Text.literal(
                        "Stored full-baseline delta kept base snapshot metadata while omitting the watched air deletion.")
        );

        world.setChunkForced(targetChunk.x, targetChunk.z, false);

        context.runAtTick(20, () -> teleportPlayer(player, world, farArrival));
        context.runAtTick(50, () -> teleportPlayer(player, world, targetArrival));
        context.runAtTick(60, () -> {
            world.getChunk(targetChunk.x, targetChunk.z);

            context.assertTrue(
                    world.getBlockState(primary)
                            .isAir(),
                    Text.literal("Reloaded chunk resurrected a block that had been deleted to air.")
            );

            context.complete();
        });
    }

    @GameTest(maxTicks = 120)
    public void persistedBaseChunkReloadDoesNotRestoreEmpty(final TestContext context) {
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

        final ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
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

    @GameTest(maxTicks = 300)
    public void fullBaselineReloadChurnDoesNotAcceptUnexpectedRealEditsOrLosePig(final TestContext context) {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        final ServerWorld world = context.getWorld();
        final BlockPos anchor = context.getAbsolutePos(BlockPos.ORIGIN);
        final ChunkPos targetChunk = new ChunkPos(new ChunkPos(anchor).x + 700, new ChunkPos(anchor).z + 700);
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
        final ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
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

    private record AirDeletionScenario(
            ServerWorld world,
            ChunkPos targetChunk,
            BlockPos targetArrival,
            BlockPos farArrival,
            BlockPos primary
    ) {

    }

    private record AirDeletionTestSetup(
            AirDeletionScenario scenario,
            ServerPlayerEntity player
    ) {

    }
}
