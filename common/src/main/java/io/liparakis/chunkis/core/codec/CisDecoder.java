package io.liparakis.chunkis.core.codec;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.core.bits.BitReader;
import io.liparakis.chunkis.core.bits.RangeDecoder;
import io.liparakis.chunkis.core.mapping.CisAdapter;
import io.liparakis.chunkis.core.model.CisConstants;
import io.liparakis.chunkis.spi.NbtAdapter;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes CIS (Chunk Incremental Storage) payloads into chunk deltas.
 *
 * <p>Not thread-safe: each instance reuses decode buffers, so callers keep one
 * decoder per thread.</p>
 *
 * @param <S> The BlockState type
 * @param <N> The NBT type
 */
public final class CisDecoder<S, N> {

    /**
     * Size of the CIS file header in bytes (magic number + version).
     */
    private static final int HEADER_SIZE = 8;

    /**
     * Number of bits in a nibble (half-byte).
     */
    private static final int BITS_PER_NIBBLE = 4;

    /**
     * Total number of blocks in a chunk section (16x16x16).
     */
    private static final int SECTION_VOLUME = 4096;

    /**
     * Maximum reasonable palette size to prevent memory exhaustion attacks.
     */
    private static final int MAX_REASONABLE_PALETTE_SIZE = 10_000;

    /**
     * Maximum entity count accepted per chunk. Only guards against corrupt counts: the
     * encoder writes any number, so this must stay well above real farms.
     */
    private static final int MAX_ENTITY_COUNT = 1_000_000;

    /**
     * Maximum block-entity count accepted per chunk; above one per block position of
     * the tallest supported column, so only corrupt counts exceed it.
     */
    private static final int MAX_BLOCK_ENTITY_COUNT = 16 * 16 * 4096;

    /**
     * Reusable reader over the packed block-state property payload in the global palette section.
     */
    private final BitReader propertyReader;
    /**
     * Reusable reader over the encoded chunk-section payload during the main decode pass.
     */
    private final BitReader sectionReader;

    /**
     * Reusable buffer for local palette indices in dense sections.
     */
    private final int[] localPaletteBuffer;
    /**
     * Reusable buffer for dense palette block indices.
     */
    private final int[] denseIndicesBuffer;
    /**
     * Adapter used to decode block entities, entities, and chunk metadata payloads.
     */
    private final NbtAdapter<N> nbtAdapter;
    /**
     * Fallback state returned when the payload refers to an invalid palette entry.
     */
    private final S airState;
    /**
     * The format version of the data being decoded. Set per {@link #decodeInternal} call.
     */
    private int decodedVersion;
    /**
     * Range decoder over the context-coded dense block indices ({@link CisConstants#CODED_DENSE_MIN_VERSION}).
     * Pointed at a chunk's stream per {@link #decodeSections} call.
     */
    private final RangeDecoder denseDecoder = new RangeDecoder();
    /**
     * Payload holding the raw dense block indices. Set per {@link #decodeSections} call.
     */
    private byte[] denseRaw;
    /**
     * Next unread offset of the raw dense block indices in {@link #denseRaw}.
     */
    private int denseRawCursor;
    /**
     * End offset, exclusive, of the raw dense block indices in {@link #denseRaw}.
     */
    private int denseRawEnd;
    /**
     * Context model decoding each dense section's indices from {@link #denseDecoder}.
     */
    private final DenseIndexCoder indexCoder = new DenseIndexCoder();

    /**
     * Whether this payload stores each coded section in a stream of its own.
     */
    private boolean codedPerSection;

    /**
     * Read position in the coded block streams (per-section layout).
     */
    private int codedCursor;

    /**
     * End of the coded block streams.
     */
    private int codedEnd;

    /**
     * Coded sections queued during the section pass (per-section layout).
     */
    private final java.util.List<PendingCodedSection> pendingCoded = new java.util.ArrayList<>();

    /**
     * Fewest coded sections in a chunk worth decoding in parallel; each takes about 35 microseconds.
     */
    private static final int PARALLEL_MIN_CODED_SECTIONS = 4;

    /**
     * Per-thread coder state for decoding coded sections in parallel.
     */
    private static final ThreadLocal<SectionDecoder> SECTION_DECODER = ThreadLocal.withInitial(SectionDecoder::new);

    /**
     * A coded section waiting to be decoded.
     *
     * @param sectionY     section Y
     * @param offset       start of its range-coded bytes
     * @param length       their length
     * @param bitsPerBlock bits per block index
     * @param palette      local palette as global ids
     */
    private record PendingCodedSection(int sectionY, int offset, int length, int bitsPerBlock, int[] palette) {
    }

    /**
     * One thread's coder state.
     */
    private static final class SectionDecoder {

        /**
         * Range decoder.
         */
        private final RangeDecoder decoder = new RangeDecoder();

        /**
         * Context model.
         */
        private final DenseIndexCoder coder = new DenseIndexCoder();
    }
    /**
     * The global palette mapping indices to BlockStates. Set per {@link #decodeInternal} call.
     */
    private List<S> globalPalette;

    /**
     * Block-state mapping used to read global palette entries.
     */
    private final CisAdapter<S> cisAdapter;

    /**
     * Reused buffer for the global palette's block ids.
     */
    private int[] blockIdsBuffer = new int[0];

    /**
     * Constructs a new CisDecoder.
     *
     * @param cisAdapter block-state mapping used for the global palette
     * @param nbtAdapter adapter for block-entity, entity and metadata payloads
     * @param airState   canonical air state
     */
    public CisDecoder(CisAdapter<S> cisAdapter, NbtAdapter<N> nbtAdapter, S airState) {
        this.cisAdapter = cisAdapter;
        this.nbtAdapter = nbtAdapter;
        this.airState = airState;
        this.propertyReader = new BitReader(new byte[0]);
        this.sectionReader = new BitReader(new byte[0]);
        this.localPaletteBuffer = new int[SECTION_VOLUME];
        this.denseIndicesBuffer = new int[SECTION_VOLUME];
    }

    /**
     * Rejects dense-section palette sizes that are negative or larger than one section.
     */
    private static void validateLocalPaletteSize(int localSize) throws IOException {
        if (localSize < 0 || localSize > SECTION_VOLUME) {
            throw new IOException("Invalid local palette size: " + localSize);
        }
    }

    /**
     * Returns the minimum bit width needed to encode values in {@code [0, maxValue)}.
     */
    static int calculateBitsNeeded(int maxValue) {
        if (maxValue <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(maxValue - 1);
    }

    /**
     * Rejects global palette sizes that are negative or implausibly large for
     * one chunk payload.
     */
    private static void validateGlobalPaletteSize(int globalPaletteSize) throws IOException {
        if (globalPaletteSize < 0 || globalPaletteSize > MAX_REASONABLE_PALETTE_SIZE) {
            throw new IOException("Invalid palette size: " + globalPaletteSize);
        }
    }

    /**
     * Ensures that the requested byte span is fully present before the decoder
     * attempts to read it.
     */
    private static void ensureAvailable(byte[] data, int offset, int length, String section) throws IOException {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IOException("Truncated data: cannot read " + section);
        }
    }

    /**
     * Reads a big-endian 32-bit integer from the raw payload.
     */
    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /**
     * Reads a big-endian 16-bit integer from the raw payload.
     */
    private static short readShortBE(byte[] b, int off) {
        return (short) (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }

    /**
     * Returns a cheap per-section reserve for decoded block instructions.
     *
     * <p>This intentionally over-allocates compared to exact sizing so decode does not have to
     * parse the section payload twice.</p>
     */
    static int coarseBlockCapacityReserve(final int sectionCount) {
        if (sectionCount <= 0) {
            return 0;
        }
        return sectionCount * SECTION_VOLUME;
    }

    /**
     * Decodes a payload's block entities, entities and metadata only, skipping its block part
     * ({@code CisBlockPart}); the caller supplies the blocks.
     *
     * @param data            payload
     * @param blockPartLength length of its block part
     * @return a delta without blocks, marked saved
     * @throws IOException when the payload is invalid
     */
    public ChunkDelta<S, N> decodeWithoutBlocks(final byte[] data, final int blockPartLength) throws IOException {
        if (data.length < HEADER_SIZE) {
            throw new IOException("CIS data too short: " + data.length + " bytes (minimum: " + HEADER_SIZE + ")");
        }
        validateHeader(data);
        final ChunkDelta<S, N> delta = new ChunkDelta<>();
        delta.setSourceVersion(decodedVersion);
        decodeBlockEntitiesAndEntities(data, blockPartLength, delta);
        delta.markSaved();
        return delta;
    }

    /**
     * Decodes one CIS payload.
     *
     * @param data raw (decompressed) CIS bytes
     * @return the decoded delta, marked saved
     * @throws IOException if the payload is corrupt or truncated
     */
    public ChunkDelta<S, N> decode(byte[] data) throws IOException {
        return decodeInternal(data);
    }

    /**
     * Decodes the global block palette from the CIS data.
     *
     * @return the offset immediately after the palette payload.
     */
    private int decodeGlobalPalette(byte[] data, int offset, Palette<S> palette) throws IOException {
        ensureAvailable(data, offset, 4, "global palette size");

        int globalPaletteSize = readIntBE(data, offset);
        offset += 4;

        validateGlobalPaletteSize(globalPaletteSize);

        int requiredBytes = globalPaletteSize * 2 + 4;
        ensureAvailable(data, offset, requiredBytes, "global palette");

        if (blockIdsBuffer.length < globalPaletteSize) {
            blockIdsBuffer = new int[globalPaletteSize];
        }
        for (int i = 0; i < globalPaletteSize; i++) {
            blockIdsBuffer[i] = readShortBE(data, offset) & 0xFFFF;
            offset += 2;
        }

        int propLength = readIntBE(data, offset);
        offset += 4;

        ensureAvailable(data, offset, propLength, "properties");

        propertyReader.setData(data, offset, propLength);
        offset += propLength;

        beginGlobalPalette(globalPaletteSize);
        for (int i = 0; i < globalPaletteSize; i++) {
            addGlobalPaletteState(palette, cisAdapter.readStateProperties(propertyReader, blockIdsBuffer[i]));
        }

        return offset;
    }

    /**
     * Orchestrates the complete decoding process for one serialized chunk delta.
     */
    private ChunkDelta<S, N> decodeInternal(byte[] data) throws IOException {
        if (data.length < HEADER_SIZE) {
            throw new IOException("CIS data too short: " + data.length + " bytes (minimum: " + HEADER_SIZE + ")");
        }

        validateHeader(data);
        int offset = HEADER_SIZE;
        ChunkDelta<S, N> delta = new ChunkDelta<>();
        delta.setSourceVersion(decodedVersion);
        Palette<S> palette = delta.getBlockPalette();

        offset = decodeGlobalPalette(data, offset, palette);
        offset = decodeSections(data, offset, delta);
        decodeBlockEntitiesAndEntities(data, offset, delta);

        delta.markSaved();
        return delta;
    }

    /**
     * Validates the fixed-width CIS header and returns the offset of the first
     * payload section.
     */
    private void validateHeader(byte[] data) throws IOException {
        int magic = readIntBE(data, 0);
        if (magic != CisConstants.MAGIC) {
            throw new IOException(String.format("Invalid CIS magic number: 0x%08X (expected: 0x%08X)",
                    magic,
                    CisConstants.MAGIC));
        }

        this.decodedVersion = readIntBE(data, 4);
        if (decodedVersion < 7 || decodedVersion > CisConstants.VERSION) {
            throw new IOException(String.format("Unsupported CIS version: %d (supported range: 7-%d)",
                    decodedVersion,
                    CisConstants.VERSION));
        }
    }

    /**
     * Decodes all chunk sections from the data stream.
     *
     * @return the offset immediately after the section payload.
     */
    private int decodeSections(byte[] data, int offset, ChunkDelta<S, N> delta) throws IOException {
        ensureAvailable(data, offset, 6, "section header");

        int sectionCount = readShortBE(data, offset) & 0xFFFF;
        offset += 2;

        int sectionDataLength = readIntBE(data, offset);
        offset += 4;

        ensureAvailable(data, offset, sectionDataLength, "sections");
        int end = offset + sectionDataLength;
        denseRaw = data;
        denseRawCursor = end;
        denseRawEnd = end;
        if (decodedVersion >= CisConstants.CODED_DENSE_MIN_VERSION) {
            final int rawLength = readStreamLength(data, end, "raw dense block");
            end += 4;
            denseRawCursor = end;
            denseRawEnd = end + rawLength;
            end = denseRawEnd;
            ensureAvailable(data, end, 4, "coded dense block stream length");
            final int codedMarker = readIntBE(data, end);
            // Negative: one stream per coded section ([int length][bytes]); otherwise a single shared stream.
            codedPerSection = codedMarker < 0;
            final int codedLength = codedPerSection ? -(codedMarker + 1) : codedMarker;
            if (codedLength < 0) {
                throw new IOException("Invalid coded dense block stream length: " + codedMarker);
            }
            end += 4;
            ensureAvailable(data, end, codedLength, "coded dense block stream");
            denseDecoder.setData(data, end, codedLength);
            codedCursor = end;
            codedEnd = end + codedLength;
            end += codedLength;
        }

        delta.ensureBlockCapacity(coarseBlockCapacityReserve(sectionCount));
        sectionReader.setData(data, offset, sectionDataLength);

        pendingCoded.clear();
        for (int i = 0; i < sectionCount; i++) {
            decodeSection(sectionReader, delta);
        }
        decodePendingCodedSections(data, delta);
        if (codedPerSection && codedCursor != codedEnd) {
            throw new IOException("Coded dense block streams not fully consumed: " + (codedEnd - codedCursor)
                    + " byte(s) left over");
        }
        if (sectionReader.hasOverrun()) {
            throw new IOException("Section data truncated: " + sectionCount + " section(s) need more than "
                    + sectionDataLength + " bytes");
        }
        if (denseRawCursor != denseRawEnd) {
            throw new IOException("Raw dense block bytes not fully consumed: " + (denseRawEnd - denseRawCursor)
                    + " byte(s) left over");
        }

        return end;
    }

    /**
     * Reads a length-prefixed stream's length and checks the stream fits in the payload.
     *
     * @param data   payload
     * @param offset offset of the length prefix
     * @param name   stream name, for errors
     * @return stream length
     * @throws IOException if the length is negative or the stream overruns the payload
     */
    private static int readStreamLength(final byte[] data, final int offset, final String name)
            throws IOException {
        ensureAvailable(data, offset, 4, name + " stream length");
        final int length = readIntBE(data, offset);
        if (length < 0) {
            throw new IOException("Invalid " + name + " stream length: " + length);
        }
        ensureAvailable(data, offset + 4, length, name + " stream");
        return length;
    }

    /**
     * Dispatches a single section to the appropriate sparse or dense decoder.
     */
    private void decodeSection(BitReader reader, ChunkDelta<S, N> delta) throws IOException {
        int sectionY = reader.readZigZag(CisConstants.SECTION_Y_BITS);
        int mode = (int) reader.read(1);

        // globalBits is 0 when the palette has only one entry (air); all indices
        // decode to 0, which is fine because reads of 0 bits return 0.
        int globalBits = calculateBitsNeeded(globalPalette.size());

        if (mode == CisConstants.SECTION_ENCODING_SPARSE) {
            decodeSparseSection(reader, delta, sectionY, globalBits);
        } else {
            decodeDenseSection(reader, delta, sectionY, globalBits);
        }
    }

    /**
     * Decodes a sparse section. The block count field doubles as a sentinel
     * discriminator for the uniform and default-sparse sub-formats.
     */
    private void decodeSparseSection(BitReader reader, ChunkDelta<S, N> delta, int sectionY, int globalBits)
            throws IOException {
        int blockCount = (int) reader.read(CisConstants.BLOCK_COUNT_BITS);

        if (blockCount == CisConstants.UNIFORM_SECTION_SENTINEL) {
            decodeUniformSection(reader, delta, sectionY, globalBits);
            return;
        }
        if (blockCount == CisConstants.DEFAULT_SPARSE_SECTION_SENTINEL) {
            decodeDefaultSparseSection(reader, delta, sectionY, globalBits);
            return;
        }

        for (int i = 0; i < blockCount; i++) {
            int packedPos = (int) reader.read(12);
            int globalIdx = (int) reader.read(globalBits);

            int y = (packedPos >> 8) & 0xF;
            int z = (packedPos >> BITS_PER_NIBBLE) & 0xF;
            int x = packedPos & 0xF;

            delta.appendDecodedBlockFast(x, (sectionY << BITS_PER_NIBBLE) + y, z, sanitizeGlobalIndex(globalIdx));
        }
    }

    /**
     * Decodes a uniform section: one state fills all 4096 positions.
     * Air is skipped - absent positions already decode as air.
     */
    private void decodeUniformSection(final BitReader reader,
            final ChunkDelta<S, N> delta,
            final int sectionY,
            final int globalBits) throws IOException {
        final int globalIdx = (int) reader.read(globalBits);
        final int paletteIndex = sanitizeGlobalIndex(globalIdx);
        if (paletteIndex == 0) {
            return;
        }
        fillSection(delta, sectionY, paletteIndex);
    }

    /**
     * Decodes a default-sparse section: one dominant state implied for all 4096
     * positions, with explicit exceptions for positions that differ.
     *
     * <p>If the default is non-air, the whole section is pre-filled then each
     * exception overwrites its position. If the default is air, only non-air
     * exceptions are written.</p>
     */
    private void decodeDefaultSparseSection(final BitReader reader,
            final ChunkDelta<S, N> delta,
            final int sectionY,
            final int globalBits) throws IOException {
        final int defaultGlobalIdx = (int) reader.read(globalBits);
        final int defaultPaletteIndex = sanitizeGlobalIndex(defaultGlobalIdx);
        final S defaultState = getStateFromPalette(defaultPaletteIndex);
        final int exceptionCount = (int) reader.read(CisConstants.BLOCK_COUNT_BITS);

        final boolean defaultIsAir = isAir(defaultState);

        final int startOffset = delta.getBlockChangesCount();

        if (!defaultIsAir) {
            fillSection(delta, sectionY, defaultPaletteIndex);
        }

        final int baseY = sectionY << BITS_PER_NIBBLE;

        for (int i = 0; i < exceptionCount; i++) {
            final int packedPos = (int) reader.read(12);
            final int globalIdx = (int) reader.read(globalBits);

            final int y = (packedPos >> 8) & 0xF;
            final int z = (packedPos >> BITS_PER_NIBBLE) & 0xF;
            final int x = packedPos & 0xF;

            final int exceptionPaletteIndex = sanitizeGlobalIndex(globalIdx);
            final S exceptionState = getStateFromPalette(exceptionPaletteIndex);

            // Skip air exceptions when the default is already air - the position
            // was never filled, so no overwrite is needed.
            if (defaultIsAir && isAir(exceptionState)) {
                continue;
            }

            if (defaultIsAir) {
                delta.appendDecodedBlockFast(x, baseY + y, z, exceptionPaletteIndex);
            } else {
                // Critical Invariant:
                // This index math relies on fillSection(...) inserting blocks in the exact order:
                // y in 0..15, z in 0..15, x in 0..15.
                final int index = startOffset + (y << 8) + (z << 4) + x;
                delta.updateDecodedPaletteAtKnownIndex(index, exceptionPaletteIndex);
            }
        }
    }


    /**
     * Decodes a dense section (full 16x16x16 bit-array with local palette).
     *
     * <p>CIS v7 used an 8-bit palette size field; v8+ use
     * {@link CisConstants#PALETTE_SIZE_BITS}.</p>
     */
    private void decodeDenseSection(BitReader reader, ChunkDelta<S, N> delta, int sectionY, int globalBits)
            throws IOException {
        int paletteBits = (decodedVersion == 7) ? 8 : CisConstants.PALETTE_SIZE_BITS;
        int localSize = (int) reader.read(paletteBits);
        validateLocalPaletteSize(localSize);

        readLocalPalette(reader, localSize, globalBits);

        int bitsPerBlock = calculateBitsNeeded(localSize + 1);
        readDenseBlocks(reader, delta, sectionY, localSize, bitsPerBlock);
    }

    /**
     * Reads the dense-section local palette into {@link #localPaletteBuffer}.
     */
    private void readLocalPalette(BitReader reader, int localSize, int globalBits) {
        for (int i = 0; i < localSize; i++) {
            localPaletteBuffer[i] = (int) reader.read(globalBits);
        }
    }

    /**
     * Queues a coded section of a per-section-stream payload for {@link #decodePendingCodedSections}.
     *
     * @param sectionY     section Y
     * @param localSize    local palette size
     * @param bitsPerBlock bits per block index
     * @throws IOException when its stream does not fit
     */
    private void deferCodedSection(final int sectionY, final int localSize, final int bitsPerBlock)
            throws IOException {
        if (codedEnd - codedCursor < 4) {
            throw new IOException("Dense section " + sectionY + " coded stream length truncated");
        }
        final int length = readIntBE(denseRaw, codedCursor);
        if (length < 0 || length > codedEnd - codedCursor - 4) {
            throw new IOException("Dense section " + sectionY + " coded stream length " + length + " out of range");
        }
        final int[] palette = new int[localSize];
        for (int i = 0; i < localSize; i++) {
            palette[i] = sanitizeGlobalIndex(localPaletteBuffer[i]);
        }
        pendingCoded.add(new PendingCodedSection(sectionY, codedCursor + 4, length, bitsPerBlock, palette));
        codedCursor += 4 + length;
    }

    /**
     * Decodes the queued coded sections, in parallel when there are enough, then appends them in section order.
     *
     * @param data  payload
     * @param delta delta being decoded
     * @throws IOException when a section's stream is invalid
     */
    private void decodePendingCodedSections(final byte[] data, final ChunkDelta<S, N> delta) throws IOException {
        final int count = pendingCoded.size();
        if (count == 0) {
            return;
        }
        final int[][] decoded = new int[count][];
        try {
            final java.util.stream.IntStream tasks = java.util.stream.IntStream.range(0, count);
            // A few sections decode faster inline than as tasks competing with world generation for workers.
            (count >= PARALLEL_MIN_CODED_SECTIONS ? tasks.parallel() : tasks).forEach(task -> {
                try {
                    decoded[task] = decodeCodedSection(data, pendingCoded.get(task));
                } catch (final IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (final java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        for (int task = 0; task < count; task++) {
            delta.appendDecodedDenseSection(pendingCoded.get(task).sectionY() << BITS_PER_NIBBLE, decoded[task]);
        }
        pendingCoded.clear();
    }

    /**
     * Decodes one coded section into global palette ids (-1 where unchanged), on whichever thread runs it.
     *
     * @param data    payload
     * @param section the queued section
     * @return 4096 global ids
     * @throws IOException when its stream is invalid
     */
    private static int[] decodeCodedSection(final byte[] data, final PendingCodedSection section)
            throws IOException {
        final SectionDecoder worker = SECTION_DECODER.get();
        final int[] indices = new int[SECTION_VOLUME];
        worker.decoder.setData(data, section.offset(), section.length());
        final int[] palette = section.palette();
        worker.coder.decode(worker.decoder, indices, section.bitsPerBlock(), palette.length + 1);
        for (int i = 0; i < SECTION_VOLUME; i++) {
            final int localIndex = indices[i];
            if (localIndex == 0) {
                indices[i] = -1;
                continue;
            }
            if (localIndex - 1 >= palette.length) {
                throw new IOException("Dense section " + section.sectionY() + " local palette index "
                        + (localIndex - 1) + " out of range (size " + palette.length + ")");
            }
            indices[i] = palette[localIndex - 1];
        }
        return indices;
    }

    /**
     * Expands a dense section by translating each local palette index back to a global
     * palette id, then bulk-appending all non-air entries via
     * {@link ChunkDelta#appendDecodedDenseSection}.
     *
     * <p>Local index 0 means "no change" and maps to global id 0 (skipped by
     * the bulk writer). Indices 1..N map to {@code localPaletteBuffer[index - 1]}.
     * Out-of-range indices are clamped to global id 0 (air) with a warning.
     * The translation pass rewrites {@code denseIndicesBuffer} in place before
     * handing it off to the tight inner loop in ChunkDelta, avoiding
     * 4096 individual method-call chains.</p>
     */
    private void readDenseBlocks(BitReader reader,
            ChunkDelta<S, N> delta,
            int sectionY,
            int localSize,
            int bitsPerBlock) throws IOException {
        if (decodedVersion >= CisConstants.CODED_DENSE_MIN_VERSION && bitsPerBlock > 0
                && bitsPerBlock <= CisConstants.MAX_CODED_DENSE_BITS) {
            if (reader.read(1) == 1) {
                if (codedPerSection) {
                    // Decoded after the section pass, in parallel with the chunk's other coded sections.
                    deferCodedSection(sectionY, localSize, bitsPerBlock);
                    return;
                }
                indexCoder.decode(denseDecoder, denseIndicesBuffer, bitsPerBlock, localSize + 1);
            } else {
                if (denseRawEnd - denseRawCursor < SECTION_VOLUME) {
                    throw new IOException("Dense section " + sectionY + " raw block bytes truncated: "
                            + (denseRawEnd - denseRawCursor) + " byte(s) left, " + SECTION_VOLUME + " needed");
                }
                for (int i = 0; i < SECTION_VOLUME; i++) {
                    denseIndicesBuffer[i] = denseRaw[denseRawCursor + i] & 0xFF;
                }
                denseRawCursor += SECTION_VOLUME;
            }
        } else {
            reader.readBatch(bitsPerBlock, denseIndicesBuffer);
        }
        final int baseY = sectionY << BITS_PER_NIBBLE;

        // Translate local indices to global palette ids in place. Local index 0 means
        // "no change" and becomes -1 (skipped by the bulk writer). Global id 0 is air and
        // is a real instruction: it clears the base block, so it must not be skipped.
        for (int i = 0; i < SECTION_VOLUME; i++) {
            int localIndex = denseIndicesBuffer[i];
            if (localIndex == 0) {
                denseIndicesBuffer[i] = -1;
                continue;
            }
            int paletteIndex = localIndex - 1;
            if (paletteIndex >= localSize) {
                throw new IOException("Dense section " + sectionY + " local palette index " + paletteIndex
                        + " out of range (size " + localSize + ")");
            }
            denseIndicesBuffer[i] = sanitizeGlobalIndex(localPaletteBuffer[paletteIndex]);
        }

        delta.appendDecodedDenseSection(baseY, denseIndicesBuffer);
    }

    /**
     * Decodes block entities, global entities, and optional chunk metadata.
     *
     * <p>A corrupt tail fails the whole decode. Returning the blocks without their
     * block entities, entities or metadata would mark a partial delta as clean, and
     * the next save would permanently overwrite the stored tail.</p>
     */
    private void decodeBlockEntitiesAndEntities(byte[] data, int offset, ChunkDelta<S, N> delta) throws IOException {
        if (offset >= data.length) {
            return;
        }

        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data, offset, data.length - offset))) {
            decodeBlockEntities(dis, delta);
            decodeEntities(dis, delta);
            if (decodedVersion >= 9) {
                decodeChunkMetadata(dis, delta, offset);
            }
        } catch (IOException e) {
            throw new IOException("Corrupt block-entity/entity/metadata tail at offset " + offset
                    + " (CIS v" + decodedVersion + ")", e);
        }
    }

    /**
     * Reads the block entity section from the stream and registers each entry
     * with the delta. Each entry is a packed 32-bit position followed by its
     * NBT payload.
     */
    private void decodeBlockEntities(DataInputStream dis, ChunkDelta<S, N> delta) throws IOException {
        int count = dis.readInt();
        if (count < 0 || count > MAX_BLOCK_ENTITY_COUNT) {
            throw new IOException("Invalid block entity count " + count);
        }

        for (int i = 0; i < count; i++) {
            int packedPos = dis.readInt();
            byte x = (byte) BlockInstruction.unpackX(packedPos);
            int y = BlockInstruction.unpackY(packedPos);
            byte z = (byte) BlockInstruction.unpackZ(packedPos);

            try {
                delta.addBlockEntityData(x, y, z, readNbtPayload(dis));
            } catch (IOException e) {
                Chunkis.LOGGER.warn("Failed to decode block entity {}/{} at {}/{}/{}", i, count, x, y, z);
                throw e;
            }
        }
    }

    /**
     * Reads the entity section from the stream, if present.
     *
     * <p>Entity data is optional. An {@link EOFException} is silently ignored -
     * older CIS files may not include this section. The count is also bounded by
     * {@link #MAX_ENTITY_COUNT} to guard against corrupt or malicious payloads.</p>
     */
    private void decodeEntities(DataInputStream dis, ChunkDelta<S, N> delta) throws IOException {
        try {
            int count = dis.readInt();
            if (count < 0 || count > MAX_ENTITY_COUNT) {
                // Not consuming the payload would desync the metadata read that follows.
                throw new IOException("Invalid entity count " + count);
            }
            if (count == 0) {
                return;
            }

            List<N> entities = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entities.add(readNbtPayload(dis));
            }
            delta.setEntities(entities, false);

        } catch (EOFException ignored) {
            // Pre-entity CIS files end here - not an error.
        }
    }

    /**
     * Reads the chunk metadata section introduced in CIS v9.
     *
     * <p>A leading boolean signals whether metadata is present. An
     * {@link EOFException} indicates a v9 file written before metadata was made
     * mandatory and is treated as absent.</p>
     *
     * @param offset original decode offset, used only for the warning message.
     */
    private void decodeChunkMetadata(DataInputStream dis, ChunkDelta<S, N> delta, int offset) throws IOException {
        try {
            if (dis.readBoolean()) {
                delta.setChunkMetadata(readNbtPayload(dis), false);
            }
        } catch (EOFException e) {
            if (decodedVersion > 9) {
                throw new IOException("Truncated CIS v" + decodedVersion + " payload: chunk metadata missing", e);
            }
            Chunkis.LOGGER.warn("CIS v9 chunk metadata missing at offset {} - treating as absent", offset);
        }
    }

    /**
     * Reads one NBT payload. Raw format is used for CIS v10+; earlier versions
     * use zlib-compressed NBT to fit within legacy region-file size constraints.
     */
    private N readNbtPayload(DataInputStream dis) throws IOException {
        if (decodedVersion >= 10) {
            return nbtAdapter.readRaw(dis);
        }
        return nbtAdapter.readCompressed(dis);
    }

    /**
     * Fills all 4096 positions of a section with the given palette id.
     *
     * <p>Delegates to {@link ChunkDelta#fillDecodedSection} which writes
     * directly into the backing array in a tight y->z->x loop, avoiding the
     * 4096 individual {@link ChunkDelta#appendDecodedBlockFast} calls that
     * this method previously issued.</p>
     */
    private void fillSection(final ChunkDelta<S, N> delta, final int sectionY, final int paletteIndex) {
        delta.fillDecodedSection(sectionY, paletteIndex);
    }

    /**
     * Validates a decoded global palette index.
     *
     * <p>Fast decode paths append packed instructions directly, so the bound is
     * checked here. An out-of-range index means corrupt data; turning it into air
     * would silently delete the block, so it fails the decode instead.</p>
     *
     * @param globalIndex decoded palette index from the stream
     * @return the same index when in range
     * @throws IOException when the index is outside the global palette
     */
    private int sanitizeGlobalIndex(final int globalIndex) throws IOException {
        if (globalIndex < 0 || globalIndex >= globalPalette.size()) {
            throw new IOException("Global palette index " + globalIndex + " out of range (size "
                    + globalPalette.size() + ")");
        }
        return globalIndex;
    }

    /**
     * Resolves a global palette index, falling back to {@link #airState} when
     * the index is out of range.
     */
    private S getStateFromPalette(int index) {
        return (index >= 0 && index < globalPalette.size()) ? globalPalette.get(index) : airState;
    }

    /**
     * Returns true when {@code state} is the canonical air state or null.
     * Uses reference equality first (fast path for canonical state singletons)
     * and falls back to {@code equals} for non-canonical instances.
     */
    private boolean isAir(final S state) {
        return state == null || state == airState || state.equals(airState);
    }

    /**
     * Allocates storage for the next decoded global palette.
     */
    private void beginGlobalPalette(int expectedSize) {
        globalPalette = new ArrayList<>(expectedSize);
    }

    /**
     * Records one decoded global palette entry in both the linear list and delta palette view.
     */
    private void addGlobalPaletteState(Palette<S> palette, S state) {
        globalPalette.add(state);
        palette.getOrAdd(state);
    }
}
