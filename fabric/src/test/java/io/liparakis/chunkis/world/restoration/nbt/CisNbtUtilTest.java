package io.liparakis.chunkis.world.restoration.nbt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CisNbtUtil}, covering structure metadata extraction,
 * chunk metadata round-trips, and suppression-flag resolution across the
 * legacy, explicit-true, and explicit-false cases.
 */
class CisNbtUtilTest {

    /**
     * Builds a minimal synthetic chunk root carrying the Chunkis delta marker,
     * suitable for passing to {@link CisNbtUtil#shouldSuppressInitialRepopulation}.
     *
     * @return root compound with {@link CisNbtUtil#HAS_DELTA_KEY} set to {@code true}
     */
    private static CompoundTag buildRootWithDeltaMarker() {
        final CompoundTag root = new CompoundTag();
        final CompoundTag chunkisData = new CompoundTag();
        chunkisData.putBoolean(CisNbtUtil.HAS_DELTA_KEY, true);
        root.put(CisNbtUtil.CHUNKIS_DATA_KEY, chunkisData);
        return root;
    }

    /**
     * Verifies that structure metadata containing only references is
     * correctly extracted from the NBT compound.
     */
    @Test
    void extractsLegacyStructureOnlyMetadata() {
        final CompoundTag structures = new CompoundTag();
        structures.put(CisNbtUtil.STRUCTURE_REFERENCES_KEY, new CompoundTag());
        structures.getCompound(CisNbtUtil.STRUCTURE_REFERENCES_KEY)
                .orElseThrow()
                .putLongArray("minecraft:village", new long[]{1L});

        final CompoundTag extracted = CisNbtUtil.extractPersistedStructureMetadata(structures);

        assertNotNull(extracted);
        assertEquals(structures, extracted);
    }

    /**
     * Verifies that extracting metadata from a null compound returns null.
     */
    @Test
    void returnsNullWhenExtractingFromNullMetadata() {
        assertNull(CisNbtUtil.extractPersistedStructureMetadata(null));
    }

    /**
     * Verifies that the suppression flag is correctly round-trip encoded
     * and decoded in the chunk metadata.
     */
    @Test
    void roundTripsEnvelopeMetadataWithSuppressionFlag() {
        final CompoundTag structures = new CompoundTag();
        structures.put(CisNbtUtil.STRUCTURE_STARTS_KEY, new CompoundTag());
        structures.getCompound(CisNbtUtil.STRUCTURE_STARTS_KEY)
                .orElseThrow()
                .putString("minecraft:village", "start");

        final CompoundTag metadata = CisNbtUtil.createChunkMetadata(structures, true);
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>("air"::equals);
        delta.setChunkMetadata(metadata, false);

        assertEquals(structures, CisNbtUtil.extractPersistedStructureMetadata(metadata));
        assertTrue(CisNbtUtil.shouldSuppressInitialRepopulation(buildRootWithDeltaMarker(), delta));
    }

    /**
     * Verifies that legacy chunks (those missing an explicit suppression flag)
     * default to suppressing repopulation if they carry the delta marker.
     */
    @Test
    void defaultsLegacyChunksWithDeltaMarkerToSuppression() {
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(0, 64, 0, "stone", false);
        delta.setChunkMetadata(new CompoundTag(), false);

        assertTrue(CisNbtUtil.shouldSuppressInitialRepopulation(buildRootWithDeltaMarker(), delta));
    }

    /**
     * Verifies that an explicit {@code false} suppression flag is respected
     * even if the chunk carries the delta marker.
     */
    @Test
    void explicitFalseFlagIsHonored() {
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(0, 64, 0, "stone", false);
        delta.setChunkMetadata(CisNbtUtil.createChunkMetadata(null, false), false);

        assertFalse(CisNbtUtil.shouldSuppressInitialRepopulation(buildRootWithDeltaMarker(), delta));
    }

    /**
     * Verifies that repopulation is NOT suppressed if no delta exists for the chunk.
     */
    @Test
    void returnsFalseWhenNoDeltaExists() {
        assertFalse(CisNbtUtil.shouldSuppressInitialRepopulation(new CompoundTag(), null));
    }

    /**
     * Verifies that {@link CisNbtUtil#buildLoadChunkNbt} uses the persisted base chunk
     * baseline when it is present in the chunk metadata.
     *
     * @throws Exception if an error occurs during test execution
     */
    @Test
    void buildLoadChunkNbtUsesPersistedBaseChunkBaselineWhenPresent() throws Exception {
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>("air"::equals);
        final CompoundTag baseChunk = new CompoundTag();
        baseChunk.putString(CisNbtUtil.STATUS_KEY, "minecraft:full");
        baseChunk.putInt(CisNbtUtil.X_POS_KEY, 3);
        baseChunk.putInt(CisNbtUtil.Z_POS_KEY, 7);

        final CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:pig");
        final ListTag baseEntities = new ListTag();
        baseEntities.add(entity.copy());
        baseChunk.put("entities", baseEntities);
        delta.setEntities(java.util.List.of(entity), false);
        delta.setChunkMetadata(
                CisNbtUtil.createChunkMetadataTakingOwnership(
                        null,
                        true,
                        false,
                        baseChunk
                ),
                false
        );

        assertTrue(delta.getChunkMetadata()
                .contains(CisNbtUtil.BASE_CHUNK_NBT_KEY));
        assertFalse(delta.getChunkMetadata()
                .contains(CisNbtUtil.BASE_CHUNK_PAYLOAD_KEY));

        final CompoundTag packedMetadata = CisMetadataCodec.packPersistedBaseChunkPayload(delta.getChunkMetadata());
        assertTrue(packedMetadata.getByteArray(CisNbtUtil.BASE_CHUNK_PAYLOAD_KEY)
                .orElseThrow().length > 0);
        assertFalse(packedMetadata.contains(CisNbtUtil.BASE_CHUNK_NBT_KEY));

        final CisNbtUtil.LoadChunkNbtResult result =
                CisNbtUtil.buildLoadChunkNbt(3, 7, 3953, delta);

        assertEquals(CisNbtUtil.PersistedBaseChunkUsage.USED, result.baseChunkUsage());
        assertEquals("minecraft:full",
                result.root()
                        .getString(CisNbtUtil.STATUS_KEY)
                        .orElseThrow());
        assertEquals(3,
                result.root()
                        .getInt(CisNbtUtil.X_POS_KEY)
                        .orElseThrow());
        assertEquals(7,
                result.root()
                        .getInt(CisNbtUtil.Z_POS_KEY)
                        .orElseThrow());
        assertTrue(result.root()
                .contains(CisNbtUtil.CHUNKIS_DATA_KEY));
        assertFalse(result.root()
                .contains("entities"));
    }

    /**
     * Verifies that {@link CisNbtUtil#buildLoadChunkNbt} skips the persisted base chunk
     * baseline when the chunk is flagged as an authoritative full baseline.
     */
    @Test
    void buildLoadChunkNbtSkipsPersistedBaseForAuthoritativeFullBaseline() {
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>("air"::equals);
        final CompoundTag baseChunk = new CompoundTag();
        baseChunk.putString("sentinel_block_from_stale_base", "minecraft:spruce_log");

        delta.addBlockChange(0, 64, 0, "oak_planks", false);
        delta.setChunkMetadata(
                CisNbtUtil.createChunkMetadataTakingOwnership(
                        null,
                        true,
                        true,
                        baseChunk
                ),
                false
        );

        final CisNbtUtil.LoadChunkNbtResult result =
                CisNbtUtil.buildLoadChunkNbt(3, 7, 3953, delta);

        assertEquals(CisNbtUtil.PersistedBaseChunkUsage.SKIPPED, result.baseChunkUsage());
        assertEquals(CisNbtUtil.STATUS_EMPTY,
                result.root()
                        .getString(CisNbtUtil.STATUS_KEY)
                        .orElseThrow());
        assertFalse(result.root()
                .contains("sentinel_block_from_stale_base"));
        assertTrue(result.root()
                .contains(CisNbtUtil.CHUNKIS_DATA_KEY));
    }

    /**
     * Verifies that {@link CisNbtUtil#extractPreservedAuxiliaryChunkNbtFromChunkRoot}
     * correctly removes runtime-owned fields from the source compound.
     */
    @Test
    void extractPreservedAuxiliaryChunkNbtRemovesRuntimeOwnedFields() {
        final CompoundTag source = new CompoundTag();
        source.putString(CisNbtUtil.STATUS_KEY, "minecraft:full");
        source.put(CisNbtUtil.STRUCTURES_KEY, new CompoundTag());
        source.put(CisNbtUtil.HEIGHTMAPS_KEY, new CompoundTag());
        source.putBoolean(CisNbtUtil.IS_LIGHT_ON_KEY, true);
        source.put("sections", new ListTag());
        source.put("entities", new ListTag());
        source.put("block_entities", new ListTag());
        source.putString("PostProcessing", "keep-me");

        final CompoundTag preserved = CisNbtUtil.extractPreservedAuxiliaryChunkNbtFromChunkRoot(source);

        assertNotNull(preserved);
        assertEquals("minecraft:full",
                preserved.getString(CisNbtUtil.STATUS_KEY)
                        .orElseThrow());
        assertEquals("keep-me",
                preserved.getString("PostProcessing")
                        .orElseThrow());
        assertFalse(preserved.contains(CisNbtUtil.STRUCTURES_KEY));
        assertFalse(preserved.contains(CisNbtUtil.HEIGHTMAPS_KEY));
        assertFalse(preserved.contains(CisNbtUtil.IS_LIGHT_ON_KEY));
        assertFalse(preserved.contains("sections"));
        assertFalse(preserved.contains("entities"));
        assertFalse(preserved.contains("block_entities"));
    }

    /**
     * Verifies that {@link CisNbtUtil#buildLoadChunkNbt} correctly merges preserved
     * auxiliary metadata for migrated authoritative chunks.
     */
    @Test
    void buildLoadChunkNbtMergesPreservedAuxiliaryMetadataForMigratedChunks() {
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(0, 64, 0, "stone", false);

        final CompoundTag auxiliary = new CompoundTag();
        auxiliary.putString(CisNbtUtil.STATUS_KEY, "minecraft:full");
        auxiliary.putString("PostProcessing", "kept");

        final CompoundTag metadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                null,
                true,
                true,
                null,
                false
        );
        CisNbtUtil.putPreservedAuxiliaryChunkNbt(metadata, auxiliary);
        CisNbtUtil.markMigratedAuthoritativeChunk(metadata);
        delta.setChunkMetadata(metadata, false);

        final CisNbtUtil.LoadChunkNbtResult result =
                CisNbtUtil.buildLoadChunkNbt(8, 9, 3953, delta);

        assertEquals("minecraft:full",
                result.root()
                        .getString(CisNbtUtil.STATUS_KEY)
                        .orElseThrow());
        assertEquals("kept",
                result.root()
                        .getString("PostProcessing")
                        .orElseThrow());
        assertEquals(8,
                result.root()
                        .getInt(CisNbtUtil.X_POS_KEY)
                        .orElseThrow());
        assertEquals(9,
                result.root()
                        .getInt(CisNbtUtil.Z_POS_KEY)
                        .orElseThrow());
    }

    /**
     * Recapture rebuilds the envelope; keys it does not own (migrated marker, unknown
     * root keys, unknown chunkis flags) must survive, while rebuilt keys win.
     */
    @Test
    void preserveCarriedMetadataKeepsUnknownKeysAndRebuiltValues() {
        final CompoundTag previous = CisNbtUtil.createChunkMetadata(null, true);
        CisNbtUtil.markMigratedAuthoritativeChunk(previous);
        previous.putString("future_root_key", "kept");
        previous.getCompound(CisNbtUtil.CHUNKIS_METADATA_KEY)
                .orElseThrow()
                .putInt("future_flag", 7);
        final CompoundTag oldBase = new CompoundTag();
        oldBase.putString("marker", "old-base");
        previous.put(CisNbtUtil.BASE_CHUNK_NBT_KEY, oldBase);

        final CompoundTag rebuilt = CisNbtUtil.createChunkMetadata(null, false);
        CisNbtUtil.preserveCarriedMetadata(previous, rebuilt);

        assertEquals("kept", rebuilt.getString("future_root_key")
                .orElseThrow());
        assertEquals(7, rebuilt.getCompound(CisNbtUtil.CHUNKIS_METADATA_KEY)
                .orElseThrow()
                .getInt("future_flag")
                .orElseThrow());
        assertTrue(CisNbtUtil.isMigratedAuthoritativeChunk(rebuilt));
        assertFalse(rebuilt.getCompound(CisNbtUtil.CHUNKIS_METADATA_KEY)
                        .orElseThrow()
                        .getBoolean(CisNbtUtil.SUPPRESS_INITIAL_REPOPULATION_KEY)
                        .orElseThrow(),
                "rebuilt flag must not be overwritten by the previous value");
        assertFalse(rebuilt.contains(CisNbtUtil.BASE_CHUNK_NBT_KEY),
                "an envelope-owned key absent from the rebuild is an intentional removal");
    }

    /**
     * A save snapshot shares the entries a capture only ever replaces whole, and copies the rest, which may be
     * changed in place after the snapshot was taken.
     */
    @Test
    void snapshotCopySharesOnlyEntriesReplacedWhole() {
        final CompoundTag metadata = new CompoundTag();
        metadata.put(CisNbtUtil.STORED_SECTIONS_KEY, new ListTag());
        metadata.put(CisNbtUtil.STORED_HEIGHTMAPS_KEY, new CompoundTag());
        metadata.put(CisNbtUtil.PENDING_TICKS_KEY, new CompoundTag());
        final CompoundTag flags = new CompoundTag();
        flags.putBoolean(CisNbtUtil.FULL_BLOCK_BASELINE_KEY, true);
        metadata.put(CisNbtUtil.CHUNKIS_METADATA_KEY, flags);

        final CompoundTag copy = CisNbtUtil.copySharingCapturedState(metadata);

        assertNotSame(metadata, copy);
        assertSame(metadata.get(CisNbtUtil.STORED_SECTIONS_KEY), copy.get(CisNbtUtil.STORED_SECTIONS_KEY));
        assertSame(metadata.get(CisNbtUtil.STORED_HEIGHTMAPS_KEY), copy.get(CisNbtUtil.STORED_HEIGHTMAPS_KEY));
        assertSame(metadata.get(CisNbtUtil.PENDING_TICKS_KEY), copy.get(CisNbtUtil.PENDING_TICKS_KEY));
        assertNotSame(flags, copy.get(CisNbtUtil.CHUNKIS_METADATA_KEY), "flags set in place are copied");
        assertEquals(flags, copy.get(CisNbtUtil.CHUNKIS_METADATA_KEY));
        flags.putBoolean(CisNbtUtil.PORTAL_CHUNK_KEY, true);
        assertFalse(copy.getCompound(CisNbtUtil.CHUNKIS_METADATA_KEY).orElseThrow()
                .contains(CisNbtUtil.PORTAL_CHUNK_KEY), "a later change does not reach the snapshot");
    }
}
