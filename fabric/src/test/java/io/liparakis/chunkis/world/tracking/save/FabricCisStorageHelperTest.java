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

    private static final Path SAVE_ROOT = Path.of("C:", "tmp", "world");

    @Test
    void overworldUsesRootChunkisDirectory() {
        final RegistryKey<World> overworld = RegistryKey.of(
                RegistryKeys.WORLD,
                Identifier.of("minecraft", "overworld"));

        assertEquals(
                SAVE_ROOT.resolve("chunkis").resolve("regions"),
                ChunkisStoragePaths.computeRegionsDirectory(SAVE_ROOT, overworld));
        assertEquals(
                SAVE_ROOT.resolve("chunkis").resolve("global_ids.json"),
                ChunkisStoragePaths.computeMappingFile(SAVE_ROOT, overworld));
        assertEquals(
                SAVE_ROOT.resolve("region"),
                ChunkisStoragePaths.computeVanillaRegionDirectory(SAVE_ROOT, overworld));
    }

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
                SAVE_ROOT.resolve("dimensions")
                         .resolve("minecraft")
                         .resolve("the_nether")
                         .resolve("region"),
                ChunkisStoragePaths.computeVanillaRegionDirectory(SAVE_ROOT, nether));
    }

    @Test
    void customDimensionNamedOverworldStillUsesDimensionDirectory() {
        final RegistryKey<World> customOverworld = RegistryKey.of(
                RegistryKeys.WORLD,
                Identifier.of("example", "overworld"));
        final Path dimensionBase = SAVE_ROOT.resolve("dimensions")
                                            .resolve("example")
                                            .resolve("overworld");

        assertEquals(
                dimensionBase.resolve("chunkis").resolve("regions"),
                ChunkisStoragePaths.computeRegionsDirectory(SAVE_ROOT, customOverworld));
        assertEquals(
                dimensionBase.resolve("region"),
                ChunkisStoragePaths.computeVanillaRegionDirectory(SAVE_ROOT, customOverworld));
    }
}


