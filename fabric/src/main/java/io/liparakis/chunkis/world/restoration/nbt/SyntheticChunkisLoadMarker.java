package io.liparakis.chunkis.world.restoration.nbt;

import net.minecraft.nbt.NbtCompound;

/**
 * Reads and writes the synthetic Chunkis load-path marker nested under chunk NBT.
 *
 * <p>This marker is transient load-path plumbing, separate from persisted chunk
 * metadata envelopes.</p>
 */
final class SyntheticChunkisLoadMarker {

    private SyntheticChunkisLoadMarker() {
        throw new AssertionError("Utility class");
    }

    static boolean hasDeltaMarker(final NbtCompound root) {
        final NbtCompound chunkisData = CisNbtUtil.getCompoundOrNull(root, CisNbtUtil.CHUNKIS_DATA_KEY);
        return chunkisData != null
                && chunkisData.getBoolean(CisNbtUtil.HAS_DELTA_KEY).orElse(false);
    }

    static void putLoadBaseChunkUsage(
            final NbtCompound root,
            final CisNbtUtil.PersistedBaseChunkUsage baseChunkUsage
                                     ) {
        final NbtCompound chunkisData = getOrCreateCompound(root, CisNbtUtil.CHUNKIS_DATA_KEY);
        chunkisData.putString(CisNbtUtil.LOAD_BASE_CHUNK_USAGE_KEY, baseChunkUsage.name());
    }

    private static NbtCompound getOrCreateCompound(
            final NbtCompound parent,
            final String key
                                                  ) {
        final NbtCompound existing = CisNbtUtil.getCompoundOrNull(parent, key);
        if (existing != null) {
            return existing;
        }

        final NbtCompound created = new NbtCompound();
        parent.put(key, created);
        return created;
    }
}
