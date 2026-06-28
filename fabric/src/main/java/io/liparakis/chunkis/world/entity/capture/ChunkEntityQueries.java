package io.liparakis.chunkis.world.entity.capture;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Shared entity lookup and verification helpers scoped to one chunk column.
 *
 * <p>These checks are used by both the restore and replay paths to answer the same
 * questions: which queued payload matches a UUID, whether an entity is visible from
 * the world, and what area should be searched for one chunk.</p>
 */
public final class ChunkEntityQueries {

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
    public static NbtCompound findPendingEntityNbt(
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            final String entityUuid
    ) {
        final NbtCompound[] found = {null};
        runtimeDelta.forEachPendingEntity(nbt -> {
            if (found[0] != null || nbt == null) {
                return;
            }
            final String candidateUuid = EntityPayloadNbt.findUuidString(nbt).orElse(null);
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
                && expectedChunk.equals(entity.getChunkPos());
    }

    /**
     * Returns {@code true} if an alive, non-removed entity with {@code uuid} appears
     * in a spatial query over {@code searchBox}.
     *
     * <p>This is a secondary confirmation on top of {@link ServerWorld#getEntity};
     * it verifies the entity is present in the world's spatial index, not just
     * registered by UUID.</p>
     *
     * @param world     world to query
     * @param uuid      UUID of the entity to look for
     * @param searchBox precomputed bounding box to search
     * @return {@code true} when the entity is visible from the world's entity system
     */
    public static boolean isVisibleFromWorldQuery(
            final ServerWorld world,
            final UUID uuid,
            final Box searchBox
    ) {
        for (final Entity entity : world.getOtherEntities(null, searchBox)) {
            if (uuid.equals(entity.getUuid()) && entity.isAlive() && !entity.isRemoved()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the world-query box covering the full chunk column, from
     * {@link ServerWorld#getBottomY()} to {@link ServerWorld#getTopYInclusive()} + 1
     * (exclusive upper bound).
     *
     * <p>Used as the spatial query boundary for all entity lookups in one chunk.
     * The upper bound is {@code getTopYInclusive() + 1} to include blocks at the
     * top of the build height in the search.</p>
     *
     * @param world           world providing vertical bounds
     * @param chunkPosition   target chunk
     * @return search box covering the full chunk column
     */
    public static Box chunkColumnBox(final ServerWorld world, final ChunkPos chunkPosition) {
        return new Box(
                chunkPosition.getStartX(), world.getBottomY(),            chunkPosition.getStartZ(),
                chunkPosition.getEndX() + 1, world.getTopYInclusive() + 1, chunkPosition.getEndZ() + 1
        );
    }
}