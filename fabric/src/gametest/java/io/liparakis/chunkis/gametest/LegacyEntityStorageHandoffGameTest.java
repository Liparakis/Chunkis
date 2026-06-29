package io.liparakis.chunkis.gametest;

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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

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

    private static SerializedEntity createLegacyEntityPayload(final ServerWorld world, final BlockPos pos) {
        final Entity entity = Objects.requireNonNull(
                EntityType.COW.create(world, SpawnReason.COMMAND),
                "Failed to create test cow entity");
        entity.refreshPositionAndAngles(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);

        try (ErrorReporter.Logging logging = new ErrorReporter.Logging(
                entity.getErrorReporterContext(),
                io.liparakis.chunkis.Chunkis.LOGGER)) {

            final NbtWriteView writeView = NbtWriteView.create(logging, entity.getRegistryManager());
            entity.writeData(writeView);

            final NbtCompound nbt = writeView.getNbt();
            CisNbtUtil.ensureEntityIdPresent(nbt, entity);
            return new SerializedEntity(entity.getUuid(), nbt);
        }
    }

    private static void clearNonPlayerEntities(final ServerWorld world, final ChunkPos chunkPos) {
        for (final Entity entity : getChunkEntities(world, chunkPos)) {
            if (!(entity instanceof PlayerEntity)) {
                entity.discard();
            }
        }
    }

    private static int countEntitiesWithUuid(final ServerWorld world, final ChunkPos chunkPos, final UUID uuid) {
        int count = 0;
        for (final Entity entity : getChunkEntities(world, chunkPos)) {
            if (uuid.equals(entity.getUuid())) {
                count++;
            }
        }
        return count;
    }

    private static List<Entity> getChunkEntities(final ServerWorld world, final ChunkPos chunkPos) {
        return world.getOtherEntities(
                null,
                new Box(
                        chunkPos.getStartX(),
                        world.getBottomY(),
                        chunkPos.getStartZ(),
                        chunkPos.getEndX() + 1,
                        world.getBottomY() + world.getHeight(),
                        chunkPos.getEndZ() + 1));
    }

    @GameTest(maxTicks = 200)
    public void rewritesLegacyEntityPayloadsOutOfCisAfterFirstReplay(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final BlockPos anchor = context.getAbsolutePos(new BlockPos(1, 2, 1));
        final ChunkPos chunkPos = new ChunkPos(anchor);
        final CisChunkPos cisChunkPos = new CisChunkPos(chunkPos.x, chunkPos.z);

        clearNonPlayerEntities(world, chunkPos);
        FabricCisStorageHelper.closeStorage(world);

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);

        storage.save(cisChunkPos, new ChunkDelta<>());

        final SerializedEntity legacyEntity = createLegacyEntityPayload(world, anchor);
        final ChunkDelta<BlockState, NbtCompound> legacyDelta = new ChunkDelta<>();
        legacyDelta.setSuppressInitialRepopulation(true);
        legacyDelta.addPendingEntity(legacyEntity.nbt());
        storage.save(cisChunkPos, legacyDelta);

        final ChunkDelta<BlockState, NbtCompound> persistedLegacyDelta = storage.load(cisChunkPos);
        context.assertTrue(
                persistedLegacyDelta.getEntitiesList().size() == 1,
                Text.literal("Expected one legacy entity payload before replay."));

        final WorldChunk chunk = world.getWorldChunk(anchor);
        final ChunkDelta<BlockState, NbtCompound> runtimeDelta = new ChunkDelta<>();
        ChunkRestorer.restore(
                world,
                chunk,
                persistedLegacyDelta,
                runtimeDelta);

        context.assertTrue(
                countEntitiesWithUuid(world, chunkPos, legacyEntity.uuid()) == 1,
                Text.literal("Expected exactly one spawned legacy entity after first replay."));
        context.assertTrue(
                runtimeDelta.getEntitiesList().isEmpty(),
                Text.literal("Expected runtime delta to drop legacy entity payloads after replay."));

        storage.save(cisChunkPos, runtimeDelta);
        FabricCisStorageHelper.closeStorage(world);

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> reopenedStorage =
                FabricCisStorageHelper.getStorage(world);
        final ChunkDelta<BlockState, NbtCompound> rewrittenDelta = reopenedStorage.load(cisChunkPos);

        context.assertTrue(
                rewrittenDelta.getEntitiesList().isEmpty(),
                Text.literal("Expected rewritten CIS delta to contain no entity payloads after handoff."));

        final int entitiesBeforeSecondRestore = countEntitiesWithUuid(world, chunkPos, legacyEntity.uuid());
        ChunkRestorer.restore(
                world,
                chunk,
                rewrittenDelta,
                new ChunkDelta<>());

        context.assertTrue(
                countEntitiesWithUuid(world, chunkPos, legacyEntity.uuid()) == entitiesBeforeSecondRestore,
                Text.literal("Expected no duplicate entity replay after CIS handoff."));

        context.complete();
    }

    private record SerializedEntity(UUID uuid, NbtCompound nbt) {

    }
}


