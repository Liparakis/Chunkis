package io.liparakis.chunkis.migration;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.migrator.CisMigrationReport;
import io.liparakis.chunkis.migrator.CisStorageMigrator;
import io.liparakis.chunkis.migrator.CisVersionMap;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;

/**
 * Upgrades existing CIS region files to the latest CIS format version.
 *
 * <p>Invoked during world load so that all CIS storage is current before
 * gameplay touches it. This is intentionally separate from {@link McaMigrator}:
 * MCA import converts vanilla region files into CIS; this class upgrades
 * CIS-to-CIS across format versions.
 *
 * <p><b>Threading:</b> {@link #migrateWorld} is called during world load. No
 * world state is mutated; only CIS storage files on disk are read and rewritten.
 */
public final class CisWorldMigrator {

    /**
     * Logger instance for writing migration logs.
     */
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private CisWorldMigrator() {
        throw new AssertionError("Utility class");
    }

    /**
     * Upgrades the world's existing CIS storage to the latest supported format.
     *
     * <p>Returns immediately with an empty report if the CIS region directory
     * does not exist, avoiding unnecessary I/O.</p>
     *
     * <p><b>Threading:</b> Must only be called before normal gameplay touches
     * the world's CIS storage.</p>
     *
     * @param world the dimension whose Chunkis storage should be upgraded
     * @return a {@link CisMigrationReport} describing the outcome for this dimension
     */
    public static CisMigrationReport migrateWorld(final ServerWorld world) {
        final Path storageDir = resolveStorageDir(world);
        if (!Files.exists(storageDir)) {
            return CisMigrationReport.empty();
        }

        final Identifier dimId = world.getRegistryKey()
                .getValue();
        LOGGER.info("Checking CIS region directory for world {}: {}", dimId, storageDir);

        final CisStorage<?, ?, ?, ?> storage = FabricCisStorageHelper.getStorage(world);
        final CisStorageMigrator<?, ?> migrator = new CisStorageMigrator<>(storage, LOGGER);
        final CisMigrationReport report = migrator.migrateStorage(storageDir);

        if (report.migratedChunks() > 0 || report.failedChunks() > 0) {
            LOGGER.info(
                    "Chunkis CIS Migration complete for world {}. Target v{}, migrated {}, skipped {}, failed {}.",
                    dimId,
                    CisVersionMap.latestVersion(),
                    report.migratedChunks(),
                    report.skippedChunks(),
                    report.failedChunks()
            );
        }

        return report;
    }

    /**
     * Resolves the Chunkis region directory for the given dimension.
     *
     * <p>The overworld uses {@code <save>/chunkis/regions}. All other dimensions
     * use {@code <save>/dimensions/<namespace>/<path>/chunkis/regions}, mirroring
     * Minecraft's own layout for non-overworld level data.</p>
     *
     * @param world the dimension whose storage directory should be resolved
     * @return absolute path to the dimension's Chunkis region directory
     */
    public static Path resolveStorageDir(final ServerWorld world) {
        final Path root = Objects.requireNonNull(world.getServer())
                .getSavePath(WorldSavePath.ROOT);
        return ChunkisStoragePaths.computeRegionsDirectory(root, world.getRegistryKey());
    }
}
