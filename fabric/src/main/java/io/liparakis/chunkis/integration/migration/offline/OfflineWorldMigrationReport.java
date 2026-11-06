package io.liparakis.chunkis.integration.migration.offline;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Aggregate outcome for one dimension's offline MCA migration pass.
 */
public record OfflineWorldMigrationReport(
        ResourceKey<Level> worldKey,
        int scannedRegions,
        int handledChunks,
        int failedChunks,
        int retiredRegions
) {

    /**
     * Returns an empty no-op report.
     */
    public static OfflineWorldMigrationReport empty(final ResourceKey<Level> worldKey) {
        return new OfflineWorldMigrationReport(worldKey, 0, 0, 0, 0);
    }

    /**
     * Performs escape.
     */
    private static String escape(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    /**
     * Returns a one-line JSON representation suitable for append-only manifests.
     */
    public String toJsonLine() {
        return "{"
                + "\"worldId\":\"" + escape(worldKey.identifier()
                .toString()) + "\","
                + "\"scannedRegions\":" + scannedRegions + ","
                + "\"handledChunks\":" + handledChunks + ","
                + "\"failedChunks\":" + failedChunks + ","
                + "\"retiredRegions\":" + retiredRegions
                + "}";
    }
}
