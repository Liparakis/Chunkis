package io.liparakis.chunkis.world.restoration.nbt;

import io.liparakis.chunkis.Chunkis;
import java.io.IOException;
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

        final byte[] rawPayload = metadata.getByteArray(CisNbtUtil.BASE_CHUNK_PAYLOAD_KEY)
                .orElseGet(() -> new byte[0]);
        if (rawPayload.length > 0) {
            try {
                return CisNbtUtil.deserializeRawCompound(rawPayload);
            } catch (final IOException e) {
                Chunkis.LOGGER.warn("Chunkis: Failed to decode persisted base chunk payload bytes", e);
                return null;
            }
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

        final byte[] rawPayload = metadata.getByteArray(CisNbtUtil.BASE_CHUNK_PAYLOAD_KEY)
                .orElseGet(() -> new byte[0]);
        if (rawPayload.length > 0) {
            return true;
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
