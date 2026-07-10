package io.liparakis.chunkis.core.codec;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.core.bits.BitReader;
import io.liparakis.chunkis.core.model.CisConstants;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for CIS (Chunk Incremental Storage) decoders containing shared
 * decoding logic.
 *
 * @param <S> The BlockState type
 * @param <N> The NBT type
 */
public abstract class AbstractCisDecoder<S, N> {

    /**
     * Size of the CIS file header in bytes (magic number + version).
     */
    protected static final int HEADER_SIZE = 8;

    /**
     * Number of bits in a nibble (half-byte).
     */
    protected static final int BITS_PER_NIBBLE = 4;

    /**
     * Total number of blocks in a chunk section (16x16x16).
     */
    protected static final int SECTION_VOLUME = 4096;

    /**
     * Maximum reasonable palette size to prevent memory exhaustion attacks.
     */
    protected static final int MAX_REASONABLE_PALETTE_SIZE = 10_000;

    /**
     * Maximum entity count accepted per chunk. Matches {@link #MAX_REASONABLE_PALETTE_SIZE}
     * by convention - both guard against corrupt or malicious payloads.
     */
    private static final int MAX_ENTITY_COUNT = 10_000;

    /**
     * Reusable reader over the packed block-state property payload in the global palette section.
     */
    protected final BitReader propertyReader;
    /**
     * Reusable reader over the encoded chunk-section payload during the main decode pass.
     */
    protected final BitReader sectionReader;

    /**
     * Reusable buffer for local palette indices in dense sections.
     */
    protected final int[] localPaletteBuffer;
    /**
     * Reusable buffer for dense palette block indices.
     */
    protected final int[] denseIndicesBuffer;
    /**
     * Adapter used to decode block entities, entities, and chunk metadata payloads.
     */
    protected final NbtAdapter<N> nbtAdapter;
    /**
     * Fallback state returned when the payload refers to an invalid palette entry.
     */
    protected final S airState;
    /**
     * The format version of the data being decoded. Set per {@link #decodeInternal} call.
     */
    protected int decodedVersion;
    /**
     * The global palette mapping indices to BlockStates. Set per {@link #decodeInternal} call.
     */
    protected List<S> globalPalette;

    protected AbstractCisDecoder(NbtAdapter<N> nbtAdapter, S airState) {
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
    protected static int calculateBitsNeeded(int maxValue) {
        if (maxValue <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(maxValue - 1);
    }

    /**
     * Rejects global palette sizes that are negative or implausibly large for
     * one chunk payload.
     */
    protected static void validateGlobalPaletteSize(int globalPaletteSize) throws IOException {
        if (globalPaletteSize < 0 || globalPaletteSize > MAX_REASONABLE_PALETTE_SIZE) {
            throw new IOException("Invalid palette size: " + globalPaletteSize);
        }
    }

    /**
     * Ensures that the requested byte span is fully present before the decoder
     * attempts to read it.
     */
    protected static void ensureAvailable(byte[] data, int offset, int length, String section) throws IOException {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IOException("Truncated data: cannot read " + section);
        }
    }

    /**
     * Reads a big-endian 32-bit integer from the raw payload.
     */
    protected static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /**
     * Reads a big-endian 16-bit integer from the raw payload.
     */
    protected static short readShortBE(byte[] b, int off) {
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
     * Decodes the global block palette from the CIS data.
     *
     * @return the offset immediately after the palette payload.
     */
    protected abstract int decodeGlobalPalette(byte[] data, int offset, Palette<S> palette) throws IOException;

    /**
     * Orchestrates the complete decoding process for one serialized chunk delta.
     */
    protected ChunkDelta<S, N> decodeInternal(byte[] data) throws IOException {
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
    protected void validateHeader(byte[] data) throws IOException {
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

        delta.ensureBlockCapacity(coarseBlockCapacityReserve(sectionCount));
        sectionReader.setData(data, offset, sectionDataLength);

        for (int i = 0; i < sectionCount; i++) {
            decodeSection(sectionReader, delta);
        }

        return offset + sectionDataLength;
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
    private void decodeSparseSection(BitReader reader, ChunkDelta<S, N> delta, int sectionY, int globalBits) {
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
            final int globalBits) {
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
            final int globalBits) {
        final int defaultGlobalIdx = (int) reader.read(globalBits);
        final int defaultPaletteIndex = sanitizeGlobalIndex(defaultGlobalIdx);
        final S defaultState = getStateFromPalette(defaultPaletteIndex);
        final int exceptionCount = (int) reader.read(CisConstants.BLOCK_COUNT_BITS);

        final boolean defaultIsAir = isAir(defaultState);

        final int startOffset = delta.getInstructionCount();

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
            int bitsPerBlock) {
        reader.readBatch(bitsPerBlock, denseIndicesBuffer);
        final int baseY = sectionY << BITS_PER_NIBBLE;
        final int paletteSize = globalPalette.size();

        // Translate local indices to global palette ids in place.
        // denseIndicesBuffer[i] == 0 means "no-change / air" and is skipped
        // by the bulk writer, preserving the exact same semantics as before.
        for (int i = 0; i < SECTION_VOLUME; i++) {
            int localIndex = denseIndicesBuffer[i];
            if (localIndex == 0) {
                continue;
            }
            int paletteIndex = localIndex - 1;
            if (paletteIndex >= localSize) {
                Chunkis.LOGGER.warn("Dense section local palette index {} out of range (size {}); using air",
                        paletteIndex,
                        localSize);
                denseIndicesBuffer[i] = 0;
                continue;
            }
            int globalIndex = localPaletteBuffer[paletteIndex];
            denseIndicesBuffer[i] = (globalIndex >= 0 && globalIndex < paletteSize) ? globalIndex : 0;
        }

        delta.appendDecodedDenseSection(baseY, denseIndicesBuffer);
    }

    /**
     * Decodes block entities, global entities, and optional chunk metadata.
     *
     * <p>Failures here are non-fatal: a warning is logged and the delta is
     * returned with whatever was successfully decoded.</p>
     */
    private void decodeBlockEntitiesAndEntities(byte[] data, int offset, ChunkDelta<S, N> delta) {
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
            Chunkis.LOGGER.warn("Failed to decode block/entity data at offset {}: {}", offset, e.getMessage());
        }
    }

    /**
     * Reads the block entity section from the stream and registers each entry
     * with the delta. Each entry is a packed 32-bit position followed by its
     * NBT payload.
     */
    private void decodeBlockEntities(DataInputStream dis, ChunkDelta<S, N> delta) throws IOException {
        int count = dis.readInt();

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
            if (count <= 0 || count > MAX_ENTITY_COUNT) {
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
     * Clamps a decoded global palette index into the current palette bounds.
     *
     * <p>Fast decode paths append packed instructions directly, so invalid indices
     * must be normalized explicitly here instead of falling through the slower
     * object lookup path. Global palette index {@code 0} is canonical air.</p>
     *
     * @param globalIndex decoded palette index from the stream
     * @return in-range palette index, or {@code 0} when invalid
     */
    private int sanitizeGlobalIndex(final int globalIndex) {
        return globalIndex >= 0 && globalIndex < globalPalette.size() ? globalIndex : 0;
    }

    /**
     * Resolves a global palette index, falling back to {@link #airState} when
     * the index is out of range.
     */
    protected S getStateFromPalette(int index) {
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
    protected void beginGlobalPalette(int expectedSize) {
        globalPalette = new ArrayList<>(expectedSize);
    }

    /**
     * Records one decoded global palette entry in both the linear list and delta palette view.
     */
    protected void addGlobalPaletteState(Palette<S> palette, S state) {
        globalPalette.add(state);
        palette.getOrAdd(state);
    }
}
