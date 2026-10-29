package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

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
    @SuppressWarnings("unchecked")
    private static void saveEntities(final ServerWorld world, final BlockPos pos)
            throws ReflectiveOperationException {
        final Field managerField = ServerWorld.class.getDeclaredField("entityManager");
        managerField.setAccessible(true);
        final ServerEntityManager<?> manager = (ServerEntityManager<?>) managerField.get(world);
        final Field accessField = ServerEntityManager.class.getDeclaredField("dataAccess");
        accessField.setAccessible(true);
        final net.minecraft.world.storage.ChunkDataAccess<Entity> dataAccess =
                (net.minecraft.world.storage.ChunkDataAccess<Entity>) accessField.get(manager);
        final net.minecraft.util.math.ChunkPos chunkPos = new net.minecraft.util.math.ChunkPos(pos);
        final java.util.List<Entity> entities = world.getOtherEntities(null, new net.minecraft.util.math.Box(
                chunkPos.getStartX(), world.getBottomY(), chunkPos.getStartZ(),
                chunkPos.getEndX() + 1, world.getTopYInclusive() + 1, chunkPos.getEndZ() + 1));
        dataAccess.writeChunkData(new net.minecraft.world.storage.ChunkDataList<>(chunkPos, entities));
    }

    /**
     * Returns the delta payload for {@code entity}, or {@code null} when absent.
     *
     * @param world  world containing the entity
     * @param entity entity to look up
     * @return stored entity NBT, or {@code null}
     */
    private static NbtCompound storedPayload(final ServerWorld world, final Entity entity) {
        final ChunkDelta<?, ?> delta = ((ChunkisDeltaDuck) world.getWorldChunk(entity.getBlockPos()))
                .chunkis$getDelta();
        if (delta == null) {
            return null;
        }
        final AtomicReference<NbtCompound> found = new AtomicReference<>();
        delta.forEachEntity(nbt -> {
            if (nbt instanceof NbtCompound compound
                    && EntityPayloadNbt.hasUuid(compound, entity.getUuidAsString())) {
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
    public void entitySaveRefreshesPayloadsChangedAfterSpawn(final TestContext context)
            throws ReflectiveOperationException {
        final ServerWorld world = context.getWorld();
        final BlockPos spawnPos = context.getAbsolutePos(new BlockPos(1, 2, 1));
        // Move within the same chunk wherever the test structure happens to be placed.
        final net.minecraft.util.math.ChunkPos chunk = new net.minecraft.util.math.ChunkPos(spawnPos);
        final BlockPos movedPos = new BlockPos(
                chunk.getStartX() + ((spawnPos.getX() - chunk.getStartX() + 8) & 15),
                spawnPos.getY(),
                chunk.getStartZ() + ((spawnPos.getZ() - chunk.getStartZ() + 8) & 15));

        final Entity cow = Objects.requireNonNull(EntityType.COW.create(world, SpawnReason.COMMAND));
        cow.refreshPositionAndAngles(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D, 0.0F, 0.0F);
        cow.setNoGravity(true);
        world.spawnEntity(cow);

        final ArmorStandEntity armorStand = Objects.requireNonNull(EntityType.ARMOR_STAND.create(world,
                SpawnReason.COMMAND));
        armorStand.refreshPositionAndAngles(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D, 0.0F,
                0.0F);
        world.spawnEntity(armorStand);

        cow.refreshPositionAndAngles(movedPos.getX() + 0.5D, movedPos.getY(), movedPos.getZ() + 0.5D, 0.0F, 0.0F);
        armorStand.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        context.assertTrue(cow.getChunkPos()
                        .equals(armorStand.getChunkPos()),
                Text.literal("Test entities must share a chunk; move the test structure."));

        saveEntities(world, spawnPos);

        final NbtCompound cowPayload = storedPayload(world, cow);
        context.assertTrue(cowPayload != null, Text.literal("Expected cow payload in delta after entity save."));
        context.assertTrue(EntityPayloadNbt.findBlockPos(cowPayload)
                        .map(movedPos::equals)
                        .orElse(false),
                Text.literal("Expected cow payload at moved position " + movedPos + " but was "
                        + EntityPayloadNbt.describeRawPos(cowPayload)));

        final NbtCompound standPayload = storedPayload(world, armorStand);
        context.assertTrue(standPayload != null,
                Text.literal("Expected armor stand payload in delta after entity save."));
        context.assertTrue(standPayload.toString()
                        .contains("minecraft:diamond_helmet"),
                Text.literal("Expected armor stand payload to keep its helmet but was " + standPayload));

        cow.discard();
        armorStand.discard();
        context.complete();
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
    public void entitySaveKeepsUnreplayedPendingPayloads(final TestContext context)
            throws ReflectiveOperationException {
        final ServerWorld world = context.getWorld();
        final BlockPos pos = context.getAbsolutePos(new BlockPos(1, 2, 1));

        // Spawning claims the chunk's delta, like a real player edit.
        final Entity anchor = Objects.requireNonNull(EntityType.COW.create(world, SpawnReason.COMMAND));
        anchor.refreshPositionAndAngles(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
        anchor.setNoGravity(true);
        world.spawnEntity(anchor);

        final java.util.UUID pendingUuid = java.util.UUID.randomUUID();
        final NbtCompound pending = new NbtCompound();
        pending.putString("id", "minecraft:pig");
        pending.putIntArray("UUID", net.minecraft.util.Uuids.toIntArray(pendingUuid));
        final ChunkDelta<?, NbtCompound> delta = (ChunkDelta<?, NbtCompound>) ((ChunkisDeltaDuck) world.getWorldChunk(pos))
                .chunkis$getDelta();
        delta.addPendingEntity(pending);

        saveEntities(world, pos);

        final boolean[] stillPending = {false};
        delta.forEachPendingEntity(nbt -> stillPending[0] |= EntityPayloadNbt.hasUuid(nbt, pendingUuid.toString()));
        context.assertTrue(stillPending[0], Text.literal("Expected unreplayed pending payload to survive entity save."));

        anchor.discard();
        context.complete();
    }
}
