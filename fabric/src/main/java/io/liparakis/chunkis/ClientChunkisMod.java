package io.liparakis.chunkis;

import io.liparakis.chunkis.client.ClientDeltaMetrics;
import io.liparakis.chunkis.client.ClientDeltaNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

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
 *
 * @author Liparakis
 * @version 1.2
 */
@Environment(EnvType.CLIENT)
public final class ClientChunkisMod implements ClientModInitializer {

    /**
     * Logs final client initialization state.
     *
     * <p>Split out so the init method stays tiny and the metrics branch remains
     * readable without inline one-liners.</p>
     */
    private static void logInitializationComplete() {
        if (ClientDeltaMetrics.ENABLED) {
            Chunkis.LOGGER.info("Chunkis Client initialized — metrics enabled.");
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

        logInitializationComplete();
    }
}