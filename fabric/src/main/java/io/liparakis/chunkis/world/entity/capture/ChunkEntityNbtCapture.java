package io.liparakis.chunkis.world.entity.capture;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Uuids;

/**
 * Serializes live {@link Entity} instances to NBT for storage in Chunkis delta payloads.
 *
 * <p>Failure modes are intentionally non-fatal: a broken entity must not abort
 * the capture of other entities in the same chunk. All failure paths return {@code null} and log at
 * {@code DEBUG} level so they surface in verbose sessions without cluttering normal output.</p>
 *
 * <p><b>Threading:</b> must be called on the server thread. Entity NBT
 * serialization is not thread-safe.</p>
 */
public final class ChunkEntityNbtCapture {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkEntityNbtCapture() {
        throw new AssertionError("Utility class");
    }

    /**
     * Serializes {@code entity} to an {@link NbtCompound} suitable for storage in a Chunkis chunk
     * delta.
     *
     * <p>Uses Minecraft's {@link NbtWriteView} / {@link ErrorReporter.Logging}
     * pipeline so that per-field serialization errors are captured and logged rather than propagated
     * as exceptions. After serialization, {@link CisNbtUtil#ensureEntityIdPresent} injects the entity
     * type ID if the vanilla serializer omitted it.</p>
     *
     * <p>Returns {@code null} if serialization throws — for example, if the
     * entity's registry manager is unavailable or the entity type is unregistered. {@code null} tells
     * the caller to skip this entity rather than storing corrupt data.</p>
     *
     * @param entity the entity to serialize; must not be {@code null}
     * @return serialized NBT, or {@code null} on failure
     */
    public static NbtCompound serializeEntityNbt(final Entity entity) {
        try (final ErrorReporter.Logging logging = new ErrorReporter.Logging(
                entity.getErrorReporterContext(), Chunkis.LOGGER)) {
            final NbtWriteView writeView = NbtWriteView.create(logging, entity.getRegistryManager());
            entity.writeData(writeView);
            // writeData always populates the write view before returning;
            // getNbt() is assumed non-null here.
            final NbtCompound nbt = writeView.getNbt();
            CisNbtUtil.ensureEntityIdPresent(nbt, entity);
            return nbt;
        } catch (final Exception e) {
            Chunkis.LOGGER.debug("Chunkis: Skipped entity capture for {} in chunk {}", entity.getType(),
                    entity.getChunkPos(), e);
            return null;
        }
    }

    /**
     * Returns the UUID string from {@code entityNbt}, or {@code null} if the NBT is missing,
     * malformed, or contains an invalid UUID array.
     *
     * @param entityNbt entity NBT compound; may be {@code null}
     * @return UUID as a string, or {@code null}
     * @see #parseEntityUuid(NbtCompound)
     */
    public static String entityUuid(final NbtCompound entityNbt) {
        final UUID uuid = parseEntityUuid(entityNbt);
        return uuid == null ? null : uuid.toString();
    }

    /**
     * Parses the {@code UUID} int-array field from {@code entityNbt}.
     *
     * <p>{@link Uuids#toUuid} throws {@link IllegalArgumentException} if the
     * stored array is not exactly 4 ints. This is treated as a missing UUID (returns {@code null})
     * since corrupt NBT cannot be meaningfully recovered from at this layer.</p>
     *
     * @param entityNbt entity NBT compound; may be {@code null}
     * @return parsed {@link UUID}, or {@code null} if missing or invalid
     */
    public static UUID parseEntityUuid(final NbtCompound entityNbt) {
        if (entityNbt == null) {
            return null;
        }
        // Uuids.toUuid throws IllegalArgumentException for non-4-element arrays;
        // orElse(null) collapses both the missing-key and the malformed-array cases.
        return entityNbt.getIntArray("UUID")
                .map(Uuids::toUuid)
                .orElse(null);
    }
}