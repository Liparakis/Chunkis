package io.liparakis.chunkis.world.entity.capture;

import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads entity payload metadata from Chunkis-managed NBT compounds.
 *
 * <p>This keeps entity replay code focused on replay decisions rather than on
 * raw NBT field parsing.</p>
 */
public final class EntityPayloadNbt {

    private static final String ENTITY_ID_KEY = "id";
    private static final String ENTITY_UUID_KEY = "UUID";
    private static final String ENTITY_POS_KEY = "Pos";

    private EntityPayloadNbt() {
        throw new AssertionError("Utility class");
    }

    public static Optional<UUID> findUuid(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return Optional.empty();
        }
        return nbt.getIntArray(ENTITY_UUID_KEY).flatMap(EntityPayloadNbt::safeUuidFromIntArray);
    }

    public static Optional<String> findUuidString(@Nullable final NbtCompound nbt) {
        return findUuid(nbt).map(UUID::toString);
    }

    public static boolean hasUuid(@Nullable final NbtCompound nbt, final String entityUuid) {
        return entityUuid != null
                && !entityUuid.isBlank()
                && findUuidString(nbt).map(entityUuid::equals).orElse(false);
    }

    public static Optional<Identifier> findTypeId(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return Optional.empty();
        }
        return nbt.getString(ENTITY_ID_KEY).map(Identifier::tryParse);
    }

    public static Optional<EntityType<?>> findEntityType(@Nullable final NbtCompound nbt) {
        return findTypeId(nbt).map(Registries.ENTITY_TYPE::get);
    }

    public static Optional<BlockPos> findBlockPos(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return Optional.empty();
        }
        return nbt.getList(ENTITY_POS_KEY)
                .filter(pos -> pos.size() >= 3)
                .map(pos -> new BlockPos(
                        (int) Math.floor(pos.getDouble(0).orElse(0.0D)),
                        (int) Math.floor(pos.getDouble(1).orElse(0.0D)),
                        (int) Math.floor(pos.getDouble(2).orElse(0.0D))
                ));
    }

    public static String describeRawPos(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return "<missing>";
        }
        return nbt.getList(ENTITY_POS_KEY).map(Object::toString).orElse("<missing>");
    }

    private static Optional<UUID> safeUuidFromIntArray(final int[] rawUuid) {
        try {
            return Optional.of(Uuids.toUuid(rawUuid));
        } catch (final IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
