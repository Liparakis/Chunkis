package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.bits.BitWriter;
import io.liparakis.chunkis.storage.model.CisChunk;
import io.liparakis.chunkis.storage.model.CisConstants;
import io.liparakis.chunkis.storage.model.CisSection;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Base class for CIS (Chunk Incremental Storage) encoders containing shared
 * encoding logic.
 *
 * @param <S> The BlockState type
 * @param <N> The NBT type
 *
 * @version 1
 * @author Liparakis
 */
public abstract class AbstractCisEncoder<S, N> {

    /**
     * Total number of blocks in a chunk section (16x16x16).
     */
    protected static final int SECTION_VOLUME = 4096;

    /** Adapter used to inspect block-state structure during palette and section encoding. */
    protected final BlockStateAdapter<?, S, ?> stateAdapter;
    /** Adapter used to serialize entity, block-entity, and chunk-metadata payloads. */
    protected final NbtAdapter<N> nbtAdapter;
    /** Canonical empty state used to guarantee stable air handling in local and global palettes. */
    protected final S airState;

    /**
     * Creates an encoder base configured with the adapters required by concrete codec variants.
     */
    protected AbstractCisEncoder(BlockStateAdapter<?, S, ?> stateAdapter, NbtAdapter<N> nbtAdapter, S airState) {
        this.stateAdapter = stateAdapter;
        this.nbtAdapter = nbtAdapter;
        this.airState = airState;
    }

    /**
     * Writes one global palette entry's block identity and properties.
     */
    protected void writeGlobalPaletteEntry(
            DataOutputStream dos,
            EncoderContext<S> ctx,
            S state) throws IOException {
        throw new UnsupportedOperationException("Subclasses must implement palette entry encoding");
    }

    /**
     * Internal method that orchestrates the complete encoding process.
     */
    protected byte[] encodeInternal(ChunkDelta<S, N> delta) throws IOException {
        EncoderContext<S> ctx = getContext();
        ctx.reset();

        DataOutputStream dos = new DataOutputStream(ctx.mainBuffer);

        EncodedChunkInput<S> input = fromDelta(delta);

        writeHeader(dos);
        writeGlobalPalette(dos, ctx, input.usedStates());
        writeSections(dos, ctx, input.chunk());
        writeBlockEntities(dos, delta);
        writeEntities(dos, delta);
        writeChunkMetadata(dos, delta);

        return ctx.mainBuffer.toByteArray();
    }

    /**
     * Returns the reusable encoding context owned by the concrete encoder implementation.
     */
    protected abstract EncoderContext<S> getContext();

    /**
     * Writes the global block palette to the output stream.
     */
    protected void writeGlobalPalette(
            DataOutputStream dos,
            EncoderContext<S> ctx,
            List<S> usedStates) throws IOException {

        ctx.globalIdMap.defaultReturnValue(-1);
        dos.writeInt(usedStates.size());
        ctx.bitWriter.reset();

        for (int i = 0; i < usedStates.size(); i++) {
            S state = usedStates.get(i);
            writeGlobalPaletteEntry(dos, ctx, state);
            ctx.globalIdMap.put(state, i);
        }

        byte[] palettePropertyData = ctx.bitWriter.toByteArray();
        dos.writeInt(palettePropertyData.length);
        dos.write(palettePropertyData);
    }

    /**
     * Converts a ChunkDelta to a CisChunk and collects states in the same pass.
     */
    private EncodedChunkInput<S> fromDelta(ChunkDelta<S, N> delta) {
        final CisChunk<S> chunk = new CisChunk<>();
        final EncoderContext<S> ctx = getContext();
        final List<S> usedStates = ctx.seenStateList;
        final Reference2IntMap<S> seenStates = ctx.seenStates;
        usedStates.clear();
        seenStates.clear();
        seenStates.put(airState, 0);
        usedStates.add(airState);

        delta.forEachBlock((x, y, z, state) -> {
            if (state != null) {
                chunk.addUniqueBlock(x, y, z, state);
                if (seenStates.getInt(state) == -1) {
                    seenStates.put(state, usedStates.size());
                    usedStates.add(state);
                }
            }
        });

        return new EncodedChunkInput<>(chunk, usedStates);
    }

    /**
     * Writes the CIS file header.
     */
    private static void writeHeader(DataOutputStream dos) throws IOException {
        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);
    }

    /**
     * Writes all chunk sections.
     */
    private void writeSections(DataOutputStream dos, EncoderContext<S> ctx, CisChunk<S> chunk)
            throws IOException {
        Int2ObjectMap<CisSection<S>> sections = chunk.getSections();
        int sectionCount = chunk.getSectionCount();

        dos.writeShort(sectionCount);
        ctx.bitWriter.reset();

        for (int i = 0; i < sectionCount; i++) {
            int sectionY = chunk.getSortedSectionIndex(i);
            encodeSection(ctx, sectionY, sections.get(sectionY));
        }
        ctx.bitWriter.flush();

        byte[] sectionData = ctx.bitWriter.toByteArray();
        dos.writeInt(sectionData.length);
        dos.write(sectionData);
    }

    /**
     * Writes block entity data.
     */
    private void writeBlockEntities(DataOutputStream dos, ChunkDelta<S, N> delta) throws IOException {
        Long2ObjectMap<N> bes = delta.getBlockEntities();
        int nonNullCount = 0;
        for (Long2ObjectMap.Entry<N> entry : bes.long2ObjectEntrySet()) {
            if (entry.getValue() != null) {
                nonNullCount++;
            }
        }

        dos.writeInt(nonNullCount);

        for (Long2ObjectMap.Entry<N> entry : bes.long2ObjectEntrySet()) {
            N blockEntityData = entry.getValue();
            if (blockEntityData == null) {
                continue;
            }

            long p = entry.getLongKey();
            int packedPos = (int) BlockInstruction.packPos(
                    BlockInstruction.unpackX(p),
                    BlockInstruction.unpackY(p),
                    BlockInstruction.unpackZ(p));
            dos.writeInt(packedPos);
            writeNbtPayload(blockEntityData, dos);
        }
    }

    /**
     * Writes entity data.
     */
    private void writeEntities(DataOutputStream dos, ChunkDelta<S, N> delta) throws IOException {
        final int entityCount = delta.countNonNullEntities();
        dos.writeInt(entityCount);

        if (entityCount > 0) {
            try {
                delta.forEachEntity(entity -> {
                    if (entity != null) {
                        try {
                            writeNbtPayload(entity, dos);
                        } catch (IOException e) {
                            throw new EntityEncodingException(e);
                        }
                    }
                });
            } catch (EntityEncodingException e) {
                throw (IOException) e.getCause();
            }
        }
    }

    /**
     * Writes optional chunk-level metadata required to reproduce stable worldgen
     * behavior, such as structure starts/references.
     */
    private void writeChunkMetadata(DataOutputStream dos, ChunkDelta<S, N> delta) throws IOException {
        final N metadata = delta.getChunkMetadata();
        dos.writeBoolean(metadata != null);

        if (metadata == null) {
            return;
        }

        final byte[] cachedPayload = delta.getEncodedChunkMetadata();
        if (cachedPayload != null) {
            dos.write(cachedPayload);
            return;
        }

        final ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream(256);
        try (DataOutputStream payloadOutput = new DataOutputStream(payloadBuffer)) {
            writeNbtPayload(metadata, payloadOutput);
            payloadOutput.flush();
        }

        final byte[] encodedPayload = payloadBuffer.toByteArray();
        delta.cacheEncodedChunkMetadata(encodedPayload);
        dos.write(encodedPayload);
    }

    /**
     * Writes one NBT payload using the format appropriate for the current CIS
     * version.
     */
    private void writeNbtPayload(N payload, DataOutputStream dos) throws IOException {
        if (CisConstants.VERSION >= 10) {
            nbtAdapter.writeRaw(payload, dos);
        } else {
            nbtAdapter.writeCompressed(payload, dos);
        }
    }

    /**
     * Encodes a single section.
     */
    private void encodeSection(EncoderContext<S> ctx, int sectionY, CisSection<S> section) {
        ctx.bitWriter.writeZigZag(sectionY, CisConstants.SECTION_Y_BITS);

        if (section.mode == CisSection.MODE_EMPTY) {
            ctx.bitWriter.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
            ctx.bitWriter.write(0, CisConstants.BLOCK_COUNT_BITS);
            return;
        }

        final S uniformState = uniformSectionState(section);
        if (uniformState != null) {
            encodeUniformSection(ctx, uniformState);
            return;
        }

        if (section.mode == CisSection.MODE_SPARSE) {
            encodeSparseSection(ctx, section);
        } else {
            encodeDenseSection(ctx, section);
        }
    }

    /**
     * Encodes a full single-state section using the sparse-mode sentinel layout.
     */
    private void encodeUniformSection(final EncoderContext<S> ctx, final S state) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
        ctx.bitWriter.write(CisConstants.UNIFORM_SECTION_SENTINEL, CisConstants.BLOCK_COUNT_BITS);

        final int globalBits = calculateBitsNeeded(ctx.globalIdMap.size());
        final int globalIdx = ctx.globalIdMap.getInt(state);
        ctx.bitWriter.write(globalIdx != -1 ? globalIdx : 0, globalBits);
    }


    /**
     * Encodes a sparse section as packed positions plus global palette indices.
     */
    @SuppressWarnings("unchecked")
    private void encodeSparseSection(EncoderContext<S> ctx, CisSection<S> section) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
        ctx.bitWriter.write(section.sparseSize, CisConstants.BLOCK_COUNT_BITS);

        if (section.sparseSize > 0) {
            int globalBits = calculateBitsNeeded(ctx.globalIdMap.size());

            for (int i = 0; i < section.sparseSize; i++) {
                ctx.bitWriter.write(section.sparseKeys[i] & 0xFFFF, 12);

                S state = (S) section.sparseValues[i];
                int globalIdx = ctx.globalIdMap.getInt(state);
                ctx.bitWriter.write(globalIdx != -1 ? globalIdx : 0, globalBits);
            }
        }
    }

    /**
     * Encodes a dense section.
     */
    private void encodeDenseSection(EncoderContext<S> ctx, CisSection<S> section) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_DENSE, 1);

        ctx.fastLocalPaletteIndex.clear();
        ctx.fastLocalPaletteIndex.defaultReturnValue(-1);
        ctx.localPaletteIds.clear();

        buildLocalPalette(ctx, section.denseBlocks);

        ensureAirInPalette(ctx);
        int localSize = ctx.localPaletteIds.size();

        ctx.bitWriter.write(localSize, CisConstants.PALETTE_SIZE_BITS);

        int globalBits = calculateBitsNeeded(ctx.globalIdMap.size());
        for (int i = 0; i < localSize; i++) {
            ctx.bitWriter.write(ctx.localPaletteIds.getInt(i), globalBits);
        }

        // Add +1 to localSize because index 0 is reserved for 'null' (no change)
        int bitsPerBlock = calculateBitsNeeded(localSize + 1);
        if (bitsPerBlock > 0) {
            writeBlockData(ctx, section.denseBlocks, bitsPerBlock);
        }
    }


    /**
     * Builds a dense-section local palette containing only states actually present in the section.
     */
    @SuppressWarnings("unchecked")
    protected void buildLocalPalette(EncoderContext<S> ctx, Object[] states) {
        for (int i = 0; i < SECTION_VOLUME; i++) {
            S state = (S) states[i];
            if (state != null && !ctx.fastLocalPaletteIndex.containsKey(state)) {
                int globalIdx = ctx.globalIdMap.getInt(state);
                if (globalIdx != -1) {
                    ctx.fastLocalPaletteIndex.put(state, ctx.localPaletteIds.size());
                    ctx.localPaletteIds.add(globalIdx);
                }
            }
        }
    }

    /**
     * Ensures the dense-section local palette always contains a stable entry for {@link #airState}.
     */
    protected void ensureAirInPalette(EncoderContext<S> ctx) {
        if (ctx.fastLocalPaletteIndex.containsKey(airState)) {
            return;
        }

        int airGlobalIdx = ctx.globalIdMap.getInt(airState);
        if (airGlobalIdx == -1) {
            airGlobalIdx = 0;
        }

        int localAirIndex = ctx.localPaletteIds.size();
        ctx.localPaletteIds.add(airGlobalIdx);
        ctx.fastLocalPaletteIndex.put(airState, localAirIndex);
    }

    /**
     * Writes the dense-section block stream using local palette indices plus one reserved null slot.
     */
    @SuppressWarnings("unchecked")
    private void writeBlockData(EncoderContext<S> ctx, Object[] states, int bitsPerBlock) {
        for (int i = 0; i < SECTION_VOLUME; i++) {
            S state = (S) states[i];

            // local index 0 means "no change" (null)
            // local index 1..N means the block was explicitly modified to the state in the
            // palette
            int localIdx = (state == null)
                    ? 0
                    : ctx.fastLocalPaletteIndex.getInt(state) + 1;

            ctx.bitWriter.write(localIdx, bitsPerBlock);
        }
    }

    /**
     * Returns the repeated state when the section is fully filled with one state.
     */
    @SuppressWarnings("unchecked")
    private S uniformSectionState(final CisSection<S> section) {
        if (section.mode != CisSection.MODE_DENSE || section.denseBlocks == null) {
            return null;
        }

        final Object first = section.denseBlocks[0];
        if (first == null) {
            return null;
        }

        for (int i = 1; i < SECTION_VOLUME; i++) {
            if (!Objects.equals(first, section.denseBlocks[i])) {
                return null;
            }
        }

        return (S) first;
    }

    /**
     * Returns the minimum bit width needed to encode values in {@code [0, maxValue)}.
     */
    protected static int calculateBitsNeeded(int maxValue) {
        return AbstractCisDecoder.calculateBitsNeeded(maxValue);
    }

    /**
     * Pair of the sparse chunk representation and the set of states that became part of the global palette.
     */
    private record EncodedChunkInput<S>(CisChunk<S> chunk, List<S> usedStates) {
    }

    public static class EncoderContext<S> {
        /** Main byte sink for the full encoded chunk payload. */
        public final ByteArrayOutputStream mainBuffer = new ByteArrayOutputStream(16384);
        /** Scratch writer reused for section payloads and palette property payloads. */
        public final BitWriter bitWriter = new BitWriter(8192);
        /** Global palette reverse lookup from block state to encoded palette index. */
        public final Reference2IntMap<S> globalIdMap = new Reference2IntOpenHashMap<>();
        /** Dense-section local palette encoded as global palette ids. */
        private final IntArrayList localPaletteIds = new IntArrayList(64);
        /** Dense-section reverse lookup from block state identity to local palette index. */
        public final Reference2IntMap<S> fastLocalPaletteIndex = new Reference2IntOpenHashMap<>();
        /** Reusable set of states encountered while converting a delta into encoder input. */
        private final Reference2IntMap<S> seenStates = new Reference2IntOpenHashMap<>();
        /** Reusable ordered list of states encountered while converting a delta into encoder input. */
        private final List<S> seenStateList = new ArrayList<>(16);

        /**
         * Clears mutable per-encode state while keeping backing buffers and maps for reuse.
         */
        public void reset() {
            mainBuffer.reset();
            globalIdMap.clear();
            seenStates.defaultReturnValue(-1);
        }
    }

    /**
     * Internal wrapper used to rethrow checked NBT write failures through a
     * lambda-based entity loop without materializing a temporary list.
     */
    private static final class EntityEncodingException extends RuntimeException {
        EntityEncodingException(final IOException cause) {
            super(cause);
        }
    }
}
