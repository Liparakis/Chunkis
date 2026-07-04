package io.liparakis.chunkis.migration.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link VanillaRegionPathResolver}.
 */
class VanillaRegionPathResolverTest {

    /**
     * The root path simulating the world save directory.
     */
    private static final Path SAVE_ROOT = Path.of("world");

    /**
     * Tests that the overworld region directory path is successfully resolved
     * to the corresponding {@link RegistryKey} for the overworld.
     */
    @Test
    void resolvesOverworldRegionDirectory() {
        assertEquals(RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld")),
                VanillaRegionPathResolver.resolveDimension(SAVE_ROOT.resolve("region"))
                        .orElseThrow());
    }

    /**
     * Tests that a dimension-scoped region directory (specifically within the minecraft namespace,
     * e.g., the nether) is successfully resolved to its corresponding {@link RegistryKey}.
     */
    @Test
    void resolvesDimensionScopedRegionDirectory() {
        assertEquals(RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "the_nether")),
                VanillaRegionPathResolver.resolveDimension(SAVE_ROOT.resolve("dimensions")
                                .resolve("minecraft")
                                .resolve("the_nether")
                                .resolve("region"))
                        .orElseThrow());
    }

    /**
     * Tests that a custom dimension-scoped region directory under a non-minecraft namespace
     * (e.g., example namespace) is successfully resolved to its corresponding {@link RegistryKey}.
     */
    @Test
    void resolvesCustomDimensionScopedRegionDirectory() {
        assertEquals(RegistryKey.of(RegistryKeys.WORLD, Identifier.of("example", "overworld")),
                VanillaRegionPathResolver.resolveDimension(SAVE_ROOT.resolve("dimensions")
                                .resolve("example")
                                .resolve("overworld")
                                .resolve("region"))
                        .orElseThrow());
    }
}
