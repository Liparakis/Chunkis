package io.liparakis.chunkis.integration.migration.state;

import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * Classifies storage directories and uses Minecraft's supplied dimension key for region guards.
 * Dimension identity must not be inferred from paths: dimension identifiers can contain slashes.
 */
public final class VanillaRegionPathResolver {

    /**
     * Private constructor to prevent utility class instantiation.
     */
    private VanillaRegionPathResolver() {
    }

    /**
     * Classifies a storage directory by its role in the world save layout.
     *
     * @param path storage path to classify
     * @return coarse storage classification used by low-level guards
     */
    public static Classification classify(final Path path) {
        if (path == null) {
            return Classification.UNKNOWN;
        }

        final String name = path.getFileName()
                .toString();
        switch (name) {
            case "region":
                return Classification.VANILLA_CHUNK_REGION;
            case "poi":
                return Classification.VANILLA_POI_REGION;
            case "entities":
                return Classification.VANILLA_ENTITY_REGION;
        }
        if (path.toString()
                .contains("chunkis") || path.toString()
                .contains("quarantine")) {
            return Classification.CHUNKIS_INTERNAL;
        }
        return Classification.UNKNOWN;
    }

    /**
     * Resolves the owning dimension only for chunk and external-entity region storage.
     *
     * <p>The constructor's storage info is authoritative even for nested dimension names or nonstandard
     * directory layouts. POI and Chunkis internal stores must never acquire the vanilla write guard.</p>
     *
     * @param storageInfo Minecraft's owning world and dimension
     * @param directory backing storage directory
     * @return owning dimension, or empty for storage that Chunkis does not guard
     */
    public static Optional<ResourceKey<Level>> resolveDimension(final RegionStorageInfo storageInfo,
            final Path directory) {
        final Classification classification = classify(directory);
        return classification == Classification.VANILLA_CHUNK_REGION
                || classification == Classification.VANILLA_ENTITY_REGION
                ? Optional.of(storageInfo.dimension()) : Optional.empty();
    }

    /**
     * Classifies a storage path.
     */
    public enum Classification {
        /**
         * Stores vanilla chunk region.
         */
        VANILLA_CHUNK_REGION,
        /**
         * Stores vanilla poi region.
         */
        VANILLA_POI_REGION,
        /**
         * Stores vanilla entity region.
         */
        VANILLA_ENTITY_REGION,
        /**
         * Stores chunkis internal.
         */
        CHUNKIS_INTERNAL,
        /**
         * Represents an unknown region classification.
         */
        UNKNOWN
    }
}
