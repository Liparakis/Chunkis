package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder;
import io.liparakis.chunkis.world.entity.capture.EntityFallbackStore;
import io.liparakis.chunkis.world.entity.replay.EntityReplayCoordinator;
import io.liparakis.chunkis.world.restoration.core.ChunkRestorer;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.state.LoadedChunks;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/** Regression for entity saves independent of a held or stored block chunk. */
public final class EntitySaveWithoutChunkGameTest {
    /** Saves a passenger tree with no terrain record, then persists an empty replacement. */
    @GameTest(maxTicks = 100)
    public void persistentEntitySaveWithoutHeldBlockChunkMustReachStorage(final GameTestHelper context)
            throws Exception {
        final ServerLevel world = Objects.requireNonNull(context.getLevel().getServer()
                .getLevel(net.minecraft.world.level.Level.END));
        final ChunkPos pos = new ChunkPos(899900, 899900);
        MigrationStateServiceHolder.markAuthoritative(world.dimension());
        final var terrain = FabricCisStorageHelper.getStorage(world);
        context.assertTrue(LoadedChunks.getHeld(world, pos.x(), pos.z()) == null,
                Component.literal("Target must not have a held block chunk"));
        context.assertTrue(GlobalChunkTracker.getDelta(world, pos) == null
                        && terrain.loadReleasedWithoutClearing(FabricCisStorageHelper.toStoragePos(pos)) == null,
                Component.literal("Target must not have prior CIS state"));
        final var cow = Objects.requireNonNull(EntityType.COW.create(world, EntitySpawnReason.COMMAND));
        cow.setNoAi(true);
        cow.setPersistenceRequired();
        cow.setNoGravity(true);
        cow.setCustomName(Component.literal("Persistent entity-only save sentinel"));
        cow.snapTo(pos.getMinBlockX() + 8.5, 80, pos.getMinBlockZ() + 8.5, 0, 0);
        final var passenger = Objects.requireNonNull(EntityType.CHICKEN.create(world, EntitySpawnReason.COMMAND));
        passenger.snapTo(cow.getX(), cow.getY(), cow.getZ(), 0, 0);
        context.assertTrue(passenger.startRiding(cow, true, false), Component.literal("Passenger must mount"));
        final EntityPersistentStorage<Entity> access = entityStorage(world);
        access.storeEntities(new ChunkEntities<>(pos, List.of(cow, passenger)));
        access.flush(true);
        final CompletableFuture<ChunkEntities<Entity>> saved = access.loadEntities(pos);
        final AtomicReference<CompletableFuture<ChunkEntities<Entity>>> empty = new AtomicReference<>();
        context.startSequence().thenWaitUntil(() -> {
            context.assertTrue(saved.isDone(), Component.literal("Entity read pending"));
            final List<Entity> loaded = saved.join().getEntities().toList();
            context.assertTrue(loaded.stream().anyMatch(entity -> entity.getUUID().equals(cow.getUUID())),
                    Component.literal("Cow must survive entity-only save"));
            context.assertTrue(loaded.stream().anyMatch(entity -> entity.getUUID().equals(passenger.getUUID())
                            && entity.getVehicle() != null && entity.getVehicle().getUUID().equals(cow.getUUID())),
                    Component.literal("Passenger UUID and riding relationship must survive"));
        }).thenExecute(() -> {
            access.storeEntities(new ChunkEntities<>(pos, List.of()));
            access.flush(true);
            empty.set(access.loadEntities(pos));
        }).thenWaitUntil(() -> {
            context.assertTrue(empty.get().isDone(), Component.literal("Empty read pending"));
            context.assertTrue(empty.get().join().isEmpty(),
                    Component.literal("Empty replacement must not resurrect old entities"));
            context.assertTrue(GlobalChunkTracker.getDelta(world, pos) == null,
                    Component.literal("Entity save must not fabricate terrain"));
        }).thenSucceed();
    }

    /** Both bulk and queued CIS replay must respect an empty fallback snapshot. */
    @GameTest
    public void emptyFallbackSupersedesStaleCisReplay(final GameTestHelper context) {
        final ServerLevel world = context.getLevel();
        final var chunk = world.getChunkAt(context.absolutePos(new BlockPos(1, 2, 1)));
        EntityFallbackStore.save(world, chunk.getPos(), List.of());
        final ChunkDelta<BlockState, CompoundTag> stale = new ChunkDelta<>();
        final CompoundTag payload = new CompoundTag();
        payload.putString("id", "minecraft:cow");
        stale.addPendingEntity(payload);
        EntityReplayCoordinator.replayPendingEntitiesIfNeeded(world, chunk, stale, null);
        final var result = EntityReplayCoordinator.replayPendingEntityIfNeeded(world, chunk, stale, null,
                "00000000-0000-0000-0000-000000000001", payload);
        context.assertTrue(result.status() == ChunkRestorer.ReplayStatus.PERMANENT_FAILURE,
                Component.literal("Queued stale CIS payload must not replay"));
        context.succeed();
    }

    /** Uses the same persistence object vanilla's entity manager invokes. */
    @SuppressWarnings("unchecked")
    private static EntityPersistentStorage<Entity> entityStorage(final ServerLevel world)
            throws ReflectiveOperationException {
        final Field managerField = ServerLevel.class.getDeclaredField("entityManager");
        managerField.setAccessible(true);
        final Object manager = managerField.get(world);
        final Field storageField = PersistentEntitySectionManager.class.getDeclaredField("permanentStorage");
        storageField.setAccessible(true);
        return (EntityPersistentStorage<Entity>) storageField.get(manager);
    }
}
