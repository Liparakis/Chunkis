package io.liparakis.chunkis.world.tracking.save;

import java.nio.file.Path;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Pure path-resolution helpers for Chunkis and vanilla world storage paths.
 *
 * <p>Chunkis keeps its own dimension-local data under a normalized
 * {@code dimensions/<namespace>/<path>} layout for every non-overworld dimension.
 * Vanilla storage is different: the Nether and End still use legacy save roots
 * ({@code DIM-1} and {@code DIM1}) while custom dimensions use
 * {@code dimensions/<namespace>/<path>}.
 *
 * <p>Kept separate from {@link FabricCisStorageHelper} so the layout contract can
 * be unit-tested without triggering block-registry bootstrap.
 */
public final class ChunkisStoragePaths {

    /**
     * Subdirectory name storing multi-dimension world states.
     */
    private static final String DIMENSIONS_DIR = "dimensions";

    /**
     * Folder label namespace containing Chunkis delta data.
     */
    private static final String CHUNKIS_DIR = "chunkis";

    /**
     * Subdirectory containing region binary payload files.
     */
    private static final String REGIONS_DIR = "regions";

    /**
     * Folder label namespace mapped for vanilla regional MCA blocks.
     */
    private static final String VANILLA_REGION_DIR = "region";
    /**
     * Folder label namespace mapped for vanilla external entity region files.
     */
    private static final String VANILLA_ENTITIES_DIR = "entities";

    /**
     * Filename mapping global identifier registry values.
     */
    private static final String MAPPING_FILE = "global_ids.json";

    /**
     * Filename storing portal locator indexes.
     */
    private static final String PORTAL_INDEX_FILE = "portal_chunks.nbt";

    /**
     * Filename storing portal pair links.
     */
    private static final String PORTAL_LINKS_FILE = "portal_links.nbt";

    /**
     * Base namespace identifying default vanilla structures.
     */
    private static final String MINECRAFT_NAMESPACE = "minecraft";

    /**
     * Path label identifying overworld dimension levels.
     */
    private static final String OVERWORLD_PATH = "overworld";

    /**
     * Legacy vanilla save directory used by the Nether.
     */
    private static final String NETHER_DIR = "DIM-1";

    /**
     * Legacy vanilla save directory used by the End.
     */
    private static final String END_DIR = "DIM1";

    /**
     * Vanilla dimension path for the Nether.
     */
    private static final String NETHER_PATH = "the_nether";

    /**
     * Vanilla dimension path for the End.
     */
    private static final String END_PATH = "the_end";

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkisStoragePaths() {
        throw new AssertionError("Utility class");
    }

    /**
     * Resolves the dimension-local Chunkis region storage directory.
     *
     * <p>This is the root directory where {@code .cis} region files are written.
     *
     * @param saveRoot the world save root (e.g. {@code server/world/})
     * @param worldKey the registry key identifying the target dimension
     * @return the resolved regions directory path
     */
    public static Path computeRegionsDirectory(final Path saveRoot, final RegistryKey<World> worldKey) {
        return computeChunkisDirectory(saveRoot, worldKey).resolve(REGIONS_DIR);
    }

    /**
     * Resolves the dimension-local mapping file that owns block ID persistence.
     *
     * @param saveRoot the world save root
     * @param worldKey the registry key identifying the target dimension
     * @return the resolved mapping file path
     */
    public static Path computeMappingFile(final Path saveRoot, final RegistryKey<World> worldKey) {
        return computeChunkisDirectory(saveRoot, worldKey).resolve(MAPPING_FILE);
    }

    /**
     * Resolves the dimension-local portal chunk index file used by Chunkis
     * portal lookup.
     *
     * @param saveRoot the world save root
     * @param worldKey the registry key identifying the target dimension
     * @return the resolved portal index file path
     */
    public static Path computePortalIndexFile(final Path saveRoot, final RegistryKey<World> worldKey) {
        return computeChunkisDirectory(saveRoot, worldKey).resolve(PORTAL_INDEX_FILE);
    }

    /**
     * Resolves the global portal link table used for deterministic bidirectional
     * portal pairing across dimensions.
     *
     * <p>This file is stored directly under the Chunkis save root rather than
     * per-dimension, since portal links span multiple worlds.
     *
     * @param saveRoot the world save root
     * @return the resolved portal links file path
     */
    public static Path computePortalLinksFile(final Path saveRoot) {
        return saveRoot.resolve(CHUNKIS_DIR)
                .resolve(PORTAL_LINKS_FILE);
    }

    /**
     * Resolves the vanilla MCA region directory for a given dimension, using the
     * same base layout as Chunkis.
     *
     * <p>Useful for reading vanilla chunk data alongside Chunkis data without
     * duplicating the dimension-path logic.
     *
     * @param saveRoot the world save root
     * @param worldKey the registry key identifying the target dimension
     * @return the resolved vanilla region directory path
     */
    public static Path computeVanillaRegionDirectory(final Path saveRoot, final RegistryKey<World> worldKey) {
        return computeVanillaDimensionBaseDirectory(saveRoot, worldKey).resolve(VANILLA_REGION_DIR);
    }

    /**
     * Resolves the vanilla external entity-region directory for a given dimension.
     *
     * <p>Modern vanilla stores chunk entities in {@code entities/} rather than in
     * the chunk-region root. Offline migration must read both stores before Chunkis
     * takes authority and blocks vanilla entity-region access.</p>
     *
     * @param saveRoot the world save root
     * @param worldKey the registry key identifying the target dimension
     * @return the resolved vanilla external entity directory path
     */
    public static Path computeVanillaEntitiesDirectory(final Path saveRoot, final RegistryKey<World> worldKey) {
        return computeVanillaDimensionBaseDirectory(saveRoot, worldKey).resolve(VANILLA_ENTITIES_DIR);
    }

    /**
     * Resolves the Chunkis subdirectory within the dimension base directory.
     *
     * <p>All Chunkis-specific files (regions, mapping, portal index) are rooted here.
     *
     * @param saveRoot the world save root
     * @param worldKey the registry key identifying the target dimension
     * @return {@code <dimensionBase>/chunkis/}
     */
    private static Path computeChunkisDirectory(final Path saveRoot, final RegistryKey<World> worldKey) {
        return computeDimensionBaseDirectory(saveRoot, worldKey).resolve(CHUNKIS_DIR);
    }

    /**
     * Resolves Chunkis' normalized dimension base directory.
     *
     * <ul>
     *   <li>{@code minecraft:overworld} -> {@code <saveRoot>/} (no subdirectory)
     *   <li>All other dimensions -> {@code <saveRoot>/dimensions/<namespace>/<path>/}
     * </ul>
     *
     * @param saveRoot the world save root
     * @param worldKey the registry key identifying the target dimension
     * @return the dimension base directory
     */
    private static Path computeDimensionBaseDirectory(final Path saveRoot, final RegistryKey<World> worldKey) {
        final Identifier dimId = worldKey.getValue();
        if (!requiresDimensionSubdirectory(dimId)) {
            return saveRoot;
        }

        return saveRoot
                .resolve(DIMENSIONS_DIR)
                .resolve(dimId.getNamespace())
                .resolve(dimId.getPath());
    }

    /**
     * Mirrors vanilla's actual dimension save roots for MCA-backed data.
     *
     * <ul>
     *   <li>{@code minecraft:overworld} -> {@code <saveRoot>/}</li>
     *   <li>{@code minecraft:the_nether} -> {@code <saveRoot>/DIM-1/}</li>
     *   <li>{@code minecraft:the_end} -> {@code <saveRoot>/DIM1/}</li>
     *   <li>Custom dimensions -> {@code <saveRoot>/dimensions/<namespace>/<path>/}</li>
     * </ul>
     *
     * @param saveRoot the world save root
     * @param worldKey the registry key identifying the target dimension
     * @return the vanilla dimension base directory
     */
    private static Path computeVanillaDimensionBaseDirectory(final Path saveRoot, final RegistryKey<World> worldKey) {
        final Identifier dimId = worldKey.getValue();
        if (!requiresDimensionSubdirectory(dimId)) {
            return saveRoot;
        }
        if (isVanillaDimension(dimId, NETHER_PATH)) {
            return saveRoot.resolve(NETHER_DIR);
        }
        if (isVanillaDimension(dimId, END_PATH)) {
            return saveRoot.resolve(END_DIR);
        }
        return saveRoot
                .resolve(DIMENSIONS_DIR)
                .resolve(dimId.getNamespace())
                .resolve(dimId.getPath());
    }

    /**
     * Returns {@code true} if the given dimension identifier requires nesting under
     * {@code dimensions/<namespace>/<path>} rather than resolving at the save root.
     *
     * <p>Only {@code minecraft:overworld} resolves at the root; all other dimensions,
     * including non-vanilla namespaces and other vanilla dimensions like the Nether
     * and End, use the subdirectory layout.
     *
     * @param dimId the dimension identifier to check
     * @return {@code true} if a dimension subdirectory is required
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean requiresDimensionSubdirectory(final Identifier dimId) {
        return !MINECRAFT_NAMESPACE.equals(dimId.getNamespace())
                || !OVERWORLD_PATH.equals(dimId.getPath());
    }

    /**
     * Returns whether the identifier refers to a vanilla dimension with the given path.
     *
     * @param dimId dimension identifier to inspect
     * @param path  expected vanilla path
     * @return {@code true} when the identifier matches the requested vanilla dimension
     */
    private static boolean isVanillaDimension(final Identifier dimId, final String path) {
        return MINECRAFT_NAMESPACE.equals(dimId.getNamespace()) && path.equals(dimId.getPath());
    }
}
