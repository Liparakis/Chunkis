package io.liparakis.chunkis.world.tracking.save;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

/**
 * Verifies the persisted storage-path contract for Chunkis dimensions.
 */
class FabricCisStorageHelperTest {

    /**
     * A temporary save root directory path used for path calculation tests.
     */
    private static final Path SAVE_ROOT = Path.of("C:", "tmp", "world");

    /**
     * Verifies that the overworld dimension uses the root "chunkis" directory
     * for regions and mapping files, and the standard root "region" directory for vanilla regions.
     */
    @Test
    void overworldUsesRootChunkisDirectory() {
        final RegistryKey<World> overworld = RegistryKey.of(
                RegistryKeys.WORLD,
                Identifier.of("minecraft", "overworld"));

        assertEquals(
                SAVE_ROOT.resolve("chunkis")
                        .resolve("regions"),
                ChunkisStoragePaths.computeRegionsDirectory(SAVE_ROOT, overworld));
        assertEquals(
                SAVE_ROOT.resolve("chunkis")
                        .resolve("global_ids.json"),
                ChunkisStoragePaths.computeMappingFile(SAVE_ROOT, overworld));
        assertEquals(
                SAVE_ROOT.resolve("region"),
                ChunkisStoragePaths.computeVanillaRegionDirectory(SAVE_ROOT, overworld));
    }

    /**
     * Verifies that non-overworld dimensions use normalized paths for Chunkis data
     * while vanilla Nether regions retain the legacy {@code DIM-1} directory.
     */
    @Test
    void nonOverworldUsesDimensionLocalChunkisDirectory() {
        final RegistryKey<World> nether = RegistryKey.of(
                RegistryKeys.WORLD,
                Identifier.of("minecraft", "the_nether"));
        final Path dimensionChunkisDir = SAVE_ROOT.resolve("dimensions")
                .resolve("minecraft")
                .resolve("the_nether")
                .resolve("chunkis");

        assertEquals(
                dimensionChunkisDir.resolve("regions"),
                ChunkisStoragePaths.computeRegionsDirectory(SAVE_ROOT, nether));
        assertEquals(
                dimensionChunkisDir.resolve("global_ids.json"),
                ChunkisStoragePaths.computeMappingFile(SAVE_ROOT, nether));
        assertEquals(
                SAVE_ROOT.resolve("DIM-1")
                        .resolve("region"),
                ChunkisStoragePaths.computeVanillaRegionDirectory(SAVE_ROOT, nether));
    }

    /**
     * Verifies that a custom dimension named "overworld" under a non-minecraft namespace
     * still resolves to a dimension-specific directory, rather than the root directory.
     */
    @Test
    void customDimensionNamedOverworldStillUsesDimensionDirectory() {
        final RegistryKey<World> customOverworld = RegistryKey.of(
                RegistryKeys.WORLD,
                Identifier.of("example", "overworld"));
        final Path dimensionBase = SAVE_ROOT.resolve("dimensions")
                .resolve("example")
                .resolve("overworld");

        assertEquals(
                dimensionBase.resolve("chunkis")
                        .resolve("regions"),
                ChunkisStoragePaths.computeRegionsDirectory(SAVE_ROOT, customOverworld));
        assertEquals(
                dimensionBase.resolve("region"),
                ChunkisStoragePaths.computeVanillaRegionDirectory(SAVE_ROOT, customOverworld));
    }
}
