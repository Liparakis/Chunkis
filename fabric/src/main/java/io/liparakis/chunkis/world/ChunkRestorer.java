package io.liparakis.chunkis.world;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceInvariants;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkSectionDebugUtil;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.debug.PayloadWatchTracer;
import io.liparakis.chunkis.mixin.accessor.ChunkBlockEntityNbtAccessor;
import io.liparakis.chunkis.storage.CisNbtUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Utility for restoring chunks from Chunkis delta data.
 *
 * <p>Restoration applies an authoritative CIS snapshot from a {@link ChunkDelta} to
 * a freshly generated {@link WorldChunk}. Missing block entries mean air.</p>
 *
 * <h2>Restoration process</h2>
 * <ol>
 *   <li>clear the target chunk block grid to air</li>
 *   <li>remove stale block-entity state</li>
 *   <li>apply saved block changes directly to chunk sections</li>
 *   <li>restore compatible block entities from NBT</li>
 *   <li>replay legacy entity payloads only when the delta still owns entity persistence</li>
 *   <li>copy validated restored data into the runtime delta without marking it dirty</li>
 * </ol>
 *
 * <p><b>Threading:</b> all methods must run on the server thread. Entity spawning,
 * block entity mutation, and chunk section writes are not thread-safe.</p>
 *
 * @author Liparakis
 * @version 2.2
 */
public final class ChunkRestorer {

    private static final Logger LOGGER = Chunkis.LOGGER;
    private static final String RESTORE_SOURCE = "ChunkRestorer#restore";

    /**
     * Bitmask for local section Y coordinate.
     */
    private static final int SECTION_Y_MASK = 15;

    /**
     * NBT key for block entity and entity registry IDs.
     */
    private static final String ID_KEY = "id";

    /**
     * NBT key storing entity UUID as an int array.
     */
    private static final String UUID_KEY = "UUID";

    private ChunkRestorer() {
        throw new AssertionError("Utility class");
    }

    /**
     * Restores a chunk from persisted delta data.
     *
     * <p>Saved block entries are replayed and copied to the runtime delta. The
     * runtime delta is populated in silent mode because restoration itself is not a
     * new player edit.</p>
     *
     * <p>This method intentionally no longer optimizes away saved block entries
     * that happen to match freshly generated terrain. The generated baseline can
     * change across Minecraft versions, datapacks, and worldgen changes, so pruning
     * here would make persistence depend on a moving target.</p>
     *
     * @param world        server world context
     * @param chunk        chunk to restore into
     * @param protoDelta   source delta loaded from disk/proto state
     * @param runtimeDelta runtime delta to populate with validated changes
     */
    public static void restore(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta
    ) {
        restore(
                world,
                chunk,
                protoDelta,
                runtimeDelta,
                null
        );
    }

    public static void restore(
            final ServerWorld world,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> protoDelta,
            final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            final String operationId
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(protoDelta, "protoDelta");

        final RestorationVisitor visitor = new RestorationVisitor(
                world,
                chunk,
                protoDelta,
                runtimeDelta,
                operationId
        );
        final ChunkPos chunkPos = chunk.getPos();
        final boolean hasPersistedBaseChunk =
                CisNbtUtil.hasPersistedBaseChunkNbt(protoDelta.getChunkMetadata());
        final boolean invalidBlockEntityOnlyPayloadWithoutBase =
                ChunkTraceInvariants.hasInvalidBlockEntityOnlyPayloadWithoutBase(
                        protoDelta,
                        hasPersistedBaseChunk
                );

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.RESTORE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                RESTORE_SOURCE,
                "starting restore with decoded payload: " + describeReplayPayload(protoDelta)
                        + ", baseChunkNbt="
                        + (hasPersistedBaseChunk ? "used" : "missing"),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                operationId,
                protoDelta.isDirty(),
                null
        );
        PayloadWatchTracer.traceRestoreStarted(world, chunkPos, chunk, protoDelta, operationId);
        if (hasPersistedBaseChunk) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.SNAPSHOT_BACKED_RESTORE,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.NONE,
                    RESTORE_SOURCE,
                    "restoring chunk from persisted base snapshot plus sparse delta",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkPos.x, chunkPos.z),
                    null,
                    operationId,
                    protoDelta.isDirty(),
                    null
            );
        }
        if (invalidBlockEntityOnlyPayloadWithoutBase) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.INVALID_PAYLOAD,
                    RESTORE_SOURCE,
                    "blockEntities without blockChanges require persisted base chunk NBT",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkPos.x, chunkPos.z),
                    null,
                    operationId,
                    protoDelta.isDirty(),
                    null
            );
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.RESTORE_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.INVALID_PAYLOAD,
                    RESTORE_SOURCE,
                    "skipped restore because sparse block-entity payload had no base snapshot",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkPos.x, chunkPos.z),
                    null,
                    operationId,
                    protoDelta.isDirty(),
                    null
            );
            return;
        }

        try {
            if (!hasPersistedBaseChunk) {
                clearChunkToAir(chunk);
            }
            visitor.cleanupReplayedEntities(protoDelta);
            protoDelta.accept(visitor);
            visitor.finishRestoration();
        } catch (final RuntimeException e) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.RESTORE_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.RESTORE_EXCEPTION,
                    RESTORE_SOURCE,
                    "restore failed with exception",
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkPos.x, chunkPos.z),
                    null,
                    operationId,
                    null,
                    null
            );
            throw e;
        }

        final int appliedCount = visitor.appliedBlocksCount()
                + visitor.restoredBlockEntitiesCount()
                + visitor.restoredEntitiesCount();
        final boolean restoreEmptyResult = ChunkTraceInvariants.shouldReportRestoreEmptyResult(
                protoDelta,
                appliedCount,
                hasPersistedBaseChunk
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SPARSE_DELTA_APPLIED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                RESTORE_SOURCE,
                "sparse delta replay: blocks=" + visitor.appliedBlocksCount()
                        + ", blockEntities=" + visitor.restoredBlockEntitiesCount()
                        + ", entities=" + visitor.restoredEntitiesCount(),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                operationId,
                runtimeDelta != null && runtimeDelta.isDirty(),
                null
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.PROTO_CHUNK_SECTIONS_AFTER_DELTA,
                ChunkTraceSeverity.INFO,
                restoreEmptyResult ? ChunkTraceReason.RESTORE_EMPTY_RESULT : ChunkTraceReason.NONE,
                RESTORE_SOURCE,
                "server chunk after sparse replay: " + ChunkSectionDebugUtil.summarize(chunk),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                operationId,
                runtimeDelta != null && runtimeDelta.isDirty(),
                null
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.RESTORE_COMPLETED,
                ChunkTraceSeverity.INFO,
                restoreEmptyResult ? ChunkTraceReason.RESTORE_EMPTY_RESULT : ChunkTraceReason.NONE,
                RESTORE_SOURCE,
                "restore completed: blocks=" + visitor.appliedBlocksCount()
                        + ", blockEntities=" + visitor.restoredBlockEntitiesCount()
                        + ", entities=" + visitor.restoredEntitiesCount()
                        + ", blockReplay=" + visitor.blockApplyFailureCounters().describe(),
                world.getRegistryKey().getValue().toString(),
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                operationId,
                runtimeDelta != null && runtimeDelta.isDirty(),
                null
        );

        if (ChunkTraceInvariants.shouldAssertNonEmptyRestore(
                protoDelta,
                appliedCount,
                hasPersistedBaseChunk
        )) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.RESTORE_EMPTY_RESULT,
                    RESTORE_SOURCE,
                    "restore replay payload produced zero applied results: blocks="
                            + protoDelta.getBlockInstructions().size()
                            + ", blockEntities="
                            + protoDelta.getBlockEntities().size(),
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkPos.x, chunkPos.z),
                    null,
                    operationId,
                    runtimeDelta != null && runtimeDelta.isDirty(),
                    null
            );
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.RESTORE_EMPTY_RESULT,
                    RESTORE_SOURCE,
                    "restore zero-result diagnostics: payload=" + describeReplayPayload(protoDelta)
                            + ", blockReplay=" + visitor.blockApplyFailureCounters().describe(),
                    world.getRegistryKey().getValue().toString(),
                    new DebugChunkKey(chunkPos.x, chunkPos.z),
                    null,
                    operationId,
                    runtimeDelta != null && runtimeDelta.isDirty(),
                    null
            );
        }
    }

    /**
     * Clears every non-empty section in the target chunk back to air before snapshot replay.
     *
     * <p>This enforces the authoritative snapshot rule: missing CIS block entries
     * mean air, not "keep whatever terrain is currently in the chunk". Block-entity
     * maps are cleared afterward so stale serialized or live block-entity state
     * cannot survive the reset.</p>
     */
    private static void clearChunkToAir(final WorldChunk chunk) {
        final ChunkSection[] sections = chunk.getSectionArray();
        for (final ChunkSection section : sections) {
            if (section == null || section.isEmpty()) {
                continue;
            }

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        section.setBlockState(x, y, z, Blocks.AIR.getDefaultState());
                    }
                }
            }
        }

        for (final BlockPos pos : Set.copyOf(chunk.getBlockEntities().keySet())) {
            removeStaleBlockEntityData(chunk, pos);
        }
        ((ChunkBlockEntityNbtAccessor) chunk).chunkis$getBlockEntityNbts().clear();
    }

    /**
     * Applies one block state directly to a chunk section.
     *
     * <p>This avoids the normal high-level block mutation path because restoration
     * is replaying already validated persisted data. Section and height bounds are
     * checked before the write so corrupt delta entries cannot explode the entire
     * restore pass.</p>
     *
     * @param chunk         chunk to mutate
     * @param chunkPosition chunk position, used for logging
     * @param localX        local chunk X coordinate
     * @param localY        absolute world Y coordinate
     * @param localZ        local chunk Z coordinate
     * @param state         state to write
     * @param worldPosition absolute world position, used for stale block entity cleanup/logging
     * @return {@code true} if the block was applied
     */
    private static boolean applyBlockChange(
            final WorldChunk chunk,
            final ChunkPos chunkPosition,
            final int localX,
            final int localY,
            final int localZ,
            final BlockState state,
            final BlockPos worldPosition,
            final BlockApplyFailureCounters counters,
            @org.jetbrains.annotations.Nullable final String operationId
    ) {
        final BlockState previousState = chunk.getBlockState(worldPosition);
        PayloadWatchTracer.traceRestoreApplyAttempt(
                chunk,
                worldPosition,
                previousState,
                state,
                operationId,
                "ChunkRestorer#applyBlockChange"
        );
        if (localY < chunk.getBottomY() || localY > chunk.getTopYInclusive()) {
            counters.recordOutOfBoundsY();
            PayloadWatchTracer.traceRestoreApplyFailed(
                    chunk,
                    worldPosition,
                    previousState,
                    state,
                    operationId,
                    "ChunkRestorer#applyBlockChange",
                    "out-of-bounds-y"
            );
            LOGGER.warn(
                    "Skipping out-of-bounds restored block at {} in chunk {}",
                    worldPosition,
                    chunkPosition
            );
            return false;
        }

        try {
            final int sectionIndex = chunk.getSectionIndex(localY);

            if (sectionIndex < 0 || sectionIndex >= chunk.getSectionArray().length) {
                counters.recordInvalidSectionIndex();
                PayloadWatchTracer.traceRestoreApplyFailed(
                        chunk,
                        worldPosition,
                        previousState,
                        state,
                        operationId,
                        "ChunkRestorer#applyBlockChange",
                        "invalid-section-index"
                );
                LOGGER.warn(
                        "Skipping restored block at {} in chunk {} with invalid section index {}",
                        worldPosition,
                        chunkPosition,
                        sectionIndex
                );
                return false;
            }

            final ChunkSection section = chunk.getSection(sectionIndex);

            if (section == null) {
                counters.recordNullSection();
                PayloadWatchTracer.traceRestoreApplyFailed(
                        chunk,
                        worldPosition,
                        previousState,
                        state,
                        operationId,
                        "ChunkRestorer#applyBlockChange",
                        "null-section"
                );
                LOGGER.warn(
                        "Skipping restored block at {} in chunk {} because section {} is null",
                        worldPosition,
                        chunkPosition,
                        sectionIndex
                );
                return false;
            }

            section.setBlockState(localX, localY & SECTION_Y_MASK, localZ, state);
            PayloadWatchTracer.traceRestoreSetBlockReturned(
                    chunk,
                    worldPosition,
                    previousState,
                    state,
                    operationId,
                    "ChunkRestorer#applyBlockChange"
            );
            PayloadWatchTracer.traceRestoreStateAfterSetBlock(
                    chunk,
                    worldPosition,
                    previousState,
                    state,
                    operationId,
                    "ChunkRestorer#applyBlockChange"
            );

            if (!state.hasBlockEntity()) {
                removeStaleBlockEntityData(chunk, worldPosition);
            }

            return true;
        } catch (final Exception e) {
            counters.recordException();
            PayloadWatchTracer.traceRestoreApplyFailed(
                    chunk,
                    worldPosition,
                    previousState,
                    state,
                    operationId,
                    "ChunkRestorer#applyBlockChange",
                    "exception"
            );
            LOGGER.error(
                    "Failed to restore block at {} in chunk {}",
                    worldPosition,
                    chunkPosition,
                    e
            );
            return false;
        }
    }

    /**
     * Removes both pending and live block entity state for a restored non-BE block.
     *
     * <p>During restoration, vanilla may still have pending block entity NBT or a
     * live block entity object from the serialized source. If Chunkis restores the
     * block grid to air/tuff/deepslate/etc., those stale payloads must be removed
     * or Minecraft can later resurrect invalid block entities.</p>
     *
     * @param chunk         chunk being restored
     * @param worldPosition position whose stale block entity data should be removed
     */
    private static void removeStaleBlockEntityData(
            final WorldChunk chunk,
            final BlockPos worldPosition
    ) {
        removePendingBlockEntityNbt(chunk, worldPosition);
        removeLiveBlockEntity(chunk, worldPosition);
    }

    /**
     * Removes pending vanilla block entity NBT for a restored position.
     *
     * <p>The lazy DUMMY block-entity path reads from {@code Chunk.blockEntityNbts}.
     * Leaving stale entries there can make Minecraft attempt to load block entity
     * NBT after Chunkis has restored the block grid to a non-BE state.</p>
     *
     * @param chunk         chunk being restored
     * @param worldPosition target position
     */
    private static void removePendingBlockEntityNbt(
            final WorldChunk chunk,
            final BlockPos worldPosition
    ) {
        ((ChunkBlockEntityNbtAccessor) chunk)
                .chunkis$getBlockEntityNbts()
                .remove(worldPosition);
    }

    /**
     * Removes a live block entity even while the chunk is not tick-ready.
     *
     * <p>{@link WorldChunk#removeBlockEntity(BlockPos)} can be gated by ticker
     * readiness in vanilla paths. Removing from the live map first prevents stale
     * block entity objects from surviving restoration and receiving tickers later.</p>
     *
     * @param chunk         chunk being restored
     * @param worldPosition target position
     */
    private static void removeLiveBlockEntity(
            final WorldChunk chunk,
            final BlockPos worldPosition
    ) {
        chunk.getBlockEntities().remove(worldPosition);
        chunk.removeBlockEntity(worldPosition);
    }

    /**
     * Single-use visitor that processes delta entries during restoration.
     *
     * <p>The visitor applies saved entries into the generated chunk and forwards
     * validated data to the runtime delta. It does not prune block entries against
     * generated terrain.</p>
     */
    private static final class RestorationVisitor
            implements ChunkDelta.DeltaVisitor<BlockState, NbtCompound> {

        private final ServerWorld world;
        private final WorldChunk chunk;
        private final ChunkPos chunkPosition;
        private final ChunkDelta<BlockState, NbtCompound> runtimeDelta;
        private final boolean replayLegacyEntities;
        private final String operationId;
        private final BlockApplyFailureCounters blockApplyFailureCounters;
        private int appliedBlocksCount;
        private int restoredBlockEntitiesCount;
        private int restoredEntitiesCount;

        private RestorationVisitor(
                final ServerWorld world,
                final WorldChunk chunk,
                final ChunkDelta<BlockState, NbtCompound> sourceDelta,
                final ChunkDelta<BlockState, NbtCompound> runtimeDelta,
                final String operationId
        ) {
            this.world = world;
            this.chunk = chunk;
            this.chunkPosition = chunk.getPos();
            this.runtimeDelta = runtimeDelta;
            this.replayLegacyEntities = shouldReplayLegacyEntities(sourceDelta);
            this.operationId = operationId;
            this.blockApplyFailureCounters = new BlockApplyFailureCounters();
        }

        /**
         * Removes regenerated non-player entities before replaying legacy persisted entities.
         *
         * <p>This prevents one-time vanilla population side effects from stacking
         * duplicate mobs/entities on top of the entities already owned by the legacy
         * delta payload.</p>
         *
         * @param sourceDelta loaded source delta
         */
        private void cleanupReplayedEntities(
                final ChunkDelta<BlockState, NbtCompound> sourceDelta
        ) {
            if (!shouldCleanupReplayedEntities(sourceDelta)) {
                return;
            }

            final Set<UUID> allowedUuids = collectPersistedEntityUuids(sourceDelta);
            final List<Entity> liveEntities = world.getOtherEntities(
                    null,
                    chunkEntitySearchBox()
            );

            for (final Entity entity : liveEntities) {
                if (entity instanceof PlayerEntity) {
                    continue;
                }

                if (allowedUuids.contains(entity.getUuid())) {
                    continue;
                }

                entity.discard();
            }
        }

        /**
         * Returns whether regenerated entities should be cleaned before replay.
         *
         * @param sourceDelta loaded source delta
         * @return {@code true} when cleanup is required
         */
        private boolean shouldCleanupReplayedEntities(
                final ChunkDelta<BlockState, NbtCompound> sourceDelta
        ) {
            return replayLegacyEntities
                    && sourceDelta != null
                    && sourceDelta.shouldSuppressInitialRepopulation();
        }

        /**
         * Builds the world-space entity search box for this chunk.
         *
         * @return chunk-height entity search box
         */
        private Box chunkEntitySearchBox() {
            return new Box(
                    chunkPosition.getStartX(),
                    world.getBottomY(),
                    chunkPosition.getStartZ(),
                    chunkPosition.getEndX() + 1,
                    world.getBottomY() + world.getHeight(),
                    chunkPosition.getEndZ() + 1
            );
        }

        /**
         * Restores a saved block change.
         *
         * @param localX local chunk X coordinate
         * @param localY absolute world Y coordinate
         * @param localZ local chunk Z coordinate
         * @param state  saved block state
         */
        @Override
        public void visitBlock(
                final int localX,
                final int localY,
                final int localZ,
                final BlockState state
        ) {
            blockApplyFailureCounters.recordVisitedInstruction();
            if (state == null) {
                blockApplyFailureCounters.recordNullState();
                return;
            }

            final BlockPos worldPos = chunkPosition.getBlockPos(localX, localY, localZ);
            final BlockState previousState = chunk.getBlockState(worldPos);
            PayloadWatchTracer.traceRestoreInstructionVisited(
                    chunk,
                    worldPos,
                    previousState,
                    state,
                    operationId,
                    "ChunkRestorer.RestorationVisitor#visitBlock"
            );

            if (!applyBlockChange(
                    chunk,
                    chunkPosition,
                    localX,
                    localY,
                    localZ,
                    state,
                    worldPos,
                    blockApplyFailureCounters,
                    operationId
            )) {
                PayloadWatchTracer.traceRestoreBlockFailure(
                        world,
                        chunkPosition,
                        worldPos,
                        operationId,
                        "restore failed before block reached live world"
                );
                return;
            }

            copyBlockToRuntimeDelta(localX, localY, localZ, state);
            blockApplyFailureCounters.recordAppliedBlock();
            appliedBlocksCount++;
            PayloadWatchTracer.traceRestoredBlock(chunk, worldPos, state, operationId);
        }

        /**
         * Restores a saved block entity payload.
         *
         * @param localX local chunk X coordinate
         * @param localY absolute world Y coordinate
         * @param localZ local chunk Z coordinate
         * @param nbt    block entity NBT
         */
        @Override
        public void visitBlockEntity(
                final int localX,
                final int localY,
                final int localZ,
                final NbtCompound nbt
        ) {
            if (nbt == null) {
                return;
            }

            restoreBlockEntity(localX, localY, localZ, nbt);
        }

        /**
         * Replays a legacy entity payload when required.
         *
         * @param nbt entity NBT
         */
        @Override
        public void visitEntity(final NbtCompound nbt) {
            if (replayLegacyEntities && nbt != null) {
                restoreEntity(nbt);
            }
        }

        /**
         * Performs final runtime-delta cleanup after restoration.
         *
         * <p>Legacy entities are replayed once into vanilla entity storage. They are
         * then removed from the runtime delta so future saves do not keep owning
         * entity persistence through Chunkis.</p>
         */
        private void finishRestoration() {
            if (replayLegacyEntities && runtimeDelta != null)
                runtimeDelta.clearPendingEntities();
            if (runtimeDelta != null)
                runtimeDelta.markSaved();
        }

        /**
         * Copies a restored block to the runtime delta in no-dirty mode.
         *
         * @param localX local chunk X coordinate
         * @param localY absolute world Y coordinate
         * @param localZ local chunk Z coordinate
         * @param state  restored block state
         */
        private void copyBlockToRuntimeDelta(
                final int localX,
                final int localY,
                final int localZ,
                final BlockState state
        ) {
            if (runtimeDelta != null) {
                runtimeDelta.addBlockChange(localX, localY, localZ, state, false);
            }
        }

        /**
         * Restores a block entity from NBT.
         *
         * <p>The current block must support block entities, and the NBT {@code id}
         * must match a block entity type compatible with that block state. Stale or
         * migrated mismatches are skipped instead of letting vanilla throw and return
         * {@code null} internally.</p>
         *
         * @param localX local chunk X coordinate
         * @param localY absolute world Y coordinate
         * @param localZ local chunk Z coordinate
         * @param nbt    serialized block entity NBT
         */
        private void restoreBlockEntity(
                final int localX,
                final int localY,
                final int localZ,
                final NbtCompound nbt
        ) {
            final BlockPos worldPos = chunkPosition.getBlockPos(localX, localY, localZ);
            final BlockState currentState = chunk.getBlockState(worldPos);

            if (!currentState.hasBlockEntity()) {
                PayloadWatchTracer.traceRestoreBlockEntitySkipped(
                        world,
                        chunkPosition,
                        worldPos,
                        operationId,
                        "restore skipped: missing block state"
                );
                return;
            }

            if (!isBlockEntityNbtCompatibleWithState(nbt, currentState)) {
                PayloadWatchTracer.traceRestoreBlockEntitySkipped(
                        world,
                        chunkPosition,
                        worldPos,
                        operationId,
                        "restore skipped: block entity type incompatible with current block state"
                );
                return;
            }

            final BlockEntity blockEntity = BlockEntity.createFromNbt(
                    worldPos,
                    currentState,
                    nbt,
                    world.getRegistryManager()
            );

            if (blockEntity == null) {
                LOGGER.warn("Failed to create block entity from NBT at {}", worldPos);
                PayloadWatchTracer.traceRestoreBlockEntitySkipped(
                        world,
                        chunkPosition,
                        worldPos,
                        operationId,
                        "restore skipped: block entity could not be created from NBT"
                );
                return;
            }

            chunk.removeBlockEntity(worldPos);
            chunk.addBlockEntity(blockEntity);

            if (runtimeDelta != null) {
                runtimeDelta.addBlockEntityData(localX, localY, localZ, nbt, false);
            }
            restoredBlockEntitiesCount++;
            PayloadWatchTracer.traceRestoredBlockEntity(world, chunkPosition, worldPos, blockEntity, nbt, operationId);
        }

        /**
         * Returns whether block entity NBT is compatible with the current block state.
         *
         * @param nbt          block entity NBT
         * @param currentState current block state at the target position
         * @return {@code true} when the NBT id maps to a type supporting the state
         */
        private static boolean isBlockEntityNbtCompatibleWithState(
                final NbtCompound nbt,
                final BlockState currentState
        ) {
            final Optional<String> rawId = nbt.getString(ID_KEY);

            if (rawId.isEmpty()) {
                return false;
            }

            final Identifier id = Identifier.tryParse(rawId.get());

            if (id == null) {
                return false;
            }

            final BlockEntityType<?> type = Registries.BLOCK_ENTITY_TYPE.get(id);

            return type != null && type.supports(currentState);
        }

        /**
         * Deserializes and spawns an entity from legacy entity NBT.
         *
         * <p>{@link EntityType#loadEntityWithPassengers} handles all vanilla entity
         * types, including vehicles with passengers. UUID checks prevent duplicate
         * spawning if the same delta is applied more than once.</p>
         *
         * @param nbt serialized entity NBT
         */
        private void restoreEntity(final NbtCompound nbt) {
            EntityType.loadEntityWithPassengers(
                    nbt,
                    world,
                    SpawnReason.LOAD,
                    entity -> {
                        if (!isEntityAlreadySpawned(entity.getUuid())) {
                            world.spawnEntity(entity);
                            restoredEntitiesCount++;
                            PayloadWatchTracer.traceRestoredEntity(world, chunkPosition, entity, nbt, operationId);
                        } else {
                            PayloadWatchTracer.traceRestoreEntitySkipped(
                                    world,
                                    chunkPosition,
                                    entity.getUuidAsString(),
                                    operationId,
                                    "restore skipped: entity already present in world"
                            );
                        }

                        return entity;
                    }
            );
        }

        /**
         * Returns whether the source delta still carries legacy entity payloads that
         * must be replayed once before vanilla entity storage takes over.
         *
         * @param sourceDelta loaded source delta
         * @return {@code true} if legacy entity replay is needed
         */
        private static boolean shouldReplayLegacyEntities(
                final ChunkDelta<BlockState, NbtCompound> sourceDelta
        ) {
            return sourceDelta != null
                    && sourceDelta.countNonNullEntities() > 0
                    && !CisNbtUtil.hasPersistedBaseChunkNbt(sourceDelta.getChunkMetadata());
        }

        /**
         * Returns whether an entity UUID already exists in the world.
         *
         * @param uuid entity UUID
         * @return {@code true} if already present
         */
        private boolean isEntityAlreadySpawned(final UUID uuid) {
            return world.getEntity(uuid) != null;
        }

        /**
         * Extracts valid entity UUIDs from persisted legacy entity payloads.
         *
         * @param sourceDelta loaded source delta
         * @return UUID allowlist for entities that should survive cleanup
         */
        private static Set<UUID> collectPersistedEntityUuids(
                final ChunkDelta<BlockState, NbtCompound> sourceDelta
        ) {
            final Set<UUID> uuids = new HashSet<>();

            sourceDelta.forEachEntity(nbt -> {
                if (nbt == null) {
                    return;
                }

                nbt.getIntArray(UUID_KEY)
                        .flatMap(RestorationVisitor::safeUuidFromIntArray)
                        .ifPresent(uuids::add);
            });

            return uuids;
        }

        /**
         * Converts a Minecraft UUID int-array payload safely.
         *
         * @param rawUuid raw UUID int array
         * @return UUID if valid
         */
        private static Optional<UUID> safeUuidFromIntArray(final int[] rawUuid) {
            try {
                return Optional.of(Uuids.toUuid(rawUuid));
            } catch (final RuntimeException ignored) {
                return Optional.empty();
            }
        }

        private int appliedBlocksCount() {
            return appliedBlocksCount;
        }

        private int restoredBlockEntitiesCount() {
            return restoredBlockEntitiesCount;
        }

        private int restoredEntitiesCount() {
            return restoredEntitiesCount;
        }

        private BlockApplyFailureCounters blockApplyFailureCounters() {
            return blockApplyFailureCounters;
        }
    }

    static String describeReplayPayload(final ChunkDelta<?, NbtCompound> delta) {
        if (delta == null) {
            return "sections=[], blockChanges=[], blockEntities=[]";
        }

        final TreeSet<Integer> sections = new TreeSet<>();
        final List<String> blockChanges = new ArrayList<>();
        final List<String> blockEntities = new ArrayList<>();

        delta.forEachBlock((x, y, z, state) -> {
            sections.add(y >> 4);
            blockChanges.add("(" + x + "," + y + "," + z + ")=" + state);
        });

        delta.getBlockEntities().forEach((packedPos, nbt) -> {
            final int x = io.liparakis.chunkis.core.BlockInstruction.unpackX(packedPos);
            final int y = io.liparakis.chunkis.core.BlockInstruction.unpackY(packedPos);
            final int z = io.liparakis.chunkis.core.BlockInstruction.unpackZ(packedPos);
            sections.add(y >> 4);
            final String id = nbt == null
                    ? "null"
                    : nbt.getString(ID_KEY).orElse("<missing-id>");
            blockEntities.add("(" + x + "," + y + "," + z + ")=" + id);
        });

        blockEntities.sort(String::compareTo);

        return "sections=" + joinIntegers(sections)
                + ", blockChanges=" + blockChanges
                + ", blockEntities=" + blockEntities;
    }

    private static String joinIntegers(final Set<Integer> values) {
        final StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (final int value : values) {
            if (!first) {
                builder.append(',');
            }
            builder.append(value);
            first = false;
        }
        return builder.append(']').toString();
    }

    static final class BlockApplyFailureCounters {

        private int visitedInstructions;
        private int appliedBlocks;
        private int nullState;
        private int outOfBoundsY;
        private int invalidSectionIndex;
        private int nullSection;
        private int exception;

        void recordVisitedInstruction() {
            visitedInstructions++;
        }

        void recordAppliedBlock() {
            appliedBlocks++;
        }

        void recordNullState() {
            nullState++;
        }

        void recordOutOfBoundsY() {
            outOfBoundsY++;
        }

        void recordInvalidSectionIndex() {
            invalidSectionIndex++;
        }

        void recordNullSection() {
            nullSection++;
        }

        void recordException() {
            exception++;
        }

        String describe() {
            return "visited=" + visitedInstructions
                    + ", applied=" + appliedBlocks
                    + ", nullState=" + nullState
                    + ", outOfBoundsY=" + outOfBoundsY
                    + ", invalidSectionIndex=" + invalidSectionIndex
                    + ", nullSection=" + nullSection
                    + ", exception=" + exception;
        }
    }
}
