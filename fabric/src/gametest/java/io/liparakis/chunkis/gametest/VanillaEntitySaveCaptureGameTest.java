package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Verifies that vanilla's entity save refreshes Chunkis entity payloads.
 *
 * <p>Entities are captured once at spawn. Changes made afterwards (a mob moving,
 * items put on an armor stand) must reach the delta when vanilla saves entities,
 * even if the chunk itself was never marked for saving.</p>
 */
@SuppressWarnings("unused")
public final class VanillaEntitySaveCaptureGameTest {

    /**
     * Runs vanilla's per-chunk entity save (the hooked {@code writeChunkData}) for the
     * chunk containing {@code pos}, with that chunk's live entities.
     *
     * <p>Scoped to one chunk on purpose: {@code ServerEntityManager.save()} would also
     * unload entities in hidden chunks of other gametests running concurrently.</p>
     *
     * @param world target world
     * @param pos   any position in the chunk to save
     * @throws ReflectiveOperationException if the entity manager internals are missing
     */
    private static void saveEntities(final ServerLevel world, final BlockPos pos)
            throws ReflectiveOperationException {
        final var dataAccess = entityStorage(world);
        final net.minecraft.world.level.ChunkPos chunkPos = net.minecraft.world.level.ChunkPos.containing(pos);
        final java.util.List<Entity> entities = world.getEntities(null, new net.minecraft.world.phys.AABB(
                chunkPos.getMinBlockX(), world.getMinY(), chunkPos.getMinBlockZ(),
                chunkPos.getMaxBlockX() + 1, world.getMaxY() + 1, chunkPos.getMaxBlockZ() + 1));
        dataAccess.storeEntities(new net.minecraft.world.level.entity.ChunkEntities<>(chunkPos, entities));
    }

    /**
     * Returns the delta payload for {@code entity}, or {@code null} when absent.
     *
     * @param world  world containing the entity
     * @param entity entity to look up
     * @return stored entity NBT, or {@code null}
     */
    private static CompoundTag storedPayload(final ServerLevel world, final Entity entity) {
        final ChunkDelta<?, ?> delta = ((ChunkisDeltaDuck) world.getChunkAt(entity.blockPosition()))
                .chunkis$getDelta();
        if (delta == null) {
            return null;
        }
        final AtomicReference<CompoundTag> found = new AtomicReference<>();
        delta.forEachEntity(nbt -> {
            if (nbt instanceof CompoundTag compound
                    && EntityPayloadNbt.hasUuid(compound, entity.getStringUUID())) {
                found.set(compound);
            }
        });
        return found.get();
    }

    /**
     * Moves a mob and equips an armor stand after spawn, then checks that vanilla's
     * entity save writes both changes into the Chunkis delta.
     *
     * @param context the game test context
     * @throws ReflectiveOperationException if the entity manager cannot be reached
     */
    @GameTest(maxTicks = 100)
    public void entitySaveRefreshesPayloadsChangedAfterSpawn(final GameTestHelper context)
            throws ReflectiveOperationException {
        final ServerLevel world = context.getLevel();
        final BlockPos spawnPos = context.absolutePos(new BlockPos(1, 2, 1));
        // Move within the same chunk wherever the test structure happens to be placed.
        final net.minecraft.world.level.ChunkPos chunk = net.minecraft.world.level.ChunkPos.containing(spawnPos);
        final BlockPos movedPos = new BlockPos(
                chunk.getMinBlockX() + ((spawnPos.getX() - chunk.getMinBlockX() + 8) & 15),
                spawnPos.getY(),
                chunk.getMinBlockZ() + ((spawnPos.getZ() - chunk.getMinBlockZ() + 8) & 15));

        final Entity cow = Objects.requireNonNull(EntityTypes.COW.create(world, EntitySpawnReason.COMMAND));
        cow.snapTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D, 0.0F, 0.0F);
        cow.setNoGravity(true);
        world.addFreshEntity(cow);

        final ArmorStand armorStand = Objects.requireNonNull(EntityTypes.ARMOR_STAND.create(world,
                EntitySpawnReason.COMMAND));
        armorStand.snapTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D, 0.0F,
                0.0F);
        world.addFreshEntity(armorStand);

        cow.snapTo(movedPos.getX() + 0.5D, movedPos.getY(), movedPos.getZ() + 0.5D, 0.0F, 0.0F);
        armorStand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        context.assertTrue(cow.chunkPosition()
                        .equals(armorStand.chunkPosition()),
                Component.literal("Test entities must share a chunk; move the test structure."));

        saveEntities(world, spawnPos);

        final CompoundTag cowPayload = storedPayload(world, cow);
        context.assertTrue(cowPayload != null, Component.literal("Expected cow payload in delta after entity save."));
        context.assertTrue(EntityPayloadNbt.findBlockPos(cowPayload)
                        .map(movedPos::equals)
                        .orElse(false),
                Component.literal("Expected cow payload at moved position " + movedPos + " but was "
                        + EntityPayloadNbt.describeRawPos(cowPayload)));

        final CompoundTag standPayload = storedPayload(world, armorStand);
        context.assertTrue(standPayload != null,
                Component.literal("Expected armor stand payload in delta after entity save."));
        assert standPayload != null;
        context.assertTrue(standPayload.toString()
                        .contains("minecraft:diamond_helmet"),
                Component.literal("Expected armor stand payload to keep its helmet but was " + standPayload));

        cow.discard();
        armorStand.discard();
        context.succeed();
    }

    /**
     * A pending (not yet replayed) entity payload whose UUID is not live anywhere must
     * survive an entity save; capture used to clear the pending list before copying it.
     *
     * @param context the game test context
     * @throws ReflectiveOperationException if the entity manager cannot be reached
     */
    @GameTest(maxTicks = 100)
    @SuppressWarnings("unchecked")
    public void entitySaveKeepsUnreplayedPendingPayloads(final GameTestHelper context)
            throws ReflectiveOperationException {
        final ServerLevel world = context.getLevel();
        final BlockPos pos = context.absolutePos(new BlockPos(1, 2, 1));

        // Spawning claims the chunk's delta, like a real player edit.
        final Entity anchor = Objects.requireNonNull(EntityTypes.COW.create(world, EntitySpawnReason.COMMAND));
        anchor.snapTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
        anchor.setNoGravity(true);
        world.addFreshEntity(anchor);

        final java.util.UUID pendingUuid = java.util.UUID.randomUUID();
        final CompoundTag pending = new CompoundTag();
        pending.putString("id", "minecraft:pig");
        pending.putIntArray("UUID", net.minecraft.core.UUIDUtil.uuidToIntArray(pendingUuid));
        final ChunkDelta<?, CompoundTag> delta = (ChunkDelta<?, CompoundTag>) ((ChunkisDeltaDuck) world.getChunkAt(pos))
                .chunkis$getDelta();
        delta.addPendingEntity(pending);

        saveEntities(world, pos);

        final boolean[] stillPending = {false};
        delta.forEachPendingEntity(nbt -> stillPending[0] |= EntityPayloadNbt.hasUuid(nbt, pendingUuid.toString()));
        context.assertTrue(stillPending[0], Component.literal("Expected unreplayed pending payload to survive entity save."));

        anchor.discard();
        context.succeed();
    }

    /**
     * Entities of a chunk Chunkis never claimed must reach a delta when vanilla saves them from hidden sections.
     *
     * <p>A chunk leaving full level has its entity sections hidden from the world's entity lookup, and vanilla
     * saves those entities afterwards, before or after the chunk's own unload save. Chunkis counted entities
     * through that lookup, saw none, and dropped them ("Skipped saving ... chunk has no Chunkis state to
     * attach them to"), so they were lost in an authoritative world. The cow is loaded the way world
     * generation and vanilla loads add entities, so no spawn hook claims its chunk first.</p>
     *
     * @param context the game test context
     * @throws ReflectiveOperationException if the entity manager internals are missing
     */
    @GameTest(maxTicks = 100)
    @SuppressWarnings("unchecked")
    public void entitySaveOfHiddenUnclaimedChunkClaimsIt(final GameTestHelper context)
            throws ReflectiveOperationException {
        final ServerLevel world = context.getLevel();
        final ChunkPos anchor = ChunkPos.containing(context.absolutePos(BlockPos.ZERO));
        final ChunkPos target = new ChunkPos(anchor.x() + 2700, anchor.z() - 2700);
        world.setChunkForced(target.x(), target.z(), true);
        final LevelChunk chunk = world.getChunk(target.x(), target.z());
        final BlockPos pos = world.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, target.getBlockAt(8, 0, 8));
        final Mob cow = Objects.requireNonNull(EntityTypes.COW.create(world, EntitySpawnReason.COMMAND));
        cow.snapTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
        cow.setNoAi(true);
        cow.setNoGravity(true);
        context.assertTrue(world.addWithUUID(cow), Component.literal("Could not load the cow into " + target + "."));
        context.assertFalse(ChunkDeltaOwnership.hasChunkisOwnedState(((ChunkisDeltaDuck) chunk).chunkis$getDelta()),
                Component.literal("Chunk " + target + " must start unclaimed; pick another chunk."));

        final Field managerField = ServerLevel.class.getDeclaredField("entityManager");
        managerField.setAccessible(true);
        final PersistentEntitySectionManager<Entity> manager = (PersistentEntitySectionManager<Entity>) managerField.get(world);
        final Field accessField = PersistentEntitySectionManager.class.getDeclaredField("permanentStorage");
        accessField.setAccessible(true);
        final net.minecraft.world.level.entity.EntityPersistentStorage<Entity> dataAccess =
                (net.minecraft.world.level.entity.EntityPersistentStorage<Entity>) accessField.get(manager);
        manager.updateChunkStatus(target, Visibility.HIDDEN);
        try {
            dataAccess.storeEntities(new net.minecraft.world.level.entity.ChunkEntities<>(target, List.of(cow)));
        } finally {
            manager.updateChunkStatus(target, Visibility.TRACKED);
        }

        context.assertTrue(storedPayload(world, cow) != null,
                Component.literal("Expected the hidden cow of unclaimed chunk " + target + " in its delta."));
        cow.discard();
        world.setChunkForced(target.x(), target.z(), false);
        context.succeed();
    }

    /**
     * Resolves the entity store owned by the game-test world without saving other test chunks.
     *
     * @param world fixture world
     * @return the world's entity storage
     * @throws ReflectiveOperationException if the expected vanilla fields changed
     */
    @SuppressWarnings("unchecked")
    private static net.minecraft.world.level.entity.EntityPersistentStorage<Entity> entityStorage(
            final ServerLevel world) throws ReflectiveOperationException {
        final Field managerField = ServerLevel.class.getDeclaredField("entityManager");
        managerField.setAccessible(true);
        final PersistentEntitySectionManager<?> manager = (PersistentEntitySectionManager<?>) managerField.get(world);
        final Field accessField = PersistentEntitySectionManager.class.getDeclaredField("permanentStorage");
        accessField.setAccessible(true);
        return (net.minecraft.world.level.entity.EntityPersistentStorage<Entity>) accessField.get(manager);
    }
}
