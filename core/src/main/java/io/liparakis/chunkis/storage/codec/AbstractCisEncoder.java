package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Base class for CIS (Chunk Incremental Storage) encoders containing shared
 * encoding logic.
 *
 * <p>Concrete subclasses supply {@link #writeGlobalPaletteEntry} for the
 * version-specific block-state serialization format and {@link #getContext}
 * to expose a per-instance reusable scratch context.</p>
 *
 * @param <S> The BlockState type
 * @param <N> The NBT type
 * @author Liparakis
 * @version 1
 */
public abstract class AbstractCisEncoder<S, N> {

    /**
     * Total number of blocks in a chunk section (16×16×16).
     */
    protected static final int SECTION_VOLUME = 4096;

    /**
     * Adapter used to serialize entity, block-entity, and chunk-metadata payloads.
     */
    protected final NbtAdapter<N> nbtAdapter;

    /**
     * Canonical empty state. Air is always assigned global palette index 0 so
     * that absent (null) blocks in delta snapshots decode correctly.
     */
    protected final S airState;

    protected AbstractCisEncoder(NbtAdapter<N> nbtAdapter, S airState) {
        this.nbtAdapter = nbtAdapter;
        this.airState = airState;
    }

    /**
     * Writes one global palette entry's block identity and properties to {@code dos}.
     * The bit-packed property data should be written to {@code ctx.bitWriter}; it is
     * flushed and length-prefixed by {@link #writeGlobalPalette} after all entries.
     */
    protected abstract void writeGlobalPaletteEntry(
            DataOutputStream dos,
            EncoderContext<S> ctx,
            S state) throws IOException;

    /**
     * Returns the reusable encoding context owned by the concrete encoder implementation.
     */
    protected abstract EncoderContext<S> getContext();

    /**
     * Orchestrates the complete encoding process for one chunk delta.
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

    private static void writeHeader(DataOutputStream dos) throws IOException {
        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);
    }

    /**
     * Writes the global block palette.
     *
     * <p>Air is always index 0. All bit-packed property data is accumulated in
     * {@code ctx.bitWriter}, then length-prefixed and flushed once to avoid
     * interleaving random-access seeking with the sequential stream.</p>
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

    private void writeBlockEntities(DataOutputStream dos, ChunkDelta<S, N> delta) throws IOException {
        Long2ObjectMap<N> bes = delta.getBlockEntities();

        // Count non-null entries up front; null entries represent deletions and
        // are omitted from the encoded payload.
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
                    BlockInstruction.unpackZ(p)
            );
            dos.writeInt(packedPos);
            writeNbtPayload(blockEntityData, dos);
        }
    }

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
     * behavior (structure starts/references). The payload is cached on the delta
     * after first serialization to avoid re-encoding on repeated flushes.
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
     * Writes one NBT payload. Raw format is used for CIS v10+; earlier versions
     * use zlib-compressed NBT to fit within legacy region-file size constraints.
     */
    private void writeNbtPayload(N payload, DataOutputStream dos) throws IOException {
        if (CisConstants.VERSION >= 10) {
            nbtAdapter.writeRaw(payload, dos);
        } else {
            nbtAdapter.writeCompressed(payload, dos);
        }
    }

    /**
     * Converts a ChunkDelta to a CisChunk and collects the global palette state
     * list in the same pass to avoid a second traversal.
     *
     * <p>Air (or any state equal to {@link #airState}) is skipped during chunk
     * population because absent positions decode as air implicitly. Air is still
     * always added as index 0 in the palette so that local palette entries that
     * map to "no explicit block" have a stable target.</p>
     */
    private EncodedChunkInput<S> fromDelta(ChunkDelta<S, N> delta) {
        final CisChunk<S> chunk = new CisChunk<>();
        final EncoderContext<S> ctx = getContext();
        final List<S> usedStates = ctx.seenStateList;
        final Reference2IntMap<S> seenStates = ctx.seenStates;
        usedStates.clear();
        seenStates.clear();
        // Air is always palette index 0.
        seenStates.put(airState, 0);
        usedStates.add(airState);

        delta.forEachBlock((x, y, z, state) -> {
            if (state != null && !isImplicitAirState(state)) {
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
     * Selects and writes the most compact encoding for a single 16×16×16 section.
     *
     * <p>Four encodings are evaluated; the one with the lowest bit cost wins:
     * <ul>
     *   <li><b>UNIFORM</b> – all 4096 positions share one state.</li>
     *   <li><b>DEFAULT_SPARSE</b> – one dominant state is implied; only differing
     *       positions are emitted as (position, state) pairs.</li>
     *   <li><b>SPARSE</b> – a flat list of (position, state) pairs for every
     *       non-null block; efficient for sections with few changes.</li>
     *   <li><b>DENSE</b> – a local palette plus a full 4096-slot bit-array;
     *       efficient when most positions are occupied.</li>
     * </ul>
     * </p>
     */
    private void encodeSection(EncoderContext<S> ctx, int sectionY, CisSection<S> section) {
        ctx.bitWriter.writeZigZag(sectionY, CisConstants.SECTION_Y_BITS);

        if (section.mode == CisSection.MODE_EMPTY) {
            ctx.bitWriter.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
            ctx.bitWriter.write(0, CisConstants.BLOCK_COUNT_BITS);
            return;
        }

        // Pre-compute the one value that every cost function needs.
        final int globalBits = calculateBitsNeeded(ctx.globalIdMap.size());

        // Uniform check: free because denseStatesForSection may be needed by
        // other candidates, but we avoid the full dense materialize if the
        // section is already in dense mode - just check the array directly.
        final S uniformState = uniformSectionState(section);
        final DefaultSparseCandidate<S> defaultSparse = defaultSparseCandidate(ctx, section);
        final int sparseBits = sparseEncodingBits(section, globalBits);
        final int uniformBits = uniformState != null ? uniformEncodingBits(globalBits) : Integer.MAX_VALUE;
        final int defaultSparseBits = defaultSparse != null
                ? defaultSparseEncodingBits(defaultSparse.exceptionCount(), globalBits)
                : Integer.MAX_VALUE;
        // NOTE: denseEncodingBits mutates ctx palette scratch as a side-effect;
        // always call it last so earlier candidates see a clean context.
        final int denseBits = denseEncodingBits(ctx, section, globalBits);

        SectionEncoding selected = SectionEncoding.DENSE;
        int selectedBits = denseBits;

        if (sparseBits <= selectedBits) {
            selected = SectionEncoding.SPARSE;
            selectedBits = sparseBits;
        }
        if (uniformBits <= selectedBits) {
            selected = SectionEncoding.UNIFORM;
            selectedBits = uniformBits;
        }
        if (defaultSparseBits < selectedBits) {
            selected = SectionEncoding.DEFAULT_SPARSE;
        }

        switch (selected) {
            case UNIFORM -> encodeUniformSection(ctx, uniformState, globalBits);
            case DEFAULT_SPARSE -> encodeDefaultSparseSection(ctx, section, defaultSparse, globalBits);
            case SPARSE -> encodeSparseSectionAdaptive(ctx, section, globalBits);
            case DENSE -> encodeDenseSectionAdaptive(ctx, section);
        }
    }

    /**
     * Emits a section that consists entirely of one repeated state.
     * Uses the sparse-mode wire format with the uniform sentinel as the block count.
     */
    private void encodeUniformSection(final EncoderContext<S> ctx, final S state, final int globalBits) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
        ctx.bitWriter.write(CisConstants.UNIFORM_SECTION_SENTINEL, CisConstants.BLOCK_COUNT_BITS);
        final int globalIdx = ctx.globalIdMap.getInt(state);
        ctx.bitWriter.write(globalIdx != -1 ? globalIdx : 0, globalBits);
    }

    /**
     * Emits a section using one implied dominant state plus explicit exception
     * entries for every position that differs. Wire format: sparse header, then
     * the default global index, then the exception count, then (pos, state) pairs.
     */
    private void encodeDefaultSparseSection(
            final EncoderContext<S> ctx,
            final CisSection<S> section,
            final DefaultSparseCandidate<S> candidate,
            final int globalBits
    ) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
        ctx.bitWriter.write(CisConstants.DEFAULT_SPARSE_SECTION_SENTINEL, CisConstants.BLOCK_COUNT_BITS);

        final int defaultGlobalIdx = ctx.globalIdMap.getInt(candidate.defaultState());
        ctx.bitWriter.write(defaultGlobalIdx != -1 ? defaultGlobalIdx : 0, globalBits);
        ctx.bitWriter.write(candidate.exceptionCount(), CisConstants.BLOCK_COUNT_BITS);
        writeDefaultSparseExceptions(ctx, denseStatesForSection(ctx, section), candidate.defaultState(), globalBits);
    }

    /**
     * Emits a sparse section. If the section is stored in sparse-mode in memory,
     * entries are written directly; otherwise the dense array is scanned for
     * non-null positions.
     */
    private void encodeSparseSectionAdaptive(
            final EncoderContext<S> ctx,
            final CisSection<S> section,
            final int globalBits
    ) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_SPARSE, 1);

        if (section.mode == CisSection.MODE_SPARSE) {
            ctx.bitWriter.write(section.sparseSize, CisConstants.BLOCK_COUNT_BITS);
            if (section.sparseSize > 0) {
                writeSparseEntriesFromSparse(ctx, section, globalBits);
            }
            return;
        }

        // Dense-mode section that is cheaper to encode as sparse.
        ctx.bitWriter.write(sparseEntryCount(section), CisConstants.BLOCK_COUNT_BITS);
        writeSparseEntriesFromDense(ctx, section.denseBlocks, globalBits);
    }

    /**
     * Emits a dense section. Resolves the correct 4096-slot state array from
     * either the native dense storage or a scratch expansion of a sparse section.
     */
    private void encodeDenseSectionAdaptive(final EncoderContext<S> ctx, final CisSection<S> section) {
        encodeDenseSectionStates(ctx, denseStatesForSection(ctx, section));
    }

    /**
     * Encodes one dense-section state array using a local palette.
     *
     * <p>Index 0 in the block stream is reserved for "no change" (null), so local
     * palette indices are shifted by +1 when written. Air is always present in the
     * local palette to guarantee a valid mapping for positions that were explicitly
     * cleared to air.</p>
     */
    private void encodeDenseSectionStates(final EncoderContext<S> ctx, final Object[] states) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_DENSE, 1);

        ctx.fastLocalPaletteIndex.clear();
        ctx.fastLocalPaletteIndex.defaultReturnValue(-1);
        ctx.localPaletteIds.clear();

        buildLocalPalette(ctx, states);
        ensureAirInPalette(ctx);

        final int localSize = ctx.localPaletteIds.size();
        ctx.bitWriter.write(localSize, CisConstants.PALETTE_SIZE_BITS);

        final int globalBits = calculateBitsNeeded(ctx.globalIdMap.size());
        for (int i = 0; i < localSize; i++) {
            ctx.bitWriter.write(ctx.localPaletteIds.getInt(i), globalBits);
        }

        // +1 because index 0 is reserved for null (no change)
        final int bitsPerBlock = calculateBitsNeeded(localSize + 1);
        if (bitsPerBlock > 0) {
            writeBlockData(ctx, states, bitsPerBlock);
        }
    }

    /**
     * Populates the dense-section local palette from the provided 4096-slot array.
     * Only states with a valid global palette entry are included.
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
     * Ensures the dense-section local palette always contains a stable entry for
     * {@link #airState}. Air must be encodable even when no air block appears
     * explicitly in the delta, because cleared positions decode as air.
     */
    protected void ensureAirInPalette(EncoderContext<S> ctx) {
        if (ctx.fastLocalPaletteIndex.containsKey(airState)) {
            return;
        }

        int airGlobalIdx = ctx.globalIdMap.getInt(airState);
        if (airGlobalIdx == -1) {
            airGlobalIdx = 0;
        }

        ctx.fastLocalPaletteIndex.put(airState, ctx.localPaletteIds.size());
        ctx.localPaletteIds.add(airGlobalIdx);
    }

    /**
     * Writes the dense-section block stream. Each position emits a local palette
     * index shifted by 1 (0 = no change / null).
     */
    @SuppressWarnings("unchecked")
    private void writeBlockData(EncoderContext<S> ctx, Object[] states, int bitsPerBlock) {
        for (int i = 0; i < SECTION_VOLUME; i++) {
            S state = (S) states[i];
            // 0 = no change; 1..N = local palette index (shifted by 1)
            int localIdx = (state == null) ? 0 : ctx.fastLocalPaletteIndex.getInt(state) + 1;
            ctx.bitWriter.write(localIdx, bitsPerBlock);
        }
    }

    /**
     * Writes sparse (position, state) pairs from a sparse-mode section directly.
     */
    @SuppressWarnings("unchecked")
    private void writeSparseEntriesFromSparse(
            final EncoderContext<S> ctx,
            final CisSection<S> section,
            final int globalBits
    ) {
        for (int i = 0; i < section.sparseSize; i++) {
            ctx.bitWriter.write(section.sparseKeys[i] & 0xFFFF, 12);
            final S state = (S) section.sparseValues[i];
            final int globalIdx = ctx.globalIdMap.getInt(state);
            ctx.bitWriter.write(globalIdx != -1 ? globalIdx : 0, globalBits);
        }
    }

    /**
     * Writes sparse (position, state) pairs by scanning a 4096-slot dense array.
     */
    @SuppressWarnings("unchecked")
    private void writeSparseEntriesFromDense(final EncoderContext<S> ctx, final Object[] states, final int globalBits) {
        for (int i = 0; i < SECTION_VOLUME; i++) {
            final S state = (S) states[i];
            if (state == null) {
                continue;
            }
            ctx.bitWriter.write(i, 12);
            final int globalIdx = ctx.globalIdMap.getInt(state);
            ctx.bitWriter.write(globalIdx != -1 ? globalIdx : 0, globalBits);
        }
    }

    /**
     * Writes only the positions whose logical state differs from {@code defaultState}.
     */
    @SuppressWarnings("unchecked")
    private void writeDefaultSparseExceptions(
            final EncoderContext<S> ctx,
            final Object[] states,
            final S defaultState,
            final int globalBits
    ) {
        for (int i = 0; i < SECTION_VOLUME; i++) {
            final S state = logicalState((S) states[i]);
            if (Objects.equals(state, defaultState)) {
                continue;
            }
            ctx.bitWriter.write(i, 12);
            final int globalIdx = ctx.globalIdMap.getInt(state);
            ctx.bitWriter.write(globalIdx != -1 ? globalIdx : 0, globalBits);
        }
    }

    private int sparseEncodingBits(final CisSection<S> section, final int globalBits) {
        return 1 + CisConstants.BLOCK_COUNT_BITS + (sparseEntryCount(section) * (12 + globalBits));
    }

    private static int uniformEncodingBits(final int globalBits) {
        return 1 + CisConstants.BLOCK_COUNT_BITS + globalBits;
    }

    private static int defaultSparseEncodingBits(final int exceptionCount, final int globalBits) {
        return 1 + CisConstants.BLOCK_COUNT_BITS + globalBits + CisConstants.BLOCK_COUNT_BITS
                + (exceptionCount * (12 + globalBits));
    }

    /**
     * Computes the bit cost of dense encoding.
     *
     * <p><b>Side-effect:</b> populates {@code ctx.fastLocalPaletteIndex} and
     * {@code ctx.localPaletteIds} as a by-product. This is intentional — the
     * actual dense encoder reuses those structures immediately after this call.
     * Do not call this method between cost evaluation and dense encoding.</p>
     */
    private int denseEncodingBits(final EncoderContext<S> ctx, final CisSection<S> section, final int globalBits) {
        ctx.fastLocalPaletteIndex.clear();
        ctx.fastLocalPaletteIndex.defaultReturnValue(-1);
        ctx.localPaletteIds.clear();

        buildLocalPalette(ctx, denseStatesForSection(ctx, section));
        ensureAirInPalette(ctx);

        final int localSize = ctx.localPaletteIds.size();
        return 1
                + CisConstants.PALETTE_SIZE_BITS
                + (localSize * globalBits)
                + (SECTION_VOLUME * calculateBitsNeeded(localSize + 1));
    }

    /**
     * Returns the single repeated state if every slot in a dense section is
     * identical, otherwise {@code null}.
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
     * Returns the most-common logical state in the section and the number of
     * exception positions, or {@code null} if the section is already uniform
     * (which is handled by a dedicated cheaper path).
     */
    @SuppressWarnings("unchecked")
    private DefaultSparseCandidate<S> defaultSparseCandidate(final EncoderContext<S> ctx, final CisSection<S> section) {
        final Object[] states = denseStatesForSection(ctx, section);
        ctx.sectionStateCounts.clear();

        S defaultState = airState;
        int defaultCount = 0;
        for (int i = 0; i < SECTION_VOLUME; i++) {
            final S state = logicalState((S) states[i]);
            final int count = ctx.sectionStateCounts.getInt(state) + 1;
            ctx.sectionStateCounts.put(state, count);
            if (count > defaultCount) {
                defaultCount = count;
                defaultState = state;
            }
        }

        final int exceptionCount = SECTION_VOLUME - defaultCount;
        // If every position matches the default the section is uniform; let the
        // uniform path handle it instead.
        if (exceptionCount <= 0) {
            return null;
        }

        return new DefaultSparseCandidate<>(defaultState, exceptionCount);
    }

    /**
     * Returns a dense 4096-slot state array for the section. If the section is
     * already in dense mode the array is returned directly. For sparse-mode
     * sections a scratch buffer is populated and returned — callers must not
     * retain a reference across subsequent calls.
     */
    private Object[] denseStatesForSection(final EncoderContext<S> ctx, final CisSection<S> section) {
        if (section.mode == CisSection.MODE_DENSE) {
            return section.denseBlocks;
        }

        Arrays.fill(ctx.denseScratch, null);
        for (int i = 0; i < section.sparseSize; i++) {
            ctx.denseScratch[section.sparseKeys[i] & 0xFFFF] = section.sparseValues[i];
        }
        return ctx.denseScratch;
    }

    /**
     * Returns the non-null block count for the section regardless of in-memory mode.
     */
    private int sparseEntryCount(final CisSection<S> section) {
        if (section.mode == CisSection.MODE_SPARSE) {
            return section.sparseSize;
        }

        int count = 0;
        for (int i = 0; i < SECTION_VOLUME; i++) {
            if (section.denseBlocks[i] != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Maps a null state to {@link #airState} for logical position comparison.
     */
    private S logicalState(final S state) {
        return state != null ? state : airState;
    }

    /**
     * Returns true when {@code state} is the canonical air state. Such states
     * are omitted from chunk storage and decoded implicitly as air on read.
     */
    private boolean isImplicitAirState(final S state) {
        return Objects.equals(state, airState);
    }

    /**
     * Returns the minimum bit width needed to encode values in {@code [0, maxValue)}.
     */
    protected static int calculateBitsNeeded(int maxValue) {
        return AbstractCisDecoder.calculateBitsNeeded(maxValue);
    }

    /**
     * Pair of the CisChunk representation and the ordered global palette state list.
     */
    private record EncodedChunkInput<S>(CisChunk<S> chunk, List<S> usedStates) {}

    /**
     * Reusable scratch state for one encoder instance.
     *
     * <p>All mutable members are kept here so the encoder itself is stateless
     * apart from the context returned by {@link #getContext}. Backing buffers
     * and maps are reused across encodes; only logical content is cleared on
     * {@link #reset}.</p>
     */
    public static class EncoderContext<S> {
        /**
         * Main byte sink for the full encoded chunk payload.
         */
        public final ByteArrayOutputStream mainBuffer = new ByteArrayOutputStream(16384);

        /**
         * Scratch writer reused for section payloads and palette property data.
         */
        public final BitWriter bitWriter = new BitWriter(8192);

        /**
         * Global palette reverse lookup: block state → encoded palette index.
         */
        public final Reference2IntMap<S> globalIdMap = new Reference2IntOpenHashMap<>();

        /**
         * Dense-section local palette encoded as global palette ids.
         */
        private final IntArrayList localPaletteIds = new IntArrayList(64);

        /**
         * Dense-section reverse lookup: block state identity → local palette index.
         */
        public final Reference2IntMap<S> fastLocalPaletteIndex = new Reference2IntOpenHashMap<>();

        /**
         * Reusable dense scratch array for expanding sparse sections during cost evaluation.
         */
        private final Object[] denseScratch = new Object[SECTION_VOLUME];

        /**
         * Reusable logical-state frequency table for default-sparse candidate selection.
         */
        private final Reference2IntMap<S> sectionStateCounts = new Reference2IntOpenHashMap<>();

        /**
         * Reverse-lookup used when collecting the global palette from a delta.
         */
        private final Reference2IntMap<S> seenStates = new Reference2IntOpenHashMap<>();

        /**
         * Ordered list of states collected while building the global palette.
         */
        private final List<S> seenStateList = new ArrayList<>(16);

        {
            // defaultReturnValue survives clear(); set once here rather than per-encode.
            seenStates.defaultReturnValue(-1);
        }

        /**
         * Clears mutable per-encode state while keeping backing buffers allocated for reuse.
         */
        public void reset() {
            mainBuffer.reset();
            globalIdMap.clear();
        }
    }

    /**
     * Wraps an {@link IOException} thrown inside a lambda entity loop so it can
     * cross the non-throwing functional interface boundary.
     */
    private static final class EntityEncodingException extends RuntimeException {
        EntityEncodingException(final IOException cause) {
            super(cause);
        }
    }

    /**
     * Section encodings considered by the adaptive cost selector.
     */
    private enum SectionEncoding {
        UNIFORM,
        DEFAULT_SPARSE,
        SPARSE,
        DENSE
    }

    /**
     * The most-common logical state for a section and the number of positions
     * that differ from it, used by the default-sparse encoder.
     */
    private record DefaultSparseCandidate<S>(S defaultState, int exceptionCount) {}
}
