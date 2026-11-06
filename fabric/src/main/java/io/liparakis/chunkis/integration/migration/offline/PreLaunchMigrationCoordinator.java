package io.liparakis.chunkis.integration.migration.offline;

import io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.WorldStem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
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
     * File in a dimension's CIS directory written only after a verified migration.
     */
    static final String MIGRATION_COMPLETE_MARKER = ".migration-complete";

    /**
     * Suffix given to vanilla region files found after migration completed.
     */
    static final String STALE_SUFFIX = ".stale";

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
     * @param newWorld   whether Minecraft is booting a freshly created world
     */
    public static void runBeforeIntegratedServerStart(final LevelStorageSource.LevelStorageAccess session,
            final WorldStem saveLoader,
            final boolean newWorld) {
        final Path saveRoot = session.getLevelPath(LevelResource.ROOT);

        try {
            MigrationStateServiceHolder.clear();
            final RegistryAccess registryManager = saveLoader.registries()
                    .compositeAccess();
            final Registry<LevelStem> dimensionRegistry = registryManager.lookupOrThrow(Registries.LEVEL_STEM);

            for (final var entry : dimensionRegistry.entrySet()) {
                final ResourceKey<Level> worldKey = Registries.levelStemToLevel(entry.getKey());
                if (newWorld) {
                    MigrationStateServiceHolder.markAuthoritative(worldKey);
                    LOGGER.info("Chunkis storage is authoritative from first boot for {}", worldKey.identifier());
                    continue;
                }
                final Holder<DimensionType> dimensionType = entry.getValue()
                        .type();
                final Path regionDir = ChunkisStoragePaths.computeVanillaRegionDirectory(saveRoot, worldKey);
                final Path entityDir = ChunkisStoragePaths.computeVanillaEntitiesDirectory(saveRoot, worldKey);
                final List<Path> liveMca = collectFiles(regionDir, "r.*.*.mca");
                final List<Path> liveEntityMca = collectFiles(entityDir, "r.*.*.mca");
                final Path cisDir = ChunkisStoragePaths.computeRegionsDirectory(saveRoot, worldKey);
                final Path completionMarker = cisDir.resolve(MIGRATION_COMPLETE_MARKER);
                final boolean hasCisData = Files.isDirectory(cisDir) && !collectFiles(cisDir, "*.cis").isEmpty();

                switch (decide(Files.exists(completionMarker), !liveMca.isEmpty() || !liveEntityMca.isEmpty())) {
                    case AUTHORITATIVE -> {
                        writeCompletionMarker(completionMarker);
                        MigrationStateServiceHolder.markAuthoritative(worldKey);
                        LOGGER.info("Chunkis storage is authoritative for {}", worldKey.identifier());
                    }
                    case SET_ASIDE_STALE_SOURCES -> {
                        setAside(liveMca);
                        setAside(liveEntityMca);
                        MigrationStateServiceHolder.markAuthoritative(worldKey);
                        LOGGER.warn("Chunkis: {} vanilla region file(s) and {} entity region file(s) appeared in {} "
                                        + "after migration completed; renamed to *{} (not deleted, not imported)",
                                liveMca.size(),
                                liveEntityMca.size(),
                                worldKey.identifier(),
                                STALE_SUFFIX);
                    }
                    case MIGRATE -> {
                        if (hasCisData) {
                            LOGGER.warn("Chunkis: Resuming interrupted migration for {} ({} region file(s) left)",
                                    worldKey.identifier(),
                                    liveMca.size());
                        }
                        final OfflineWorldMigrationReport report = OfflineMcaCisTranslator.translateWorld(saveRoot,
                                worldKey,
                                registryManager,
                                dimensionType);
                        verifyMigrationSucceeded(report, saveRoot.resolve("chunkis"));
                        writeCompletionMarker(completionMarker);
                        MigrationStateServiceHolder.markAuthoritative(worldKey);
                        LOGGER.info("Chunkis migration completed; CIS is authoritative for {}", worldKey.identifier());
                    }
                }
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
    static void verifyMigrationSucceeded(final OfflineWorldMigrationReport report, final Path saveRoot) {
        if (report.failedChunks() > 0) {
            throw new IllegalStateException("Chunkis pre-launch migration failed for " + report.worldKey()
                    .identifier() + " under " + saveRoot + ": " + report.failedChunks() + " chunk(s) failed, "
                    + report.handledChunks() + " converted, " + report.retiredRegions()
                    + " region(s) retired. Failed regions were"
                    + " kept in place and will be retried on the next launch; see the log for chunk coordinates.");
        }
    }

    /**
     * Decides what to do with one existing dimension.
     *
     * <p>Only the persisted completion marker proves a finished migration. CIS files
     * alone do not: an interrupted or failed run leaves both CIS files and
     * un-migrated vanilla regions, which must be resumed, never deleted.</p>
     *
     * @param migrationComplete   whether the completion marker exists
     * @param hasLiveVanillaFiles whether any vanilla region or entity file remains
     * @return the action to take
     */
    static DimensionAction decide(final boolean migrationComplete, final boolean hasLiveVanillaFiles) {
        if (!hasLiveVanillaFiles) {
            return DimensionAction.AUTHORITATIVE;
        }
        return migrationComplete ? DimensionAction.SET_ASIDE_STALE_SOURCES : DimensionAction.MIGRATE;
    }

    /**
     * Writes the completion marker atomically.
     */
    private static void writeCompletionMarker(final Path marker) throws IOException {
        if (Files.exists(marker)) {
            return;
        }
        Files.createDirectories(marker.getParent());
        final Path temp = marker.resolveSibling(marker.getFileName() + ".tmp");
        Files.writeString(temp, "complete\n");
        Files.move(temp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Renames vanilla region files out of the {@code r.*.*.mca} glob without deleting them.
     */
    private static void setAside(final List<Path> files) throws IOException {
        for (final Path file : files) {
            Files.move(file,
                    file.resolveSibling(file.getFileName() + STALE_SUFFIX),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Per-dimension migration action.
     */
    enum DimensionAction {
        /**
         * Nothing vanilla remains; CIS is the store.
         */
        AUTHORITATIVE,
        /**
         * Migration finished earlier but vanilla files reappeared; keep them aside.
         */
        SET_ASIDE_STALE_SOURCES,
        /**
         * First migration or resume of an interrupted one.
         */
        MIGRATE
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
