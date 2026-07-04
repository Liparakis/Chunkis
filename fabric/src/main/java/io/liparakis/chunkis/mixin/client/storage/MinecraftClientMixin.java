package io.liparakis.chunkis.mixin.client.storage;

import io.liparakis.chunkis.integration.migration.offline.PreLaunchMigrationCoordinator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.server.SaveLoader;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs Chunkis' offline migration gate before integrated-server startup begins.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    /**
     * Executes the blocking pre-launch MCA-to-CIS migration stage after the save
     * session lock exists but before the integrated server is started.
     */
    @Inject(method = "startIntegratedServer", at = @At("HEAD"))
    private void chunkis$runPreLaunchMigrationGate(
            final LevelStorage.Session session,
            final ResourcePackManager dataPackManager,
            final SaveLoader saveLoader,
            final boolean newWorld,
            final CallbackInfo ci
    ) {
        PreLaunchMigrationCoordinator.runBeforeIntegratedServerStart(session, saveLoader);
    }
}
