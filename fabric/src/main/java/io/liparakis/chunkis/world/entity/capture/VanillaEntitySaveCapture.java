package io.liparakis.chunkis.world.entity.capture;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

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
    public static void capture(final ServerWorld world, final ChunkPos chunkPos,
            final List<? extends Entity> entities) {
        final WorldChunk chunk = LoadedChunks.getHeld(world, chunkPos.x, chunkPos.z);
        if (chunk instanceof ChunkisDeltaDuck deltaDuck) {
            captureLoaded(world, chunk, deltaDuck, entities);
        } else {
            captureUnloaded(world, chunkPos, entities);
        }
    }

    /**
     * Captures entities for a chunk that is still loaded.
     */
    private static void captureLoaded(final ServerWorld world, final WorldChunk chunk,
            final ChunkisDeltaDuck deltaDuck, final List<? extends Entity> entities) {
        if (SnapshotSafetyChecker.isSnapshotUnsafe(chunk)) {
            return;
        }
        final boolean owned = ChunkDeltaOwnership.hasChunkisOwnedState(deltaDuck.chunkis$getDelta());
        if (!owned && LiveEntitySnapshotCapture.countSavableLiveEntities(world, chunk) == 0) {
            return;
        }
        final ChunkDelta<BlockState, NbtCompound> delta = OwnedDeltaAccess.getOrClaim(chunk,
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
     * Captures entities for a chunk whose {@link WorldChunk} is no longer reachable.
     */
    @SuppressWarnings("unchecked")
    private static void captureUnloaded(final ServerWorld world, final ChunkPos chunkPos,
            final List<? extends Entity> entities) {
        ChunkDelta<BlockState, NbtCompound> delta =
                (ChunkDelta<BlockState, NbtCompound>) GlobalChunkTracker.getDelta(world, chunkPos);
        if (delta == null) {
            if (StoredEntityFingerprints.consumeIfMatches(world, chunkPos,
                    LiveEntitySnapshotCapture.fingerprintSavable(chunkPos, entities))) {
                // The stored entry already holds exactly these entities; skip reading and decoding it.
                return;
            }
            delta = loadStoredDelta(world, chunkPos, entities.size());
        }
        if (delta == null) {
            if (!entities.isEmpty() && MigrationStateServiceHolder.isAuthoritative(world.getRegistryKey())) {
                Chunkis.LOGGER.warn("Chunkis: Skipped saving {} entity/entities for unloaded chunk {} in {}: "
                                + "chunk has no Chunkis state to attach them to", entities.size(), chunkPos,
                        world.getRegistryKey()
                                .getValue());
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
     * Loads the persisted delta for {@code chunkPos}, or returns {@code null} when
     * storage is closed, has no entry, or the entry cannot be read.
     *
     * <p>A clean tracker entry means any async write already finished, so the
     * stored entry is current. The entry is never cleared on read failure.</p>
     */
    private static ChunkDelta<BlockState, NbtCompound> loadStoredDelta(final ServerWorld world,
            final ChunkPos chunkPos, final int entityCount) {
        if (!FabricCisStorageHelper.isStorageOpen(world)) {
            return null;
        }
        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = FabricCisStorageHelper.getStorage(world);
        final CisChunkPos cisPos = FabricCisStorageHelper.toStoragePos(chunkPos);
        if (!storage.contains(cisPos)) {
            return null;
        }
        try {
            final ChunkDelta<BlockState, NbtCompound> delta = storage.loadWithoutClearing(cisPos);
            delta.setSuppressInitialRepopulation(CisNbtUtil.shouldSuppressInitialRepopulation(delta));
            return delta;
        } catch (final IOException e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to read CIS chunk {} in {} while saving {} entity/entities; "
                            + "entity changes since the last chunk save were not persisted", chunkPos,
                    world.getRegistryKey()
                            .getValue(), entityCount, e);
            return null;
        }
    }
}
