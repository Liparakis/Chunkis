package io.liparakis.chunkis.mixin.client.storage;

import io.liparakis.chunkis.client.migration.PreLaunchMigrationGate;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs Chunkis' offline migration gate before integrated-server startup begins.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {

    /**
     * Executes the blocking pre-launch MCA-to-CIS migration stage after the save
     * session lock exists but before the integrated server is started.
     */
    @Inject(method = "doWorldLoad", at = @At("HEAD"), cancellable = true)
    private void chunkis$runPreLaunchMigrationGate(final LevelStorageSource.LevelStorageAccess session,
            final PackRepository dataPackManager,
            final WorldStem saveLoader,
            final Optional<GameRules> gameRules,
            final boolean newWorld,
            final CallbackInfo ci) {
        if (PreLaunchMigrationGate.shouldBlockStartup((Minecraft) (Object) this,
                session,
                dataPackManager,
                saveLoader,
                gameRules,
                newWorld)) {
            ci.cancel();
        }
    }
}
