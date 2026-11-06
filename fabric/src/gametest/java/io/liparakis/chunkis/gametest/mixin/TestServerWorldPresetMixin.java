package io.liparakis.chunkis.gametest.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lets the stress benchmark run on normally generated terrain: with {@code -Dchunkis.stress.natural=true} the
 * game test server creates its world from the default world preset instead of the superflat one. The world seed
 * stays the test server's fixed one, so every run sees the same terrain.
 */
@Mixin(GameTestServer.class)
public abstract class TestServerWorldPresetMixin {

    /**
     * Swaps the world preset the test server looks up when it creates its world.
     *
     * @param preset preset the test server asks for (superflat)
     * @return the default preset in natural stress mode, else {@code preset}
     */
    @ModifyArg(method = "lambda$create$1", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/HolderLookup$RegistryLookup;getOrThrow(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;"))
    private static ResourceKey<WorldPreset> chunkis$naturalTerrain(final ResourceKey<WorldPreset> preset) {
        return Boolean.getBoolean("chunkis.stress.natural") ? WorldPresets.NORMAL : preset;
    }
}
