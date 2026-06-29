package io.liparakis.chunkis.world.entity.capture;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Reads entity payload metadata from Chunkis-managed NBT compounds.
 *
 * <p>This keeps entity replay code focused on replay decisions rather than on
 * raw NBT field parsing. All methods are null-safe: a {@code null} input
 * compound returns empty / {@code false} rather than throwing.</p>
 */
public final class EntityPayloadNbt {

    /**
     * Key for entity type ID in NBT.
     */
    private static final String ENTITY_ID_KEY = "id";

    /**
     * Key for entity UUID in NBT.
     */
    private static final String ENTITY_UUID_KEY = "UUID";

    /**
     * Key for entity position double list in NBT.
     */
    private static final String ENTITY_POS_KEY = "Pos";

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private EntityPayloadNbt() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads the {@code UUID} int-array field from {@code nbt} and converts it to a
     * {@link UUID}.
     *
     * @param nbt compound to read; may be {@code null}
     * @return parsed UUID, or empty when {@code nbt} is {@code null}, the field is
     *         absent, or the array has the wrong length
     */
    public static Optional<UUID> findUuid(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return Optional.empty();
        }
        return nbt.getIntArray(ENTITY_UUID_KEY)
                .flatMap(EntityPayloadNbt::safeUuidFromIntArray);
    }

    /**
     * Returns the entity UUID as a string, or empty when absent or malformed.
     *
     * @param nbt compound to read; may be {@code null}
     * @return {@link UUID#toString()} of the parsed UUID, or empty
     */
    public static Optional<String> findUuidString(@Nullable final NbtCompound nbt) {
        return findUuid(nbt).map(UUID::toString);
    }

    /**
     * Returns {@code true} when {@code nbt} contains a UUID field equal to
     * {@code entityUuid}.
     *
     * @param nbt        compound to read; may be {@code null}
     * @param entityUuid UUID string to match; returns {@code false} when {@code null} or blank
     * @return {@code true} iff the compound's UUID field equals {@code entityUuid}
     */
    public static boolean hasUuid(@Nullable final NbtCompound nbt, final String entityUuid) {
        return entityUuid != null
                && !entityUuid.isBlank()
                && findUuidString(nbt).map(entityUuid::equals)
                .orElse(false);
    }

    /**
     * Reads the {@code id} string field and parses it as a resource {@link Identifier}.
     *
     * @param nbt compound to read; may be {@code null}
     * @return parsed identifier, or empty when absent or unparseable
     */
    public static Optional<Identifier> findTypeId(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return Optional.empty();
        }
        return nbt.getString(ENTITY_ID_KEY)
                .map(Identifier::tryParse);
    }

    /**
     * Resolves the entity type from the {@code id} field, looking it up in
     * {@link net.minecraft.registry.Registries#ENTITY_TYPE}.
     *
     * @param nbt compound to read; may be {@code null}
     * @return registered entity type, or empty when the id is absent or unknown
     */
    public static Optional<EntityType<?>> findEntityType(@Nullable final NbtCompound nbt) {
        return findTypeId(nbt).map(Registries.ENTITY_TYPE::get);
    }

    /**
     * Reads the {@code Pos} double-list field and converts it to a {@link BlockPos} by
     * flooring each coordinate.
     *
     * <p>If any coordinate is missing from the list it defaults to {@code 0.0}, which
     * produces a potentially wrong block position rather than empty. Callers that need
     * strict validation should call {@link #findUuid} first to verify the compound is
     * well-formed.</p>
     *
     * @param nbt compound to read; may be {@code null}
     * @return floored block position, or empty when {@code nbt} is {@code null} or
     *         the list has fewer than three elements
     */
    public static Optional<BlockPos> findBlockPos(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return Optional.empty();
        }
        return nbt.getList(ENTITY_POS_KEY)
                .filter(pos -> pos.size() >= 3)
                .map(pos -> new BlockPos(
                        (int) Math.floor(pos.getDouble(0)
                                .orElse(0.0D)),
                        (int) Math.floor(pos.getDouble(1)
                                .orElse(0.0D)),
                        (int) Math.floor(pos.getDouble(2)
                                .orElse(0.0D))
                ));
    }

    /**
     * Returns a human-readable string of the raw {@code Pos} list for diagnostic
     * logging, falling back to {@code "<missing>"} when absent.
     *
     * @param nbt compound to read; may be {@code null}
     * @return raw position string, or {@code "<missing>"}
     */
    public static String describeRawPos(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return "<missing>";
        }
        return nbt.getList(ENTITY_POS_KEY)
                .map(Object::toString)
                .orElse("<missing>");
    }

    /**
     * Wraps {@link Uuids#toUuid} to return empty rather than throwing when the
     * int array is the wrong length or otherwise invalid.
     *
     * @param rawUuid raw four-element int array from NBT
     * @return parsed UUID, or empty on {@link IllegalArgumentException}
     */
    private static Optional<UUID> safeUuidFromIntArray(final int[] rawUuid) {
        try {
            return Optional.of(Uuids.toUuid(rawUuid));
        } catch (final IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}