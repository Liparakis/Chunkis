package io.liparakis.chunkis.world.entity.capture;

import io.liparakis.chunkis.core.ChunkDelta;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Shared entity lookup and verification helpers scoped to one chunk column.
 *
 * <p>These checks are used by both the restore and replay paths to answer the same
 * questions: which queued payload matches a UUID, whether an entity is visible from
 * the world, and what area should be searched for one chunk.</p>
 */
public final class ChunkEntityQueries {

    /**
     * Margin added to an entity's bounds for the visibility query, in blocks.
     */
    private static final double BOUNDS_MARGIN = 1.0E-4;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkEntityQueries() {
        throw new AssertionError("Utility class");
    }

    /**
     * Scans pending entities for the payload whose UUID matches {@code entityUuid}.
     *
     * <p>Uses a single-element array as a mutable capture for the lambda because
     * {@code forEachPendingEntity} does not support early exit; iteration continues
     * but skips processing once a match is found.</p>
     *
     * @param runtimeDelta delta to scan
     * @param entityUuid   UUID string to match
     * @return matching pending entity NBT, or {@code null} when absent
     */
    @Nullable
    public static CompoundTag findPendingEntityNbt(
            final ChunkDelta<BlockState, CompoundTag> runtimeDelta,
            final String entityUuid
    ) {
        final CompoundTag[] found = {null};
        runtimeDelta.forEachPendingEntity(nbt -> {
            if (found[0] != null || nbt == null) {
                return;
            }
            final String candidateUuid = EntityPayloadNbt.findUuidString(nbt)
                    .orElse(null);
            if (entityUuid.equals(candidateUuid)) {
                found[0] = nbt;
            }
        });
        return found[0];
    }

    /**
     * Parses {@code entityUuid} into a {@link UUID}, returning empty rather than
     * throwing on malformed input.
     *
     * @param entityUuid UUID string to parse
     * @return parsed UUID, or empty when the string is not a valid UUID
     */
    public static Optional<UUID> parseUuid(final String entityUuid) {
        try {
            return Optional.of(UUID.fromString(entityUuid));
        } catch (final IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} if a live entity matches the expected type and chunk.
     *
     * <p>All four conditions must hold: the entity is non-null, alive, not removed,
     * matches {@code expectedType}, and its chunk position matches
     * {@code expectedChunk}. A {@code null} {@code expectedType} always returns
     * {@code false}.</p>
     *
     * @param entity        live entity to inspect; may be {@code null}
     * @param expectedType  expected entity type; may be {@code null}
     * @param expectedChunk expected chunk coordinate
     * @return {@code true} when the entity satisfies all conditions
     */
    public static boolean isMatchingLiveEntity(
            final Entity entity,
            @Nullable final EntityType<?> expectedType,
            final ChunkPos expectedChunk
    ) {
        return entity != null
                && entity.isAlive()
                && !entity.isRemoved()
                && expectedType != null
                && entity.getType() == expectedType
                && expectedChunk.equals(entity.chunkPosition());
    }

    /**
     * Returns {@code true} if the alive, non-removed entity registered under {@code uuid} lies within
     * {@code searchBox} and appears in a spatial query.
     *
     * <p>This is a secondary confirmation on top of {@link ServerLevel#getEntity};
     * it verifies the entity is present in the world's spatial index, not just
     * registered by UUID. The spatial query covers only the entity's own bounds: a query over a whole chunk
     * column walks every entity section in it and cost far more than the replay it confirmed.</p>
     *
     * @param world     world to query
     * @param uuid      UUID of the entity to look for
     * @param searchBox precomputed bounding box to search
     * @return {@code true} when the entity is visible from the world's entity system
     */
    public static boolean isVisibleFromWorldQuery(
            final ServerLevel world,
            final UUID uuid,
            final AABB searchBox
    ) {
        final Entity registered = world.getEntity(uuid);
        if (registered == null || !registered.isAlive() || registered.isRemoved()) {
            return false;
        }
        // Grown a hair so zero-size entities such as markers still intersect their own bounds.
        final AABB bounds = registered.getBoundingBox()
                .inflate(BOUNDS_MARGIN);
        if (!searchBox.intersects(bounds)) {
            return false;
        }
        for (final Entity entity : world.getEntities(null, bounds)) {
            if (entity == registered) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the world-query box covering the full chunk column, from
     * {@link ServerLevel#getMinY()} to {@link ServerLevel#getMaxY()} + 1
     * (exclusive upper bound).
     *
     * <p>Used as the spatial query boundary for all entity lookups in one chunk.
     * The upper bound is {@code getTopYInclusive() + 1} to include blocks at the
     * top of the build height in the search.</p>
     *
     * @param world         world providing vertical bounds
     * @param chunkPosition target chunk
     * @return search box covering the full chunk column
     */
    public static AABB chunkColumnBox(final ServerLevel world, final ChunkPos chunkPosition) {
        return new AABB(
                chunkPosition.getMinBlockX(), world.getMinY(), chunkPosition.getMinBlockZ(),
                chunkPosition.getMaxBlockX() + 1, world.getMaxY() + 1, chunkPosition.getMaxBlockZ() + 1
        );
    }
}