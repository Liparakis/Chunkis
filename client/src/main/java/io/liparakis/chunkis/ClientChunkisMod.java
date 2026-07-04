package io.liparakis.chunkis;

import io.liparakis.chunkis.client.ClientDeltaMetrics;
import io.liparakis.chunkis.client.ClientDeltaNetworking;
import io.liparakis.chunkis.migration.MigrationProgressTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.text.Text;

/**
 * Client-side Fabric entrypoint for Chunkis.
 *
 * <p>This class intentionally stays thin. Client packet handling, delta decoding,
 * and metrics are delegated to focused client-side components.</p>
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>register client delta networking handlers</li>
 *   <li>log whether client-side delta metrics are enabled</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
@SuppressWarnings("unused")
public final class ClientChunkisMod implements ClientModInitializer {

    /**
     * Logs final client initialization state.
     *
     * <p>Split out so the init method stays tiny and the metrics branch remains
     * readable without inline one-liners.</p>
     */
    private static void logInitializationComplete() {
        if (ClientDeltaMetrics.ENABLED) {
            Chunkis.LOGGER.info("Chunkis Client initialized - metrics enabled.");
            return;
        }

        Chunkis.LOGGER.info("Chunkis Client initialized.");
    }

    /**
     * Fabric client initialization hook.
     */
    @Override
    public void onInitializeClient() {
        Chunkis.LOGGER.info("Chunkis Client initializing...");

        ClientDeltaNetworking.register();

        MigrationProgressTracker.setStatusListener(status -> {
            if (status != null) {
                final MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) {
                    client.setScreenAndRender(new MessageScreen(Text.literal(status)));
                }
            }
        });

        logInitializationComplete();
    }
}