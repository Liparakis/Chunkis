package io.liparakis.chunkis.gametest.mixin;

import io.liparakis.chunkis.gametest.OverloadedUnloadGameTest;
import java.util.function.BooleanSupplier;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lets a test run vanilla's chunk unload loop as an overloaded server does: with no tick time left.
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class NoUnloadBudgetMixin {

    /**
     * Replaces the unload loop's tick budget with an exhausted one while a test asks for it.
     *
     * @param shouldKeepTicking vanilla's budget
     * @return the budget to use
     */
    @ModifyVariable(method = "unloadChunks", at = @At("HEAD"), argsOnly = true)
    private BooleanSupplier chunkis$noUnloadBudget(final BooleanSupplier shouldKeepTicking) {
        return OverloadedUnloadGameTest.noBudget() ? () -> false : shouldKeepTicking;
    }
}
