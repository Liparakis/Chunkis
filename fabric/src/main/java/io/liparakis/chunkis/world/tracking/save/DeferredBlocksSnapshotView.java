package io.liparakis.chunkis.world.tracking.save;

import net.minecraft.world.chunk.IdListPalette;
import net.minecraft.state.property.Property;
import net.minecraft.block.Block;
import java.io.IOException;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.core.codec.CisBlockPart;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.core.SectionStatesSource;
import io.liparakis.chunkis.world.restoration.core.PalettedContainerFields;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.function.Consumer;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.collection.PaletteStorage;
import net.minecraft.world.chunk.Palette;
import net.minecraft.world.chunk.PalettedContainer;

/**
 * Async-save snapshot of a chunk whose blocks were copied as section containers instead of captured as
 * instructions ({@code CisSnapshotCapture#captureDeferringBlocks}): the save worker reads the blocks from the
 * copies, so the server thread only copies the containers, as vanilla does to save a chunk. Everything else
 * comes from the delta's own snapshot, whose block instructions are ignored.
 *
 * <p>Blocks are listed exactly as a full capture appends them: every non-air block, section by section, in
 * y, z, x order. Each section is unpacked once, on first use, on the save worker, into its 4096 states (air
 * left {@code null}), which the encoder also takes a whole section at a time ({@link SectionStatesSource}).</p>
 */
public final class DeferredBlocksSnapshotView implements ChunkDeltaView<BlockState, NbtCompound>,
        SectionStatesSource<BlockState> {

    /**
     * Blocks per section edge.
     */
    private static final int SECTION_SIZE = 16;

    /**
     * Positions per section.
     */
    private static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    /**
     * Snapshot of the delta, for everything but blocks.
     */
    private final ChunkDeltaView<BlockState, NbtCompound> rest;

    /**
     * Copied block containers by section index; {@code null} for sections holding only air.
     */
    private final PalettedContainer<BlockState>[] sections;

    /**
     * Bottom block Y of the chunk.
     */
    private final int bottomY;


    /**
     * Each section's 4096 states, air {@code null}; {@code null} for a section holding only air.
     */
    private Object[][] states;

    /**
     * Fingerprint the stored entry was last written with, captured when this save was queued; may be stale.
     */
    private final long[] previousPrint;

    /**
     * Whether this is the save of a chunk unloading.
     */
    private final boolean unloading;

    /**
     * These sections' content hash, once computed.
     */
    private long[] sectionsHash;

    /**
     * Fingerprint of what this snapshot's write stored, once written.
     */
    private long[] writtenPrint;

    /**
     * Non-air blocks per section, matching {@link #states}.
     */
    private int[] counts;

    /**
     * Total non-air blocks.
     */
    private int blockCount;

    /**
     * Sections holding at least one non-air block.
     */
    private int touchedSections;

    /**
     * Creates the view.
     *
     * @param rest     snapshot of the delta, for everything but blocks
     * @param sections copied block containers by section index
     * @param bottomY  bottom block Y of the chunk
     */
    DeferredBlocksSnapshotView(final ChunkDeltaView<BlockState, NbtCompound> rest,
            final PalettedContainer<BlockState>[] sections, final int bottomY, final long[] previousPrint,
            final boolean unloading) {
        this.rest = rest;
        this.sections = sections;
        this.previousPrint = previousPrint;
        this.unloading = unloading;
        this.bottomY = bottomY;
    }

    /**
     * Unpacks every section's non-air blocks once: one bulk index unpack per section and one palette lookup per
     * palette entry.
     */
    /**
     * Prepares the save by copying the stored entry's block part when these sections are exactly the ones it was
     * last written from: their content hash matches, and the stored block part still hashes as written.
     *
     * @param storage chunk storage
     * @param pos     chunk position
     * @return the prepared save, or {@code null} when the blocks must be encoded
     */
    CisStorage.PreparedSave prepareReusingStoredBlocks(
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage, final CisChunkPos pos) {
        scan();
        if (previousPrint == null || previousPrint[0] != sectionsHash[0] || previousPrint[1] != sectionsHash[1]) {
            return null;
        }
        try {
            return storage.prepareSaveReusingStoredBlocks(pos, this,
                    new long[]{previousPrint[2], previousPrint[3]});
        } catch (final IOException e) {
            Chunkis.LOGGER.warn("Chunkis: Could not reuse the stored blocks of chunk {}; encoding them", pos, e);
            return null;
        }
    }

    /**
     * Records what the write of this snapshot stored, for the next save to compare against.
     *
     * @param payload the written payload, uncompressed
     */
    void recordWritten(final byte[] payload) {
        final long[] blockPart = CisBlockPart.hashOf(payload);
        if (blockPart == null) {
            return;
        }
        scan();
        writtenPrint = new long[]{sectionsHash[0], sectionsHash[1], blockPart[0], blockPart[1]};
    }

    /**
     * Returns the fingerprint of what this snapshot's write stored.
     *
     * @return section hash then block part hash, or {@code null} when not written or unknown
     */
    long[] writtenPrint() {
        return writtenPrint;
    }

    /**
     * Returns the copied section containers this snapshot was written from.
     *
     * @return containers by section index, {@code null} for sections of only air
     */
    PalettedContainer<BlockState>[] sections() {
        return sections;
    }

    /**
     * Returns the world bottom Y.
     *
     * @return bottom Y
     */
    int bottomY() {
        return bottomY;
    }

    /**
     * Returns whether this is the save of a chunk unloading.
     *
     * @return {@code true} for an unload save
     */
    boolean unloading() {
        return unloading;
    }

    /**
     * Fingerprints section containers as a stored entry keeps them ({@link ContentPrint}).
     *
     * @param sections containers by section index, {@code null} for sections of only air
     * @return two 64-bit hashes
     */
    static long[] contentPrintOf(final PalettedContainer<BlockState>[] sections) {
        final DeferredBlocksSnapshotView view = new DeferredBlocksSnapshotView(null, sections, 0, null, false);
        view.scan();
        return view.sectionsHash;
    }

    /**
     * Walks every section once without expanding it: counts its non-air blocks and fingerprints its content
     * ({@link ContentPrint}).
     */
    private void scan() {
        if (sectionsHash != null) {
            return;
        }
        counts = new int[sections.length];
        final ContentPrint print = new ContentPrint();
        int total = 0;
        int touched = 0;
        final int[] indices = new int[SECTION_VOLUME];
        final int[] ids = new int[SECTION_VOLUME];
        for (int section = 0; section < sections.length; section++) {
            final PalettedContainer<BlockState> container = sections[section];
            if (container == null) {
                continue;
            }
            final Object data = PalettedContainerFields.data(container);
            final Palette<BlockState> palette = PalettedContainerFields.palette(data);
            final PaletteStorage storage = PalettedContainerFields.storage(data);
            final boolean global = palette instanceof IdListPalette<?>;
            final int paletteSize = global ? 0 : palette.getSize();
            final int[] paletteIds = new int[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                paletteIds[i] = ContentPrint.idOf(palette.get(i));
            }
            storage.writePaletteIndices(indices);
            int count = 0;
            for (int index = 0; index < SECTION_VOLUME; index++) {
                final int id = global ? ContentPrint.idOf(palette.get(indices[index])) : paletteIds[indices[index]];
                ids[index] = id;
                if (id >= 0) {
                    count++;
                }
            }
            if (count > 0) {
                print.section(section, ids);
            }
            counts[section] = count;
            total += count;
            touched += count > 0 ? 1 : 0;
        }
        blockCount = total;
        touchedSections = touched;
        sectionsHash = print.finish();
    }

    /**
     * Fingerprint of a chunk's blocks as a stored entry keeps them: for every section holding a non-air block, in
     * section order, the raw state id at each position, air as -1 (the format does not keep air variants, nor
     * sections of only air). The load seeds it from the containers it builds; the save compares against it.
     */
    public static final class ContentPrint {

        /**
         * First lane.
         */
        private long a = 0x243F6A8885A308D3L;

        /**
         * Second lane.
         */
        private long b = 0x13198A2E03707344L;

        /**
         * Returns the id a state contributes: its raw id, or -1 for air.
         *
         * @param state block state, possibly {@code null}
         * @return raw id or -1
         */
        public static int idOf(final BlockState state) {
            return state == null || state.isAir() ? -1 : Block.STATE_IDS.getRawId(state);
        }

        /**
         * Adds one section holding at least one non-air block.
         *
         * @param sectionIndex index in the chunk's section array
         * @param ids          4096 ids ({@link #idOf}), index {@code y << 8 | z << 4 | x}
         */
        public void section(final int sectionIndex, final int[] ids) {
            a = CisBlockPart.mix(a ^ sectionIndex);
            b = CisBlockPart.mix(b + sectionIndex * 0x9E3779B97F4A7C15L);
            for (int index = 0; index < SECTION_VOLUME; index++) {
                a = (a + ids[index]) * 0x100000001B3L;
                b = (b ^ ids[index]) * 0xC2B2AE3D27D4EB4FL + index;
            }
            a = CisBlockPart.mix(a);
            b = CisBlockPart.mix(b);
        }

        /**
         * Returns the fingerprint.
         *
         * @return two 64-bit hashes
         */
        public long[] finish() {
            return new long[]{a, b};
        }
    }

    /**
     * Expands each section into its 4096 states, air left {@code null}, for the encoder. Counts come from
     * {@link #scan}.
     */
    private void unpack() {
        if (states != null) {
            return;
        }
        scan();
        states = new Object[sections.length][];
        final int[] indices = new int[SECTION_VOLUME];
        for (int section = 0; section < sections.length; section++) {
            final PalettedContainer<BlockState> container = sections[section];
            if (container == null || counts[section] == 0) {
                continue;
            }
            final Object data = PalettedContainerFields.data(container);
            final Palette<BlockState> palette = PalettedContainerFields.palette(data);
            final PaletteStorage storage = PalettedContainerFields.storage(data);
            final BlockState[] byIndex = new BlockState[palette.getSize()];
            for (int i = 0; i < byIndex.length; i++) {
                final BlockState state = palette.get(i);
                byIndex[i] = state == null || state.isAir() ? null : state;
            }
            storage.writePaletteIndices(indices);
            final Object[] sectionStates = new Object[SECTION_VOLUME];
            for (int index = 0; index < SECTION_VOLUME; index++) {
                sectionStates[index] = byIndex[indices[index]];
            }
            states[section] = sectionStates;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forEachBlock(final ChunkDelta.BlockVisitor<BlockState> visitor) {
        unpack();
        for (int section = 0; section < sections.length; section++) {
            final Object[] sectionStates = states[section];
            if (sectionStates == null) {
                continue;
            }
            final int sectionBottomY = bottomY + section * SECTION_SIZE;
            for (int index = 0; index < SECTION_VOLUME; index++) {
                if (sectionStates[index] != null) {
                    visitor.visitBlock(index & (SECTION_SIZE - 1), sectionBottomY + (index >>> 8),
                            (index >>> 4) & (SECTION_SIZE - 1), (BlockState) sectionStates[index]);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forEachSectionStates(final SectionStatesVisitor visitor) {
        unpack();
        for (int section = 0; section < sections.length; section++) {
            if (states[section] != null) {
                visitor.visit((bottomY >> 4) + section, states[section]);
            }
        }
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
        scan();
        return blockCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getTouchedSectionCount() {
        scan();
        return touchedSections;
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

    /**
     * Returns the delta's own snapshot, which holds everything but the blocks.
     *
     * @return the wrapped snapshot
     */
    ChunkDeltaView<BlockState, NbtCompound> rest() {
        return rest;
    }
}
