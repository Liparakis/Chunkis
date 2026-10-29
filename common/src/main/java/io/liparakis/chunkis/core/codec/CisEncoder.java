package io.liparakis.chunkis.core.codec;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.core.SectionStatesSource;
import io.liparakis.chunkis.core.bits.BitWriter;
import io.liparakis.chunkis.core.bits.RangeEncoder;
import io.liparakis.chunkis.core.mapping.CisAdapter;
import io.liparakis.chunkis.core.model.CisChunk;
import io.liparakis.chunkis.core.model.CisConstants;
import io.liparakis.chunkis.core.model.CisSection;
import io.liparakis.chunkis.spi.NbtAdapter;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
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
 * Encodes chunk deltas into the CIS (Chunk Incremental Storage) wire format.
 *
 * <p>Not thread-safe: each instance owns reusable scratch buffers, so callers keep
 * one encoder per thread.</p>
 *
 * @param <S> The BlockState type
 * @param <N> The NBT type
 */
public final class CisEncoder<S, N> {

    /**
     * Total number of blocks in a chunk section (16x16x16).
     */
    private static final int SECTION_VOLUME = 4096;

    /**
     * Adapter used to serialize entity, block-entity, and chunk-metadata payloads.
     */
    private final NbtAdapter<N> nbtAdapter;

    /**
     * Canonical empty state. Air is always assigned global palette index 0 so
     * that absent (null) blocks in delta snapshots decode correctly.
     */
    private final S airState;

    /**
     * Block-state mapping used to write global palette entries.
     */
    private final CisAdapter<S> cisAdapter;

    /**
     * Reusable encoding buffers. Each encoder instance is confined to one thread
     * (CisStorage keeps one per thread), so a plain field replaces a thread-local.
     */
    private final EncoderContext<S> context = new EncoderContext<>();

    /**
     * Constructs a new CisEncoder.
     *
     * @param cisAdapter block-state mapping used for the global palette
     * @param nbtAdapter adapter for block-entity, entity and metadata payloads
     * @param airState   canonical air state, always global palette index 0
     */
    public CisEncoder(CisAdapter<S> cisAdapter, NbtAdapter<N> nbtAdapter, S airState) {
        this.cisAdapter = cisAdapter;
        this.nbtAdapter = nbtAdapter;
        this.airState = airState;
    }

    /**
     * Encodes a live delta.
     *
     * @param delta delta to encode
     * @return encoded CIS payload
     * @throws IOException if a payload cannot be written
     */
    public byte[] encode(ChunkDelta<S, N> delta) throws IOException {
        return encodeInternal(delta);
    }

    /**
     * Encodes a read-only delta view (e.g. an async-save snapshot).
     *
     * @param delta delta view to encode
     * @return encoded CIS payload
     * @throws IOException if a payload cannot be written
     */
    public byte[] encode(final ChunkDeltaView<S, N> delta) throws IOException {
        return encodeInternal(delta);
    }

    /**
     * Performs write header.
     */
    private static void writeHeader(DataOutputStream dos) throws IOException {
        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);
    }

    /**
     * Performs uniform encoding bits.
     */
    private static int uniformEncodingBits(final int globalBits) {
        return 1 + CisConstants.BLOCK_COUNT_BITS + globalBits;
    }

    /**
     * Performs default sparse encoding bits.
     */
    private static int defaultSparseEncodingBits(final int exceptionCount, final int globalBits) {
        return 1 + CisConstants.BLOCK_COUNT_BITS + globalBits + CisConstants.BLOCK_COUNT_BITS
                + (exceptionCount * (12 + globalBits));
    }

    /**
     * Returns the minimum bit width needed to encode values in {@code [0, maxValue)}.
     */
    private static int calculateBitsNeeded(int maxValue) {
        return CisDecoder.calculateBitsNeeded(maxValue);
    }

    /**
     * Writes one global palette entry's block identity and properties to {@code dos}.
     * The bit-packed property data goes to {@code ctx.bitWriter}; it is flushed and
     * length-prefixed by {@link #writeGlobalPalette} after all entries.
     */
    private void writeGlobalPaletteEntry(DataOutputStream dos, EncoderContext<S> ctx, S state) throws IOException {
        dos.writeShort(cisAdapter.getBlockId(state));
        cisAdapter.writeStateProperties(ctx.bitWriter, state);
    }

    /**
     * Encodes a chunk whose blocks are exactly those of an existing payload's block part ({@link CisBlockPart}):
     * the block part is copied as is and only block entities, entities and metadata are encoded.
     *
     * @param blockPart the block part of a current-version payload holding exactly these blocks
     * @param delta     the chunk; its blocks are not read
     * @return the payload
     * @throws IOException when encoding fails
     */
    public byte[] encodeWithBlockPart(final byte[] blockPart, final ChunkDeltaView<S, N> delta) throws IOException {
        final EncoderContext<S> ctx = context;
        ctx.reset();
        final DataOutputStream dos = new DataOutputStream(ctx.mainBuffer);
        dos.write(blockPart);
        writeBlockEntities(dos, delta);
        writeEntities(dos, delta);
        writeChunkMetadata(dos, delta);
        return ctx.mainBuffer.toByteArray();
    }

    /**
     * Orchestrates the complete encoding process for one chunk delta.
     */
    private byte[] encodeInternal(ChunkDeltaView<S, N> delta) throws IOException {
        EncoderContext<S> ctx = context;
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
     * Writes the global block palette.
     *
     * <p>Air is always index 0. All bit-packed property data is accumulated in
     * {@code ctx.bitWriter}, then length-prefixed and flushed once to avoid
     * interleaving random-access seeking with the sequential stream.</p>
     */
    private void writeGlobalPalette(
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
     * Performs write sections.
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
        // Dense sections' raw then context-coded block indices, in section order
        // (CisConstants#CODED_DENSE_MIN_VERSION).
        dos.writeInt(ctx.denseRaw.size());
        ctx.denseRaw.writeTo(dos);
        // Each coded section in a stream of its own ([int length][bytes], in section order) so they decode in
        // parallel; a negative length marks that layout (CisDecoder still reads a single shared stream). Without
        // coded sections both layouts are an empty stream, written as length 0 like before.
        final int codedSize = ctx.denseCodedSections.size();
        dos.writeInt(codedSize == 0 ? 0 : -codedSize - 1);
        ctx.denseCodedSections.writeTo(dos);
    }

    /**
     * Performs write block entities.
     */
    private void writeBlockEntities(DataOutputStream dos, ChunkDeltaView<S, N> delta) throws IOException {
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

    /**
     * Performs write entities.
     */
    private void writeEntities(DataOutputStream dos, ChunkDeltaView<S, N> delta) throws IOException {
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
    private void writeChunkMetadata(DataOutputStream dos, ChunkDeltaView<S, N> delta) throws IOException {
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
     * <p>Air is still added as palette index 0, but explicit air edits are kept
     * in the chunk payload. That matters for base-backed sparse deltas where
     * "missing" means "leave the persisted base block alone", not "decode as
     * air".</p>
     */
    @SuppressWarnings("unchecked")
    private EncodedChunkInput<S> fromDelta(ChunkDeltaView<S, N> delta) {
        final CisChunk<S> chunk = new CisChunk<>();
        final EncoderContext<S> ctx = context;
        final List<S> usedStates = ctx.seenStateList;
        final Reference2IntMap<S> seenStates = ctx.seenStates;
        usedStates.clear();
        seenStates.clear();
        // Air is always palette index 0.
        seenStates.put(airState, 0);
        usedStates.add(airState);

        if (delta instanceof SectionStatesSource<?> source) {
            // Whole sections: same sections, same states and same first-seen palette order as the per-block walk.
            source.forEachSectionStates((sectionY, states) -> {
                chunk.putDenseSection(sectionY, states);
                Object previous = null;
                for (final Object state : states) {
                    if (state == null || state == previous) {
                        continue;
                    }
                    previous = state;
                    if (seenStates.getInt(state) == -1) {
                        seenStates.put((S) state, usedStates.size());
                        usedStates.add((S) state);
                    }
                }
            });
        } else {
            delta.forEachBlock((x, y, z, state) -> {
                if (state == null) {
                    return;
                }
                chunk.addUniqueBlock(x, y, z, state);
                if (seenStates.getInt(state) == -1) {
                    seenStates.put(state, usedStates.size());
                    usedStates.add(state);
                }
            });
        }

        return new EncodedChunkInput<>(chunk, usedStates);
    }

    /**
     * Selects and writes the most compact encoding for a single 16x16x16 section.
     *
     * <p>Four encodings are evaluated; the one with the lowest bit cost wins:
     * <ul>
     *   <li><b>UNIFORM</b> - all 4096 positions share one state.</li>
     *   <li><b>DEFAULT_SPARSE</b> - one dominant state is implied; only differing
     *       positions are emitted as (position, state) pairs.</li>
     *   <li><b>SPARSE</b> - a flat list of (position, state) pairs for every
     *       non-null block; efficient for sections with few changes.</li>
     *   <li><b>DENSE</b> - a local palette plus a full 4096-slot bit-array;
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
        final Object[] sectionStates = denseStatesForSection(ctx, section);
        // One pass gives every candidate its numbers and leaves the dense local palette and indices ready.
        final SectionAnalysis<S> analysis = analyseSection(ctx, sectionStates);
        final boolean hasExplicitAir = analysis.hasExplicitAir();

        final S uniformState = analysis.uniformState();
        final DefaultSparseCandidate<S> defaultSparse = analysis.defaultSparse();
        final int sparseBits = 1 + CisConstants.BLOCK_COUNT_BITS + (analysis.nonNullCount() * (12 + globalBits));
        final int uniformBits = uniformState != null ? uniformEncodingBits(globalBits) : Integer.MAX_VALUE;
        final int defaultSparseBits = defaultSparse != null
                ? defaultSparseEncodingBits(defaultSparse.exceptionCount(), globalBits)
                : Integer.MAX_VALUE;
        final int denseBits = 1
                + CisConstants.PALETTE_SIZE_BITS
                + (ctx.localPaletteIds.size() * globalBits)
                + (SECTION_VOLUME * calculateBitsNeeded(ctx.localPaletteIds.size() + 1));

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
            case DEFAULT_SPARSE -> encodeDefaultSparseSection(ctx, sectionStates, defaultSparse, globalBits);
            case SPARSE -> encodeSparseSectionAdaptive(ctx, section, globalBits);
            case DENSE -> encodeAnalysedDenseSection(ctx, sectionStates);
        }
    }

    /**
     * What every section-encoding candidate needs to know, from one pass over the section.
     *
     * @param hasExplicitAir whether any slot holds an explicit air state
     * @param uniformState   the one state of a uniform section, or {@code null}
     * @param defaultSparse  the default-sparse candidate, or {@code null}
     * @param nonNullCount   slots holding a state
     * @param <S>            block state type
     */
    private record SectionAnalysis<S>(boolean hasExplicitAir, S uniformState, DefaultSparseCandidate<S> defaultSparse,
            int nonNullCount) {
    }

    /**
     * Walks a section once: builds the dense local palette (first-occurrence order, air added last when absent),
     * the shifted local indices (0 = no change) and the per-state counts, and derives every candidate's numbers
     * exactly as the separate per-candidate scans did, ties included.
     *
     * @param ctx    encoder context; its local palette and dense indices are left filled for the dense writer
     * @param states 4096 states of the section, {@code null} where unchanged
     * @return the section's analysis
     */
    @SuppressWarnings("unchecked")
    private SectionAnalysis<S> analyseSection(final EncoderContext<S> ctx, final Object[] states) {
        ctx.fastLocalPaletteIndex.clear();
        ctx.fastLocalPaletteIndex.defaultReturnValue(-1);
        ctx.localPaletteIds.clear();
        ctx.localStates.clear();
        final int[] indices = ctx.denseIndices;
        final int[] counts = ctx.localCounts;
        int nullCount = 0;
        int previousLocal = -1;
        Object previous = null;
        // Default-sparse running maximum over logical states (null counts as air), as the per-candidate scan did.
        int airLocal = -1;
        int logicalAirCount = 0;
        int defaultLocal = -2;
        int defaultCount = 0;
        for (int i = 0; i < SECTION_VOLUME; i++) {
            final Object state = states[i];
            if (state == null) {
                indices[i] = 0;
                nullCount++;
                final int count = ++logicalAirCount + (airLocal >= 0 ? counts[airLocal] : 0);
                if (count > defaultCount) {
                    defaultCount = count;
                    defaultLocal = -1;
                }
                continue;
            }
            int local;
            if (state == previous) {
                local = previousLocal;
            } else {
                local = ctx.fastLocalPaletteIndex.getInt(state);
                if (local == -1) {
                    local = ctx.localPaletteIds.size();
                    ctx.fastLocalPaletteIndex.put((S) state, local);
                    ctx.localPaletteIds.add(globalIndexOf(ctx, (S) state));
                    ctx.localStates.add((S) state);
                    counts[local] = 0;
                    if (Objects.equals(state, airState)) {
                        airLocal = local;
                    }
                }
                previous = state;
                previousLocal = local;
            }
            indices[i] = local + 1;
            final int own = ++counts[local];
            final int count = local == airLocal ? own + logicalAirCount : own;
            if (count > defaultCount) {
                defaultCount = count;
                defaultLocal = local == airLocal ? -1 : local;
            }
        }
        ensureAirInPalette(ctx);

        final boolean hasExplicitAir = airLocal >= 0 && counts[airLocal] > 0;
        final int distinct = ctx.localStates.size();
        S uniform = null;
        if (nullCount == 0 && distinct == 1) {
            final S only = ctx.localStates.get(0);
            if (!(hasExplicitAir && Objects.equals(only, airState))) {
                uniform = only;
            }
        }
        DefaultSparseCandidate<S> defaultSparse = null;
        if (nullCount == 0 && SECTION_VOLUME - defaultCount > 0) {
            final S defaultState = defaultLocal < 0 ? airState : ctx.localStates.get(defaultLocal);
            if (!(hasExplicitAir && Objects.equals(defaultState, airState))) {
                defaultSparse = new DefaultSparseCandidate<>(defaultState, SECTION_VOLUME - defaultCount);
            }
        }
        return new SectionAnalysis<>(hasExplicitAir, uniform, defaultSparse, SECTION_VOLUME - nullCount);
    }

    /**
     * Writes a dense section from the palette and indices {@link #analyseSection} left in the context.
     *
     * @param ctx    encoder context
     * @param states the section's states, for the wide (bit-packed) form
     */
    private void encodeAnalysedDenseSection(final EncoderContext<S> ctx, final Object[] states) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_DENSE, 1);
        final int localSize = ctx.localPaletteIds.size();
        ctx.bitWriter.write(localSize, CisConstants.PALETTE_SIZE_BITS);
        final int globalBits = calculateBitsNeeded(ctx.globalIdMap.size());
        for (int i = 0; i < localSize; i++) {
            ctx.bitWriter.write(ctx.localPaletteIds.getInt(i), globalBits);
        }
        final int bitsPerBlock = calculateBitsNeeded(localSize + 1);
        if (bitsPerBlock > CisConstants.MAX_CODED_DENSE_BITS) {
            final int[] indices = ctx.denseIndices;
            for (int i = 0; i < SECTION_VOLUME; i++) {
                ctx.bitWriter.write(indices[i], bitsPerBlock);
            }
        } else if (bitsPerBlock > 0) {
            writeAnalysedCodedBlocks(ctx, bitsPerBlock, localSize + 1);
        }
    }

    /**
     * Writes the dense indices {@link #analyseSection} left in the context, context-coded or raw as
     * the threshold decides.
     *
     * @param ctx          encoder context
     * @param bitsPerBlock bits per block index
     * @param alphabet     number of distinct index values
     */
    private void writeAnalysedCodedBlocks(final EncoderContext<S> ctx, final int bitsPerBlock, final int alphabet) {
        final int[] indices = ctx.denseIndices;
        if (DenseIndexCoder.countPredicted(indices) >= CisConstants.CODED_DENSE_MIN_PREDICTED) {
            ctx.bitWriter.write(1, 1);
            ctx.denseCoder.reset();
            ctx.indexCoder.encode(ctx.denseCoder, indices, bitsPerBlock, alphabet);
            ctx.denseCoder.finish();
            final int length = ctx.denseCoder.size();
            ctx.denseCodedSections.write(length >>> 24);
            ctx.denseCodedSections.write(length >>> 16);
            ctx.denseCodedSections.write(length >>> 8);
            ctx.denseCodedSections.write(length);
            ctx.denseCodedSections.write(ctx.denseCoder.buffer(), 0, length);
        } else {
            ctx.bitWriter.write(0, 1);
            for (int i = 0; i < SECTION_VOLUME; i++) {
                ctx.denseRaw.write(indices[i]);
            }
        }
    }

    /**
     * Emits a section that consists entirely of one repeated state.
     * Uses the sparse-mode wire format with the uniform sentinel as the block count.
     */
    private void encodeUniformSection(final EncoderContext<S> ctx, final S state, final int globalBits) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
        ctx.bitWriter.write(CisConstants.UNIFORM_SECTION_SENTINEL, CisConstants.BLOCK_COUNT_BITS);
        ctx.bitWriter.write(globalIndexOf(ctx, state), globalBits);
    }

    /**
     * Emits a section using one implied dominant state plus explicit exception
     * entries for every position that differs. Wire format: sparse header, then
     * the default global index, then the exception count, then (pos, state) pairs.
     */
    private void encodeDefaultSparseSection(
            final EncoderContext<S> ctx,
            final Object[] states,
            final DefaultSparseCandidate<S> candidate,
            final int globalBits
    ) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
        ctx.bitWriter.write(CisConstants.DEFAULT_SPARSE_SECTION_SENTINEL, CisConstants.BLOCK_COUNT_BITS);

        ctx.bitWriter.write(globalIndexOf(ctx, candidate.defaultState()), globalBits);
        ctx.bitWriter.write(candidate.exceptionCount(), CisConstants.BLOCK_COUNT_BITS);
        writeDefaultSparseExceptions(ctx, states, candidate.defaultState(), globalBits);
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
     * Returns the global palette index of {@code state}.
     *
     * <p>Every state in the delta is registered in the global palette before sections
     * are written, so a miss is a broken invariant. Writing air instead would silently
     * delete the block, so it fails the save.</p>
     */
    private int globalIndexOf(final EncoderContext<S> ctx, final S state) {
        final int globalIdx = ctx.globalIdMap.getInt(state);
        if (globalIdx == -1) {
            throw new IllegalStateException("Block state missing from global palette: " + state);
        }
        return globalIdx;
    }

    /**
     * Ensures the dense-section local palette always contains a stable entry for
     * {@link #airState}. Air must be encodable even when no air block appears
     * explicitly in the delta, because cleared positions decode as air.
     */
    private void ensureAirInPalette(EncoderContext<S> ctx) {
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
            ctx.bitWriter.write(globalIndexOf(ctx, state), globalBits);
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
            ctx.bitWriter.write(globalIndexOf(ctx, state), globalBits);
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
            ctx.bitWriter.write(globalIndexOf(ctx, state), globalBits);
        }
    }

    /**
     * Returns a dense 4096-slot state array for the section. If the section is
     * already in dense mode the array is returned directly. For sparse-mode
     * sections a scratch buffer is populated and returned - callers must not
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
     * Section encodings considered by the adaptive cost selector.
     */
    private enum SectionEncoding {
        /**
         * Stores uniform.
         */
        UNIFORM,
        /**
         * Stores default sparse.
         */
        DEFAULT_SPARSE,
        /**
         * Stores sparse.
         */
        SPARSE,
        /**
         * Stores chunk.
         */
        DENSE
    }

    /**
     * Pair of the CisChunk representation and the ordered global palette state list.
     */
    private record EncodedChunkInput<S>(CisChunk<S> chunk, List<S> usedStates) {

    }

    /**
     * Reusable scratch state for one encoder instance.
     *
     * <p>All mutable members are kept here so the encoder itself is stateless
     * apart from its {@link #context}. Backing buffers
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
         * Raw dense-section block indices, one byte each, written after the section bit stream.
         */
        private final ByteArrayOutputStream denseRaw = new ByteArrayOutputStream(16384);

        /**
         * Range coder of the context-coded dense-section block indices, written after the raw ones.
         */
        private final RangeEncoder denseCoder = new RangeEncoder();

        /**
         * Context model coding each dense section's indices into {@link #denseCoder}.
         */
        private final DenseIndexCoder indexCoder = new DenseIndexCoder();

        /**
         * One section's block indices, built before coding.
         */
        private final int[] denseIndices = new int[SECTION_VOLUME];

        /**
         * Coded sections of the chunk so far, each as [int length][range-coded bytes].
         */
        private final ByteArrayOutputStream denseCodedSections = new ByteArrayOutputStream(4096);

        /**
         * Global palette reverse lookup: block state -> encoded palette index.
         */
        public final Reference2IntMap<S> globalIdMap = new Reference2IntOpenHashMap<>();
        /**
         * Dense-section reverse lookup: block state identity -> local palette index.
         */
        public final Reference2IntMap<S> fastLocalPaletteIndex = new Reference2IntOpenHashMap<>();
        /**
         * Dense-section local palette encoded as global palette ids.
         */
        private final IntArrayList localPaletteIds = new IntArrayList(64);

        /**
         * Distinct states of the section being analysed, in local palette order (air added later not included).
         */
        private final List<S> localStates = new ArrayList<>(64);

        /**
         * Occurrences per local palette index of the section being analysed.
         */
        private final int[] localCounts = new int[SECTION_VOLUME + 1];
        /**
         * Reusable dense scratch array for expanding sparse sections during cost evaluation.
         */
        private final Object[] denseScratch = new Object[SECTION_VOLUME];

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
            denseRaw.reset();
            denseCoder.reset();
            denseCodedSections.reset();
            globalIdMap.clear();
        }
    }

    /**
     * Wraps an {@link IOException} thrown inside a lambda entity loop so it can
     * cross the non-throwing functional interface boundary.
     */
    private static final class EntityEncodingException extends RuntimeException {

        /**
         * Performs entity encoding exception.
         */
        EntityEncodingException(final IOException cause) {
            super(cause);
        }
    }

    /**
     * The most-common logical state for a section and the number of positions
     * that differ from it, used by the default-sparse encoder.
     */
    private record DefaultSparseCandidate<S>(S defaultState, int exceptionCount) {

    }
}
