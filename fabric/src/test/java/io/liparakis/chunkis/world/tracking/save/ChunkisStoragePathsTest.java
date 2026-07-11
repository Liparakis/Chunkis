package io.liparakis.chunkis.world.tracking.save;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for Chunkis and vanilla dimension path resolution.
 */
class ChunkisStoragePathsTest {

    /**
     * Root path used for deterministic path assertions.
     */
    private static final Path SAVE_ROOT = Path.of("world");

    /**
     * Creates a vanilla world registry key for test assertions.
     *
     * @param path vanilla world path
     * @return registry key for the requested vanilla dimension
     */
    private static RegistryKey<World> vanillaWorld(final String path) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.ofVanilla(path));
    }

    /**
     * Verifies Chunkis keeps Nether CIS data in its normalized multi-dimension layout.
     */
    @Test
    void chunkisNetherStorageRemainsNormalized() {
        assertEquals(
                SAVE_ROOT.resolve("dimensions")
                        .resolve("minecraft")
                        .resolve("the_nether")
                        .resolve("chunkis")
                        .resolve("regions"),
                ChunkisStoragePaths.computeRegionsDirectory(SAVE_ROOT, vanillaWorld("the_nether"))
        );
    }

    /**
     * Verifies vanilla Nether chunk regions still resolve to the legacy {@code DIM-1} root.
     */
    @Test
    void vanillaNetherRegionDirectoryUsesLegacyRoot() {
        assertEquals(
                SAVE_ROOT.resolve("DIM-1")
                        .resolve("region"),
                ChunkisStoragePaths.computeVanillaRegionDirectory(SAVE_ROOT, vanillaWorld("the_nether"))
        );
    }

    /**
     * Verifies vanilla End entity regions still resolve to the legacy {@code DIM1} root.
     */
    @Test
    void vanillaEndEntityDirectoryUsesLegacyRoot() {
        assertEquals(
                SAVE_ROOT.resolve("DIM1")
                        .resolve("entities"),
                ChunkisStoragePaths.computeVanillaEntitiesDirectory(SAVE_ROOT, vanillaWorld("the_end"))
        );
    }
}
