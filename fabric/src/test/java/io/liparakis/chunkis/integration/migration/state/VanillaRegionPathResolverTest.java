package io.liparakis.chunkis.integration.migration.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Storage guards must follow the supplied dimension, regardless of directory naming.
 */
class VanillaRegionPathResolverTest {

    /**
     * Exercises normalized, legacy and nested layouts, including names that fool path heuristics.
     *
     * @param dimension actual owning dimension
     * @param directory storage directory relative to the fixture root
     */
    @ParameterizedTest
    @CsvSource({
        "minecraft:overworld, world/region",
        "minecraft:overworld, world/entities",
        "minecraft:the_nether, world/DIM-1/region",
        "minecraft:the_end, world/DIM1/entities",
        "minecraft:overworld, world/dimensions/minecraft/overworld/region",
        "minecraft:the_nether, world/dimensions/minecraft/the_nether/entities",
        "example:caves, world/dimensions/example/caves/region",
        "example:world/caves, world/dimensions/example/world/caves/region",
        "example:world/caves, world/dimensions/example/world/caves/entities",
        "example:world/caves, world/dimensions/example/world/DIM-1/region",
        "example:world/caves, world/dimensions/example/world/DIM1/entities",
        "example:world/dimensions/nested, world/dimensions/example/world/dimensions/nested/region",
        "example:world/caves, dimensions/saves/world/dimensions/example/world/caves/region",
        "example:world/caves, alternate-layout/region"
    })
    void resolvesExplicitOwningDimension(final String dimension, final String directory) {
        final ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
        final RegionStorageInfo info = new RegionStorageInfo("test-world", key, "chunk");
        assertEquals(key, VanillaRegionPathResolver.resolveDimension(info, Path.of(directory)).orElseThrow());
    }

    /**
     * Internal and POI stores must remain outside chunk/entity authority guards.
     *
     * @param directory unguarded directory, or null
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"world/poi", "world/chunkis/unfinished", "world/chunkis/regions", "world/quarantine"})
    void doesNotGuardOtherStorage(final String directory) {
        final ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse("example:world/caves"));
        final RegionStorageInfo info = new RegionStorageInfo("test-world", key, "poi");
        assertTrue(VanillaRegionPathResolver.resolveDimension(info, directory == null ? null : Path.of(directory))
                .isEmpty());
    }
}
