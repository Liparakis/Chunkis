package io.liparakis.chunkis.debug;

import java.util.Objects;

public record PayloadWatchTarget(
        String worldId,
        PayloadWatchType type,
        Integer blockX,
        Integer blockY,
        Integer blockZ,
        String entityUuid
) {
    public PayloadWatchTarget {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(type, "type");

        if (type == PayloadWatchType.ENTITY) {
            Objects.requireNonNull(entityUuid, "entityUuid");
        } else if (blockX == null || blockY == null || blockZ == null) {
            throw new IllegalArgumentException("block and block entity watches require coordinates");
        }
    }

    public static PayloadWatchTarget block(
            final String worldId,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        return new PayloadWatchTarget(worldId, PayloadWatchType.BLOCK, blockX, blockY, blockZ, null);
    }

    public static PayloadWatchTarget blockEntity(
            final String worldId,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        return new PayloadWatchTarget(worldId, PayloadWatchType.BLOCK_ENTITY, blockX, blockY, blockZ, null);
    }

    public static PayloadWatchTarget entity(
            final String worldId,
            final String entityUuid
    ) {
        return new PayloadWatchTarget(worldId, PayloadWatchType.ENTITY, null, null, null, entityUuid);
    }

    public boolean matchesWorld(final String otherWorldId) {
        return worldId.equals(otherWorldId);
    }

    public boolean matchesBlock(
            final String otherWorldId,
            final PayloadWatchType otherType,
            final int otherX,
            final int otherY,
            final int otherZ
    ) {
        return type == otherType
                && matchesWorld(otherWorldId)
                && blockX != null
                && blockX == otherX
                && blockY == otherY
                && blockZ == otherZ;
    }

    public boolean matchesEntity(
            final String otherWorldId,
            final String otherEntityUuid
    ) {
        return type == PayloadWatchType.ENTITY
                && matchesWorld(otherWorldId)
                && entityUuid != null
                && entityUuid.equals(otherEntityUuid);
    }

    public DebugChunkKey chunkKey() {
        if (type == PayloadWatchType.ENTITY || blockX == null || blockZ == null) {
            return null;
        }
        return new DebugChunkKey(blockX >> 4, blockZ >> 4);
    }

    public String describe() {
        return switch (type) {
            case BLOCK -> "block@" + blockX + ',' + blockY + ',' + blockZ + " world=" + worldId;
            case BLOCK_ENTITY -> "blockentity@" + blockX + ',' + blockY + ',' + blockZ + " world=" + worldId;
            case ENTITY -> "entity@" + entityUuid + " world=" + worldId;
        };
    }
}
