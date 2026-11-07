package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.restoration.core.ChunkRestorer;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

/**
 * Verifies the one-time handoff from legacy Chunkis-owned entity payloads to
 * vanilla entity storage.
 *
 * <p>The test seeds a chunk with one legacy entity payload in CIS, replays it
 * through {@link ChunkRestorer}, then saves the runtime delta back through the
 * active storage. The rewritten CIS entry must no longer contain any entity
 * payloads, proving that later loads will not replay the same entity again.</p>
 */
@SuppressWarnings("unused")
public final class LegacyEntityStorageHandoffGameTest {

    /**
     * Creates a serialized legacy entity payload (a cow) at the specified block position.
     *
     * @param world the server world where the entity is temporarily created to serialize its data
     * @param pos   the block position for the entity's starting position
     * @return a {@link SerializedEntity} container holding the entity's UUID and its serialized NBT payload
     * @throws NullPointerException if the cow entity cannot be created
     */
    private static SerializedEntity createLegacyEntityPayload(final ServerLevel world, final BlockPos pos) {
        final Entity entity = Objects.requireNonNull(
                EntityTypes.COW.create(world, EntitySpawnReason.COMMAND),
                "Failed to create test cow entity");
        entity.snapTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);

        try (ProblemReporter.ScopedCollector logging = new ProblemReporter.ScopedCollector(
                entity.problemPath(),
                io.liparakis.chunkis.Chunkis.LOGGER)) {

            final TagValueOutput writeView = TagValueOutput.createWithContext(logging, entity.registryAccess());
            entity.saveAsPassenger(writeView);

            final CompoundTag nbt = writeView.buildResult();
            EntityPayloadNbt.ensureEntityIdPresent(nbt, entity);
            return new SerializedEntity(entity.getUUID(), nbt);
        }
    }

    /**
     * Discards all non-player entities currently present in the specified chunk.
     *
     * @param world    the server world
     * @param chunkPos the position of the chunk to clear
     */
    private static void clearNonPlayerEntities(final ServerLevel world, final ChunkPos chunkPos) {
        for (final Entity entity : getChunkEntities(world, chunkPos)) {
            if (!(entity instanceof Player)) {
                entity.discard();
            }
        }
    }

    /**
     * Counts the number of active entities in the specified chunk that match the given UUID.
     *
     * @param world    the server world
     * @param chunkPos the chunk position to search
     * @param uuid     the target entity UUID to match
     * @return the number of matching entities found in the chunk
     */
    private static int countEntitiesWithUuid(final ServerLevel world, final ChunkPos chunkPos, final UUID uuid) {
        int count = 0;
        for (final Entity entity : getChunkEntities(world, chunkPos)) {
            if (uuid.equals(entity.getUUID())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Retrieves a list of all entities within the boundaries of the specified chunk.
     *
     * @param world    the server world
     * @param chunkPos the position of the chunk
     * @return a list of entities in the chunk
     */
    private static List<Entity> getChunkEntities(final ServerLevel world, final ChunkPos chunkPos) {
        return world.getEntities(
                null,
                new AABB(
                        chunkPos.getMinBlockX(),
                        world.getMinY(),
                        chunkPos.getMinBlockZ(),
                        chunkPos.getMaxBlockX() + 1,
                        world.getMinY() + world.getHeight(),
                        chunkPos.getMaxBlockZ() + 1));
    }

    /**
     * Verifies that legacy entity payloads are successfully replayed, saved to vanilla storage,
     * and removed from the CIS storage on the first chunk load/replay cycle.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 200)
    public void rewritesLegacyEntityPayloadsOutOfCisAfterFirstReplay(final GameTestHelper context) {
        final ServerLevel world = context.getLevel();
        final BlockPos anchor = context.absolutePos(new BlockPos(1, 2, 1));
        final ChunkPos chunkPos = ChunkPos.containing(anchor);
        final CisChunkPos cisChunkPos = new CisChunkPos(chunkPos.x(), chunkPos.z());

        clearNonPlayerEntities(world, chunkPos);
        FabricCisStorageHelper.closeStorage(world);

        final CisStorage<Block, BlockState, Property<?>, CompoundTag> storage =
                FabricCisStorageHelper.getStorage(world);

        storage.save(cisChunkPos, new ChunkDelta<>());

        final SerializedEntity legacyEntity = createLegacyEntityPayload(world, anchor);
        final ChunkDelta<BlockState, CompoundTag> legacyDelta = new ChunkDelta<>();
        legacyDelta.setSuppressInitialRepopulation(true);
        legacyDelta.setChunkMetadata(CisNbtUtil.createChunkMetadata(null, true), false);
        legacyDelta.addPendingEntity(legacyEntity.nbt());
        storage.save(cisChunkPos, legacyDelta);

        final ChunkDelta<BlockState, CompoundTag> persistedLegacyDelta = storage.load(cisChunkPos);
        persistedLegacyDelta.setSuppressInitialRepopulation(
                CisNbtUtil.shouldSuppressInitialRepopulation(persistedLegacyDelta));
        context.assertTrue(
                persistedLegacyDelta.getEntitiesList()
                        .size() == 1,
                Component.literal("Expected one legacy entity payload before replay."));

        world.waitForEntities(chunkPos, 1);
        context.assertTrue(
                world.canSpawnEntitiesInChunk(chunkPos),
                Component.literal("Expected legacy handoff chunk to be entity-ready before synchronous replay."));

        final LevelChunk chunk = world.getChunkAt(anchor);
        final ChunkDelta<BlockState, CompoundTag> runtimeDelta = new ChunkDelta<>();
        ChunkRestorer.restore(
                world,
                chunk,
                persistedLegacyDelta,
                runtimeDelta);

        context.assertTrue(
                countEntitiesWithUuid(world, chunkPos, legacyEntity.uuid()) == 1,
                Component.literal("Expected exactly one spawned legacy entity after first replay."));
        context.assertTrue(
                runtimeDelta.countPendingEntities() == 0,
                Component.literal("Expected runtime delta to drop legacy entity payloads after replay."));

        storage.save(cisChunkPos, runtimeDelta);
        FabricCisStorageHelper.closeStorage(world);

        final CisStorage<Block, BlockState, Property<?>, CompoundTag> reopenedStorage =
                FabricCisStorageHelper.getStorage(world);
        final ChunkDelta<BlockState, CompoundTag> rewrittenDelta = reopenedStorage.load(cisChunkPos);

        context.assertTrue(
                rewrittenDelta.getEntitiesList()
                        .isEmpty(),
                Component.literal("Expected rewritten CIS delta to contain no entity payloads after handoff."));

        final int entitiesBeforeSecondRestore = countEntitiesWithUuid(world, chunkPos, legacyEntity.uuid());
        ChunkRestorer.restore(
                world,
                chunk,
                rewrittenDelta,
                new ChunkDelta<>());

        context.assertTrue(
                countEntitiesWithUuid(world, chunkPos, legacyEntity.uuid()) == entitiesBeforeSecondRestore,
                Component.literal("Expected no duplicate entity replay after CIS handoff."));

        context.succeed();
    }

    /**
     * A holder for a serialized entity's runtime UUID and its raw NBT data.
     *
     * @param uuid the unique ID of the entity
     * @param nbt  the serialized NBT compound of the entity
     */
    private record SerializedEntity(UUID uuid, CompoundTag nbt) {

    }
}


