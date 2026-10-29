package io.liparakis.chunkis.world.restoration.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

/**
 * Test class for verifying chunk snapshot capture logic in {@link CisSnapshotCapture}.
 */
class CisSnapshotCaptureTest {

    /**
     * Verifies the translation from section index and local Y offset to absolute world Y coordinates.
     */
    @Test
    void convertsSectionIndexToAbsoluteBlockY() {
        assertEquals(-64, CisSnapshotCapture.toWorldY(-64, 0, 0));
        assertEquals(-49, CisSnapshotCapture.toWorldY(-64, 0, 15));
        assertEquals(-48, CisSnapshotCapture.toWorldY(-64, 1, 0));
        assertEquals(319, CisSnapshotCapture.toWorldY(-64, 23, 15));
    }

    /**
     * Verifies detection of suspicious baseline size shrinkage.
     */
    @Test
    void detectsSuspiciousBaselineShrink() {
        assertTrue(CisSnapshotCapture.isSuspiciousBaselineShrink(1000, 599));
        assertFalse(CisSnapshotCapture.isSuspiciousBaselineShrink(1000, 600));
        assertFalse(CisSnapshotCapture.isSuspiciousBaselineShrink(0, 0));
    }

    /**
     * Verifies that creating authoritative snapshot metadata correctly drops the persisted base chunk
     * and marks the chunk as having a full block baseline.
     */
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

        final NbtCompound metadata = CisSnapshotCapture.createAuthoritativeSnapshotMetadata(existingMetadata, false, null, false, null);

        assertTrue(CisNbtUtil.hasFullBlockBaseline(metadata));
        assertNull(CisNbtUtil.extractPersistedBaseChunkNbt(metadata));
        assertEquals(structures, CisNbtUtil.extractPersistedStructureMetadata(metadata));
        assertFalse(CisNbtUtil.hasPersistedPortalChunk(metadata));
    }

    /**
     * Verifies that migrated auxiliary metadata (such as chunk status and post processing flags)
     * is correctly preserved during authoritative snapshot metadata creation.
     */
    @Test
    void authoritativeSnapshotMetadataPreservesMigratedAuxiliaryMetadata() {
        final NbtCompound existingMetadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                null,
                true,
                false,
                null,
                true
        );
        final NbtCompound auxiliary = new NbtCompound();
        auxiliary.putString(CisNbtUtil.STATUS_KEY, "minecraft:full");
        auxiliary.putString("PostProcessing", "kept");
        CisNbtUtil.putPreservedAuxiliaryChunkNbt(existingMetadata, auxiliary);
        CisNbtUtil.markMigratedAuthoritativeChunk(existingMetadata);

        final NbtCompound metadata = CisSnapshotCapture.createAuthoritativeSnapshotMetadata(existingMetadata, false, null, false, null);

        assertTrue(CisNbtUtil.isMigratedAuthoritativeChunk(metadata));
        final NbtCompound preservedAuxiliary = CisNbtUtil.extractPreservedAuxiliaryChunkNbt(metadata);
        assertNotNull(preservedAuxiliary);
        assertEquals("minecraft:full",
                preservedAuxiliary.getString(CisNbtUtil.STATUS_KEY)
                        .orElseThrow());
        assertEquals("kept",
                preservedAuxiliary.getString("PostProcessing")
                        .orElseThrow());
    }

    /**
     * A full-baseline snapshot stores the sections it is given, loads as a finished chunk, and never carries a
     * base chunk forward, whether the previous entry held it as NBT or as a raw payload.
     */
    @Test
    void authoritativeSnapshotMetadataStoresSectionsAndDropsBasePayload() {
        final NbtCompound existingMetadata = CisNbtUtil.createChunkMetadataTakingOwnership(null, true, false, null, false);
        existingMetadata.putByteArray(CisNbtUtil.BASE_CHUNK_PAYLOAD_KEY, new byte[] {1, 2, 3});
        final NbtList sections = oneSection();

        final NbtCompound metadata =
                CisSnapshotCapture.createAuthoritativeSnapshotMetadata(existingMetadata, false, sections, true, null);

        assertEquals(sections, CisNbtUtil.getStoredSections(metadata));
        assertTrue(CisNbtUtil.loadsAsFinishedChunk(metadata));
        assertTrue(CisNbtUtil.loadsWithStoredLight(metadata));
        assertFalse(metadata.contains(CisNbtUtil.BASE_CHUNK_PAYLOAD_KEY));
        assertFalse(CisNbtUtil.hasPersistedBaseChunkNbt(metadata));
    }

    /**
     * An unlit capture must not inherit the stored-light flag of the entry it replaces, or the chunk would load
     * with light that does not match its blocks.
     */
    @Test
    void unlitSnapshotDoesNotInheritStoredLight() {
        final NbtCompound lit =
                CisSnapshotCapture.createAuthoritativeSnapshotMetadata(null, false, oneSection(), true, null);

        final NbtCompound unlit =
                CisSnapshotCapture.createAuthoritativeSnapshotMetadata(lit, false, oneSection(), false, null);

        assertTrue(CisNbtUtil.loadsAsFinishedChunk(unlit));
        assertFalse(CisNbtUtil.loadsWithStoredLight(unlit));
    }

    /**
     * An entry written before sections were stored still loads through terrain generation.
     */
    @Test
    void entryWithoutStoredSectionsDoesNotLoadAsFinishedChunk() {
        final NbtCompound metadata = CisSnapshotCapture.createAuthoritativeSnapshotMetadata(null, false, null, true, null);

        assertFalse(CisNbtUtil.loadsAsFinishedChunk(metadata));
        assertFalse(CisNbtUtil.loadsWithStoredLight(metadata));
    }

    /**
     * A snapshot stores the chunk's pending ticks, and one without pending ticks drops the previous snapshot's,
     * which already ran.
     */
    @Test
    void pendingTicksAreStoredAndNeverCarriedOver() {
        final NbtCompound ticks = new NbtCompound();
        ticks.put("fluid_ticks", new NbtList());
        final NbtCompound withTicks =
                CisSnapshotCapture.createAuthoritativeSnapshotMetadata(null, false, oneSection(), true, ticks);
        assertTrue(withTicks.contains(CisNbtUtil.PENDING_TICKS_KEY));

        final NbtCompound next =
                CisSnapshotCapture.createAuthoritativeSnapshotMetadata(withTicks, false, oneSection(), true, null);
        assertFalse(next.contains(CisNbtUtil.PENDING_TICKS_KEY));
    }

    /**
     * Builds a one-entry section list.
     *
     * @return section list
     */
    private static NbtList oneSection() {
        final NbtList sections = new NbtList();
        final NbtCompound section = new NbtCompound();
        section.putByte("Y", (byte) 0);
        sections.add(section);
        return sections;
    }
}
