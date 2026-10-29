package io.liparakis.chunkis.gametest.mixin;

import io.liparakis.chunkis.gametest.stress.StressBenchGameTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.TestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The gametest server skips vanilla's wait for the next tick and runs ticks back to back. While the stress
 * benchmark asks for a real tick rate, restore vanilla's wait so the server keeps its own tick budget, which
 * chunk unloading and other between-tick work depend on.
 */
@Mixin(TestServer.class)
public abstract class TestServerMixin {

    /**
     * Runs vanilla's tick-end wait instead of the gametest server's immediate return.
     *
     * @param ci callback info, cancelled when pacing
     */
    @Inject(method = "runTasksTillTickEnd", at = @At("HEAD"), cancellable = true)
    private void chunkis$waitForNextTick(final CallbackInfo ci) {
        if (!StressBenchGameTest.isPacingTicks()) {
            return;
        }
        final MinecraftServer server = (MinecraftServer) (Object) this;
        final MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
        accessor.chunkis$setWaitingForNextTick(true);
        try {
            server.runTasks(() -> !accessor.chunkis$shouldKeepTicking());
        } finally {
            accessor.chunkis$setWaitingForNextTick(false);
        }
        ci.cancel();
    }
}
