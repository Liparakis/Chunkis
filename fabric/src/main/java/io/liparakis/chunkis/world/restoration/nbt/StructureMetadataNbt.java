package io.liparakis.chunkis.world.restoration.nbt;

import net.minecraft.nbt.CompoundTag;

/**
 * Read helpers for vanilla structure metadata stored in Chunkis chunk metadata.
 *
 * <p>This isolates structure-specific NBT probing from the broader
 * {@link CisNbtUtil} facade so callers do not need to understand whether the
 * structures payload came from a raw chunk root, a persisted metadata envelope,
 * or the older legacy structure-only format.</p>
 */
final class StructureMetadataNbt {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private StructureMetadataNbt() {
        throw new AssertionError("Utility class");
    }

    /**
     * Extracts copied vanilla structure metadata from a serialized chunk root.
     *
     * @param root serialized chunk NBT
     * @return copied structures compound, or {@code null}
     */
    static CompoundTag extractStructureData(final CompoundTag root) {
        final CompoundTag structures = getCompoundOrNull(root, CisNbtUtil.STRUCTURES_KEY);
        return hasStructureData(structures) ? structures.copy() : null;
    }

    /**
     * Extracts copied vanilla structure metadata from persisted Chunkis metadata.
     *
     * @param chunkMetadata metadata stored in the delta
     * @return copied structure metadata, or {@code null}
     */
    static CompoundTag extractPersistedStructureMetadata(final CompoundTag chunkMetadata) {
        if (chunkMetadata == null || chunkMetadata.isEmpty()) {
            return null;
        }

        final CompoundTag envelopedStructures = getCompoundOrNull(chunkMetadata, CisNbtUtil.STRUCTURES_KEY);

        if (envelopedStructures != null) {
            return hasStructureData(envelopedStructures) ? envelopedStructures.copy() : null;
        }

        return hasStructureData(chunkMetadata) ? chunkMetadata.copy() : null;
    }

    /**
     * Returns whether a structures compound contains starts or references.
     *
     * @param structures vanilla structures compound
     * @return {@code true} if structure starts or references exist
     */
    static boolean hasStructureData(final CompoundTag structures) {
        return structures != null && !structures.isEmpty() && (getCompoundOrNull(structures,
                CisNbtUtil.STRUCTURE_STARTS_KEY)
                != null || getCompoundOrNull(structures,
                CisNbtUtil.STRUCTURE_REFERENCES_KEY) != null);
    }

    /**
     * Returns a nested compound, or {@code null} when the parent/key is absent.
     *
     * @param parent parent compound, may be {@code null}
     * @param key    nested compound key
     * @return nested compound, or {@code null}
     */
    static CompoundTag getCompoundOrNull(final CompoundTag parent, final String key) {
        if (parent == null || !parent.contains(key)) {
            return null;
        }

        return parent.getCompound(key)
                .orElse(null);
    }
}
