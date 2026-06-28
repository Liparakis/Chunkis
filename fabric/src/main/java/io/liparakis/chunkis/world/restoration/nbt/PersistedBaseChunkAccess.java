package io.liparakis.chunkis.world.restoration.nbt;

import net.minecraft.nbt.NbtCompound;

/**
 * Reads persisted base-chunk metadata from Chunkis chunk metadata envelopes.
 *
 * <p>This keeps base-chunk presence and baseline-selection rules together instead
 * of spreading them across the larger NBT utility class.</p>
 */
final class PersistedBaseChunkAccess {

    private PersistedBaseChunkAccess() {
        throw new AssertionError("Utility class");
    }

    static NbtCompound extractCopiedBaseChunkNbt(final Object chunkMetadata) {
        if (!(chunkMetadata instanceof NbtCompound metadata) || metadata.isEmpty()) {
            return null;
        }

        final NbtCompound baseChunkNbt = CisNbtUtil.getCompoundOrNull(metadata, CisNbtUtil.BASE_CHUNK_NBT_KEY);

        return baseChunkNbt != null && !baseChunkNbt.isEmpty() ? baseChunkNbt.copy() : null;
    }

    static boolean hasPersistedBaseChunkNbt(final Object chunkMetadata) {
        if (!(chunkMetadata instanceof NbtCompound metadata) || metadata.isEmpty()) {
            return false;
        }

        final NbtCompound baseChunkNbt = CisNbtUtil.getCompoundOrNull(metadata, CisNbtUtil.BASE_CHUNK_NBT_KEY);

        return baseChunkNbt != null && !baseChunkNbt.isEmpty();
    }

    static boolean shouldUsePersistedBaseChunkForBlockBaseline(final Object chunkMetadata) {
        return hasPersistedBaseChunkNbt(chunkMetadata) && !CisNbtUtil.hasFullBlockBaseline(chunkMetadata);
    }
}
