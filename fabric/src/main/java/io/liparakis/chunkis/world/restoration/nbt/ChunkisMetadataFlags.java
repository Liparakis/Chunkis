package io.liparakis.chunkis.world.restoration.nbt;

import net.minecraft.nbt.NbtCompound;

/**
 * Reads boolean flags from the nested persisted Chunkis metadata envelope.
 *
 * <p>This is separate from synthetic load markers and separate from the broader
 * chunk-NBT construction helpers.</p>
 */
final class ChunkisMetadataFlags {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkisMetadataFlags() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads the suppress initial repopulation flag from chunk metadata.
     *
     * @param chunkMetadata chunk metadata NBT compound
     * @return Boolean value or null if not found
     */
    static Boolean readSuppressInitialRepopulationFlag(final NbtCompound chunkMetadata) {
        return readChunkisBooleanFlag(
                chunkMetadata,
                CisNbtUtil.SUPPRESS_INITIAL_REPOPULATION_KEY
        );
    }

    /**
     * Reads the full block baseline flag from chunk metadata.
     *
     * @param chunkMetadata chunk metadata NBT compound
     * @return Boolean value or null if not found
     */
    static Boolean readFullBlockBaselineFlag(final NbtCompound chunkMetadata) {
        return readChunkisBooleanFlag(
                chunkMetadata,
                CisNbtUtil.FULL_BLOCK_BASELINE_KEY
        );
    }

    /**
     * Reads the portal chunk flag from chunk metadata.
     *
     * @param chunkMetadata chunk metadata NBT compound
     * @return Boolean value or null if not found
     */
    static Boolean readPortalChunkFlag(final NbtCompound chunkMetadata) {
        return readChunkisBooleanFlag(
                chunkMetadata,
                CisNbtUtil.PORTAL_CHUNK_KEY
        );
    }

    /**
     * Reads boolean flags from the nested chunkis metadata envelope.
     *
     * @param chunkMetadata chunk metadata NBT compound
     * @param key           NBT key mapping
     * @return Boolean value or null if not found
     */
    private static Boolean readChunkisBooleanFlag(
            final NbtCompound chunkMetadata,
            final String key
    ) {
        final NbtCompound chunkisMetadata =
                CisNbtUtil.getCompoundOrNull(chunkMetadata, CisNbtUtil.CHUNKIS_METADATA_KEY);

        if (chunkisMetadata == null || !chunkisMetadata.contains(key)) {
            return null;
        }

        return chunkisMetadata.getBoolean(key)
                .orElse(null);
    }
}
