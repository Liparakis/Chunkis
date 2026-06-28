package io.liparakis.chunkis.debug;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchType;
import io.liparakis.chunkis.world.ChunkMutationTrackingScope;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PayloadWatchTracer {

    private static final long ENTITY_RELOAD_TIMEOUT_TICKS = 40L;
    private static final ConcurrentHashMap<WatchTraceKey, WatchTraceState> WATCH_TRACE_STATE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<EntityWatchKey, EntityWatchState> ENTITY_WATCH_STATE =
            new ConcurrentHashMap<>();
    private static volatile long currentServerTick;

    private PayloadWatchTracer() {
        throw new AssertionError("Utility class");
    }

    public static void traceBlockSetStateEntered(
            final WorldChunk chunk,
            final BlockPos pos,
            final BlockState previous,
            final BlockState next,
            final int flags,
            final String caller,
            final ChunkMutationTrackingScope.Cause passiveCause,
            final boolean mutationSuppressed,
            final boolean mutationAccepted,
            final boolean deltaCreated,
            final int blockChangesBefore,
            final int blockChangesAfter,
            final long mutationGeneration,
            @Nullable final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        final String worldId = worldId(chunk);
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(
                worldId,
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }

        traceWatch(
                ChunkTraceEventType.WATCH_BLOCK_SETSTATE_ENTERED,
                "mutation",
                caller,
                message != null ? message : "setBlockState entered",
                worldId,
                chunk.getPos(),
                null,
                target,
                "pos=" + pos.getX() + ',' + pos.getY() + ',' + pos.getZ()
                        + " oldState=" + previous
                        + " newState=" + next
                        + " flags=" + flags
                        + " caller=" + caller
                        + " thread=" + Thread.currentThread().getName()
                        + " classification=" + classifySetBlockStateEvent(chunk)
                        + " authoritative=" + !chunk.getWorld().isClient()
                        + " passiveContext=" + passiveCause
                        + " mutationSuppressed=" + mutationSuppressed
                        + " mutationAccepted=" + mutationAccepted
                        + " deltaCreated=" + deltaCreated
                        + " blockChangesBefore=" + blockChangesBefore
                        + " blockChangesAfter=" + blockChangesAfter
                        + " mutationGeneration=" + mutationGeneration,
                null
        );
    }

    public static void traceCapturedBlocks(final WorldChunk chunk) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        final String worldId = worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }

            final BlockPos pos = new BlockPos(target.blockX(), target.blockY(), target.blockZ());
            final BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) {
                traceWatch(
                        ChunkTraceEventType.WATCH_SKIPPED,
                        "capture",
                        "PayloadWatchTracer#traceCapturedBlocks",
                        "capture skipped: block is air",
                        worldId,
                        chunkPos,
                        null,
                        target,
                        summarizeBlock(target, state),
                        null
                );
                continue;
            }

            traceWatch(
                    ChunkTraceEventType.WATCH_CAPTURED,
                    "capture",
                    "PayloadWatchTracer#traceCapturedBlocks",
                    "block captured",
                    worldId,
                    chunkPos,
                    null,
                    target,
                    summarizeBlock(target, state),
                    null
            );
        }
    }

    public static void traceCapturedBlockEntity(
            final String worldId,
            final ChunkPos chunkPos,
            final BlockPos pos,
            @Nullable final BlockEntity blockEntity,
            @Nullable final NbtCompound nbt
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(
                worldId,
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }

        final String summary = summarizeBlockEntity(target, blockEntity, nbt);
        if (blockEntity == null || nbt == null || nbt.isEmpty()) {
            traceWatch(
                    ChunkTraceEventType.WATCH_SKIPPED,
                    "capture",
                    "PayloadWatchTracer#traceCapturedBlockEntity",
                    "capture skipped: block entity NBT missing",
                    worldId,
                    chunkPos,
                    null,
                    target,
                    summary,
                    null
            );
            return;
        }

        traceWatch(
                ChunkTraceEventType.WATCH_CAPTURED,
                "capture",
                "PayloadWatchTracer#traceCapturedBlockEntity",
                "block entity captured",
                worldId,
                chunkPos,
                null,
                target,
                summary,
                null
        );
    }

    public static void traceSkippedBlockEntityCapture(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final BlockPos pos,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(
                worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }

        traceWatch(
                ChunkTraceEventType.WATCH_SKIPPED,
                "capture",
                "PayloadWatchTracer#traceSkippedBlockEntityCapture",
                message,
                worldId(world),
                chunkPos,
                null,
                target,
                "pos=" + pos.getX() + ',' + pos.getY() + ',' + pos.getZ(),
                null
        );
    }

    public static void traceCapturedEntities(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final List<NbtCompound> entities
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || entities.isEmpty()) {
            return;
        }

        for (final NbtCompound entityNbt : entities) {
            final String entityUuid = entityUuid(entityNbt);
            if (entityUuid == null) {
                continue;
            }

            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(worldId(world), entityUuid);
            if (target == null) {
                continue;
            }

            traceWatch(
                    ChunkTraceEventType.WATCH_CAPTURED,
                    "capture",
                    "PayloadWatchTracer#traceCapturedEntities",
                    "entity captured",
                    worldId(world),
                    chunkPos,
                    null,
                    target,
                    summarizeEntity(target, entityNbt),
                    null
            );
        }
    }

    public static void traceDeltaStage(
            final String worldId,
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final ChunkTraceEventType eventType,
            final String stage,
            final String source,
            final String message,
            final Integer byteSize
    ) {
        traceDeltaStage(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                chunkPos.getStartX(),
                chunkPos.getStartZ(),
                delta,
                operationId,
                eventType,
                stage,
                source,
                message,
                byteSize
        );
    }

    static void traceDeltaStage(
            final String worldId,
            final DebugChunkKey chunkKey,
            final int chunkStartX,
            final int chunkStartZ,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final ChunkTraceEventType eventType,
            final String stage,
            final String source,
            final String message,
            final Integer byteSize
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        Objects.requireNonNull(delta, "delta");

        delta.forEachBlock((x, y, z, state) -> {
            final int worldX = chunkStartX + x;
            final int worldZ = chunkStartZ + z;
            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(worldId, worldX, y, worldZ);
            if (target != null) {
                traceWatch(
                        eventType,
                        stage,
                        source,
                        message,
                        worldId,
                        chunkKey,
                        operationId,
                        target,
                        summarizeBlock(target, state),
                        byteSize
                );
            }
        });

        delta.getBlockEntities().long2ObjectEntrySet().forEach(entry -> {
            final int x = io.liparakis.chunkis.core.BlockInstruction.unpackX(entry.getLongKey());
            final int y = io.liparakis.chunkis.core.BlockInstruction.unpackY(entry.getLongKey());
            final int z = io.liparakis.chunkis.core.BlockInstruction.unpackZ(entry.getLongKey());
            final int worldX = chunkStartX + x;
            final int worldZ = chunkStartZ + z;
            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(worldId, worldX, y, worldZ);
            if (target != null) {
                traceWatch(
                        eventType,
                        stage,
                        source,
                        message,
                        worldId,
                        chunkKey,
                        operationId,
                        target,
                        summarizeBlockEntity(target, null, entry.getValue()),
                        byteSize
                );
            }
        });

        delta.forEachEntity(entityNbt -> {
            if (entityNbt == null) {
                return;
            }
            final String entityUuid = entityUuid(entityNbt);
            if (entityUuid == null) {
                return;
            }
            final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(worldId, entityUuid);
            if (target != null) {
                traceWatch(
                        eventType,
                        stage,
                        source,
                        message,
                        worldId,
                        chunkKey,
                        operationId,
                        target,
                        summarizeEntity(target, entityNbt),
                        byteSize
                );
            }
        });

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                chunkKey
        )) {
            if (target.type() == PayloadWatchType.ENTITY || !target.hasBlockCoordinates()) {
                continue;
            }
            if (!contains(delta, target, chunkStartX, chunkStartZ, worldId)) {
                traceWatch(
                        ChunkTraceEventType.WATCH_FAILED,
                        stage,
                        source,
                        "missing during " + stage,
                        worldId,
                        chunkKey,
                        operationId,
                        target,
                        target.describe(),
                        byteSize
                );
            }
        }
    }

    public static void traceDecodeOutcome(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final boolean storageEntryPresent
    ) {
        final String worldId = worldId(world);
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        if (storageEntryPresent) {
            traceDeltaStage(
                    worldId,
                    chunkPos,
                    delta,
                    operationId,
                    ChunkTraceEventType.WATCH_STORAGE_READ,
                    "storage-read",
                    "PayloadWatchTracer#traceDecodeOutcome",
                    "payload bytes read from storage",
                    null
            );
        }

        traceDeltaStage(
                worldId,
                chunkPos,
                delta,
                operationId,
                ChunkTraceEventType.WATCH_DECODED,
                "decode",
                "PayloadWatchTracer#traceDecodeOutcome",
                "payload decoded",
                null
        );

        registerDecodedWatchTargets(worldId, chunkPos, delta, operationId);

        if (!storageEntryPresent) {
            return;
        }

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() == PayloadWatchType.ENTITY || !target.hasBlockCoordinates()) {
                continue;
            }
            if (!contains(delta, target, chunkPos.getStartX(), chunkPos.getStartZ(), worldId)) {
                traceWatch(
                        ChunkTraceEventType.WATCH_FAILED,
                        "decode",
                        "PayloadWatchTracer#traceDecodeOutcome",
                        "missing after decode",
                        worldId,
                        chunkPos,
                        operationId,
                        target,
                        target.describe(),
                        null
                );
            }
        }
    }

    public static void traceRestoreStarted(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        final String worldId = worldId(world);
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = findWatchedBlockState(delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            final String resolvedOperationId = resolveOperationId(worldId, chunkPos, target, operationId);
            final BlockPos pos = new BlockPos(target.blockX(), target.blockY(), target.blockZ());
            final BlockState actualState = chunk.getBlockState(pos);
            markVisibilitySeen(worldId, chunkPos, target, resolvedOperationId);
            traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    resolvedOperationId,
                    "restore-start",
                    "ChunkRestorer#restore",
                    chunk
            );
            traceWatch(
                    ChunkTraceEventType.WATCH_RESTORE_STARTED,
                    "restore-start",
                    "PayloadWatchTracer#traceRestoreStarted",
                    "payload entered restore",
                    worldId,
                    chunkPos,
                    resolvedOperationId,
                    target,
                    summarizeExpectedAndActual(
                            target,
                            expectedState,
                            actualState,
                            null,
                            chunk.getStatus(),
                            "ChunkRestorer#restore",
                            Thread.currentThread().getName(),
                            chunk
                    ),
                    null
            );
        }
    }

    public static void traceProtoDeltaAttached(
            final String worldId,
            final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || operationId == null) {
            return;
        }
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = findWatchedBlockState(delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            final WatchTraceKey key = new WatchTraceKey(
                    worldId,
                    chunkPos.x,
                    chunkPos.z,
                    target.blockX(),
                    target.blockY(),
                    target.blockZ()
            );
            final WatchTraceState state = WATCH_TRACE_STATE.get(key);
            if (state != null && operationId.equals(state.operationId)) {
                state.protoAttachedSeen = true;
            }
            traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    operationId,
                    "proto-attach",
                    source,
                    chunk
            );
            traceWatch(
                    ChunkTraceEventType.WATCH_PROTO_DELTA_ATTACHED,
                    "proto-attach",
                    source,
                    "decoded watched payload attached to proto chunk",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    "expectedState=" + expectedState
                            + " chunkStatus=" + chunk.getStatus()
                            + " source=" + source
                            + " thread=" + Thread.currentThread().getName(),
                    null
            );
        }
    }

    public static void traceProtoDeltaPresentBeforeConversion(
            final String worldId,
            final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || operationId == null) {
            return;
        }
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = findWatchedBlockState(delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    operationId,
                    "conversion-before",
                    source,
                    chunk
            );
            traceWatch(
                    ChunkTraceEventType.WATCH_PROTO_DELTA_PRESENT_BEFORE_CONVERSION,
                    "conversion-before",
                    source,
                    "watched payload delta present on proto chunk before conversion",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    "expectedState=" + expectedState
                            + " chunkStatus=" + chunk.getStatus()
                            + " source=" + source
                            + " thread=" + Thread.currentThread().getName(),
                    null
            );
        }
    }

    public static void traceProtoDeltaPresentAfterConversion(
            final String worldId,
            final Chunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || operationId == null) {
            return;
        }
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = findWatchedBlockState(delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    operationId,
                    "conversion-after",
                    source,
                    chunk
            );
            traceWatch(
                    ChunkTraceEventType.WATCH_PROTO_DELTA_PRESENT_AFTER_CONVERSION,
                    "conversion-after",
                    source,
                    "watched payload delta present on proto chunk after conversion",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    "expectedState=" + expectedState
                            + " chunkStatus=" + chunk.getStatus()
                            + " source=" + source
                            + " thread=" + Thread.currentThread().getName(),
                    null
            );
        }
    }

    public static void traceWorldChunkDeltaAttached(
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || operationId == null) {
            return;
        }
        final String worldId = worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = findWatchedBlockState(delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    operationId,
                    "worldchunk-attach",
                    source,
                    chunk
            );
            traceWatch(
                    ChunkTraceEventType.WATCH_WORLDCHUNK_DELTA_ATTACHED,
                    "worldchunk-attach",
                    source,
                    "watched payload delta attached to WorldChunk",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    "expectedState=" + expectedState
                            + " chunkStatus=" + chunk.getStatus()
                            + " source=" + source
                            + " thread=" + Thread.currentThread().getName(),
                    null
            );
        }
    }

    public static void traceWorldChunkDeltaMissing(
            final WorldChunk chunk,
            final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || operationId == null) {
            return;
        }
        final String worldId = worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    operationId,
                    "worldchunk-missing",
                    source,
                    chunk
            );
            traceWatch(
                    ChunkTraceEventType.WATCH_WORLDCHUNK_DELTA_MISSING,
                    "worldchunk-missing",
                    source,
                    "watched payload delta missing from WorldChunk",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    "chunkStatus=" + chunk.getStatus()
                            + " source=" + source
                            + " thread=" + Thread.currentThread().getName(),
                    null
            );
        }
    }

    public static void checkUnrestoredAssertions() {
        for (final java.util.Map.Entry<WatchTraceKey, WatchTraceState> entry : WATCH_TRACE_STATE.entrySet()) {
            final WatchTraceState state = entry.getValue();
            if (state.protoAttachedSeen && !state.restoreDecisionSeen && !state.decodedPayloadNotAppliedAsserted) {
                state.decodedPayloadNotAppliedAsserted = true;
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.ASSERTIONS,
                        ChunkTraceEventType.ASSERTION_FAILED,
                        ChunkTraceSeverity.ERROR,
                        ChunkTraceReason.PROTO_DELTA_NOT_RESTORED,
                        "PayloadWatchTracer#checkUnrestoredAssertions",
                        "watched payload was attached to proto chunk but never reached restore (no restore decision)",
                        entry.getKey().worldId,
                        new DebugChunkKey(entry.getKey().chunkX, entry.getKey().chunkZ),
                        null,
                        state.operationId,
                        null,
                        null
                );
            }
        }
    }

    public static void tickEntityReloadAssertions() {
        currentServerTick++;
        for (final java.util.Map.Entry<EntityWatchKey, EntityWatchState> entry : ENTITY_WATCH_STATE.entrySet()) {
            final EntityWatchState state = entry.getValue();
            if (!state.unloadedWithoutReload
                    || state.asserted
                    || !state.hasKnownChunk
                    || currentServerTick - state.unloadTick < ENTITY_RELOAD_TIMEOUT_TICKS) {
                continue;
            }
            state.asserted = true;
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS,
                    ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR,
                    ChunkTraceReason.WATCHED_ENTITY_UNLOADED_NOT_RELOADED,
                    "PayloadWatchTracer#tickEntityReloadAssertions",
                    "watched entity was unloaded from the live server manager and no entity-manager-load or " +
                            "entity-tracking-start followed within " + ENTITY_RELOAD_TIMEOUT_TICKS + " ticks",
                    entry.getKey().worldId,
                    new DebugChunkKey(state.lastKnownChunkX, state.lastKnownChunkZ),
                    null,
                    null,
                    null,
                    null,
                    PayloadWatchTarget.entity(entry.getKey().worldId, entry.getKey().entityUuid),
                    "entity-unload-timeout",
                    "entity@" + entry.getKey().entityUuid + " world=" + entry.getKey().worldId
            );
        }
    }

    public static void traceWorldChunkConstructorConsumed(
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || operationId == null) {
            return;
        }
        final String worldId = worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = resolveExpectedState(chunk, delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            final WatchTraceState state = WATCH_TRACE_STATE.get(new WatchTraceKey(
                    worldId,
                    chunkPos.x,
                    chunkPos.z,
                    target.blockX(),
                    target.blockY(),
                    target.blockZ()
            ));
            if (state != null && operationId.equals(state.operationId)) {
                state.worldConstructorConsumedSeen = true;
            }
            traceWatch(
                    ChunkTraceEventType.WATCH_WORLD_CHUNK_CONSTRUCTOR_CONSUMED,
                    "worldchunk-constructor",
                    source,
                    "proto-carried watched payload consumed by world chunk constructor",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    summarizeExpectedAndActual(
                            target,
                            expectedState,
                            chunk.getBlockState(new BlockPos(target.blockX(), target.blockY(), target.blockZ())),
                            null,
                            chunk.getStatus(),
                            source,
                            Thread.currentThread().getName(),
                            chunk
                    ),
                    null
            );
        }
    }

    public static void traceRestoreInstructionVisited(
            final WorldChunk chunk,
            final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId,
            final String source
    ) {
        traceRestoreMutationStage(
                ChunkTraceEventType.WATCH_RESTORE_INSTRUCTION_VISITED,
                "restore-instruction-visited",
                chunk,
                pos,
                previousState,
                expectedState,
                operationId,
                source,
                null
        );
    }

    public static void traceRestoreApplyAttempt(
            final WorldChunk chunk,
            final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId,
            final String source
    ) {
        traceRestoreMutationStage(
                ChunkTraceEventType.WATCH_RESTORE_APPLY_ATTEMPT,
                "restore-apply-attempt",
                chunk,
                pos,
                previousState,
                expectedState,
                operationId,
                source,
                null
        );
    }

    public static void traceRestoreSetBlockReturned(
            final WorldChunk chunk,
            final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId,
            final String source
    ) {
        traceRestoreMutationStage(
                ChunkTraceEventType.WATCH_RESTORE_SETBLOCK_RETURNED,
                "restore-setblock-returned",
                chunk,
                pos,
                previousState,
                expectedState,
                operationId,
                source,
                null
        );
    }

    public static void traceRestoreStateAfterSetBlock(
            final WorldChunk chunk,
            final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId,
            final String source
    ) {
        traceRestoreMutationStage(
                ChunkTraceEventType.WATCH_RESTORE_STATE_AFTER_SETBLOCK,
                "restore-state-after-setblock",
                chunk,
                pos,
                previousState,
                expectedState,
                operationId,
                source,
                null
        );
        final String worldId = worldId(chunk);
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(worldId, pos.getX(), pos.getY(),
                pos.getZ());
        if (target == null || expectedState == null || Objects.equals(chunk.getBlockState(pos), expectedState)) {
            return;
        }
        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.RESTORE_SETBLOCK_DID_NOT_APPLY,
                source,
                "restore write did not produce expected immediate live block state",
                worldId,
                new DebugChunkKey(chunk.getPos().x, chunk.getPos().z),
                null,
                operationId,
                null,
                null
        );
    }

    public static void traceRestoreApplyFailed(
            final WorldChunk chunk,
            final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId,
            final String source,
            final String reason
    ) {
        traceRestoreMutationStage(
                ChunkTraceEventType.WATCH_RESTORE_APPLY_FAILED,
                "restore-apply-failed",
                chunk,
                pos,
                previousState,
                expectedState,
                operationId,
                source,
                reason
        );
    }

    public static void traceRestoreSkipped(
            final WorldChunk chunk,
            final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final String worldId = worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = resolveExpectedState(chunk, expectedDelta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            final String resolvedOperationId = resolveOperationId(worldId, chunkPos, target, operationId);
            if (isRestoreDecisionSeen(worldId, chunkPos, target, resolvedOperationId)) {
                continue;
            }
            markRestoreDecisionSeen(worldId, chunkPos, target, resolvedOperationId);
            markVisibilitySeen(worldId, chunkPos, target, resolvedOperationId);
            traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    resolvedOperationId,
                    "restore-skipped",
                    source,
                    chunk
            );
            traceWatch(
                    ChunkTraceEventType.WATCH_RESTORE_SKIPPED,
                    "restore-skipped",
                    source,
                    "decoded watched block never reached restore apply",
                    worldId,
                    chunkPos,
                    resolvedOperationId,
                    target,
                    summarizeExpectedAndActual(
                            target,
                            expectedState,
                            chunk.getBlockState(new BlockPos(target.blockX(), target.blockY(), target.blockZ())),
                            null,
                            chunk.getStatus(),
                            source,
                            Thread.currentThread().getName(),
                            chunk
                    ),
                    null
            );
        }
    }

    public static void traceRestoredBlock(
            final WorldChunk chunk,
            final BlockPos pos,
            final BlockState state,
            final String operationId
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final String worldId = worldId(chunk);
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(
                worldId,
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }
        final BlockState actualState = chunk.getBlockState(pos);
        final String resolvedOperationId = resolveOperationId(worldId, chunk.getPos(), target, operationId);
        markRestoreDecisionSeen(worldId, chunk.getPos(), target, resolvedOperationId);
        markVisibilitySeen(worldId, chunk.getPos(), target, resolvedOperationId);
        recordAppliedChunkInstance(worldId, chunk.getPos(), target, resolvedOperationId, chunk);
        traceChunkIdentityAndStatus(
                worldId,
                chunk.getPos(),
                target,
                resolvedOperationId,
                "restore-applied",
                "ChunkRestorer.RestorationVisitor#visitBlock",
                chunk
        );
        traceWatch(
                ChunkTraceEventType.WATCH_RESTORE_APPLIED,
                "restore-applied",
                "PayloadWatchTracer#traceRestoredBlock",
                "block restored to live world",
                worldId,
                chunk.getPos(),
                resolvedOperationId,
                target,
                summarizeExpectedAndActual(
                        target,
                        state,
                        actualState,
                        null,
                        chunk.getStatus(),
                        "ChunkRestorer.RestorationVisitor#visitBlock",
                        Thread.currentThread().getName(),
                        chunk
                ),
                null
        );
    }

    public static void traceRestoreBlockFailure(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final BlockPos pos,
            final String operationId,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(
                worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }
        traceWatch(
                ChunkTraceEventType.WATCH_FAILED,
                "restore",
                "PayloadWatchTracer#traceRestoreBlockFailure",
                message,
                worldId(world),
                chunkPos,
                operationId,
                target,
                target.describe(),
                null
        );
    }

    public static void traceRestoredBlockEntity(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final BlockPos pos,
            final BlockEntity blockEntity,
            final NbtCompound nbt,
            final String operationId
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(
                worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }
        traceWatch(
                ChunkTraceEventType.WATCH_RESTORE_APPLIED,
                "restore",
                "PayloadWatchTracer#traceRestoredBlockEntity",
                "block entity restored to live world",
                worldId(world),
                chunkPos,
                operationId,
                target,
                summarizeBlockEntity(target, blockEntity, nbt),
                null
        );
    }

    public static void traceRestoreBlockEntitySkipped(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final BlockPos pos,
            final String operationId,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlockEntity(
                worldId(world),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
        if (target == null) {
            return;
        }
        traceWatch(
                ChunkTraceEventType.WATCH_SKIPPED,
                "restore",
                "PayloadWatchTracer#traceRestoreBlockEntitySkipped",
                message,
                worldId(world),
                chunkPos,
                operationId,
                target,
                target.describe(),
                null
        );
    }

    public static void traceRestoredEntity(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final Entity entity,
            final NbtCompound nbt,
            final String operationId
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                worldId(world),
                entity.getUuidAsString()
        );
        if (target == null) {
            return;
        }
        traceWatch(
                ChunkTraceEventType.WATCH_RESTORE_APPLIED,
                "restore",
                "PayloadWatchTracer#traceRestoredEntity",
                "entity restored to live world",
                worldId(world),
                chunkPos,
                operationId,
                target,
                summarizeEntity(target, nbt),
                null
        );
    }

    public static void traceRestoreEntityInstructionVisited(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final NbtCompound nbt,
            final String operationId,
            final String source
    ) {
        traceEntityRestoreStage(
                world,
                chunkPos,
                nbt,
                operationId,
                ChunkTraceEventType.WATCH_RESTORE_INSTRUCTION_VISITED,
                "restore-entity-visit",
                source,
                "decoded entity payload visited by restore",
                null
        );
    }

    public static void traceRestoreEntityApplyAttempt(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final NbtCompound nbt,
            final String operationId,
            final String source
    ) {
        traceEntityRestoreStage(
                world,
                chunkPos,
                nbt,
                operationId,
                ChunkTraceEventType.WATCH_RESTORE_APPLY_ATTEMPT,
                "restore-entity-attempt",
                source,
                "attempting to materialize decoded entity payload into live server world",
                null
        );
    }

    public static void traceRestoreEntitySkipped(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final String entityUuid,
            final String operationId,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                worldId(world),
                entityUuid
        );
        if (target == null) {
            return;
        }
        traceWatch(
                ChunkTraceEventType.WATCH_SKIPPED,
                "restore",
                "PayloadWatchTracer#traceRestoreEntitySkipped",
                message,
                worldId(world),
                chunkPos,
                operationId,
                target,
                target.describe(),
                null
        );
    }

    public static void traceEntityPresenceAfterChunkFull(
            final ServerWorld world,
            final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        final String worldId = worldId(world);
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloads()) {
            if (target.type() != PayloadWatchType.ENTITY) {
                continue;
            }
            if (!target.matchesWorld(worldId)) {
                continue;
            }
            if (!shouldTraceEntityForChunk(worldId, chunkPos, target)) {
                continue;
            }

            final String resolvedOperationId = resolveOperationId(worldId, chunkPos, target, operationId);
            final Entity liveEntity = resolveWatchedEntity(world, target.entityUuid());
            if (liveEntity != null && !liveEntity.getChunkPos().equals(chunkPos)) {
                continue;
            }
            final NbtCompound expectedNbt = expectedDelta != null
                    ? findWatchedEntityNbt(expectedDelta, target.entityUuid())
                    : null;
            assertEntityReloadedAfterUnload(worldId, chunkPos, target, resolvedOperationId, liveEntity);
            traceWatch(
                    liveEntity != null
                            ? ChunkTraceEventType.WATCH_PRESENT_AFTER_CHUNK_FULL
                            : ChunkTraceEventType.WATCH_FAILED,
                    "chunk-full-entity",
                    source,
                    liveEntity != null
                            ? "watched entity present after chunk became sendable"
                            : "watched entity missing after chunk became sendable",
                    worldId,
                    chunkPos,
                    resolvedOperationId,
                    target,
                    summarizeExpectedEntityAndPresence(target, expectedNbt, liveEntity, chunk),
                    null
            );
        }
    }

    public static void traceEntityReplayState(
            final ServerWorld world,
            final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta,
            @Nullable final String operationId,
            final ChunkTraceEventType eventType,
            final String stage,
            final String source,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || world == null || chunk == null) {
            return;
        }

        final String worldId = worldId(world);
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloads()) {
            if (target.type() != PayloadWatchType.ENTITY) {
                continue;
            }
            if (!target.matchesWorld(worldId)) {
                continue;
            }
            if (!shouldTraceEntityForChunk(worldId, chunkPos, target)) {
                continue;
            }

            final String resolvedOperationId = resolveOperationId(worldId, chunkPos, target, operationId);
            final Entity liveEntity = resolveWatchedEntity(world, target.entityUuid());
            if (liveEntity != null && !liveEntity.getChunkPos().equals(chunkPos)) {
                continue;
            }
            final NbtCompound expectedNbt = expectedDelta != null
                    ? findWatchedEntityNbt(expectedDelta, target.entityUuid())
                    : null;
            traceWatch(
                    eventType,
                    stage,
                    source,
                    message,
                    worldId,
                    chunkPos,
                    resolvedOperationId,
                    target,
                    summarizeExpectedEntityAndPresence(target, expectedNbt, liveEntity, chunk),
                    null
            );
        }
    }

    public static void traceLiveEntityRemoved(
            final ServerWorld world,
            final Entity entity,
            final Entity.RemovalReason reason,
            @Nullable final String operationId,
            final String source
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || world == null || entity == null) {
            return;
        }

        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                worldId(world),
                entity.getUuidAsString()
        );
        if (target == null) {
            return;
        }

        final ChunkPos chunkPos = entity.getChunkPos();
        traceWatch(
                ChunkTraceEventType.WATCH_ENTITY_REMOVED,
                "live-remove",
                source,
                "watched entity removed from live server world",
                worldId(world),
                chunkPos,
                operationId,
                target,
                summarizeRemovedEntity(target, entity, reason, source),
                null
        );
    }

    public static void traceEntityChunkTransfer(
            final ServerWorld world,
            final Entity entity,
            @Nullable final String operationId,
            final String stage,
            final String source,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || world == null || entity == null) {
            return;
        }

        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                worldId(world),
                entity.getUuidAsString()
        );
        if (target == null) {
            return;
        }
        markEntityTransfer(target, stage, entity.getChunkPos());

        traceWatch(
                ChunkTraceEventType.WATCH_CAPTURED,
                stage,
                source,
                message,
                worldId(world),
                entity.getChunkPos(),
                operationId,
                target,
                summarizeLiveEntity(target, entity, source),
                null
        );
    }

    public static void traceEntityTrackingEvent(
            final ServerWorld world,
            final Entity entity,
            final ServerPlayerEntity player,
            final String stage,
            final String source,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || world == null || entity == null || player == null) {
            return;
        }

        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(
                worldId(world),
                entity.getUuidAsString()
        );
        if (target == null) {
            return;
        }
        if ("entity-tracking-start".equals(stage)) {
            clearPendingEntityReload(target);
        }
        rememberEntityChunk(target, entity.getChunkPos());

        traceWatch(
                ChunkTraceEventType.WATCH_CAPTURED,
                stage,
                source,
                message,
                worldId(world),
                entity.getChunkPos(),
                null,
                target,
                summarizeLiveEntity(target, entity, source)
                        + " player=" + player.getName().getString()
                        + " playerId=" + player.getUuidAsString(),
                null
        );
    }

    public static void traceEntityChunkReentry(
            final ServerWorld world,
            final WorldChunk chunk,
            final String stage,
            final String source,
            final String message
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches() || world == null || chunk == null) {
            return;
        }

        final String worldId = worldId(world);
        final ChunkPos chunkPos = chunk.getPos();
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloads()) {
            if (target.type() != PayloadWatchType.ENTITY) {
                continue;
            }
            if (!target.matchesWorld(worldId)) {
                continue;
            }
            if (!shouldTraceEntityForChunk(worldId, chunkPos, target)) {
                continue;
            }
            final Entity liveEntity = resolveWatchedEntity(world, target.entityUuid());
            if (liveEntity != null && !liveEntity.getChunkPos().equals(chunkPos)) {
                continue;
            }
            assertEntityReloadedAfterUnload(worldId, chunkPos, target, null, liveEntity);
            traceWatch(
                    liveEntity != null
                            ? ChunkTraceEventType.WATCH_CAPTURED
                            : ChunkTraceEventType.WATCH_FAILED,
                    stage,
                    source,
                    message,
                    worldId,
                    chunkPos,
                    null,
                    target,
                    liveEntity != null
                            ? summarizeLiveEntity(target, liveEntity, source)
                            : target.describe()
                                    + " chunkStatus=" + chunk.getStatus()
                                    + " chunkInstanceId=" + chunkInstanceId(chunk)
                                    + " source=" + source
                                    + " thread=" + Thread.currentThread().getName(),
                    null
            );
        }
    }

    public static void traceLiveChunkState(
            final WorldChunk chunk,
            final ChunkTraceEventType presentEventType,
            final String stage,
            final String source,
            @Nullable final String operationId,
            @Nullable final ChunkDelta<BlockState, NbtCompound> expectedDelta
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }

        final String worldId = worldId(chunk);
        final ChunkPos chunkPos = chunk.getPos();
        final boolean clientChunk = chunk.getWorld().isClient();

        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }

            final BlockPos pos = new BlockPos(target.blockX(), target.blockY(), target.blockZ());
            final BlockState liveState = chunk.getBlockState(pos);
            final BlockState expectedState = resolveExpectedState(chunk, expectedDelta, target, chunkPos, worldId);
            final String resolvedOperationId = resolveOperationId(worldId, chunkPos, target, operationId);
            markVisibilitySeen(worldId, chunkPos, target, resolvedOperationId);
            assertRestoreDecisionSeen(
                    worldId,
                    chunkPos,
                    target,
                    resolvedOperationId,
                    source
            );
            traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    resolvedOperationId,
                    stage,
                    source,
                    chunk
            );
            assertSameAppliedChunkInstance(
                    worldId,
                    chunkPos,
                    target,
                    resolvedOperationId,
                    source,
                    chunk
            );

            traceWatch(
                    presentEventType,
                    stage,
                    source,
                    "watched block observed in live chunk",
                    worldId,
                    chunkPos,
                    resolvedOperationId,
                    target,
                    summarizeExpectedAndActual(
                            target,
                            expectedState,
                            clientChunk ? null : liveState,
                            clientChunk ? liveState : null,
                            chunk.getStatus(),
                            source,
                            Thread.currentThread().getName(),
                            chunk
                    ),
                    null
            );

            if (expectedState != null && !Objects.equals(liveState, expectedState)) {
                traceWatch(
                        ChunkTraceEventType.WATCH_OVERWRITTEN_AFTER_RESTORE,
                        stage,
                        source,
                        "watched block differs from expected restored state",
                        worldId,
                        chunkPos,
                        resolvedOperationId,
                        target,
                        summarizeExpectedAndActual(
                                target,
                                expectedState,
                                clientChunk ? null : liveState,
                                clientChunk ? liveState : null,
                                chunk.getStatus(),
                                source,
                                Thread.currentThread().getName(),
                                chunk
                        ),
                        null
                );
            }

            if (clientChunk && expectedState != null && Objects.equals(liveState, expectedState)) {
                traceWatch(
                        ChunkTraceEventType.WATCH_CLIENT_SEES_EXPECTED_STATE,
                        stage,
                        source,
                        "client world sees expected watched block state",
                        worldId,
                        chunkPos,
                        resolvedOperationId,
                        target,
                        summarizeExpectedAndActual(
                                target,
                                expectedState,
                                null,
                                liveState,
                                chunk.getStatus(),
                                source,
                                Thread.currentThread().getName(),
                                chunk
                        ),
                        null
                );
            }
        }
    }

    private static boolean contains(
            final ChunkDelta<BlockState, NbtCompound> delta,
            final PayloadWatchTarget target,
            final int chunkStartX,
            final int chunkStartZ,
            final String worldId
    ) {
        final boolean[] found = { false };

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
            delta.getBlockEntities().long2ObjectEntrySet().forEach(entry -> {
                if (found[0]) {
                    return;
                }
                final int x = io.liparakis.chunkis.core.BlockInstruction.unpackX(entry.getLongKey());
                final int y = io.liparakis.chunkis.core.BlockInstruction.unpackY(entry.getLongKey());
                final int z = io.liparakis.chunkis.core.BlockInstruction.unpackZ(entry.getLongKey());
                final int worldX = chunkStartX + x;
                final int worldZ = chunkStartZ + z;
                found[0] = target.matchesBlock(worldId, PayloadWatchType.BLOCK_ENTITY, worldX, y, worldZ);
            });
            return found[0];
        }

        delta.forEachEntity(entityNbt -> {
            if (found[0] || entityNbt == null) {
                return;
            }
            final String uuid = entityUuid(entityNbt);
            found[0] = uuid != null && target.matchesEntity(worldId, uuid);
        });
        return found[0];
    }

    @Nullable
    private static NbtCompound findWatchedEntityNbt(
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String entityUuid
    ) {
        final NbtCompound[] found = {null};
        delta.forEachEntity(entityNbt -> {
            if (found[0] != null || entityNbt == null) {
                return;
            }
            final String uuid = entityUuid(entityNbt);
            if (entityUuid.equals(uuid)) {
                found[0] = entityNbt;
            }
        });
        return found[0];
    }

    @Nullable
    private static BlockState findWatchedBlockState(
            final ChunkDelta<BlockState, NbtCompound> delta,
            final PayloadWatchTarget target,
            final ChunkPos chunkPos,
            final String worldId
    ) {
        final BlockState[] found = { null };
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

    private static void traceWatch(
            final ChunkTraceEventType eventType,
            final String stage,
            final String source,
            final String message,
            final String worldId,
            final ChunkPos chunkPos,
            final String operationId,
            final PayloadWatchTarget target,
            final String summary,
            final Integer byteSize
    ) {
        traceWatch(
                eventType,
                stage,
                source,
                message,
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                operationId,
                target,
                summary,
                byteSize
        );
    }

    private static void traceWatch(
            final ChunkTraceEventType eventType,
            final String stage,
            final String source,
            final String message,
            final String worldId,
            final DebugChunkKey chunkKey,
            final String operationId,
            final PayloadWatchTarget target,
            final String summary,
            final Integer byteSize
    ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                eventType,
                eventType == ChunkTraceEventType.WATCH_FAILED ? ChunkTraceSeverity.ERROR : ChunkTraceSeverity.INFO,
                ChunkTraceReason.NONE,
                source,
                message,
                worldId,
                chunkKey,
                null,
                operationId,
                null,
                byteSize,
                target,
                stage,
                summary
        );
    }

    private static String summarizeBlock(final PayloadWatchTarget target, final BlockState state) {
        if (!target.hasBlockCoordinates()) {
            return target.describe() + " state=" + state;
        }
        return "pos=" + target.blockX() + ',' + target.blockY() + ',' + target.blockZ()
                + " state=" + state
                + " section=" + (target.blockY() >> 4);
    }

    private static String summarizeExpectedAndActual(
            final PayloadWatchTarget target,
            @Nullable final BlockState expectedState,
            @Nullable final BlockState actualServerState,
            @Nullable final BlockState actualClientState,
            final Object chunkStatus,
            final String source,
            final String threadName,
            @Nullable final WorldChunk chunk
    ) {
        if (!target.hasBlockCoordinates()) {
            return target.describe()
                    + " expectedState=" + expectedState
                    + " actualServerState=" + actualServerState
                    + " actualClientState=" + actualClientState
                    + " chunkInstanceId=" + chunkInstanceId(chunk)
                    + " chunkStatus=" + chunkStatus
                    + " source=" + source
                    + " thread=" + threadName;
        }
        return "pos=" + target.blockX() + ',' + target.blockY() + ',' + target.blockZ()
                + " expectedState=" + expectedState
                + " actualServerState=" + actualServerState
                + " actualClientState=" + actualClientState
                + " chunkInstanceId=" + chunkInstanceId(chunk)
                + " chunkStatus=" + chunkStatus
                + " source=" + source
                + " thread=" + threadName
                + " section=" + (target.blockY() >> 4);
    }

    private static void registerDecodedWatchTargets(
            final String worldId,
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> delta,
            final String operationId
    ) {
        if (operationId == null) {
            return;
        }
        for (final PayloadWatchTarget target : ChunkTraceWatchpoints.watchedPayloadsForChunk(
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z)
        )) {
            if (target.type() != PayloadWatchType.BLOCK || !target.hasBlockCoordinates()) {
                continue;
            }
            final BlockState expectedState = findWatchedBlockState(delta, target, chunkPos, worldId);
            if (expectedState == null) {
                continue;
            }
            final WatchTraceKey key = new WatchTraceKey(
                    worldId,
                    chunkPos.x,
                    chunkPos.z,
                    target.blockX(),
                    target.blockY(),
                    target.blockZ()
            );
            final WatchTraceState previous = WATCH_TRACE_STATE.put(
                    key,
                    new WatchTraceState(operationId, expectedState)
            );
            traceWatch(
                    ChunkTraceEventType.WATCH_DECODED_DELTA_STATE,
                    "decode-delta-state",
                    "PayloadWatchTracer#registerDecodedWatchTargets",
                    "decoded payload state recorded before restore",
                    worldId,
                    chunkPos,
                    operationId,
                    target,
                    summarizeExpectedAndActual(
                            target,
                            expectedState,
                            null,
                            null,
                            "DECODED_DELTA_ONLY",
                            "PayloadWatchTracer#registerDecodedWatchTargets",
                            Thread.currentThread().getName(),
                            null
                    ),
                    null
            );
            traceChunkIdentityAndStatus(
                    worldId,
                    chunkPos,
                    target,
                    operationId,
                    "decode",
                    "PayloadWatchTracer#registerDecodedWatchTargets",
                    null
            );
            if (previous != null && !previous.hasVisibilityEvent) {
                traceIncompleteWatch(key, previous.operationId);
            }
            if (previous != null
                    && previous.protoAttachedSeen
                    && !previous.worldConstructorConsumedSeen) {
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.ASSERTIONS,
                        ChunkTraceEventType.ASSERTION_FAILED,
                        ChunkTraceSeverity.ERROR,
                        ChunkTraceReason.DECODED_PAYLOAD_NOT_CONSUMED_BY_WORLD_CONSTRUCTOR,
                        "PayloadWatchTracer#registerDecodedWatchTargets",
                        "decoded watched payload was attached to proto chunk but never consumed by world chunk " +
                                "constructor",
                        worldId,
                        new DebugChunkKey(chunkPos.x, chunkPos.z),
                        null,
                        previous.operationId,
                        null,
                        null
                );
            }
        }
    }

    @Nullable
    private static String resolveOperationId(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId
    ) {
        if (operationId != null) {
            return operationId;
        }
        if (!target.hasBlockCoordinates()) {
            return null;
        }
        final WatchTraceState state = WATCH_TRACE_STATE.get(new WatchTraceKey(
                worldId,
                chunkPos.x,
                chunkPos.z,
                target.blockX(),
                target.blockY(),
                target.blockZ()
        ));
        return state != null ? state.operationId : null;
    }

    private static void markVisibilitySeen(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId
    ) {
        if (operationId == null) {
            return;
        }
        final WatchTraceState state = WATCH_TRACE_STATE.get(new WatchTraceKey(
                worldId,
                chunkPos.x,
                chunkPos.z,
                target.blockX(),
                target.blockY(),
                target.blockZ()
        ));
        if (state != null && operationId.equals(state.operationId)) {
            state.hasVisibilityEvent = true;
        }
    }

    private static void markRestoreDecisionSeen(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId
    ) {
        if (operationId == null) {
            return;
        }
        final WatchTraceState state = WATCH_TRACE_STATE.get(new WatchTraceKey(
                worldId,
                chunkPos.x,
                chunkPos.z,
                target.blockX(),
                target.blockY(),
                target.blockZ()
        ));
        if (state != null && operationId.equals(state.operationId)) {
            state.restoreDecisionSeen = true;
        }
    }

    private static boolean isRestoreDecisionSeen(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId
    ) {
        if (operationId == null) {
            return false;
        }
        final WatchTraceState state = WATCH_TRACE_STATE.get(new WatchTraceKey(
                worldId,
                chunkPos.x,
                chunkPos.z,
                target.blockX(),
                target.blockY(),
                target.blockZ()
        ));
        return state != null && operationId.equals(state.operationId) && state.restoreDecisionSeen;
    }

    private static void assertRestoreDecisionSeen(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId,
            final String source
    ) {
        if (operationId == null) {
            return;
        }
        final WatchTraceState state = WATCH_TRACE_STATE.get(new WatchTraceKey(
                worldId,
                chunkPos.x,
                chunkPos.z,
                target.blockX(),
                target.blockY(),
                target.blockZ()
        ));
        if (state == null
                || !operationId.equals(state.operationId)
                || state.restoreDecisionSeen
                || state.decodedPayloadNotAppliedAsserted) {
            return;
        }
        state.decodedPayloadNotAppliedAsserted = true;
        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.DECODED_PAYLOAD_NOT_APPLIED,
                source,
                "decoded watched payload reached later chunk lifecycle without restore-applied or restore-skipped",
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                operationId,
                null,
                null
        );
    }

    private static void traceIncompleteWatch(final WatchTraceKey key, final String operationId) {
        traceWatch(
                ChunkTraceEventType.WATCH_TRACE_INCOMPLETE,
                "trace-incomplete",
                "PayloadWatchTracer#registerDecodedWatchTargets",
                "decoded watched payload had no later restore/client visibility event for same load operation",
                key.worldId,
                new DebugChunkKey(key.chunkX, key.chunkZ),
                operationId,
                PayloadWatchTarget.block(key.worldId, key.blockX, key.blockY, key.blockZ),
                "pos=" + key.blockX + ',' + key.blockY + ',' + key.blockZ,
                null
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.WATCHED_PAYLOAD_TRACE_INCOMPLETE,
                "PayloadWatchTracer#registerDecodedWatchTargets",
                "decoded watched payload had no later restore/client visibility event for same load operation",
                key.worldId,
                new DebugChunkKey(key.chunkX, key.chunkZ),
                null,
                operationId,
                null,
                null
        );
        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.DECODED_PAYLOAD_NOT_VISITED_BY_RESTORE,
                "PayloadWatchTracer#registerDecodedWatchTargets",
                "decoded watched payload never emitted a restore visit/apply decision before trace completion",
                key.worldId,
                new DebugChunkKey(key.chunkX, key.chunkZ),
                null,
                operationId,
                null,
                null
        );
    }

    private static void traceChunkIdentityAndStatus(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId,
            final String stage,
            final String source,
            @Nullable final Chunk chunk
    ) {
        final String chunkInstanceId = chunkInstanceId(chunk);
        final String chunkStatus = chunk != null ? String.valueOf(chunk.getStatus()) : "UNAVAILABLE";
        traceWatch(
                ChunkTraceEventType.WATCH_CHUNK_INSTANCE_ID,
                stage,
                source,
                "chunk instance identity observed",
                worldId,
                chunkPos,
                operationId,
                target,
                "chunkInstanceId=" + chunkInstanceId
                        + " source=" + source
                        + " thread=" + Thread.currentThread().getName(),
                null
        );
        traceWatch(
                ChunkTraceEventType.WATCH_CHUNK_STATUS,
                stage,
                source,
                "chunk status observed",
                worldId,
                chunkPos,
                operationId,
                target,
                "chunkStatus=" + chunkStatus
                        + " chunkInstanceId=" + chunkInstanceId
                        + " source=" + source
                        + " thread=" + Thread.currentThread().getName(),
                null
        );
    }

    private static void traceRestoreMutationStage(
            final ChunkTraceEventType eventType,
            final String stage,
            final WorldChunk chunk,
            final BlockPos pos,
            @Nullable final BlockState previousState,
            @Nullable final BlockState expectedState,
            final String operationId,
            final String source,
            @Nullable final String skipReason
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final String worldId = worldId(chunk);
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedBlock(worldId, pos.getX(), pos.getY(),
                pos.getZ());
        if (target == null) {
            return;
        }
        markRestoreDecisionSeen(worldId, chunk.getPos(), target, operationId);
        traceChunkIdentityAndStatus(worldId, chunk.getPos(), target, operationId, stage, source, chunk);
        traceWatch(
                eventType,
                stage,
                source,
                skipReason != null ? skipReason : "restore mutation stage",
                worldId,
                chunk.getPos(),
                operationId,
                target,
                "pos=" + pos.getX() + ',' + pos.getY() + ',' + pos.getZ()
                        + " expectedState=" + String.valueOf(expectedState)
                        + " previousState=" + String.valueOf(previousState)
                        + " newState=" + String.valueOf(expectedState)
                        + " setBlockReturnValue=UNAVAILABLE_DIRECT_SECTION_WRITE"
                        + " actualStateImmediatelyAfter=" + chunk.getBlockState(pos)
                        + " chunkInstanceId=" + chunkInstanceId(chunk)
                        + " chunkStatus=" + chunk.getStatus()
                        + " source=" + source
                        + " thread=" + Thread.currentThread().getName()
                        + (skipReason != null ? " skipReason=" + skipReason : ""),
                null
        );
    }

    private static void recordAppliedChunkInstance(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId,
            final WorldChunk chunk
    ) {
        if (operationId == null) {
            return;
        }
        final WatchTraceState state = WATCH_TRACE_STATE.get(new WatchTraceKey(
                worldId,
                chunkPos.x,
                chunkPos.z,
                target.blockX(),
                target.blockY(),
                target.blockZ()
        ));
        if (state != null && operationId.equals(state.operationId)) {
            state.appliedChunkInstanceId = chunkInstanceId(chunk);
        }
    }

    private static void assertSameAppliedChunkInstance(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId,
            final String source,
            final WorldChunk chunk
    ) {
        if (operationId == null) {
            return;
        }
        final WatchTraceState state = WATCH_TRACE_STATE.get(new WatchTraceKey(
                worldId,
                chunkPos.x,
                chunkPos.z,
                target.blockX(),
                target.blockY(),
                target.blockZ()
        ));
        if (state == null
                || !operationId.equals(state.operationId)
                || state.appliedChunkInstanceId == null
                || state.appliedChunkInstanceId.equals(chunkInstanceId(chunk))
                || state.appliedDifferentChunkAsserted) {
            return;
        }
        state.appliedDifferentChunkAsserted = true;
        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.RESTORE_APPLIED_TO_DIFFERENT_CHUNK_INSTANCE,
                source,
                "watched payload was applied on one chunk instance but later observed on another",
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                operationId,
                null,
                null
        );
    }

    @Nullable
    private static BlockState resolveExpectedState(
            final WorldChunk chunk,
            @Nullable final ChunkDelta<BlockState, NbtCompound> explicitExpectedDelta,
            final PayloadWatchTarget target,
            final ChunkPos chunkPos,
            final String worldId
    ) {
        final ChunkDelta<BlockState, NbtCompound> expectedDelta =
                explicitExpectedDelta != null ? explicitExpectedDelta : attachedDelta(chunk);
        return expectedDelta != null
                ? findWatchedBlockState(expectedDelta, target, chunkPos, worldId)
                : null;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static ChunkDelta<BlockState, NbtCompound> attachedDelta(final WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck duck)) {
            return null;
        }
        return (ChunkDelta<BlockState, NbtCompound>) duck.chunkis$getDelta();
    }

    private static String classifySetBlockStateEvent(final WorldChunk chunk) {
        if (!chunk.getWorld().isClient()) {
            return "SERVER_AUTHORITATIVE_EVENT";
        }
        return Thread.currentThread().getName().contains("Render")
                ? "CLIENT_RENDER_LOCAL_VISUAL_EVENT"
                : "CLIENT_LOCAL_VISUAL_EVENT";
    }

    private static String summarizeBlockEntity(
            final PayloadWatchTarget target,
            @Nullable final BlockEntity blockEntity,
            @Nullable final NbtCompound nbt
    ) {
        final String type = blockEntity != null
                ? String.valueOf(net.minecraft.block.entity.BlockEntityType.getId(blockEntity.getType()))
                : nbt != null ? nbt.getString("id").orElse("<missing-id>") : "<missing>";
        return "pos=" + target.blockX() + ',' + target.blockY() + ',' + target.blockZ()
                + " type=" + type
                + " nbtBytes=" + nbtSize(nbt);
    }

    private static String summarizeEntity(
            final PayloadWatchTarget target,
            final NbtCompound nbt
    ) {
        return "uuid=" + target.entityUuid()
                + " type=" + nbt.getString("id").orElse("<missing-id>")
                + " pos=" + nbt.getList("Pos").map(Object::toString).orElse("[]")
                + " nbtBytes=" + nbtSize(nbt);
    }

    private static void traceEntityRestoreStage(
            final ServerWorld world,
            final ChunkPos chunkPos,
            final NbtCompound nbt,
            final String operationId,
            final ChunkTraceEventType eventType,
            final String stage,
            final String source,
            final String message,
            @Nullable final String suffix
    ) {
        if (!ChunkTraceWatchpoints.hasPayloadWatches()) {
            return;
        }
        final String entityUuid = entityUuid(nbt);
        if (entityUuid == null) {
            return;
        }
        final PayloadWatchTarget target = ChunkTraceWatchpoints.watchedEntity(worldId(world), entityUuid);
        if (target == null) {
            return;
        }
        traceWatch(
                eventType,
                stage,
                source,
                message,
                worldId(world),
                chunkPos,
                operationId,
                target,
                summarizeEntity(target, nbt)
                        + " chunkStatus=entity-restore"
                        + " source=" + source
                        + " thread=" + Thread.currentThread().getName()
                        + (suffix != null ? " " + suffix : ""),
                null
        );
    }

    @Nullable
    private static Entity resolveWatchedEntity(final ServerWorld world, final String entityUuid) {
        try {
            return world.getEntity(UUID.fromString(entityUuid));
        } catch (final IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String summarizeExpectedEntityAndPresence(
            final PayloadWatchTarget target,
            @Nullable final NbtCompound expectedNbt,
            @Nullable final Entity liveEntity,
            final WorldChunk chunk
    ) {
        return "uuid=" + target.entityUuid()
                + " expectedType=" + (expectedNbt != null ? expectedNbt.getString("id").orElse("<missing-id>") : "<unknown>")
                + " actualServerEntityPresent=" + (liveEntity != null)
                + " actualServerEntityType=" + (liveEntity != null ? liveEntity.getType() : "<missing>")
                + " chunkStatus=" + chunk.getStatus()
                + " chunkInstanceId=" + chunkInstanceId(chunk)
                + " thread=" + Thread.currentThread().getName();
    }

    private static String summarizeRemovedEntity(
            final PayloadWatchTarget target,
            final Entity entity,
            final Entity.RemovalReason reason,
            final String source
    ) {
        return "uuid=" + target.entityUuid()
                + " actualServerEntityPresent=false"
                + " actualServerEntityType=" + entity.getType()
                + " removalReason=" + reason
                + " pos=[" + entity.getX() + ',' + entity.getY() + ',' + entity.getZ() + ']'
                + " blockPos=" + entity.getBlockPos().toShortString()
                + " chunk=" + entity.getChunkPos().x + ',' + entity.getChunkPos().z
                + " isAlive=" + entity.isAlive()
                + " isRemoved=" + entity.isRemoved()
                + " source=" + source
                + " thread=" + Thread.currentThread().getName();
    }

    private static String summarizeLiveEntity(
            final PayloadWatchTarget target,
            final Entity entity,
            final String source
    ) {
        return "uuid=" + target.entityUuid()
                + " actualServerEntityPresent=" + !entity.isRemoved()
                + " actualServerEntityType=" + entity.getType()
                + " pos=[" + entity.getX() + ',' + entity.getY() + ',' + entity.getZ() + ']'
                + " blockPos=" + entity.getBlockPos().toShortString()
                + " chunk=" + entity.getChunkPos().x + ',' + entity.getChunkPos().z
                + " isAlive=" + entity.isAlive()
                + " isRemoved=" + entity.isRemoved()
                + " source=" + source
                + " thread=" + Thread.currentThread().getName();
    }

    private static int nbtSize(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return 0;
        }
        try {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                net.minecraft.nbt.NbtIo.writeCompound(nbt, output);
            }
            return buffer.size();
        } catch (final IOException ignored) {
            return nbt.toString().length();
        }
    }

    @Nullable
    private static String entityUuid(@Nullable final NbtCompound nbt) {
        if (nbt == null) {
            return null;
        }
        return nbt.getIntArray("UUID")
                .map(net.minecraft.util.Uuids::toUuid)
                .map(java.util.UUID::toString)
                .orElse(null);
    }

    private static String worldId(final ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    private static String worldId(final WorldChunk chunk) {
        return chunk.getWorld().getRegistryKey().getValue().toString();
    }

    private static String chunkInstanceId(@Nullable final Chunk chunk) {
        return chunk != null
                ? chunk.getClass().getSimpleName() + '@' + Integer.toHexString(System.identityHashCode(chunk))
                : "UNAVAILABLE";
    }

    private static void markEntityTransfer(
            final PayloadWatchTarget target,
            final String stage,
            final ChunkPos chunkPos
    ) {
        final EntityWatchState state = ENTITY_WATCH_STATE.computeIfAbsent(
                new EntityWatchKey(target.worldId(), target.entityUuid()),
                ignored -> new EntityWatchState()
        );
        state.lastKnownChunkX = chunkPos.x;
        state.lastKnownChunkZ = chunkPos.z;
        state.hasKnownChunk = true;
        if ("entity-manager-unload".equals(stage)) {
            state.unloadedWithoutReload = true;
            state.unloadTick = currentServerTick;
            return;
        }
        if ("entity-manager-load".equals(stage)) {
            state.unloadedWithoutReload = false;
            state.asserted = false;
        }
    }

    private static void clearPendingEntityReload(final PayloadWatchTarget target) {
        final EntityWatchState state = ENTITY_WATCH_STATE.get(
                new EntityWatchKey(target.worldId(), target.entityUuid())
        );
        if (state != null) {
            state.unloadedWithoutReload = false;
            state.asserted = false;
        }
    }

    private static void rememberEntityChunk(final PayloadWatchTarget target, final ChunkPos chunkPos) {
        final EntityWatchState state = ENTITY_WATCH_STATE.computeIfAbsent(
                new EntityWatchKey(target.worldId(), target.entityUuid()),
                ignored -> new EntityWatchState()
        );
        state.lastKnownChunkX = chunkPos.x;
        state.lastKnownChunkZ = chunkPos.z;
        state.hasKnownChunk = true;
    }

    private static boolean shouldTraceEntityForChunk(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target
    ) {
        final EntityWatchState state = ENTITY_WATCH_STATE.get(
                new EntityWatchKey(worldId, target.entityUuid())
        );
        return state == null
                || !state.hasKnownChunk
                || (state.lastKnownChunkX == chunkPos.x && state.lastKnownChunkZ == chunkPos.z);
    }

    private static void assertEntityReloadedAfterUnload(
            final String worldId,
            final ChunkPos chunkPos,
            final PayloadWatchTarget target,
            @Nullable final String operationId,
            @Nullable final Entity liveEntity
    ) {
        final EntityWatchState state = ENTITY_WATCH_STATE.get(
                new EntityWatchKey(worldId, target.entityUuid())
        );
        if (state == null || !state.unloadedWithoutReload || liveEntity != null || state.asserted) {
            return;
        }
        state.asserted = true;
        ChunkTraceStore.trace(
                ChunkisDebugDomain.ASSERTIONS,
                ChunkTraceEventType.ASSERTION_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.WATCHED_ENTITY_UNLOADED_NOT_RELOADED,
                "PayloadWatchTracer#assertEntityReloadedAfterUnload",
                "watched entity was unloaded from the live server manager and chunk became sendable again without " +
                        "entity-manager-load or entity-tracking-start",
                worldId,
                new DebugChunkKey(chunkPos.x, chunkPos.z),
                null,
                operationId,
                null,
                null,
                target,
                "chunk-full-entity",
                target.describe()
        );
    }

    private record WatchTraceKey(
            String worldId,
            int chunkX,
            int chunkZ,
            int blockX,
            int blockY,
            int blockZ
    ) {
    }

    private static final class WatchTraceState {
        private final String operationId;
        private final BlockState expectedState;
        private volatile boolean hasVisibilityEvent;
        private volatile boolean restoreDecisionSeen;
        private volatile boolean decodedPayloadNotAppliedAsserted;
        private volatile String appliedChunkInstanceId;
        private volatile boolean appliedDifferentChunkAsserted;
        private volatile boolean protoAttachedSeen;
        private volatile boolean worldConstructorConsumedSeen;

        private WatchTraceState(final String operationId, final BlockState expectedState) {
            this.operationId = operationId;
            this.expectedState = expectedState;
        }
    }

    private record EntityWatchKey(String worldId, String entityUuid) {
    }

    private static final class EntityWatchState {
        private volatile boolean unloadedWithoutReload;
        private volatile boolean asserted;
        private volatile long unloadTick;
        private volatile int lastKnownChunkX;
        private volatile int lastKnownChunkZ;
        private volatile boolean hasKnownChunk;
    }
}
