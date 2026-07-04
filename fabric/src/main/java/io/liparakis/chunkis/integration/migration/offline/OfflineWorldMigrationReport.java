package io.liparakis.chunkis.integration.migration.offline;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * Aggregate outcome for one dimension's offline MCA migration pass.
 */
public record OfflineWorldMigrationReport(
        RegistryKey<World> worldKey,
        int scannedRegions,
        int handledChunks,
        int failedChunks,
        int retiredRegions
) {

    /**
     * Returns an empty no-op report.
     */
    public static OfflineWorldMigrationReport empty(final RegistryKey<World> worldKey) {
        return new OfflineWorldMigrationReport(worldKey, 0, 0, 0, 0);
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    /**
     * Returns a one-line JSON representation suitable for append-only manifests.
     */
    public String toJsonLine() {
        return "{"
                + "\"worldId\":\"" + escape(worldKey.getValue()
                .toString()) + "\","
                + "\"scannedRegions\":" + scannedRegions + ","
                + "\"handledChunks\":" + handledChunks + ","
                + "\"failedChunks\":" + failedChunks + ","
                + "\"retiredRegions\":" + retiredRegions
                + "}";
    }
}
