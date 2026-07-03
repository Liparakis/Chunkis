package io.liparakis.chunkis.world.restoration.nbt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
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
    private static NbtCompound buildRootWithDeltaMarker() {
        final NbtCompound root = new NbtCompound();
        final NbtCompound chunkisData = new NbtCompound();
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
        final NbtCompound structures = new NbtCompound();
        structures.put(CisNbtUtil.STRUCTURE_REFERENCES_KEY, new NbtCompound());
        structures.getCompound(CisNbtUtil.STRUCTURE_REFERENCES_KEY)
                  .orElseThrow()
                  .putLongArray("minecraft:village", new long[]{1L});

        final NbtCompound extracted = CisNbtUtil.extractPersistedStructureMetadata(structures);

        assertNotNull(extracted);
        assertEquals(structures, extracted);
    }

    // -------------------------------------------------------------------------
    // shouldSuppressInitialRepopulation
    // -------------------------------------------------------------------------

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
        final NbtCompound structures = new NbtCompound();
        structures.put(CisNbtUtil.STRUCTURE_STARTS_KEY, new NbtCompound());
        structures.getCompound(CisNbtUtil.STRUCTURE_STARTS_KEY)
                  .orElseThrow()
                  .putString("minecraft:village", "start");

        final NbtCompound metadata = CisNbtUtil.createChunkMetadata(structures, true);
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>("air"::equals);
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
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(0, 64, 0, "stone", false);
        delta.setChunkMetadata(new NbtCompound(), false);

        assertTrue(CisNbtUtil.shouldSuppressInitialRepopulation(buildRootWithDeltaMarker(), delta));
    }

    /**
     * Verifies that an explicit {@code false} suppression flag is respected
     * even if the chunk carries the delta marker.
     */
    @Test
    void explicitFalseFlagIsHonored() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(0, 64, 0, "stone", false);
        delta.setChunkMetadata(CisNbtUtil.createChunkMetadata(null, false), false);

        assertFalse(CisNbtUtil.shouldSuppressInitialRepopulation(buildRootWithDeltaMarker(), delta));
    }

    /**
     * Verifies that repopulation is NOT suppressed if no delta exists for the chunk.
     */
    @Test
    void returnsFalseWhenNoDeltaExists() {
        assertFalse(CisNbtUtil.shouldSuppressInitialRepopulation(new NbtCompound(), null));
    }

    @Test
    void buildLoadChunkNbtUsesPersistedBaseChunkBaselineWhenPresent() throws Exception {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>("air"::equals);
        final NbtCompound baseChunk = new NbtCompound();
        baseChunk.putString(CisNbtUtil.STATUS_KEY, "minecraft:full");
        baseChunk.putInt(CisNbtUtil.X_POS_KEY, 3);
        baseChunk.putInt(CisNbtUtil.Z_POS_KEY, 7);

        final NbtCompound entity = new NbtCompound();
        entity.putString("id", "minecraft:pig");
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

        assertTrue(delta.getChunkMetadata().contains(CisNbtUtil.BASE_CHUNK_NBT_KEY));
        assertFalse(delta.getChunkMetadata().contains(CisNbtUtil.BASE_CHUNK_PAYLOAD_KEY));

        final NbtCompound packedMetadata = CisNbtUtil.packPersistedBaseChunkPayload(delta.getChunkMetadata());
        assertTrue(packedMetadata.getByteArray(CisNbtUtil.BASE_CHUNK_PAYLOAD_KEY).orElseThrow().length > 0);
        assertFalse(packedMetadata.contains(CisNbtUtil.BASE_CHUNK_NBT_KEY));

        final CisNbtUtil.LoadChunkNbtResult result =
                CisNbtUtil.buildLoadChunkNbt(3, 7, 3953, delta);

        assertEquals(CisNbtUtil.PersistedBaseChunkUsage.USED, result.baseChunkUsage());
        assertEquals("minecraft:full", result.root().getString(CisNbtUtil.STATUS_KEY).orElseThrow());
        assertEquals(3, result.root().getInt(CisNbtUtil.X_POS_KEY).orElseThrow());
        assertEquals(7, result.root().getInt(CisNbtUtil.Z_POS_KEY).orElseThrow());
        assertTrue(result.root().contains(CisNbtUtil.CHUNKIS_DATA_KEY));
        final NbtList entities = result.root().getList("entities").orElseThrow();
        assertEquals(1, entities.size());
    }

    // -------------------------------------------------------------------------
    // Shared fixture builder
    // -------------------------------------------------------------------------

    @Test
    void buildLoadChunkNbtSkipsPersistedBaseForAuthoritativeFullBaseline() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>("air"::equals);
        final NbtCompound baseChunk = new NbtCompound();
        baseChunk.putString(CisNbtUtil.STATUS_KEY, "minecraft:full");
        baseChunk.putInt(CisNbtUtil.X_POS_KEY, 3);
        baseChunk.putInt(CisNbtUtil.Z_POS_KEY, 7);
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
        assertEquals(CisNbtUtil.STATUS_EMPTY, result.root().getString(CisNbtUtil.STATUS_KEY).orElseThrow());
        assertFalse(result.root().contains("sentinel_block_from_stale_base"));
        assertTrue(result.root().contains(CisNbtUtil.CHUNKIS_DATA_KEY));
    }

    @Test
    void extractPreservedAuxiliaryChunkNbtRemovesRuntimeOwnedFields() {
        final NbtCompound source = new NbtCompound();
        source.putString(CisNbtUtil.STATUS_KEY, "minecraft:full");
        source.put(CisNbtUtil.STRUCTURES_KEY, new NbtCompound());
        source.put(CisNbtUtil.HEIGHTMAPS_KEY, new NbtCompound());
        source.putBoolean(CisNbtUtil.IS_LIGHT_ON_KEY, true);
        source.put("sections", new NbtList());
        source.put("entities", new NbtList());
        source.put("block_entities", new NbtList());
        source.putString("PostProcessing", "keep-me");

        final NbtCompound preserved = CisNbtUtil.extractPreservedAuxiliaryChunkNbtFromChunkRoot(source);

        assertNotNull(preserved);
        assertEquals("minecraft:full", preserved.getString(CisNbtUtil.STATUS_KEY).orElseThrow());
        assertEquals("keep-me", preserved.getString("PostProcessing").orElseThrow());
        assertFalse(preserved.contains(CisNbtUtil.STRUCTURES_KEY));
        assertFalse(preserved.contains(CisNbtUtil.HEIGHTMAPS_KEY));
        assertFalse(preserved.contains(CisNbtUtil.IS_LIGHT_ON_KEY));
        assertFalse(preserved.contains("sections"));
        assertFalse(preserved.contains("entities"));
        assertFalse(preserved.contains("block_entities"));
    }

    @Test
    void buildLoadChunkNbtMergesPreservedAuxiliaryMetadataForMigratedChunks() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(0, 64, 0, "stone", false);

        final NbtCompound auxiliary = new NbtCompound();
        auxiliary.putString(CisNbtUtil.STATUS_KEY, "minecraft:full");
        auxiliary.putString("PostProcessing", "kept");

        final NbtCompound metadata = CisNbtUtil.createChunkMetadataTakingOwnership(
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

        assertEquals("minecraft:full", result.root().getString(CisNbtUtil.STATUS_KEY).orElseThrow());
        assertEquals("kept", result.root().getString("PostProcessing").orElseThrow());
        assertEquals(8, result.root().getInt(CisNbtUtil.X_POS_KEY).orElseThrow());
        assertEquals(9, result.root().getInt(CisNbtUtil.Z_POS_KEY).orElseThrow());
    }
}


