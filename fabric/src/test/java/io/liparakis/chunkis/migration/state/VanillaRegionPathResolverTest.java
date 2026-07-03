package io.liparakis.chunkis.migration.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class VanillaRegionPathResolverTest {

    private static final Path SAVE_ROOT = Path.of("world");

    @Test
    void resolvesOverworldRegionDirectory() {
        assertEquals(RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld")),
                VanillaRegionPathResolver.resolveDimension(SAVE_ROOT.resolve("region"))
                        .orElseThrow());
    }

    @Test
    void resolvesDimensionScopedRegionDirectory() {
        assertEquals(RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "the_nether")),
                VanillaRegionPathResolver.resolveDimension(SAVE_ROOT.resolve("dimensions")
                                .resolve("minecraft")
                                .resolve("the_nether")
                                .resolve("region"))
                        .orElseThrow());
    }

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
