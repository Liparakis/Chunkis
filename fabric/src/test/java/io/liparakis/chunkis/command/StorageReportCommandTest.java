package io.liparakis.chunkis.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.liparakis.chunkis.command.report.CisPayloadDiagnosticsReader;
import io.liparakis.chunkis.command.report.StorageReportModels.ChunkEncodingKind;
import io.liparakis.chunkis.command.report.StorageReportModels.ChunkPayloadDiagnostics;
import io.liparakis.chunkis.command.report.StorageReportModels.SectionPayloadDiagnostics;
import io.liparakis.chunkis.core.bits.BitWriter;
import io.liparakis.chunkis.core.model.CisConstants;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Verifies raw payload diagnostics used by {@link StorageReportCommand}.
 */
class StorageReportCommandTest {

    /**
     * Builds a minimal raw CIS payload that exercises uniform, sparse,
     * default-sparse, and dense section accounting.
     *
     * @param version CIS version to write; from {@link CisConstants#CODED_DENSE_MIN_VERSION} on, the dense
     *                section's indices go to the byte stream after the section bits
     * @return a byte array containing the raw chunk payload
     * @throws Exception if payload generation fails
     */
    private static byte[] buildChunkPayload(final int version) throws Exception {
        final boolean byteDense = version >= CisConstants.CODED_DENSE_MIN_VERSION;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            data.writeInt(CisConstants.MAGIC);
            data.writeInt(version);

            data.writeInt(3);
            data.writeShort(0);
            data.writeShort(1);
            data.writeShort(2);
            data.writeInt(0);

            final BitWriter writer = new BitWriter(2048);

            writer.writeZigZag(4, CisConstants.SECTION_Y_BITS);
            writer.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
            writer.write(CisConstants.UNIFORM_SECTION_SENTINEL, CisConstants.BLOCK_COUNT_BITS);
            writer.write(1, 2);

            writer.writeZigZag(5, CisConstants.SECTION_Y_BITS);
            writer.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
            writer.write(2, CisConstants.BLOCK_COUNT_BITS);
            writer.write(0, 12);
            writer.write(2, 2);
            writer.write(1, 12);
            writer.write(2, 2);

            writer.writeZigZag(6, CisConstants.SECTION_Y_BITS);
            writer.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
            writer.write(CisConstants.DEFAULT_SPARSE_SECTION_SENTINEL, CisConstants.BLOCK_COUNT_BITS);
            writer.write(1, 2);
            writer.write(2, CisConstants.BLOCK_COUNT_BITS);
            writer.write(0, 12);
            writer.write(0, 2);
            writer.write(1, 12);
            writer.write(2, 2);

            writer.writeZigZag(7, CisConstants.SECTION_Y_BITS);
            writer.write(CisConstants.SECTION_ENCODING_DENSE, 1);
            writer.write(2, CisConstants.PALETTE_SIZE_BITS);
            writer.write(1, 2);
            writer.write(2, 2);
            if (byteDense) {
                writer.write(0, 1);
            }
            for (int i = 0; !byteDense && i < 4096; i++) {
                writer.write(1, 2);
            }

            writer.flush();
            final byte[] sectionData = writer.toByteArray();

            data.writeShort(4);
            data.writeInt(sectionData.length);
            data.write(sectionData);
            if (byteDense) {
                data.writeInt(4096);
                for (int i = 0; i < 4096; i++) {
                    data.writeByte(1);
                }
                data.writeInt(0);
            }

            data.writeInt(2);
        }

        return out.toByteArray();
    }

    /**
     * Tests that raw CIS payload diagnostics are correctly inspected, including
     * section encodings, block entity counts, and bits per block calculations.
     *
     * @throws Exception if payload build or inspection fails
     */
    @Test
    void inspectsSectionEncodingsAndBlockEntitiesFromRawChunkPayload() throws Exception {
        assertDiagnostics(buildChunkPayload(CisConstants.CODED_DENSE_MIN_VERSION - 1));
    }

    /**
     * Tests the same accounting on a payload whose dense section stores its indices in the byte stream.
     *
     * @throws Exception if payload build or inspection fails
     */
    @Test
    void inspectsByteAlignedDenseSectionsFromCurrentPayload() throws Exception {
        assertDiagnostics(buildChunkPayload(CisConstants.VERSION));
    }

    /**
     * Inspects a payload built by {@link #buildChunkPayload} and checks every count.
     *
     * @param payload raw chunk payload
     * @throws Exception if inspection fails
     */
    private static void assertDiagnostics(final byte[] payload) throws Exception {

        final ChunkPayloadDiagnostics diagnostics =
                CisPayloadDiagnosticsReader.inspectChunkPayload(payload);

        assertEquals(4, diagnostics.totalSections());
        assertEquals(1, diagnostics.uniformSections());
        assertEquals(1, diagnostics.sparseSections());
        assertEquals(1, diagnostics.denseSections());
        assertEquals(1, diagnostics.defaultSparseSections());
        assertEquals(2, diagnostics.blockEntities());
        assertEquals(ChunkEncodingKind.MIXED, diagnostics.chunkEncodingKind());
        assertEquals(16, diagnostics.uniformSectionBits());
        assertEquals(42, diagnostics.sparseSectionBits());
        assertEquals(8209, diagnostics.denseSectionBits());
        assertEquals(57, diagnostics.defaultSparseSectionBits());
        assertEquals(2, diagnostics.globalBits());
        assertEquals(4,
                diagnostics.sections()
                        .size());
        final SectionPayloadDiagnostics denseSection = diagnostics.sections()
                .get(3);
        assertEquals(7, denseSection.sectionY());
        assertEquals(2, denseSection.localPaletteSize());
        assertEquals(2, denseSection.bitsPerBlock());
    }
}
