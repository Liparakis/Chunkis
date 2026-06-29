package io.liparakis.chunkis.world.restoration.nbt;

import net.minecraft.nbt.NbtCompound;

/**
 * Builder for the persisted Chunkis chunk-metadata envelope.
 *
 * <p>This keeps the envelope shape in one place while leaving higher-level load
 * and save decisions in {@link CisNbtUtil}.</p>
 */
final class ChunkMetadataEnvelope {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkMetadataEnvelope() {
        throw new AssertionError("Utility class");
    }

    /**
     * Creates a chunk metadata envelope with structure and baseline configurations.
     *
     * @param structureData               serialized structure metadata
     * @param suppressInitialRepopulation true if initial population should be suppressed
     * @param fullBlockBaseline           true if full block baseline is saved
     * @param baseChunkNbt                optional base chunk NBT compound
     * @param portalChunk                 true if target is portal chunk
     * @return NBT compound containing envelope structure
     */
    static NbtCompound create(
            final NbtCompound structureData,
            final boolean suppressInitialRepopulation,
            final boolean fullBlockBaseline,
            final NbtCompound baseChunkNbt,
            final boolean portalChunk
    ) {
        final NbtCompound metadata = new NbtCompound();

        if (structureData != null && !structureData.isEmpty()) {
            metadata.put(CisNbtUtil.STRUCTURES_KEY, structureData);
        }

        if (baseChunkNbt != null && !baseChunkNbt.isEmpty()) {
            metadata.put(CisNbtUtil.BASE_CHUNK_NBT_KEY, baseChunkNbt);
        }

        metadata.put(
                CisNbtUtil.CHUNKIS_METADATA_KEY,
                createChunkisMetadata(
                        suppressInitialRepopulation,
                        fullBlockBaseline,
                        portalChunk
                )
        );

        return metadata;
    }

    /**
     * Synthesizes the sub-nest mapping boolean chunkis configuration parameters.
     *
     * @param suppressInitialRepopulation suppress population
     * @param fullBlockBaseline           full block baseline
     * @param portalChunk                 portal chunk mapping
     * @return NBT compound containing flags configuration sub-nest
     */
    private static NbtCompound createChunkisMetadata(
            final boolean suppressInitialRepopulation,
            final boolean fullBlockBaseline,
            final boolean portalChunk
    ) {
        final NbtCompound chunkisMetadata = new NbtCompound();
        chunkisMetadata.putBoolean(
                CisNbtUtil.SUPPRESS_INITIAL_REPOPULATION_KEY,
                suppressInitialRepopulation
        );
        chunkisMetadata.putBoolean(
                CisNbtUtil.FULL_BLOCK_BASELINE_KEY,
                fullBlockBaseline
        );
        chunkisMetadata.putBoolean(
                CisNbtUtil.PORTAL_CHUNK_KEY,
                portalChunk
        );
        return chunkisMetadata;
    }
}
