package io.liparakis.chunkis.world.entity.capture;

import io.liparakis.chunkis.Chunkis;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueOutput;

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
     * Serializes {@code entity} to an {@link CompoundTag} suitable for storage in a Chunkis chunk
     * delta.
     *
     * <p>Uses Minecraft's {@link TagValueOutput} / {@link ProblemReporter.ScopedCollector}
     * pipeline so that per-field serialization errors are captured and logged rather than propagated
     * as exceptions. After serialization, {@link EntityPayloadNbt#ensureEntityIdPresent} injects the entity
     * type ID if the vanilla serializer omitted it.</p>
     *
     * <p>Returns {@code null} if serialization throws - for example, if the
     * entity's registry manager is unavailable or the entity type is unregistered. {@code null} tells
     * the caller to skip this entity rather than storing corrupt data.</p>
     *
     * @param entity the entity to serialize; must not be {@code null}
     * @return serialized NBT, or {@code null} on failure
     */
    public static CompoundTag serializeEntityNbt(final Entity entity) {
        try (final ProblemReporter.ScopedCollector logging = new ProblemReporter.ScopedCollector(
                entity.problemPath(), Chunkis.LOGGER)) {
            final TagValueOutput writeView = TagValueOutput.createWithContext(logging, entity.registryAccess());
            if (!entity.saveAsPassenger(writeView)) {
                return null;
            }
            final CompoundTag nbt = writeView.buildResult();
            EntityPayloadNbt.ensureEntityIdPresent(nbt, entity);
            return nbt;
        } catch (final Exception e) {
            Chunkis.LOGGER.debug("Chunkis: Skipped entity capture for {} in chunk {}", entity.getType(),
                    entity.chunkPosition(), e);
            return null;
        }
    }

    /**
     * Returns the UUID string from {@code entityNbt}, or {@code null} if the NBT is missing,
     * malformed, or contains an invalid UUID array.
     *
     * @param entityNbt entity NBT compound; may be {@code null}
     * @return UUID as a string, or {@code null}
     * @see #parseEntityUuid(CompoundTag)
     */
    public static String entityUuid(final CompoundTag entityNbt) {
        final UUID uuid = parseEntityUuid(entityNbt);
        return uuid == null ? null : uuid.toString();
    }

    /**
     * Parses the {@code UUID} int-array field from {@code entityNbt}.
     *
     * <p>{@link UUIDUtil#uuidFromIntArray} throws {@link IllegalArgumentException} if the
     * stored array is not exactly 4 ints. This is treated as a missing UUID (returns {@code null})
     * since corrupt NBT cannot be meaningfully recovered from at this layer.</p>
     *
     * @param entityNbt entity NBT compound; may be {@code null}
     * @return parsed {@link UUID}, or {@code null} if missing or invalid
     */
    public static UUID parseEntityUuid(final CompoundTag entityNbt) {
        if (entityNbt == null) {
            return null;
        }
        // Uuids.toUuid throws for arrays that are not 4 ints; EntityPayloadNbt.findUuid absorbs that.
        return EntityPayloadNbt.findUuid(entityNbt)
                .orElse(null);
    }
}
