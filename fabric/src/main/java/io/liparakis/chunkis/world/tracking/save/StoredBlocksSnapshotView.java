package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.io.CisStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.io.IOException;
import java.util.function.Consumer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.Property;

/**
 * Save snapshot of a delta whose block payload is released, saved without its chunk (it unloaded): the blocks are
 * the stored entry's with the delta's recorded edits applied on top ({@link ChunkDelta#isBlockPayloadReleased()}),
 * read back on the save worker. Everything else comes from the delta's own snapshot, which is newer than the entry.
 *
 * <p>{@link #loadStoredBlocks()} must succeed before the view is encoded. It fails instead of yielding no blocks
 * when the entry is missing or unreadable, since an encoded empty view would clear the stored chunk.</p>
 */
final class StoredBlocksSnapshotView implements ChunkDeltaView<BlockState, NbtCompound> {

    /**
     * Snapshot of the delta: its block instructions are the edits applied over the entry, the rest is taken as
     * it is.
     */
    private final ChunkDeltaView<BlockState, NbtCompound> rest;

    /**
     * Storage holding the entry the blocks come from.
     */
    private final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage;

    /**
     * Position of the entry.
     */
    private final CisChunkPos pos;

    /**
     * Stored entry, once loaded.
     */
    private ChunkDelta<BlockState, NbtCompound> stored;

    /**
     * Creates the view; nothing is read until {@link #loadStoredBlocks()}.
     *
     * @param rest    snapshot of the delta, whose block instructions are applied over the entry's blocks
     * @param storage storage holding the entry
     * @param pos     position of the entry
     */
    StoredBlocksSnapshotView(final ChunkDeltaView<BlockState, NbtCompound> rest,
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage, final CisChunkPos pos) {
        this.rest = rest;
        this.storage = storage;
        this.pos = pos;
    }

    /**
     * Reads the stored entry's blocks and applies the delta's recorded edits over them, once.
     *
     * @throws IOException if the entry is missing or could not be decoded
     */
    void loadStoredBlocks() throws IOException {
        if (stored == null) {
            final ChunkDelta<BlockState, NbtCompound> entry = readStoredBlocks(storage, pos);
            applyEdits(rest, entry);
            stored = entry;
        }
    }

    /**
     * Applies a released delta's recorded block edits over an entry's blocks.
     *
     * @param edits view whose block instructions are the edits
     * @param entry decoded stored entry, changed in place
     */
    static void applyEdits(final ChunkDeltaView<BlockState, NbtCompound> edits,
            final ChunkDelta<BlockState, NbtCompound> entry) {
        edits.forEachBlock((x, y, z, state) -> entry.addBlockChange(x, y, z, state, false));
    }

    /**
     * Reads an entry whose blocks a released delta relies on.
     *
     * @param storage storage holding the entry
     * @param pos     position of the entry
     * @return the decoded entry
     * @throws IOException if the entry is missing or could not be decoded
     */
    static ChunkDelta<BlockState, NbtCompound> readStoredBlocks(
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage, final CisChunkPos pos)
            throws IOException {
        final CisStorage.LoadResult<BlockState, NbtCompound> result = storage.loadWithPresence(pos,
                "stored-blocks");
        if (!result.storageEntryPresent() || result.failed()) {
            throw new IOException("Stored entry of chunk " + pos + " holding its released blocks is "
                    + (result.failed() ? "unreadable" : "missing"));
        }
        return result.delta();
    }

    /**
     * Returns the loaded entry.
     *
     * @return the stored entry
     * @throws IllegalStateException if {@link #loadStoredBlocks()} has not succeeded
     */
    private ChunkDelta<BlockState, NbtCompound> stored() {
        if (stored == null) {
            throw new IllegalStateException("Stored blocks of chunk " + pos + " read before loadStoredBlocks");
        }
        return stored;
    }

    /**
     * Returns the delta's own snapshot, which holds everything but the blocks.
     *
     * @return the wrapped snapshot
     */
    ChunkDeltaView<BlockState, NbtCompound> rest() {
        return rest;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forEachBlock(final ChunkDelta.BlockVisitor<BlockState> visitor) {
        stored().forEachBlock(visitor);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long2ObjectMap<NbtCompound> getBlockEntities() {
        return rest.getBlockEntities();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forEachEntity(final Consumer<? super NbtCompound> consumer) {
        rest.forEachEntity(consumer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int countNonNullEntities() {
        return rest.countNonNullEntities();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getBlockChangesCount() {
        return stored().getBlockChangesCount();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getTouchedSectionCount() {
        return stored().getTouchedSectionCount();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NbtCompound getChunkMetadata() {
        return rest.getChunkMetadata();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getEncodedChunkMetadata() {
        return rest.getEncodedChunkMetadata();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void cacheEncodedChunkMetadata(final byte[] encodedPayload) {
        rest.cacheEncodedChunkMetadata(encodedPayload);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getSourceVersion() {
        return rest.getSourceVersion();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getMutationGeneration() {
        return rest.getMutationGeneration();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldSuppressInitialRepopulation() {
        return rest.shouldSuppressInitialRepopulation();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDirty() {
        return rest.isDirty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        return getBlockChangesCount() == 0 && rest.isEmpty();
    }
}
