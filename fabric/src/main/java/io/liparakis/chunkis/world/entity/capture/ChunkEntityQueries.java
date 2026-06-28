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
 * <p>These checks are used by both restore and replay paths to answer the same
 * questions: which queued payload matches a UUID, whether an entity is really
 * visible from the world, and what area should be searched for one chunk.</p>
 */
public final class ChunkEntityQueries {

    private ChunkEntityQueries() {
        throw new AssertionError("Utility class");
    }

    /**
     * Scans pending entities for the payload whose UUID matches {@code entityUuid}.
     *
     * @param runtimeDelta delta to scan
     * @param entityUuid UUID string to match
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
     * Parses {@code entityUuid} into a UUID.
     *
     * @param entityUuid UUID string to parse
     * @return parsed UUID, or empty when invalid
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
     * @param entity live entity to inspect
     * @param expectedType expected entity type
     * @param expectedChunk expected chunk coordinate
     * @return {@code true} when the entity is alive, not removed, and matches both checks
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
     * Returns {@code true} if an alive, non-removed entity with {@code uuid}
     * appears in a spatial query over {@code searchBox}.
     *
     * @param world world to query
     * @param uuid UUID of the entity to look for
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
     * Returns the world-query box covering the full chunk column.
     *
     * @param world world providing vertical bounds
     * @param chunkPosition target chunk
     * @return search box covering the full chunk column
     */
    public static Box chunkColumnBox(final ServerWorld world, final ChunkPos chunkPosition) {
        return new Box(
                chunkPosition.getStartX(), world.getBottomY(), chunkPosition.getStartZ(),
                chunkPosition.getEndX() + 1, world.getBottomY() + world.getHeight(), chunkPosition.getEndZ() + 1
        );
    }
}
