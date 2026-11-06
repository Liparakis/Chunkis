package io.liparakis.chunkis.world.tracking.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.filefix.fixes.DimensionStorageFileFix;
import net.minecraft.util.filefix.virtualfilesystem.CopyOnWriteFileSystem;
import net.minecraft.util.worldupdate.UpgradeProgress;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    private static ResourceKey<Level> vanillaWorld(final String path) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(path));
    }

    /**
     * Runs Minecraft's actual directory migration against legacy vanilla files alongside existing CIS data.
     *
     * @param dimension dimension identifier path
     * @param legacyRoot vanilla's pre-26.1 dimension directory
     * @param temporary temporary directory containing the save and copy-on-write workspace
     * @throws IOException if fixture creation or migration fails
     */
    @ParameterizedTest
    @CsvSource({"overworld, .", "the_nether, DIM-1", "the_end, DIM1"})
    void minecraftFileFixPreservesCis(final String dimension, final String legacyRoot,
                                    @TempDir final Path temporary) throws IOException {
        final Path world = temporary.resolve("save");
        final ResourceKey<Level> key = vanillaWorld(dimension);
        final Path cisRoot = ChunkisStoragePaths.computeRegionsDirectory(world, key).getParent();
        final List<String> cisFiles = List.of("regions/r.0.0.cis", "global_ids.json",
                "unfinished/r.0.0.mca", "portal_chunks.nbt");
        for (final String file : cisFiles) {
            final Path path = cisRoot.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "unchanged:" + file);
        }
        for (final String directory : List.of("region", "entities", "poi")) {
            final Path path = world.resolve(legacyRoot).resolve(directory).resolve("r.0.0.mca");
            Files.createDirectories(path.getParent());
            Files.writeString(path, "vanilla:" + directory);
        }

        try (CopyOnWriteFileSystem fileSystem = CopyOnWriteFileSystem.create("migration-test",
                world, temporary.resolve("cow"), ignoredPath -> false)) {
            final Path migrated = fileSystem.rootPath();
            final var schema = new com.mojang.datafixers.DataFixerBuilder(99)
                    .addSchema(99, net.minecraft.util.datafix.schemas.V99::new);
            new DimensionStorageFileFix(schema).runFixOperations(migrated, new UpgradeProgress.Noop());
            final Path vanillaRoot = ChunkisStoragePaths.computeVanillaRegionDirectory(migrated, key).getParent();
            for (final String directory : List.of("region", "entities", "poi")) {
                assertEquals("vanilla:" + directory,
                        Files.readString(vanillaRoot.resolve(directory).resolve("r.0.0.mca")));
                assertFalse(Files.exists(migrated.resolve(legacyRoot).resolve(directory)));
            }
            final Path migratedCis = ChunkisStoragePaths.computeRegionsDirectory(migrated, key).getParent();
            for (final String file : cisFiles) {
                assertEquals("unchanged:" + file, Files.readString(migratedCis.resolve(file)));
            }
        }
    }

    /**
     * Minecraft's Overworld move must not redirect existing CIS data into a new empty directory.
     */
    @Test
    void overworldVanillaMoveKeepsExistingCisRoot() {
        assertEquals(SAVE_ROOT.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("entities"),
                ChunkisStoragePaths.computeVanillaEntitiesDirectory(SAVE_ROOT, vanillaWorld("overworld")));
        assertEquals(SAVE_ROOT.resolve("chunkis").resolve("regions"),
                ChunkisStoragePaths.computeRegionsDirectory(SAVE_ROOT, vanillaWorld("overworld")));
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
     * Verifies vanilla Nether chunk regions resolve to the 26.1 dimension root.
     */
    @Test
    void vanillaNetherRegionDirectoryUsesNormalizedRoot() {
        assertEquals(
                SAVE_ROOT.resolve("dimensions").resolve("minecraft").resolve("the_nether")
                        .resolve("region"),
                ChunkisStoragePaths.computeVanillaRegionDirectory(SAVE_ROOT, vanillaWorld("the_nether"))
        );
    }

    /**
     * Verifies vanilla End entity regions resolve to the 26.1 dimension root.
     */
    @Test
    void vanillaEndEntityDirectoryUsesNormalizedRoot() {
        assertEquals(
                SAVE_ROOT.resolve("dimensions").resolve("minecraft").resolve("the_end")
                        .resolve("entities"),
                ChunkisStoragePaths.computeVanillaEntitiesDirectory(SAVE_ROOT, vanillaWorld("the_end"))
        );
    }
}
