package io.liparakis.chunkis.world.restoration.nbt;

import net.minecraft.nbt.NbtCompound;

/**
 * Builder for the persisted Chunkis chunk-metadata envelope.
 *
 * <p>This keeps the envelope shape in one place while leaving higher-level load
 * and save decisions in {@link CisNbtUtil}.</p>
 */
final class ChunkMetadataEnvelope {

    private ChunkMetadataEnvelope() {
        throw new AssertionError("Utility class");
    }

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
