package io.liparakis.chunkis.world.restoration.nbt;

import net.minecraft.nbt.NbtCompound;

/**
 * Reads and writes the synthetic Chunkis load-path marker nested under chunk NBT.
 *
 * <p>This marker is transient load-path plumbing, separate from persisted chunk
 * metadata envelopes.</p>
 */
final class SyntheticChunkisLoadMarker {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private SyntheticChunkisLoadMarker() {
        throw new AssertionError("Utility class");
    }

    /**
     * Evaluates if root chunk NBT has synthetic load-path delta markers.
     *
     * @param root serialized chunk NBT root compound
     * @return true if marker exists
     */
    static boolean hasDeltaMarker(final NbtCompound root) {
        final NbtCompound chunkisData = CisNbtUtil.getCompoundOrNull(root, CisNbtUtil.CHUNKIS_DATA_KEY);
        return chunkisData != null
                && chunkisData.getBoolean(CisNbtUtil.HAS_DELTA_KEY)
                .orElse(false);
    }

    /**
     * Attaches transient load base chunk usage code string to target root NBT.
     *
     * @param root           serialized chunk NBT root compound
     * @param baseChunkUsage base chunk usage status
     */
    static void putLoadBaseChunkUsage(
            final NbtCompound root,
            final CisNbtUtil.PersistedBaseChunkUsage baseChunkUsage
    ) {
        final NbtCompound chunkisData = getOrCreateCompound(root, CisNbtUtil.CHUNKIS_DATA_KEY);
        chunkisData.putString(CisNbtUtil.LOAD_BASE_CHUNK_USAGE_KEY, baseChunkUsage.name());
    }

    /**
     * Returns a nested compound mapping the key, constructing one if missing.
     *
     * @param parent parent compound to search/populate
     * @param key    nested key mapping
     * @return found or created nested compound
     */
    @SuppressWarnings("SameParameterValue")
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