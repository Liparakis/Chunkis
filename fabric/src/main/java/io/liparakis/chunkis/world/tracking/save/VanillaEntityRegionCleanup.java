package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.integration.migration.offline.RegionFileRetirement;
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
 * Retires stale vanilla entity-region files to {@code .backup} after an authoritative world unload.
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
     * Retires vanilla external entity MCA files of a CIS-authoritative world to {@code .backup}.
     *
     * @param world world being unloaded
     */
    public static void retire(final ServerWorld world) {
        if (!MigrationStateServiceHolder.isAuthoritative(world.getRegistryKey())) {
            return;
        }

        final Path directory = ChunkisStoragePaths.computeVanillaEntitiesDirectory(
                Objects.requireNonNull(world.getServer()).getSavePath(WorldSavePath.ROOT),
                world.getRegistryKey());
        PENDING_DIRECTORIES.add(directory);
        retireDirectory(directory);
    }

    /**
     * Retries cleanup after the server has closed vanilla storage handles.
     */
    public static void retirePending() {
        for (final Path directory : Set.copyOf(PENDING_DIRECTORIES)) {
            retireDirectory(directory);
        }
        PENDING_DIRECTORIES.clear();
    }

    /**
     * Retires every vanilla entity region file in one directory.
     */
    private static void retireDirectory(final Path directory) {
        if (!Files.isDirectory(directory)) {
            PENDING_DIRECTORIES.remove(directory);
            return;
        }

        int retired = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "r.*.*.mca")) {
            for (final Path file : files) {
                // Entity data not imported by migration must stay recoverable: back up, never delete.
                if (RegionFileRetirement.retire(file)) {
                    retired++;
                }
            }
        } catch (final IOException e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to retire vanilla entity regions in {}", directory, e);
            return;
        }

        PENDING_DIRECTORIES.remove(directory);

        if (retired > 0) {
            Chunkis.LOGGER.info("Chunkis: Retired {} vanilla entity region file(s) to .backup in {}", retired, directory);
        }
    }
}
