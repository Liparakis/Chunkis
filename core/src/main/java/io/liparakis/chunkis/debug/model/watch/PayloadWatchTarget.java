package io.liparakis.chunkis.debug.model.watch;

import io.liparakis.chunkis.debug.model.key.DebugChunkKey;

import java.util.Objects;

/**
 * Immutable payload-level watch target used by the chunk trace debugger.
 *
 * <p>A target can represent exactly one logical kind of payload watch:</p>
 * <ul>
 *     <li>{@link PayloadWatchType#BLOCK}: a block position in a world.</li>
 *     <li>{@link PayloadWatchType#BLOCK_ENTITY}: a block entity position in a world.</li>
 *     <li>{@link PayloadWatchType#ENTITY}: an entity UUID in a world.</li>
 * </ul>
 *
 * <p>The compact constructor enforces the minimum required fields for each type while preserving
 * the existing public record shape. Prefer the static factories because they make invalid component
 * combinations harder to create.</p>
 *
 * @param worldId    world identifier that scopes the target; never {@code null}.
 * @param type       kind of payload target; never {@code null}.
 * @param blockX     world-space block x coordinate for block-based targets, otherwise usually {@code null}.
 * @param blockY     world-space block y coordinate for block-based targets, otherwise usually {@code null}.
 * @param blockZ     world-space block z coordinate for block-based targets, otherwise usually {@code null}.
 * @param entityUuid entity UUID string for entity targets, otherwise usually {@code null}.
 */
public record PayloadWatchTarget(
        String worldId,
        PayloadWatchType type,
        Integer blockX,
        Integer blockY,
        Integer blockZ,
        String entityUuid
) {

    /**
     * Validates required components for the chosen watch type.
     *
     * <p>Entity watches require an entity UUID. Block and block-entity watches require all three
     * block coordinates. Extra components are not rejected to preserve the record constructor's
     * existing compatibility; callers should use the static factories for canonical targets.</p>
     *
     * @throws NullPointerException     when {@code worldId}, {@code type}, or a required entity UUID is null.
     * @throws IllegalArgumentException when a block-based target is missing any coordinate.
     */
    public PayloadWatchTarget {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(type, "type");

        if (type == PayloadWatchType.ENTITY) {
            Objects.requireNonNull(entityUuid, "entityUuid");
        } else if (blockX == null || blockY == null || blockZ == null) {
            throw new IllegalArgumentException("block and block entity watches require coordinates");
        }
    }

    /**
     * Creates a block payload watch target for a world-space block position.
     *
     * @param worldId world identifier that scopes the watch.
     * @param blockX  world-space block x coordinate.
     * @param blockY  world-space block y coordinate.
     * @param blockZ  world-space block z coordinate.
     * @return immutable block watch target.
     */
    public static PayloadWatchTarget block(
            final String worldId,
            final int blockX,
            final int blockY,
            final int blockZ
                                          ) {
        return new PayloadWatchTarget(worldId, PayloadWatchType.BLOCK, blockX, blockY, blockZ, null);
    }

    /**
     * Creates a block-entity payload watch target for a world-space block position.
     *
     * @param worldId world identifier that scopes the watch.
     * @param blockX  world-space block x coordinate.
     * @param blockY  world-space block y coordinate.
     * @param blockZ  world-space block z coordinate.
     * @return immutable block-entity watch target.
     */
    public static PayloadWatchTarget blockEntity(
            final String worldId,
            final int blockX,
            final int blockY,
            final int blockZ
                                                ) {
        return new PayloadWatchTarget(worldId, PayloadWatchType.BLOCK_ENTITY, blockX, blockY, blockZ, null);
    }

    /**
     * Creates an entity payload watch target scoped to a world.
     *
     * @param worldId    world identifier that scopes the watch.
     * @param entityUuid entity UUID string to watch.
     * @return immutable entity watch target.
     */
    public static PayloadWatchTarget entity(
            final String worldId,
            final String entityUuid
                                           ) {
        return new PayloadWatchTarget(worldId, PayloadWatchType.ENTITY, null, null, null, entityUuid);
    }

    /**
     * Returns whether this target belongs to the supplied world.
     *
     * @param otherWorldId candidate world identifier.
     * @return {@code true} when {@code otherWorldId} equals this target's world identifier.
     */
    public boolean matchesWorld(final String otherWorldId) {
        return worldId.equals(otherWorldId);
    }

    /**
     * Returns whether this target represents a block-based watch at the supplied position.
     *
     * <p>The target type must match exactly, which lets callers distinguish normal block watches
     * from block-entity watches even when they share coordinates.</p>
     *
     * @param otherWorldId candidate world identifier.
     * @param otherType    expected block-based payload watch type.
     * @param otherX       candidate world-space block x coordinate.
     * @param otherY       candidate world-space block y coordinate.
     * @param otherZ       candidate world-space block z coordinate.
     * @return {@code true} when type, world, and all coordinates match.
     */
    public boolean matchesBlock(
            final String otherWorldId,
            final PayloadWatchType otherType,
            final int otherX,
            final int otherY,
            final int otherZ
                               ) {
        return type == otherType
                && matchesWorld(otherWorldId)
                && hasBlockCoordinates()
                && blockX == otherX
                && blockY == otherY
                && blockZ == otherZ;
    }

    /**
     * Returns whether this target represents the supplied entity in the supplied world.
     *
     * @param otherWorldId    candidate world identifier.
     * @param otherEntityUuid candidate entity UUID string.
     * @return {@code true} when this is an entity watch for the same world and UUID.
     */
    public boolean matchesEntity(
            final String otherWorldId,
            final String otherEntityUuid
                                ) {
        return type == PayloadWatchType.ENTITY
                && matchesWorld(otherWorldId)
                && entityUuid != null
                && entityUuid.equals(otherEntityUuid);
    }

    /**
     * Returns whether all block coordinate components are present.
     *
     * <p>This is mainly useful for code that handles the public canonical constructor, where an
     * entity target can technically carry extra coordinate components for compatibility.</p>
     *
     * @return {@code true} when x, y, and z block coordinates are non-null.
     */
    public boolean hasBlockCoordinates() {
        return blockX != null && blockY != null && blockZ != null;
    }

    /**
     * Returns the chunk containing this target's block coordinates.
     *
     * <p>Only block-based targets have chunk positions. Entity targets return {@code null} because
     * their position is not represented by this record. Coordinates are converted with {@code >> 4},
     * matching Minecraft-style 16-block chunk addressing, including negative coordinates.</p>
     *
     * @return chunk key for block-based targets, or {@code null} for entity targets or malformed targets.
     */
    public DebugChunkKey chunkKey() {
        if (type == PayloadWatchType.ENTITY || blockX == null || blockZ == null) {
            return null;
        }
        return new DebugChunkKey(blockX >> 4, blockZ >> 4);
    }

    /**
     * Returns a compact human-readable description for logs and debug output.
     *
     * <p>The format is intentionally stable and simple rather than localized because trace output is
     * normally consumed by developers and tooling.</p>
     *
     * @return description containing target type, target identity, and world identifier.
     */
    public String describe() {
        return switch (type) {
            case BLOCK -> "block@" + blockX + ',' + blockY + ',' + blockZ + " world=" + worldId;
            case BLOCK_ENTITY -> "blockentity@" + blockX + ',' + blockY + ',' + blockZ + " world=" + worldId;
            case ENTITY -> "entity@" + entityUuid + " world=" + worldId;
        };
    }
}
