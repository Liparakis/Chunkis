package io.liparakis.chunkis.integration.migration.offline;

/**
 * Per-region migration report written after each offline translation attempt.
 */
public record OfflineRegionMigrationReport(
        String worldId,
        String regionFileName,
        int presentChunks,
        int handledChunks,
        int failedChunks,
        boolean retired,
        String translatorVersion
) {

    private static String escape(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    /**
     * Returns whether the region met the retirement criteria.
     */
    public boolean canRetireSourceRegion() {
        return failedChunks == 0
                && presentChunks == handledChunks;
    }

    /**
     * Returns a one-line JSON representation suitable for append-only manifests.
     */
    public String toJsonLine() {
        return "{"
                + "\"worldId\":\"" + escape(worldId) + "\","
                + "\"region\":\"" + escape(regionFileName) + "\","
                + "\"presentChunks\":" + presentChunks + ","
                + "\"handledChunks\":" + handledChunks + ","
                + "\"failedChunks\":" + failedChunks + ","
                + "\"retired\":" + retired + ","
                + "\"translatorVersion\":\"" + escape(translatorVersion) + "\""
                + "}";
    }
}
