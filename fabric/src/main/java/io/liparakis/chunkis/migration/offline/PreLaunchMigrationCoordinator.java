package io.liparakis.chunkis.migration.offline;

import io.liparakis.chunkis.migration.state.MigrationStateServiceHolder;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.SaveLoader;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates the blocking offline migration stage before integrated-server
 * startup begins.
 */
public final class PreLaunchMigrationCoordinator {

    /**
     * Logger for migration-stage startup decisions and cleanup actions.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(PreLaunchMigrationCoordinator.class);

    /**
     * Private constructor to prevent utility class instantiation.
     */
    private PreLaunchMigrationCoordinator() {
        throw new AssertionError("Utility class");
    }

    /**
     * Runs offline migration for every configured dimension before normal world
     * startup continues.
     *
     * @param session    locked save session for the world being opened
     * @param saveLoader save loader containing dimension registries
     */
    public static void runBeforeIntegratedServerStart(
            final LevelStorage.Session session,
            final SaveLoader saveLoader
    ) {
        final Path saveRoot = session.getDirectory(WorldSavePath.ROOT);

        try {
            MigrationStateServiceHolder.clear();
            final DynamicRegistryManager registryManager =
                    saveLoader.combinedDynamicRegistries()
                            .getCombinedRegistryManager();
            final Registry<DimensionOptions> dimensionRegistry =
                    registryManager.getOrThrow(RegistryKeys.DIMENSION);

            for (final var entry : dimensionRegistry.getEntrySet()) {
                final RegistryKey<World> worldKey = RegistryKeys.toWorldKey(entry.getKey());
                final RegistryEntry<DimensionType> dimensionType = entry.getValue()
                        .dimensionTypeEntry();
                final Path regionDir = ChunkisStoragePaths.computeVanillaRegionDirectory(saveRoot, worldKey);
                final List<Path> liveMca = collectFiles(regionDir, "r.*.*.mca");
                final List<Path> backupMca = collectFiles(regionDir, "r.*.*.mca.backup");
                final Path cisDir = ChunkisStoragePaths.computeRegionsDirectory(saveRoot, worldKey);
                final boolean hasCisData = Files.isDirectory(cisDir) && !collectFiles(cisDir, "*.cis").isEmpty();

                if (liveMca.isEmpty()) {
                    if (!backupMca.isEmpty() || hasCisData) {
                        MigrationStateServiceHolder.markAuthoritative(worldKey);
                        LOGGER.info("Chunkis storage is authoritative for {}", worldKey.getValue());
                    }
                    continue;
                }

                if (!backupMca.isEmpty() || hasCisData) {
                    for (final Path staleMca : liveMca) {
                        Files.deleteIfExists(staleMca);
                    }
                    MigrationStateServiceHolder.markAuthoritative(worldKey);
                    LOGGER.warn("Deleted {} stale vanilla MCA file(s) after CIS takeover for {}",
                            liveMca.size(), worldKey.getValue());
                    continue;
                }

                final OfflineWorldMigrationReport report = OfflineMcaCisTranslator.translateWorld(
                        saveRoot,
                        worldKey,
                        registryManager,
                        dimensionType
                );
                verifyMigrationSucceeded(report, saveRoot.resolve("chunkis"));
                MigrationStateServiceHolder.markAuthoritative(worldKey);
                LOGGER.info("Chunkis migration completed; CIS is authoritative for {}", worldKey.getValue());
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Chunkis pre-launch migration failed for " + saveRoot, e);
        }
    }

    /**
     * Aborts startup when the offline migration gate recorded chunk failures for
     * a dimension.
     *
     * @param report   migration result to inspect
     * @param saveRoot save root used for the failure message context
     */
    static void verifyMigrationSucceeded(
            final OfflineWorldMigrationReport report,
            final Path saveRoot
    ) {
        if (report.failedChunks() > 0) {
            throw new IllegalStateException(
                    "Chunkis pre-launch migration failed for "
                            + report.worldKey()
                            .getValue()
                            + " under "
                            + saveRoot
            );
        }
    }

    /**
     * Collects files directly under a directory matching the supplied glob pattern.
     *
     * @param dir  directory to scan
     * @param glob glob passed to {@link Files#newDirectoryStream(Path, String)}
     * @return matching child paths, or an empty list when the directory is absent
     * @throws IOException if directory enumeration fails
     */
    private static List<Path> collectFiles(final Path dir, final String glob) throws IOException {
        final List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (final Path path : stream) {
                result.add(path);
            }
        }
        return result;
    }
}
