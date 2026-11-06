package io.liparakis.chunkis.world.tracking.save;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
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
        final ResourceKey<Level> overworld = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld"));

        assertEquals(
                SAVE_ROOT.resolve("chunkis")
                        .resolve("regions"),
                ChunkisStoragePaths.computeRegionsDirectory(SAVE_ROOT, overworld));
        assertEquals(
                SAVE_ROOT.resolve("chunkis")
                        .resolve("global_ids.json"),
                ChunkisStoragePaths.computeMappingFile(SAVE_ROOT, overworld));
        assertEquals(
                SAVE_ROOT.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region"),
                ChunkisStoragePaths.computeVanillaRegionDirectory(SAVE_ROOT, overworld));
    }

    /**
     * Verifies that non-overworld dimensions use normalized paths for Chunkis data
     * and vanilla Nether regions use Minecraft's normalized dimension directory.
     */
    @Test
    void nonOverworldUsesDimensionLocalChunkisDirectory() {
        final ResourceKey<Level> nether = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "the_nether"));
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
                SAVE_ROOT.resolve("dimensions").resolve("minecraft").resolve("the_nether")
                        .resolve("region"),
                ChunkisStoragePaths.computeVanillaRegionDirectory(SAVE_ROOT, nether));
    }

    /**
     * Verifies that a custom dimension named "overworld" under a non-minecraft namespace
     * still resolves to a dimension-specific directory, rather than the root directory.
     */
    @Test
    void customDimensionNamedOverworldStillUsesDimensionDirectory() {
        final ResourceKey<Level> customOverworld = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("example", "overworld"));
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
