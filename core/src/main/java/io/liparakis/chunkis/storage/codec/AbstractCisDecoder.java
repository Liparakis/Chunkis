package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.bits.BitReader;
import io.liparakis.chunkis.storage.model.CisConstants;

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
 * @author Liparakis
 * @version 1
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
     * Width/height/depth of a chunk section in blocks.
     */
    protected static final int SECTION_SIZE = 16;

    /**
     * Maximum reasonable palette size to prevent memory exhaustion attacks.
     */
    protected static final int MAX_REASONABLE_PALETTE_SIZE = 10000;

    /**
     * Bit reader for block property data.
     */
    protected final BitReader propertyReader;

    /**
     * Bit reader for section data.
     */
    protected final BitReader sectionReader;

    /**
     * Reusable buffer for local palette indices in dense sections.
     */
    protected final int[] localPaletteBuffer;

    /**
     * The format version of the data being decoded.
     */
    protected int decodedVersion;

    /**
     * The global palette mapping indices to BlockStates.
     */
    protected List<S> globalPalette;

    /**
     * Adapter used to rebuild concrete block states from decoded palette and property data.
     */
    protected final BlockStateAdapter<?, S, ?> stateAdapter;
    /**
     * Adapter used to decode block entities, entities, and chunk metadata payloads.
     */
    protected final NbtAdapter<N> nbtAdapter;
    /**
     * Fallback state returned when the payload refers to an invalid palette entry.
     */
    protected final S airState;

    /**
     * Constructs a new AbstractCisDecoder with initialized readers and buffers.
     */
    protected AbstractCisDecoder(BlockStateAdapter<?, S, ?> stateAdapter, NbtAdapter<N> nbtAdapter, S airState) {
        this.stateAdapter = stateAdapter;
        this.nbtAdapter = nbtAdapter;
        this.airState = airState;
        this.propertyReader = new BitReader(new byte[0]);
        this.sectionReader = new BitReader(new byte[0]);
        this.localPaletteBuffer = new int[SECTION_VOLUME];
    }

    /**
     * Decodes the global block palette from the CIS data.
     */
    protected abstract int decodeGlobalPalette(byte[] data, int offset, Palette<S> palette) throws IOException;

    /**
     * Internal method that orchestrates the complete decoding process.
     */
    protected ChunkDelta<S, N> decodeInternal(byte[] data) throws IOException {
        if (data.length < HEADER_SIZE) {
            throw new IOException("CIS data too short: " + data.length + " bytes (minimum: " + HEADER_SIZE + ")");
        }

        int offset = validateHeader(data);
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
     * Validates the fixed-width CIS header and returns the offset of the first payload section.
     */
    protected int validateHeader(byte[] data) throws IOException {
        int magic = readIntBE(data, 0);
        if (magic != CisConstants.MAGIC) {
            throw new IOException(String.format(
                    "Invalid CIS magic number: 0x%08X (expected: 0x%08X)",
                    magic, CisConstants.MAGIC
            ));
        }

        this.decodedVersion = readIntBE(data, 4);
        if (decodedVersion < 7 || decodedVersion > CisConstants.VERSION) {
            throw new IOException(String.format(
                    "Unsupported CIS version: %d (expected 7 or %d)",
                    decodedVersion, CisConstants.VERSION
            ));
        }

        return HEADER_SIZE;
    }

    /**
     * Decodes all chunk sections from the data stream.
     *
     * @param data   The raw byte array.
     * @param offset The current read offset.
     * @param delta  The delta to populate.
     * @return The new offset after reading sections.
     * @throws IOException If data is truncated or invalid.
     */
    private int decodeSections(byte[] data, int offset, ChunkDelta<S, N> delta) throws IOException {
        ensureAvailable(data, offset, 6, "section header");

        int sectionCount = readShortBE(data, offset) & 0xFFFF;
        offset += 2;

        int sectionDataLength = readIntBE(data, offset);
        offset += 4;

        ensureAvailable(data, offset, sectionDataLength, "sections");

        sectionReader.setData(data, offset, sectionDataLength);
        delta.ensureBlockCapacity(sectionCount * SECTION_VOLUME);

        for (int i = 0; i < sectionCount; i++) {
            decodeSection(sectionReader, delta);
        }

        return offset + sectionDataLength;
    }

    /**
     * Decodes a single section.
     *
     * @param reader The bit reader positioned at the section start.
     * @param delta  The delta to populate.
     */
    private void decodeSection(BitReader reader, ChunkDelta<S, N> delta) throws IOException {
        int sectionY = reader.readZigZag(CisConstants.SECTION_Y_BITS);
        int mode = (int) reader.read(1);

        int globalBits = calculateBitsNeeded(globalPalette.size());

        if (mode == CisConstants.SECTION_ENCODING_SPARSE) {
            decodeSparseSection(reader, delta, sectionY, globalBits);
        } else {
            decodeDenseSection(reader, delta, sectionY, globalBits);
        }
    }

    /**
     * Decodes a sparse section (list of blocks).
     */
    private void decodeSparseSection(BitReader reader, ChunkDelta<S, N> delta, int sectionY, int globalBits) {
        int blockCount = (int) reader.read(CisConstants.BLOCK_COUNT_BITS);

        if (blockCount == CisConstants.UNIFORM_SECTION_SENTINEL) {
            decodeUniformSection(reader, delta, sectionY, globalBits);
            return;
        }

        for (int i = 0; i < blockCount; i++) {
            int packedPos = (int) reader.read(12);
            int globalIdx = (int) reader.read(globalBits);

            int y = (packedPos >> 8) & 0xF;
            int z = (packedPos >> BITS_PER_NIBBLE) & 0xF;
            int x = packedPos & 0xF;

            S state = getStateFromPalette(globalIdx);

            if (state != null) {
                delta.addBlockChange(
                        (byte) x,
                        (sectionY << BITS_PER_NIBBLE) + y,
                        (byte) z,
                        state
                );
            }
        }
    }

    /**
     * Decodes a full single-state section encoded through the sparse sentinel path.
     */
    private void decodeUniformSection(
            final BitReader reader,
            final ChunkDelta<S, N> delta,
            final int sectionY,
            final int globalBits
    ) {
        final int globalIdx = (int) reader.read(globalBits);
        final S state = getStateFromPalette(globalIdx);
        if (state == null) {
            return;
        }

        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    delta.addBlockChange(
                            (byte) x,
                            (sectionY << BITS_PER_NIBBLE) + y,
                            (byte) z,
                            state
                    );
                }
            }
        }
    }

    /**
     * Decodes a dense section (full 16x16x16 array).
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
     * Rejects dense-section palette sizes that are negative or impossible for one section.
     */
    private static void validateLocalPaletteSize(int localSize) throws IOException {
        if (localSize < 0 || localSize > SECTION_VOLUME) {
            throw new IOException("Invalid local palette size: " + localSize);
        }
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
     * Expands a dense section by translating each local palette index back into a block state.
     */
    private void readDenseBlocks(
            BitReader reader,
            ChunkDelta<S, N> delta,
            int sectionY,
            int localSize,
            int bitsPerBlock) {
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int localIndex = bitsPerBlock > 0 ? (int) reader.read(bitsPerBlock) : 0;
                    if (localIndex == 0) {
                        continue;
                    }

                    int paletteIndex = localIndex - 1;
                    if (paletteIndex >= localSize) {
                        paletteIndex = 0;
                    }

                    int globalIndex = localPaletteBuffer[paletteIndex];
                    if (globalIndex < 0 || globalIndex >= globalPalette.size()) {
                        globalIndex = 0;
                    }

                    delta.appendDecodedBlock(
                            x,
                            (sectionY << BITS_PER_NIBBLE) + y,
                            z,
                            globalIndex
                    );
                }
            }
        }
    }

    /**
     * Decodes block entities, global entities, and optional chunk metadata from the serialized chunk
     * data stream starting at the given offset.
     *
     * @param data   raw serialized chunk bytes
     * @param offset byte offset into {@code data} where the block entity section begins
     * @param delta  target delta to populate
     */
    private void decodeBlockEntitiesAndEntities(byte[] data, int offset, ChunkDelta<S, N> delta) {
        if (offset >= data.length) {
            return;
        }

        try (DataInputStream dis = new DataInputStream(
                new ByteArrayInputStream(data, offset, data.length - offset))) {

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
     * Reads the block entity section from the stream and registers each entry with the delta.
     * Each block entity is stored as a packed 32-bit position followed by its NBT payload.
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
     * Entity data is optional and bounded to a sanity limit of 10 000 entries to guard against
     * corrupt or malicious payloads. An {@link EOFException} is silently ignored — older CIS
     * files may not include this section.
     */
    private void decodeEntities(DataInputStream dis, ChunkDelta<S, N> delta) throws IOException {
        final int ENTITY_COUNT_LIMIT = 10_000;

        try {
            int count = dis.readInt();
            if (count <= 0 || count > ENTITY_COUNT_LIMIT) {
                return;
            }

            List<N> entities = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entities.add(readNbtPayload(dis));
            }
            delta.setEntities(entities, false);

        } catch (EOFException ignored) {
            // Pre-entity CIS files end here — not an error
        }
    }

    /**
     * Reads the chunk metadata section introduced in CIS v9.
     * A leading boolean signals whether metadata is present; an {@link EOFException} indicates
     * a v9 file written before metadata was made mandatory and is treated as absent.
     *
     * @param offset original decode offset, used only for the warning message
     */
    private void decodeChunkMetadata(DataInputStream dis, ChunkDelta<S, N> delta, int offset) throws IOException {
        try {
            if (dis.readBoolean()) {
                delta.setChunkMetadata(readNbtPayload(dis), false);
            }
        } catch (EOFException e) {
            Chunkis.LOGGER.warn(
                    "CIS v9 chunk metadata missing at offset {} — treating as absent", offset);
        }
    }

    /**
     * Reads one NBT payload using the format declared by the currently decoded CIS
     * version.
     */
    private N readNbtPayload(DataInputStream dis) throws IOException {
        if (decodedVersion >= 10) {
            return nbtAdapter.readRaw(dis);
        }
        return nbtAdapter.readCompressed(dis);
    }

    /**
     * Returns the minimum bit width needed to encode values in {@code [0, maxValue)}.
     */
    protected static int calculateBitsNeeded(int maxValue) {
        if (maxValue <= 1)
            return 0;
        return 32 - Integer.numberOfLeadingZeros(maxValue - 1);
    }

    /**
     * Resolves a global palette index, falling back to {@link #airState} when the index is invalid.
     */
    protected S getStateFromPalette(int index) {
        return (index >= 0 && index < globalPalette.size())
                ? globalPalette.get(index)
                : airState;
    }

    /**
     * Rejects global palette sizes that are negative or implausibly large for one chunk payload.
     */
    protected static void validateGlobalPaletteSize(int globalPaletteSize) throws IOException {
        if (globalPaletteSize < 0 || globalPaletteSize > MAX_REASONABLE_PALETTE_SIZE) {
            throw new IOException(String.format(
                    "Invalid palette size: %d", globalPaletteSize));
        }
    }

    /**
     * Ensures that the requested byte span is fully present before the decoder reads it.
     */
    protected static void ensureAvailable(byte[] data, int offset, int length, String section) throws IOException {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IOException("Truncated data: cannot read " + section);
        }
    }

    /**
     * Allocates storage for the next decoded global palette.
     */
    protected void beginGlobalPalette(int expectedSize) {
        globalPalette = new ArrayList<>(expectedSize);
    }

    /**
     * Records one decoded global palette entry in both the linear palette and delta palette view.
     */
    protected void addGlobalPaletteState(Palette<S> palette, S state) {
        globalPalette.add(state);
        palette.getOrAdd(state);
    }

    /**
     * Reads a big-endian 32-bit integer from the raw payload.
     */
    protected static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    /**
     * Reads a big-endian 16-bit integer from the raw payload.
     */
    protected static short readShortBE(byte[] b, int off) {
        return (short) (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }
}
