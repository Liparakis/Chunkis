package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;

/**
 * Removes stale vanilla entity-region files after an authoritative world unload.
 */
public final class VanillaEntityRegionCleanup {

    /**
     * Stores pending directories.
     */
    private static final Set<Path> PENDING_DIRECTORIES = ConcurrentHashMap.newKeySet();

    /**
     * Performs vanilla entity region cleanup.
     */
    private VanillaEntityRegionCleanup() {
    }

    /**
     * Deletes vanilla external entity MCA files for a CIS-authoritative world.
     *
     * @param world world being unloaded
     */
    public static void delete(final ServerWorld world) {
        if (!MigrationStateServiceHolder.isAuthoritative(world.getRegistryKey())) {
            return;
        }

        final Path directory = ChunkisStoragePaths.computeVanillaEntitiesDirectory(
                Objects.requireNonNull(world.getServer()).getSavePath(WorldSavePath.ROOT),
                world.getRegistryKey());
        PENDING_DIRECTORIES.add(directory);
        deleteDirectory(directory);
    }

    /**
     * Retries cleanup after the server has closed vanilla storage handles.
     */
    public static void deletePending() {
        for (final Path directory : Set.copyOf(PENDING_DIRECTORIES)) {
            deleteDirectory(directory);
        }
        PENDING_DIRECTORIES.clear();
    }

    /**
     * Performs delete directory.
     */
    private static void deleteDirectory(final Path directory) {
        if (!Files.isDirectory(directory)) {
            PENDING_DIRECTORIES.remove(directory);
            return;
        }

        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "r.*.*.mca")) {
            for (final Path file : files) {
                if (Files.deleteIfExists(file)) {
                    deleted++;
                }
            }
        } catch (final IOException e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to delete vanilla entity regions in {}", directory, e);
            return;
        }

        PENDING_DIRECTORIES.remove(directory);

        if (deleted > 0) {
            Chunkis.LOGGER.info("Chunkis: Deleted {} vanilla entity region file(s) from {}", deleted, directory);
        }
    }
}
