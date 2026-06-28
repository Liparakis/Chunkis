package io.liparakis.chunkis.world.restoration.nbt;

import net.minecraft.nbt.NbtCompound;

/**
 * Reads boolean flags from the nested persisted Chunkis metadata envelope.
 *
 * <p>This is separate from synthetic load markers and separate from the broader
 * chunk-NBT construction helpers.</p>
 */
final class ChunkisMetadataFlags {

    private ChunkisMetadataFlags() {
        throw new AssertionError("Utility class");
    }

    static Boolean readSuppressInitialRepopulationFlag(final NbtCompound chunkMetadata) {
        return readChunkisBooleanFlag(
                chunkMetadata,
                CisNbtUtil.SUPPRESS_INITIAL_REPOPULATION_KEY
        );
    }

    static Boolean readFullBlockBaselineFlag(final NbtCompound chunkMetadata) {
        return readChunkisBooleanFlag(
                chunkMetadata,
                CisNbtUtil.FULL_BLOCK_BASELINE_KEY
        );
    }

    static Boolean readPortalChunkFlag(final NbtCompound chunkMetadata) {
        return readChunkisBooleanFlag(
                chunkMetadata,
                CisNbtUtil.PORTAL_CHUNK_KEY
        );
    }

    private static Boolean readChunkisBooleanFlag(
            final NbtCompound chunkMetadata,
            final String key
    ) {
        final NbtCompound chunkisMetadata =
                CisNbtUtil.getCompoundOrNull(chunkMetadata, CisNbtUtil.CHUNKIS_METADATA_KEY);

        if (chunkisMetadata == null || !chunkisMetadata.contains(key)) {
            return null;
        }

        return chunkisMetadata.getBoolean(key).orElse(null);
    }
}
