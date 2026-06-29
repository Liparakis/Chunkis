package io.liparakis.chunkis.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.liparakis.chunkis.command.report.CisPayloadDiagnosticsReader;
import io.liparakis.chunkis.command.report.StorageReportModels.ChunkEncodingKind;
import io.liparakis.chunkis.command.report.StorageReportModels.ChunkPayloadDiagnostics;
import io.liparakis.chunkis.command.report.StorageReportModels.SectionPayloadDiagnostics;
import io.liparakis.chunkis.storage.bits.BitWriter;
import io.liparakis.chunkis.storage.model.CisConstants;
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
     */
    private static byte[] buildChunkPayload() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            data.writeInt(CisConstants.MAGIC);
            data.writeInt(CisConstants.VERSION);

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
            for (int i = 0; i < 4096; i++) {
                writer.write(1, 2);
            }

            writer.flush();
            final byte[] sectionData = writer.toByteArray();

            data.writeShort(4);
            data.writeInt(sectionData.length);
            data.write(sectionData);

            data.writeInt(2);
        }

        return out.toByteArray();
    }

    @Test
    void inspectsSectionEncodingsAndBlockEntitiesFromRawChunkPayload() throws Exception {
        final byte[] payload = buildChunkPayload();

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
        assertEquals(4, diagnostics.sections().size());
        final SectionPayloadDiagnostics denseSection = diagnostics.sections().get(3);
        assertEquals(7, denseSection.sectionY());
        assertEquals(2, denseSection.localPaletteSize());
        assertEquals(2, denseSection.bitsPerBlock());
    }
}
