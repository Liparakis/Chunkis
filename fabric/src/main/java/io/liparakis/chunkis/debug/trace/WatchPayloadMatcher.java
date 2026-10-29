package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchType;
import io.liparakis.chunkis.debug.util.DebugChunkKeys;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.watch.EntityWatchTracker;
import io.liparakis.chunkis.debug.watch.PayloadWatchSummaries;
import java.util.function.Consumer;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Matches active watchpoint targets against the blocks, block entities and entities
 * held in a chunk delta.
 */
final class WatchPayloadMatcher {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private WatchPayloadMatcher() {
        throw new AssertionError("Utility class");
    }

    /**
     * Checks if the given chunk delta contains block or entity updates matching active watchpoint targets.
     *
     * @param delta       chunk delta representation
     * @param target      watchpoint filter target
     * @param chunkStartX chunk X block coordinate origin start
     * @param chunkStartZ chunk Z block coordinate origin start
     * @param worldId     dimension registry ID string
     * @return true if matching updates exist
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    static boolean contains(final ChunkDeltaView<BlockState, NbtCompound> delta, final PayloadWatchTarget target,
            final int chunkStartX, final int chunkStartZ, final String worldId) {
        final boolean[] found = {false};

        if (target.type() == PayloadWatchType.BLOCK) {
            delta.forEachBlock((x, y, z, state) -> {
                if (found[0]) {
                    return;
                }
                final int worldX = chunkStartX + x;
                final int worldZ = chunkStartZ + z;
                found[0] = target.matchesBlock(worldId, PayloadWatchType.BLOCK, worldX, y, worldZ);
            });
            return found[0];
        }

        if (target.type() == PayloadWatchType.BLOCK_ENTITY) {
            delta.getBlockEntities()
                    .long2ObjectEntrySet()
                    .forEach(entry -> {
                        if (found[0]) {
                            return;
                        }
                        final LocalBlockPosition localPos = unpackLocalBlockPosition(entry.getLongKey());
                        final int worldX = chunkStartX + localPos.x();
                        final int worldZ = chunkStartZ + localPos.z();
                        found[0] = target.matchesBlock(
                                worldId,
                                PayloadWatchType.BLOCK_ENTITY,
                                worldX,
                                localPos.y(),
                                worldZ
                        );
                    });
            return found[0];
        }

        delta.forEachEntity(entityNbt -> {
            if (found[0] || entityNbt == null) {
                return;
            }
            final String uuid = PayloadWatchSummaries.entityUuid(entityNbt);
            found[0] = uuid != null && target.matchesEntity(worldId, uuid);
        });
        return found[0];
    }

    /**
     * Finds entity NBT matching watched UUID string.
     *
     * @param delta      chunk delta representation
     * @param entityUuid target entity UUID
     * @return matching entity NBT compound if found, or null
     */
    @Nullable
    static NbtCompound findWatchedEntityNbt(final ChunkDeltaView<BlockState, NbtCompound> delta,
            final String entityUuid) {
        final NbtCompound[] found = {null};
        delta.forEachEntity(entityNbt -> {
            if (found[0] != null || entityNbt == null) {
                return;
            }
            final String uuid = PayloadWatchSummaries.entityUuid(entityNbt);
            if (entityUuid.equals(uuid)) {
                found[0] = entityNbt;
            }
        });
        return found[0];
    }

    /**
     * Finds the expected block state for a watched target inside delta representation.
     *
     * @param delta    chunk delta representation
     * @param target   watchpoint filter target
     * @param chunkPos coordinates of the chunk
     * @param worldId  dimension registry ID string
     * @return expected BlockState if found, or null
     */
    @Nullable
    static BlockState findWatchedBlockState(final ChunkDeltaView<BlockState, NbtCompound> delta,
            final PayloadWatchTarget target, final ChunkPos chunkPos,
            final String worldId) {
        final BlockState[] found = {null};
        delta.forEachBlock((x, y, z, state) -> {
            if (found[0] != null) {
                return;
            }
            final int worldX = chunkPos.getStartX() + x;
            final int worldZ = chunkPos.getStartZ() + z;
            if (target.matchesBlock(worldId, PayloadWatchType.BLOCK, worldX, y, worldZ)) {
                found[0] = state;
            }
        });
        return found[0];
    }

    /**
     * Iterates over watched block states for the given chunk delta.
     *
     * @param worldId  target world dimension registry ID string
     * @param chunkPos coordinates of the chunk
     * @param delta    chunk delta representation
     * @param consumer receiver callback for matches found
     */
    static void forEachWatchedBlockState(
            final String worldId,
            final ChunkPos chunkPos,
            final ChunkDeltaView<BlockState, NbtCompound> delta,
            final Consumer<WatchedBlockState> consumer
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                DebugChunkKeys.of(chunkPos)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = findWatchedBlockState(delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            consumer.accept(new WatchedBlockState(target, expectedState, null));
        }
    }

    /**
     * Iterates over resolved block states resolving expected delta parameters.
     *
     * @param chunk         loaded world chunk instance
     * @param expectedDelta chunk delta representation containing expected states, may be null
     * @param operationId   active trace session operation ID, may be null
     * @param consumer      receiver callback for matches found
     */
    static void forEachResolvedWatchedBlockState(
            final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId,
            final Consumer<WatchedBlockState> consumer
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        final String worldId = PayloadWatchSummaries.worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                DebugChunkKeys.of(chunkPos)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState resolvedExpectedState = WatchTraceEmitter.resolveExpectedState(
                    chunk,
                    expectedDelta,
                    target,
                    chunkPos,
                    worldId
            );
            if (resolvedExpectedState == null) {
                continue;
            }
            consumer.accept(new WatchedBlockState(
                    target,
                    resolvedExpectedState,
                    WatchTraceEmitter.resolveOperationId(worldId, chunkPos, target, operationId)
            ));
        }
    }

    /**
     * Iterates over watched entity states resolving expected delta NBT parameters.
     *
     * @param world         target world instance
     * @param chunk         loaded world chunk instance
     * @param expectedDelta chunk delta representation containing expected states, may be null
     * @param operationId   active trace session operation ID, may be null
     * @param consumer      receiver callback for matches found
     */
    static void forEachWatchedEntityState(
            final ServerWorld world,
            final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId,
            final Consumer<WatchedEntityState> consumer
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || world == null || chunk == null) {
            return;
        }

        final String worldId = PayloadWatchSummaries.worldId(world);
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloads()) {
            if (target.type() != PayloadWatchType.ENTITY || !target.matchesWorld(worldId)) {
                continue;
            }
            if (!EntityWatchTracker.shouldTraceForChunk(worldId, chunkPos, target)) {
                continue;
            }

            final Entity liveEntity = WatchTraceEmitter.resolveWatchedEntity(world, target.entityUuid());
            if (liveEntity != null && !liveEntity.getChunkPos()
                    .equals(chunkPos)) {
                continue;
            }
            consumer.accept(new WatchedEntityState(
                    target,
                    WatchTraceEmitter.resolveOperationId(worldId, chunkPos, target, operationId),
                    liveEntity,
                    expectedDelta != null ? findWatchedEntityNbt(expectedDelta, target.entityUuid()) : null
            ));
        }
    }

    /**
     * Resolves matching block target if watchpoints configured.
     *
     * @param worldId target world dimension registry ID string
     * @param pos     block coordinate pos
     * @return matching watchpoint target, or null
     */
    static @Nullable PayloadWatchTarget watchedBlockTarget(final String worldId, final BlockPos pos) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return null;
        }
        return ChunkTraceWatchpoints.watchedBlock(worldId, pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Unpacks packed 64-bit coordinates into localized block positions.
     *
     * @param packedPos packed 64-bit coordinate pos
     * @return LocalBlockPosition record
     */
    static LocalBlockPosition unpackLocalBlockPosition(final long packedPos) {
        return new LocalBlockPosition(
                io.liparakis.chunkis.core.BlockInstruction.unpackX(packedPos),
                io.liparakis.chunkis.core.BlockInstruction.unpackY(packedPos),
                io.liparakis.chunkis.core.BlockInstruction.unpackZ(packedPos)
        );
    }


    /**
     * State container tracking block state match details.
     *
     * @param target        watchpoint target
     * @param expectedState expected state code
     * @param operationId   active session operation ID, may be null
     */
    record WatchedBlockState(
            PayloadWatchTarget target,
            BlockState expectedState,
            @Nullable String operationId
    ) {

    }

    /**
     * State container tracking entity state match details.
     *
     * @param target      watchpoint target
     * @param operationId active session operation ID, may be null
     * @param liveEntity  resolved live entity instance, may be null
     * @param expectedNbt expected entity NBT, may be null
     */
    record WatchedEntityState(
            PayloadWatchTarget target,
            @Nullable String operationId,
            @Nullable Entity liveEntity,
            @Nullable NbtCompound expectedNbt
    ) {

    }

    /**
     * Helper mapping relative coordinates of local block offsets.
     *
     * @param x local X coordinate offset
     * @param y local Y coordinate offset
     * @param z local Z coordinate offset
     */
    record LocalBlockPosition(int x, int y, int z) {

    }
}
