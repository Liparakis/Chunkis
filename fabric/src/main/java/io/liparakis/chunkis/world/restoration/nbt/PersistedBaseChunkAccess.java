package io.liparakis.chunkis.world.restoration.nbt;

import net.minecraft.nbt.NbtCompound;

/**
 * Reads persisted base-chunk metadata from Chunkis chunk metadata envelopes.
 *
 * <p>This keeps base-chunk presence and baseline-selection rules together instead
 * of spreading them across the larger NBT utility class.</p>
 */
final class PersistedBaseChunkAccess {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private PersistedBaseChunkAccess() {
        throw new AssertionError("Utility class");
    }

    /**
     * Extracts a copied NBT compound from persisted base chunk metadata.
     *
     * @param chunkMetadata chunk metadata envelope object
     * @return copied NBT compound or null
     */
    static NbtCompound extractCopiedBaseChunkNbt(final Object chunkMetadata) {
        if (!(chunkMetadata instanceof NbtCompound metadata) || metadata.isEmpty()) {
            return null;
        }

        final NbtCompound baseChunkNbt = CisNbtUtil.getCompoundOrNull(metadata, CisNbtUtil.BASE_CHUNK_NBT_KEY);

        return baseChunkNbt != null && !baseChunkNbt.isEmpty() ? baseChunkNbt.copy() : null;
    }

    /**
     * Evaluates if chunk metadata contains persisted base chunk NBT details.
     *
     * @param chunkMetadata chunk metadata envelope object
     * @return true if base chunk compound exists
     */
    static boolean hasPersistedBaseChunkNbt(final Object chunkMetadata) {
        if (!(chunkMetadata instanceof NbtCompound metadata) || metadata.isEmpty()) {
            return false;
        }

        final NbtCompound baseChunkNbt = CisNbtUtil.getCompoundOrNull(metadata, CisNbtUtil.BASE_CHUNK_NBT_KEY);

        return baseChunkNbt != null && !baseChunkNbt.isEmpty();
    }

    /**
     * Evaluates if base chunk NBT should supply the block deserialization baseline.
     *
     * @param chunkMetadata chunk metadata envelope object
     * @return true if base chunk compound is preferred for blocks baseline
     */
    static boolean shouldUsePersistedBaseChunkForBlockBaseline(final Object chunkMetadata) {
        return hasPersistedBaseChunkNbt(chunkMetadata) && !CisNbtUtil.hasFullBlockBaseline(chunkMetadata);
    }
}
