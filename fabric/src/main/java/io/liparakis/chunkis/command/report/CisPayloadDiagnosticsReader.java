package io.liparakis.chunkis.command.report;

import io.liparakis.chunkis.command.report.StorageReportModels.ChunkEncodingKind;
import io.liparakis.chunkis.command.report.StorageReportModels.ChunkPayloadDiagnostics;
import io.liparakis.chunkis.command.report.StorageReportModels.RegionCoordinates;
import io.liparakis.chunkis.command.report.StorageReportModels.SectionEncodingKind;
import io.liparakis.chunkis.command.report.StorageReportModels.SectionPayloadDiagnostics;
import io.liparakis.chunkis.core.bits.BitReader;
import io.liparakis.chunkis.core.compression.CisCompression;
import io.liparakis.chunkis.core.model.CisConstants;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reader and parser for extracting diagnostic storage metrics directly from serialized CIS block payloads.
 *
 * <p>Supports reading versions from version 7 to the current {@link CisConstants#VERSION}.
 * It parses metadata, palettes, sections, and block entities without invoking the full deserialization logic.</p>
 */
public final class CisPayloadDiagnosticsReader {

    /**
     * Regex pattern matching region filenames like r.1.-2.cis.
     */
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cis");

    /**
     * Byte size of region file allocation headers.
     */
    private static final int HEADER_BYTES = 8192;

    /**
     * Number of bits representing individual sparse block positions (12 bits inside a section coordinates mapping).
     */
    private static final int SPARSE_ENTRY_POSITION_BITS = 12;

    /**
     * Total block positions inside a single Minecraft chunk section (16x16x16 = 4096 blocks).
     */
    private static final int SECTION_BLOCKS = 4096;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always, as instantiation is not allowed
     */
    private CisPayloadDiagnosticsReader() {
        throw new AssertionError("Utility class");
    }

    /**
     * Inspects a raw CIS chunk payload and parses its diagnostic metadata.
     *
     * @param raw the raw compressed/decompressed CIS chunk byte array, must not be null
     * @return a diagnostics container describing section encodings, counts, and sizes
     * @throws IOException if the payload is too small, uses an invalid magic/version, or contains malformed data
     */
    public static ChunkPayloadDiagnostics inspectChunkPayload(final byte[] raw) throws IOException {
        if (raw.length < 8) {
            throw new IOException("CIS chunk payload too small: " + raw.length);
        }

        final PayloadCursor cursor = new PayloadCursor(raw);
        final int magic = cursor.readInt("magic");
        if (magic != CisConstants.MAGIC) {
            throw new IOException("Invalid CIS magic");
        }

        final int version = cursor.readInt("version");
        if (version < 7 || version > CisConstants.VERSION) {
            throw new IOException("Unsupported CIS version " + version);
        }

        final int globalPaletteSize = cursor.readNonNegativeInt("global palette size");
        cursor.skip(checkedByteCount(globalPaletteSize, 2, "global palette"), "global palette");

        final int propertyBytes = cursor.readNonNegativeInt("property table length");
        cursor.skip(propertyBytes, "property table");

        final int sectionCount = cursor.readUnsignedShort("section count");
        final int sectionDataLength = cursor.readNonNegativeInt("section data length");
        final byte[] sectionData = cursor.readBytes(sectionDataLength, "section data");

        final ChunkSectionPayloadAccumulator sections = inspectSectionPayloads(sectionData, sectionCount, version,
                calculateBitsNeeded(globalPaletteSize));

        final int blockEntities = cursor.readNonNegativeInt("block entity count");
        final ChunkEncodingKind kind = chunkEncodingKind(sections.sawUniform, sections.sawDefaultSparse,
                sections.sawSparse, sections.sawDense);

        return new ChunkPayloadDiagnostics(sectionCount, sections.uniformSections, sections.defaultSparseSections,
                sections.sparseSections, sections.denseSections, blockEntities, sections.uniformBits,
                sections.defaultSparseBits, sections.sparseBits, sections.denseBits, sections.globalBits,
                sections.sectionReports(), kind);
    }

    /**
     * Inspects the raw section bytes and iterates through each section's header and bitstream data.
     *
     * @param sectionData  raw bits containing chunk section data
     * @param sectionCount total number of sections stored
     * @param version      the CIS format version number
     * @param globalBits   number of bits representing global palette references
     * @return an accumulator tracking section structure analysis
     * @throws IOException if reading the bitstream fails
     */
    private static ChunkSectionPayloadAccumulator inspectSectionPayloads(final byte[] sectionData,
            final int sectionCount, final int version,
            final int globalBits) throws IOException {
        final BitReader reader = new BitReader(sectionData);
        final ChunkSectionPayloadAccumulator accumulator = new ChunkSectionPayloadAccumulator(sectionCount, globalBits);
        final int paletteBits = (version == 7) ? 8 : CisConstants.PALETTE_SIZE_BITS;

        for (int i = 0; i < sectionCount; i++) {
            final int sectionY = readZigZag(reader, CisConstants.SECTION_Y_BITS, "section y");
            final int mode = (int) readBits(reader, 1, "section encoding mode");

            if (mode == CisConstants.SECTION_ENCODING_SPARSE) {
                inspectSparseLikeSection(reader, accumulator, sectionY, globalBits);
            } else {
                inspectDensePayloadSection(reader, accumulator, sectionY, globalBits, paletteBits);
            }
        }

        return accumulator;
    }

    /**
     * Inspects sparse-like sections, determining if they are uniform, default-sparse, or standard sparse.
     *
     * @param reader      bitstream reader
     * @param accumulator target section accumulator
     * @param sectionY    the section's vertical Y coordinate
     * @param globalBits  global palette bits count
     * @throws IOException if reading the bitstream fails
     */
    private static void inspectSparseLikeSection(final BitReader reader,
            final ChunkSectionPayloadAccumulator accumulator, final int sectionY
            , final int globalBits) throws IOException {
        final int blockCount = (int) readBits(reader, CisConstants.BLOCK_COUNT_BITS, "sparse block count");

        if (blockCount == CisConstants.UNIFORM_SECTION_SENTINEL) {
            readBits(reader, globalBits, "uniform state");
            accumulator.addUniform(sectionY, 1L + CisConstants.BLOCK_COUNT_BITS + globalBits);
            return;
        }

        if (blockCount == CisConstants.DEFAULT_SPARSE_SECTION_SENTINEL) {
            readBits(reader, globalBits, "default sparse state");
            final int exceptionCount = (int) readBits(reader, CisConstants.BLOCK_COUNT_BITS, "default sparse " +
                    "exception count");
            skipSparseEntries(reader, exceptionCount, globalBits, "default sparse exception");
            accumulator.addDefaultSparse(sectionY, exceptionCount);
            return;
        }

        skipSparseEntries(reader, blockCount, globalBits, "sparse entry");
        accumulator.addSparse(sectionY, blockCount);
    }

    /**
     * Inspects dense sections, reading the local palette size, palette entries, and block indices.
     *
     * @param reader      bitstream reader
     * @param accumulator target section accumulator
     * @param sectionY    the section's vertical Y coordinate
     * @param globalBits  global palette bits count
     * @param paletteBits palette size bit count
     * @throws IOException if reading the bitstream fails
     */
    private static void inspectDensePayloadSection(final BitReader reader,
            final ChunkSectionPayloadAccumulator accumulator,
            final int sectionY, final int globalBits, final int paletteBits)
            throws IOException {
        final int localSize = (int) readBits(reader, paletteBits, "local palette size");
        for (int entry = 0; entry < localSize; entry++) {
            readBits(reader, globalBits, "local palette entry");
        }

        final int bitsPerBlock = calculateBitsNeeded(localSize + 1);
        for (int block = 0; block < SECTION_BLOCKS; block++) {
            readBits(reader, bitsPerBlock, "dense block palette index");
        }

        accumulator.addDense(sectionY, localSize, bitsPerBlock, paletteBits);
    }

    /**
     * Skips sparse entry offsets and state lookups from the bitstream.
     *
     * @param reader     bitstream reader
     * @param count      number of sparse entries to skip
     * @param globalBits global palette bits count
     * @param label      diagnostic tag label
     * @throws IOException if reading the bitstream fails
     */
    private static void skipSparseEntries(final BitReader reader, final int count, final int globalBits,
            final String label) throws IOException {
        for (int entry = 0; entry < count; entry++) {
            readBits(reader, SPARSE_ENTRY_POSITION_BITS, label + " position");
            readBits(reader, globalBits, label + " state");
        }
    }

    /**
     * Calculates the minimum number of bits required to store an integer value up to {@code maxValue}.
     *
     * @param maxValue the maximum integer value
     * @return the number of bits needed (e.g. 0 for values <= 1)
     */
    public static int calculateBitsNeeded(final int maxValue) {
        if (maxValue <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(maxValue - 1);
    }

    /**
     * Validates and returns the checked byte count to prevent buffer overflows or negative allocation.
     *
     * @param count         number of elements
     * @param bytesPerEntry byte size of each element
     * @param field         name of the field being validated
     * @return total bytes as a safe integer
     * @throws IOException if count is negative or the total byte size exceeds {@link Integer#MAX_VALUE}
     */
    public static int checkedByteCount(final int count, final int bytesPerEntry, final String field)
            throws IOException {
        if (count < 0) {
            throw new IOException("Negative " + field + " count: " + count);
        }

        final long bytes = (long) count * bytesPerEntry;
        if (bytes > Integer.MAX_VALUE) {
            throw new IOException(field + " is too large: " + bytes + " bytes");
        }
        return (int) bytes;
    }

    /**
     * Reads a specified number of bits from the bit reader, throwing an IOException on stream issues.
     *
     * @param reader the bit reader, must not be null
     * @param bits   number of bits to read (0 to 64)
     * @param field  the field label for error tracing
     * @return the read bits as a long
     * @throws IOException if the bitstream is malformed or hits an EOF
     */
    public static long readBits(final BitReader reader, final int bits, final String field) throws IOException {
        if (bits == 0) {
            return 0L;
        }

        try {
            return reader.read(bits);
        } catch (final RuntimeException e) {
            throw new IOException("Malformed CIS section bitstream while reading " + field, e);
        }
    }

    /**
     * Reads a ZigZag-encoded signed value from the bit reader.
     *
     * @param reader the bit reader, must not be null
     * @param bits   number of bits to decode
     * @param field  the field label for error tracing
     * @return the decoded signed integer
     * @throws IOException if the bitstream is malformed or hits an EOF
     */
    public static int readZigZag(final BitReader reader, final int bits, final String field) throws IOException {
        try {
            return reader.readZigZag(bits);
        } catch (final RuntimeException e) {
            throw new IOException("Malformed CIS section bitstream while reading " + field, e);
        }
    }

    /**
     * Determines the chunk-wide encoding classification based on the types of section encodings encountered.
     *
     * @param sawUniform       whether any section used uniform encoding
     * @param sawDefaultSparse whether any section used default-sparse encoding
     * @param sawSparse        whether any section used sparse encoding
     * @param sawDense         whether any section used dense encoding
     * @return the classified ChunkEncodingKind
     */
    private static ChunkEncodingKind chunkEncodingKind(final boolean sawUniform, final boolean sawDefaultSparse,
            final boolean sawSparse, final boolean sawDense) {
        final int kinds = (sawUniform ? 1 : 0) + (sawDefaultSparse ? 1 : 0) + (sawSparse ? 1 : 0) + (sawDense ? 1 : 0);
        if (kinds == 0) {
            return ChunkEncodingKind.EMPTY;
        }
        if (kinds > 1) {
            return ChunkEncodingKind.MIXED;
        }
        if (sawDense) {
            return ChunkEncodingKind.DENSE_ONLY;
        }
        if (sawSparse) {
            return ChunkEncodingKind.SPARSE_ONLY;
        }
        if (sawDefaultSparse) {
            return ChunkEncodingKind.DEFAULT_SPARSE_ONLY;
        }
        return ChunkEncodingKind.UNIFORM_ONLY;
    }

    /**
     * Reads bytes fully from a file channel into a target byte buffer.
     *
     * @param channel  the file channel to read from, must not be null
     * @param buffer   the destination byte buffer, must not be null
     * @param position starting position in the file channel
     * @throws IOException if the channel hits an unexpected EOF or fails to make progress
     */
    public static void readFully(final FileChannel channel, final ByteBuffer buffer, final long position)
            throws IOException {
        long current = position;
        while (buffer.hasRemaining()) {
            final int read = channel.read(buffer, current);
            if (read < 0) {
                throw new IOException("Unexpected EOF while reading " + channel);
            }
            if (read == 0) {
                throw new IOException("Read made no progress while reading " + channel);
            }
            current += read;
        }
    }

    /**
     * Utility class for reading and parsing Region files.
     */
    public static final class RegionFileReader {

        /**
         * Private constructor to prevent RegionFileReader utility class instantiation.
         */
        private RegionFileReader() {
        }

        /**
         * Parses region coordinates from a region filename matching {@code r.x.z.cis}.
         *
         * @param regionPath path to the region file, must not be null
         * @return region coordinates containing x and z
         * @throws IOException if the filename is unexpected or coordinate integers are out of range
         */
        public static RegionCoordinates parseCoordinates(final Path regionPath) throws IOException {
            final Matcher matcher = REGION_FILE_PATTERN.matcher(regionPath.getFileName()
                    .toString());
            if (!matcher.matches()) {
                throw new IOException("Unexpected region filename: " + regionPath.getFileName());
            }

            try {
                return new RegionCoordinates(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            } catch (final NumberFormatException e) {
                throw new IOException("Region coordinates out of integer range: " + regionPath.getFileName(), e);
            }
        }

        /**
         * Validates that a chunk's data range falls within safe file bounds.
         *
         * @param regionPath path to the region file
         * @param offset     starting byte offset of the chunk
         * @param length     byte length of the chunk
         * @param fileBytes  total physical size of the region file
         * @throws IOException if the offset or length falls outside the file bounds
         */
        public static void validateChunkRange(final Path regionPath, final int offset, final int length,
                final long fileBytes) throws IOException {
            final long end = (long) offset + length;
            if (offset < HEADER_BYTES || end > fileBytes) {
                throw new IOException("Invalid chunk range in " + regionPath.getFileName() + ": offset=" + offset +
                        ", length=" + length + ", fileBytes=" + fileBytes);
            }
        }

        /**
         * Reads raw chunk bytes from a file channel at a given offset and length.
         *
         * @param channel file channel to read from, must not be null
         * @param offset  starting byte offset of the chunk
         * @param length  length of the chunk in bytes
         * @return a byte array containing the chunk's payload
         * @throws IOException if reading from the channel fails
         */
        public static byte[] readChunkBytes(final FileChannel channel, final int offset, final int length)
                throws IOException {
            final ByteBuffer buffer = ByteBuffer.allocate(length);
            readFully(channel, buffer, offset);
            return buffer.array();
        }

        /**
         * Decompresses a raw chunk payload using Zstd decompression.
         *
         * @param compressed the compressed payload bytes
         * @return the decompressed payload bytes
         * @throws IOException if decompression fails
         */
        public static byte[] decompressChunkPayload(final byte[] compressed) throws IOException {
            return CisCompression.decompressZstd(compressed);
        }
    }

    /**
     * Accumulator tracking parsed section statistics for a single chunk payload scan.
     */
    private static final class ChunkSectionPayloadAccumulator {

        /**
         * The global bits parameter computed from palette sizes.
         */
        private final int globalBits;

        /**
         * List of generated section payload diagnostics reports.
         */
        private final List<SectionPayloadDiagnostics> sectionReports;

        /**
         * Count of uniform-encoded sections found.
         */
        private int uniformSections;

        /**
         * Count of default-sparse encoded sections found.
         */
        private int defaultSparseSections;

        /**
         * Count of sparse-encoded sections found.
         */
        private int sparseSections;

        /**
         * Count of dense-encoded sections found.
         */
        private int denseSections;

        /**
         * Total bit size of uniform sections.
         */
        private long uniformBits;

        /**
         * Total bit size of default-sparse sections.
         */
        private long defaultSparseBits;

        /**
         * Total bit size of sparse sections.
         */
        private long sparseBits;

        /**
         * Total bit size of dense sections.
         */
        private long denseBits;

        /**
         * Flag indicating if any uniform sections were found.
         */
        private boolean sawUniform;

        /**
         * Flag indicating if any default-sparse sections were found.
         */
        private boolean sawDefaultSparse;

        /**
         * Flag indicating if any sparse sections were found.
         */
        private boolean sawSparse;

        /**
         * Flag indicating if any dense sections were found.
         */
        private boolean sawDense;

        /**
         * Class constructor.
         *
         * @param sectionCount expected total section count
         * @param globalBits   bits reference size
         */
        private ChunkSectionPayloadAccumulator(final int sectionCount, final int globalBits) {
            this.globalBits = globalBits;
            this.sectionReports = new ArrayList<>(sectionCount);
        }

        /**
         * Adds uniform section metrics to the accumulator.
         *
         * @param sectionY    vertical section position
         * @param encodedBits size in bits
         */
        private void addUniform(final int sectionY, final long encodedBits) {
            uniformSections++;
            uniformBits += encodedBits;
            sawUniform = true;
            sectionReports.add(new SectionPayloadDiagnostics(sectionY, SectionEncodingKind.UNIFORM, encodedBits, 0, 0));
        }

        /**
         * Adds default-sparse section metrics to the accumulator.
         *
         * @param sectionY       vertical section position
         * @param exceptionCount exceptions count
         */
        private void addDefaultSparse(final int sectionY, final int exceptionCount) {
            final long encodedBits =
                    1L + CisConstants.BLOCK_COUNT_BITS + globalBits + CisConstants.BLOCK_COUNT_BITS + (
                            (long) exceptionCount * (SPARSE_ENTRY_POSITION_BITS + globalBits));
            defaultSparseSections++;
            defaultSparseBits += encodedBits;
            sawDefaultSparse = true;
            sectionReports.add(new SectionPayloadDiagnostics(sectionY, SectionEncodingKind.DEFAULT_SPARSE,
                    encodedBits, 0, 0));
        }

        /**
         * Adds sparse section metrics to the accumulator.
         *
         * @param sectionY   vertical section position
         * @param blockCount total block states count
         */
        private void addSparse(final int sectionY, final int blockCount) {
            final long encodedBits =
                    1L + CisConstants.BLOCK_COUNT_BITS + ((long) blockCount * (SPARSE_ENTRY_POSITION_BITS
                            + globalBits));
            sparseSections++;
            sparseBits += encodedBits;
            sawSparse = true;
            sectionReports.add(new SectionPayloadDiagnostics(sectionY, SectionEncodingKind.SPARSE, encodedBits, 0, 0));
        }

        /**
         * Adds dense section metrics to the accumulator.
         *
         * @param sectionY     vertical section position
         * @param localSize    local palette size
         * @param bitsPerBlock bits per block configuration
         * @param paletteBits  palette size bit width
         */
        private void addDense(final int sectionY, final int localSize, final int bitsPerBlock, final int paletteBits) {
            final long encodedBits =
                    1L + paletteBits + ((long) localSize * globalBits) + ((long) SECTION_BLOCKS * bitsPerBlock);
            denseSections++;
            denseBits += encodedBits;
            sawDense = true;
            sectionReports.add(new SectionPayloadDiagnostics(sectionY, SectionEncodingKind.DENSE, encodedBits,
                    localSize, bitsPerBlock));
        }

        /**
         * Returns an immutable copy of accumulated section diagnostic reports.
         *
         * @return section diagnostics list
         */
        private List<SectionPayloadDiagnostics> sectionReports() {
            return List.copyOf(sectionReports);
        }
    }

    /**
     * Bounded wrapper over a ByteBuffer to parse field boundaries securely.
     */
    private record PayloadCursor(ByteBuffer buffer) {

        /**
         * Convenience constructor wrapping a byte array.
         *
         * @param buffer source byte array
         */
        private PayloadCursor(final byte[] buffer) {
            this(ByteBuffer.wrap(buffer));
        }

        /**
         * Reads a 4-byte integer from the payload cursor.
         *
         * @param field field debug description label
         * @return parsed integer
         * @throws IOException if bounds check fails
         */
        private int readInt(final String field) throws IOException {
            require(Integer.BYTES, field);
            return buffer.getInt();
        }

        /**
         * Reads a non-negative 4-byte integer from the payload cursor.
         *
         * @param field field debug description label
         * @return parsed integer
         * @throws IOException if bounds check fails or parsed integer is negative
         */
        private int readNonNegativeInt(final String field) throws IOException {
            final int value = readInt(field);
            if (value < 0) {
                throw new IOException("Negative " + field + ": " + value);
            }
            return value;
        }

        /**
         * Reads an unsigned 2-byte short integer from the payload cursor.
         *
         * @param field field debug description label
         * @return parsed short value converted to int
         * @throws IOException if bounds check fails
         */
        @SuppressWarnings("SameParameterValue")
        private int readUnsignedShort(final String field) throws IOException {
            require(Short.BYTES, field);
            return buffer.getShort() & 0xFFFF;
        }

        /**
         * Reads a sequence of bytes from the cursor into a new byte array.
         *
         * @param length number of bytes to read
         * @param field  field debug description label
         * @return new byte array holding read bytes
         * @throws IOException if bounds check fails
         */
        @SuppressWarnings("SameParameterValue")
        private byte[] readBytes(final int length, final String field) throws IOException {
            require(length, field);
            final byte[] bytes = new byte[length];
            buffer.get(bytes);
            return bytes;
        }

        /**
         * Skips a specified count of bytes in the cursor stream.
         *
         * @param bytes number of bytes to skip
         * @param field field debug description label
         * @throws IOException if bounds check fails
         */
        private void skip(final int bytes, final String field) throws IOException {
            require(bytes, field);
            buffer.position(buffer.position() + bytes);
        }

        /**
         * Asserts that the cursor buffer contains at least the specified number of bytes remaining.
         *
         * @param bytes number of bytes required
         * @param field field description label
         * @throws IOException if remaining bytes are insufficient or request count is negative
         */
        private void require(final int bytes, final String field) throws IOException {
            if (bytes < 0) {
                throw new IOException("Negative byte count while reading " + field + ": " + bytes);
            }
            if (buffer.remaining() < bytes) {
                throw new IOException("Malformed CIS payload: expected " + bytes + " bytes for " + field + " but " +
                        "only" + " " + buffer.remaining() + " bytes remain");
            }
        }
    }
}
