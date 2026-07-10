package io.liparakis.chunkis.integration.migration.state;

import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Resolves vanilla chunk and external-entity region directories to dimension keys.
 * Used by low-level storage guards that have no world context.
 */
public final class VanillaRegionPathResolver {

    /**
     * Directory name used by vanilla chunk region storage.
     */
    private static final String REGION_DIR = "region";

    /**
     * Directory name used by vanilla external entity region storage.
     */
    private static final String ENTITIES_DIR = "entities";

    /**
     * Root directory name used by vanilla for non-overworld dimensions.
     */
    private static final String DIMENSIONS_DIR = "dimensions";

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
     * Resolves the dimension key from a vanilla chunk or entity region directory path.
     * Supports overworld, nether, end, and custom dimensions.
     *
     * @param regionDirectory vanilla region or entity directory path
     * @return resolved dimension key, or empty when the path is not a supported storage directory
     */
    public static Optional<RegistryKey<World>> resolveDimension(final Path regionDirectory) {
        if (regionDirectory == null) {
            return Optional.empty();
        }
        final Path normalized = regionDirectory.toAbsolutePath()
                .normalize();
        final Path fileName = normalized.getFileName();
        if (fileName == null
                || (!REGION_DIR.equals(fileName.toString())
                && !ENTITIES_DIR.equals(fileName.toString()))) {
            return Optional.empty();
        }
        final Path dimensionPath = normalized.getParent();
        if (dimensionPath == null) {
            return Optional.empty();
        }
        final Path dimensionName = dimensionPath.getFileName();
        if (dimensionName == null) {
            return Optional.empty();
        }
        final String dimensionSegment = dimensionName.toString();
        if ("DIM-1".equals(dimensionSegment)) {
            return Optional.of(RegistryKey.of(RegistryKeys.WORLD, Identifier.ofVanilla("the_nether")));
        }
        if ("DIM1".equals(dimensionSegment)) {
            return Optional.of(RegistryKey.of(RegistryKeys.WORLD, Identifier.ofVanilla("the_end")));
        }

        final Path namespacePath = dimensionPath.getParent();
        final Path dimensionsRoot = namespacePath == null ? null : namespacePath.getParent();
        if (namespacePath != null
                && dimensionsRoot != null
                && DIMENSIONS_DIR.equals(dimensionsRoot.getFileName()
                .toString())) {
            return Optional.of(RegistryKey.of(
                    RegistryKeys.WORLD,
                    Identifier.of(namespacePath.getFileName()
                            .toString(), dimensionSegment)
            ));
        }

        return Optional.of(RegistryKey.of(RegistryKeys.WORLD, Identifier.ofVanilla("overworld")));
    }

    /**
     * Classifies a storage path.
     */
    public enum Classification {
        VANILLA_CHUNK_REGION,
        VANILLA_POI_REGION,
        VANILLA_ENTITY_REGION,
        CHUNKIS_INTERNAL,
        UNKNOWN
    }
}
