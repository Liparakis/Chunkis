package io.liparakis.chunkis.world.entity.capture;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.integration.c2me.C2meSaveBridge;
import io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.restoration.capture.BaseChunkCaptureUtil;
import io.liparakis.chunkis.world.restoration.capture.SnapshotSafetyChecker;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.ownership.OwnedDeltaAccess;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.state.LoadedChunks;
import java.io.IOException;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Mirrors vanilla's per-chunk entity save into the Chunkis delta.
 *
 * <p>Authoritative worlds block vanilla {@code entities/} region writes, so the
 * delta is the only entity store. Vanilla saves entities independently of the
 * chunk's dirty flag (autosave, shutdown flush, entity-section unload), and the
 * chunk-save hook alone misses those: mob positions and container/item-frame/
 * armor-stand contents changed after the last chunk save were lost. This class
 * runs at that vanilla entity save, while the entities are still live.</p>
 *
 * <p><b>Threading:</b> server thread only.</p>
 */
public final class VanillaEntitySaveCapture {

    /**
     * Trace and ownership source label.
     */
    private static final String SOURCE = "VanillaEntitySaveCapture#capture";

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private VanillaEntitySaveCapture() {
        throw new AssertionError("Utility class");
    }

    /**
     * Captures the entities vanilla is saving for {@code chunkPos} into the chunk's
     * delta and registers that delta as dirty.
     *
     * <p>While the chunk is still held, its attached delta is used (claiming it
     * when live entities exist, as the spawn and save hooks do). That includes a
     * chunk below full level waiting to unload: vanilla saves its entities then,
     * and an unclaimed chunk has no other state to take them. When the chunk has
     * already unloaded, the tracked delta or, failing that, the stored
     * CIS entry is updated instead. A chunk with no Chunkis state at all cannot be
     * written safely without its blocks, so its entities are skipped with a
     * warning in authoritative worlds.</p>
     *
     * @param world    world owning the chunk
     * @param chunkPos chunk whose entities vanilla is saving
     * @param entities entities vanilla is saving for that chunk
     */
    public static void capture(final ServerLevel world, final ChunkPos chunkPos,
            final List<? extends Entity> entities) {
        final LevelChunk chunk = LoadedChunks.getHeld(world, chunkPos.x(), chunkPos.z());
        if (chunk instanceof ChunkisDeltaDuck deltaDuck) {
            captureLoaded(world, chunk, deltaDuck, entities);
        } else {
            captureUnloaded(world, chunkPos, entities);
        }
    }

    /**
     * Returns whether the region guard would drop vanilla's entity write for this world, which is what makes
     * this capture the only copy.
     *
     * @param world world owning the entities
     * @return {@code true} when vanilla's write would never reach disk
     */
    public static boolean isVanillaWriteBlocked(final ServerLevel world) {
        return MigrationStateServiceHolder.isAuthoritative(world.dimension()) && !C2meSaveBridge.isUnavailable();
    }

    /**
     * Captures entities for a chunk that is still loaded.
     */
    private static void captureLoaded(final ServerLevel world, final LevelChunk chunk,
            final ChunkisDeltaDuck deltaDuck, final List<? extends Entity> entities) {
        final boolean owned = ChunkDeltaOwnership.hasChunkisOwnedState(deltaDuck.chunkis$getDelta());
        if (owned && entities.isEmpty() && holdsNoEntities(deltaDuck.chunkis$getDelta())) {
            // Most chunks: nothing to capture, and nothing stored for an empty list to clear.
            return;
        }
        if (SnapshotSafetyChecker.isSnapshotUnsafe(chunk)) {
            return;
        }
        if (!owned && LiveEntitySnapshotCapture.countSavableLiveEntities(world, chunk) == 0) {
            return;
        }
        final ChunkDelta<BlockState, CompoundTag> delta = OwnedDeltaAccess.getOrClaim(chunk,
                ChunkTraceReason.EXPLICIT_CHUNKIS_MUTATION, SOURCE);
        if (!owned) {
            BaseChunkCaptureUtil.captureBaseChunkIfMissing(world, chunk, delta);
        }
        LiveEntitySnapshotCapture.capture(world, chunk.getPos(), entities, delta, null, SOURCE);
        if (delta.isDirty()) {
            GlobalChunkTracker.markDirty(chunk, SOURCE);
        }
    }

    /**
     * Returns whether a delta holds no entity at all, spawned or still pending.
     */
    private static boolean holdsNoEntities(final ChunkDelta<?, ?> delta) {
        return delta.countNonNullEntities() == 0;
    }

    /**
     * Captures entities for a chunk whose {@link LevelChunk} is no longer reachable.
     */
    @SuppressWarnings("unchecked")
    private static void captureUnloaded(final ServerLevel world, final ChunkPos chunkPos,
            final List<? extends Entity> entities) {
        ChunkDelta<BlockState, CompoundTag> delta =
                (ChunkDelta<BlockState, CompoundTag>) GlobalChunkTracker.getDelta(world, chunkPos);
        if (delta == null) {
            if (StoredEntityFingerprints.consumeIfMatches(world, chunkPos,
                    LiveEntitySnapshotCapture.fingerprintSavable(chunkPos, entities))) {
                // The stored entry already holds exactly these entities; skip reading and decoding it.
                return;
            }
            delta = loadStoredDelta(world, chunkPos, entities.size());
        }
        if (delta == null) {
            if (!entities.isEmpty() && MigrationStateServiceHolder.isAuthoritative(world.dimension())) {
                Chunkis.LOGGER.warn("Chunkis: Skipped saving {} entity/entities for unloaded chunk {} in {}: "
                                + "chunk has no Chunkis state to attach them to", entities.size(), chunkPos,
                        world.dimension()
                                .identifier());
            }
            return;
        }
        if (!ChunkDeltaOwnership.hasChunkisOwnedState(delta)) {
            if (entities.isEmpty()) {
                return;
            }
            delta.claimOwnership(ChunkTraceReason.EXPLICIT_CHUNKIS_MUTATION.name(), SOURCE);
        }
        LiveEntitySnapshotCapture.capture(world, chunkPos, entities, delta, null, SOURCE);
        if (delta.isDirty()) {
            GlobalChunkTracker.addDelta(world, chunkPos, delta, SOURCE);
        }
    }

    /**
     * Loads the persisted delta for {@code chunkPos} without its blocks, as a released
     * delta whose blocks stay the stored entry's, or returns {@code null} when storage is
     * closed, has no entry, or the entry cannot be read.
     *
     * <p>A clean tracker entry means any async write already finished, so the
     * stored entry is current. The entry is never cleared on read failure.</p>
     */
    private static ChunkDelta<BlockState, CompoundTag> loadStoredDelta(final ServerLevel world,
            final ChunkPos chunkPos, final int entityCount) {
        if (!FabricCisStorageHelper.isStorageOpen(world)) {
            return null;
        }
        final CisStorage<Block, BlockState, Property<?>, CompoundTag> storage = FabricCisStorageHelper.getStorage(world);
        final CisChunkPos cisPos = FabricCisStorageHelper.toStoragePos(chunkPos);
        try {
            // Entities only: the blocks stay the stored entry's (a released delta), not decoded on this thread.
            final ChunkDelta<BlockState, CompoundTag> delta = storage.loadReleasedWithoutClearing(cisPos);
            if (delta == null) {
                return null;
            }
            delta.setSuppressInitialRepopulation(CisNbtUtil.shouldSuppressInitialRepopulation(delta));
            return delta;
        } catch (final IOException e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to read CIS chunk {} in {} while saving {} entity/entities; "
                            + "entity changes since the last chunk save were not persisted", chunkPos,
                    world.dimension()
                            .identifier(), entityCount, e);
            return null;
        }
    }
}
