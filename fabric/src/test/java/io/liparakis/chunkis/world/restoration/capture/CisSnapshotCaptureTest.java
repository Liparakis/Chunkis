package io.liparakis.chunkis.world.restoration.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

class CisSnapshotCaptureTest {

    @Test
    void convertsSectionIndexToAbsoluteBlockY() {
        assertEquals(-64, CisSnapshotCapture.toWorldY(-64, 0, 0));
        assertEquals(-49, CisSnapshotCapture.toWorldY(-64, 0, 15));
        assertEquals(-48, CisSnapshotCapture.toWorldY(-64, 1, 0));
        assertEquals(319, CisSnapshotCapture.toWorldY(-64, 23, 15));
    }

    @Test
    void detectsSuspiciousBaselineShrink() {
        assertTrue(CisSnapshotCapture.isSuspiciousBaselineShrink(1000, 599));
        assertFalse(CisSnapshotCapture.isSuspiciousBaselineShrink(1000, 600));
        assertFalse(CisSnapshotCapture.isSuspiciousBaselineShrink(0, 0));
    }

    @Test
    void authoritativeSnapshotMetadataDropsPersistedBaseAndMarksFullBaseline() {
        final NbtCompound structures = new NbtCompound();
        structures.put(CisNbtUtil.STRUCTURE_REFERENCES_KEY, new NbtCompound());
        final NbtCompound existingMetadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                structures,
                true,
                false,
                new NbtCompound(),
                true
        );

        final NbtCompound metadata = CisSnapshotCapture.createAuthoritativeSnapshotMetadata(existingMetadata, false);

        assertTrue(CisNbtUtil.hasFullBlockBaseline(metadata));
        assertNull(CisNbtUtil.extractPersistedBaseChunkNbt(metadata));
        assertEquals(structures, CisNbtUtil.extractPersistedStructureMetadata(metadata));
        assertFalse(CisNbtUtil.hasPersistedPortalChunk(metadata));
    }
}
