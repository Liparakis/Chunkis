package io.liparakis.chunkis.migration.offline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Coordinates the blocking offline migration stage before integrated-server
 * startup begins.
 */
public final class PreLaunchMigrationCoordinator {

    /**
     * Directory name for migration manifests.
     */
    private static final String MIGRATION_REPORT_DIR = "migration";

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
        final Path migrationReportDir = saveRoot.resolve("chunkis").resolve(MIGRATION_REPORT_DIR);

        try {
            Files.createDirectories(migrationReportDir);
            final DynamicRegistryManager registryManager =
                    saveLoader.combinedDynamicRegistries().getCombinedRegistryManager();
            final Registry<DimensionOptions> dimensionRegistry =
                    registryManager.getOrThrow(RegistryKeys.DIMENSION);

            for (final var entry : dimensionRegistry.getEntrySet()) {
                final RegistryKey<World> worldKey = RegistryKeys.toWorldKey(entry.getKey());
                final RegistryEntry<DimensionType> dimensionType = entry.getValue().dimensionTypeEntry();
                final OfflineWorldMigrationReport report = OfflineMcaCisTranslator.translateWorld(
                        saveRoot,
                        worldKey,
                        registryManager,
                        dimensionType,
                        migrationReportDir
                );
                verifyMigrationSucceeded(report, migrationReportDir);
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Chunkis pre-launch migration failed for " + saveRoot, e);
        }
    }

    /**
     * Aborts startup when the offline migration gate recorded chunk failures for
     * a dimension.
     */
    static void verifyMigrationSucceeded(
            final OfflineWorldMigrationReport report,
            final Path migrationReportDir
    ) {
        if (report.failedChunks() > 0) {
            throw new IllegalStateException(
                    "Chunkis pre-launch migration failed for "
                            + report.worldKey().getValue()
                            + "; see "
                            + migrationReportDir
            );
        }
    }
}
