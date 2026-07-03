package io.liparakis.chunkis.migration;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * Tracks the current MCA-to-CIS migration phase for startup messaging.
 */
public final class MigrationProgressTracker {

    private static final AtomicReference<String> STATUS = new AtomicReference<>();

    private MigrationProgressTracker() {
        throw new AssertionError("Utility class");
    }

    public static void begin(final String worldId, final int totalRegions) {
        chunkis$updateStatus("Scanning MCA regions for " + worldId + " (" + totalRegions + " region(s))");
    }

    public static void region(
            final String worldId,
            final String sourceRegionFileName,
            final int currentRegion,
            final int totalRegions
    ) {
        final String targetRegionFileName = sourceRegionFileName.replace(".mca", ".cis");
        chunkis$updateStatus(
                "Converting "
                        + sourceRegionFileName
                        + " region to "
                        + targetRegionFileName
                        + " region... ("
                        + currentRegion
                        + "/"
                        + totalRegions
                        + ") ["
                        + worldId
                        + "]"
        );
    }

    public static void finish(final String worldId) {
        chunkis$updateStatus("Finished MCA -> CIS migration for " + worldId);
    }

    public static void clear() {
        STATUS.set(null);
    }

    public static @Nullable String getStatus() {
        return STATUS.get();
    }

    private static void chunkis$updateStatus(final @Nullable String status) {
        STATUS.set(status);

        final MinecraftClient client = MinecraftClient.getInstance();
        if (status == null || client == null) {
            return;
        }

        client.setScreenAndRender(new MessageScreen(Text.literal(status)));
    }
}
