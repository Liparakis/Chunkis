package io.liparakis.chunkis.command.report;

import io.liparakis.chunkis.command.report.StorageReportModels.ChunkEncodingKind;
import io.liparakis.chunkis.command.report.StorageReportModels.ChunkPayloadDiagnostics;
import io.liparakis.chunkis.command.report.StorageReportModels.RegionCoordinates;
import io.liparakis.chunkis.command.report.StorageReportModels.SectionEncodingKind;
import io.liparakis.chunkis.command.report.StorageReportModels.SectionPayloadDiagnostics;
import io.liparakis.chunkis.storage.bits.BitReader;
import io.liparakis.chunkis.storage.io.CisCompression;
import io.liparakis.chunkis.storage.model.CisConstants;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CisPayloadDiagnosticsReader {

    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cis");
    private static final int HEADER_BYTES = 8192;
    private static final int SPARSE_ENTRY_POSITION_BITS = 12;
    private static final int SECTION_BLOCKS = 4096;

    private CisPayloadDiagnosticsReader() {
        throw new AssertionError("Utility class");
    }

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

    private static void skipSparseEntries(final BitReader reader, final int count, final int globalBits,
                                          final String label) throws IOException {
        for (int entry = 0; entry < count; entry++) {
            readBits(reader, SPARSE_ENTRY_POSITION_BITS, label + " position");
            readBits(reader, globalBits, label + " state");
        }
    }

    public static int calculateBitsNeeded(final int maxValue) {
        if (maxValue <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(maxValue - 1);
    }

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

    public static int readZigZag(final BitReader reader, final int bits, final String field) throws IOException {
        try {
            return reader.readZigZag(bits);
        } catch (final RuntimeException e) {
            throw new IOException("Malformed CIS section bitstream while reading " + field, e);
        }
    }

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

    public static final class RegionFileReader {

        private RegionFileReader() {
        }

        public static RegionCoordinates parseCoordinates(final Path regionPath) throws IOException {
            final Matcher matcher = REGION_FILE_PATTERN.matcher(regionPath.getFileName().toString());
            if (!matcher.matches()) {
                throw new IOException("Unexpected region filename: " + regionPath.getFileName());
            }

            try {
                return new RegionCoordinates(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            } catch (final NumberFormatException e) {
                throw new IOException("Region coordinates out of integer range: " + regionPath.getFileName(), e);
            }
        }

        public static void validateChunkRange(final Path regionPath, final int offset, final int length,
                                              final long fileBytes) throws IOException {
            final long end = (long) offset + length;
            if (offset < HEADER_BYTES || end > fileBytes) {
                throw new IOException("Invalid chunk range in " + regionPath.getFileName() + ": offset=" + offset +
                                              ", length=" + length + ", fileBytes=" + fileBytes);
            }
        }

        public static byte[] readChunkBytes(final FileChannel channel, final int offset, final int length)
                throws IOException {
            final ByteBuffer buffer = ByteBuffer.allocate(length);
            readFully(channel, buffer, offset);
            return buffer.array();
        }

        public static byte[] decompressChunkPayload(final byte[] compressed) throws IOException {
            return CisCompression.decompressZstd(compressed);
        }
    }

    private static final class ChunkSectionPayloadAccumulator {

        private final int globalBits;
        private final List<SectionPayloadDiagnostics> sectionReports;
        private int uniformSections;
        private int defaultSparseSections;
        private int sparseSections;
        private int denseSections;
        private long uniformBits;
        private long defaultSparseBits;
        private long sparseBits;
        private long denseBits;
        private boolean sawUniform;
        private boolean sawDefaultSparse;
        private boolean sawSparse;
        private boolean sawDense;

        private ChunkSectionPayloadAccumulator(final int sectionCount, final int globalBits) {
            this.globalBits = globalBits;
            this.sectionReports = new ArrayList<>(sectionCount);
        }

        private void addUniform(final int sectionY, final long encodedBits) {
            uniformSections++;
            uniformBits += encodedBits;
            sawUniform = true;
            sectionReports.add(new SectionPayloadDiagnostics(sectionY, SectionEncodingKind.UNIFORM, encodedBits, 0, 0));
        }

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

        private void addSparse(final int sectionY, final int blockCount) {
            final long encodedBits =
                    1L + CisConstants.BLOCK_COUNT_BITS + ((long) blockCount * (SPARSE_ENTRY_POSITION_BITS
                            + globalBits));
            sparseSections++;
            sparseBits += encodedBits;
            sawSparse = true;
            sectionReports.add(new SectionPayloadDiagnostics(sectionY, SectionEncodingKind.SPARSE, encodedBits, 0, 0));
        }

        private void addDense(final int sectionY, final int localSize, final int bitsPerBlock, final int paletteBits) {
            final long encodedBits =
                    1L + paletteBits + ((long) localSize * globalBits) + ((long) SECTION_BLOCKS * bitsPerBlock);
            denseSections++;
            denseBits += encodedBits;
            sawDense = true;
            sectionReports.add(new SectionPayloadDiagnostics(sectionY, SectionEncodingKind.DENSE, encodedBits,
                                                             localSize, bitsPerBlock));
        }

        private List<SectionPayloadDiagnostics> sectionReports() {
            return List.copyOf(sectionReports);
        }
    }

    private record PayloadCursor(ByteBuffer buffer) {

        private PayloadCursor(final byte[] buffer) {
            this(ByteBuffer.wrap(buffer));
        }

        private int readInt(final String field) throws IOException {
            require(Integer.BYTES, field);
            return buffer.getInt();
        }

        private int readNonNegativeInt(final String field) throws IOException {
            final int value = readInt(field);
            if (value < 0) {
                throw new IOException("Negative " + field + ": " + value);
            }
            return value;
        }

        private int readUnsignedShort(final String field) throws IOException {
            require(Short.BYTES, field);
            return buffer.getShort() & 0xFFFF;
        }

        private byte[] readBytes(final int length, final String field) throws IOException {
            require(length, field);
            final byte[] bytes = new byte[length];
            buffer.get(bytes);
            return bytes;
        }

        private void skip(final int bytes, final String field) throws IOException {
            require(bytes, field);
            buffer.position(buffer.position() + bytes);
        }

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
