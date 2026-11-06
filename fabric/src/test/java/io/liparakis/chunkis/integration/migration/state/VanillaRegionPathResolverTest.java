package io.liparakis.chunkis.integration.migration.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
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
     * to the corresponding {@link ResourceKey} for the overworld.
     */
    @Test
    void resolvesOverworldRegionDirectory() {
        assertEquals(ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld")),
                VanillaRegionPathResolver.resolveDimension(SAVE_ROOT.resolve("region"))
                        .orElseThrow());
    }

    /**
     * Verifies that the overworld entity directory resolves to the overworld.
     */
    @Test
    void resolvesOverworldEntityDirectory() {
        assertEquals(ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld")),
                VanillaRegionPathResolver.resolveDimension(SAVE_ROOT.resolve("entities"))
                        .orElseThrow());
    }

    /**
     * Tests that a dimension-scoped region directory (specifically within the minecraft namespace,
     * e.g., the nether) is successfully resolved to its corresponding {@link ResourceKey}.
     */
    @Test
    void resolvesDimensionScopedRegionDirectory() {
        assertEquals(ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "the_nether")),
                VanillaRegionPathResolver.resolveDimension(SAVE_ROOT.resolve("dimensions")
                                .resolve("minecraft")
                                .resolve("the_nether")
                                .resolve("region"))
                        .orElseThrow());
    }

    /**
     * Tests that a custom dimension-scoped region directory under a non-minecraft namespace
     * (e.g., example namespace) is successfully resolved to its corresponding {@link ResourceKey}.
     */
    @Test
    void resolvesCustomDimensionScopedRegionDirectory() {
        assertEquals(ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("example", "overworld")),
                VanillaRegionPathResolver.resolveDimension(SAVE_ROOT.resolve("dimensions")
                                .resolve("example")
                                .resolve("overworld")
                                .resolve("region"))
                        .orElseThrow());
    }

    /**
     * Verifies that a dimension-scoped entity directory resolves to its dimension.
     */
    @Test
    void resolvesDimensionScopedEntityDirectory() {
        assertEquals(ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "the_nether")),
                VanillaRegionPathResolver.resolveDimension(SAVE_ROOT.resolve("DIM-1")
                                .resolve("entities"))
                        .orElseThrow());
    }
}
