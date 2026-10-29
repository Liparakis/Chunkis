package io.liparakis.chunkis.gametest.mixin;

import net.minecraft.registry.RegistryKey;
import net.minecraft.test.TestServer;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lets the stress benchmark run on normally generated terrain: with {@code -Dchunkis.stress.natural=true} the
 * game test server creates its world from the default world preset instead of the superflat one. The world seed
 * stays the test server's fixed one, so every run sees the same terrain.
 */
@Mixin(TestServer.class)
public abstract class TestServerWorldPresetMixin {

    /**
     * Swaps the world preset the test server looks up when it creates its world.
     *
     * @param preset preset the test server asks for (superflat)
     * @return the default preset in natural stress mode, else {@code preset}
     */
    @ModifyArg(method = "method_40377", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/registry/RegistryWrapper$Impl;getOrThrow(Lnet/minecraft/registry/RegistryKey;)"
                    + "Lnet/minecraft/registry/entry/RegistryEntry$Reference;"))
    private static RegistryKey<WorldPreset> chunkis$naturalTerrain(final RegistryKey<WorldPreset> preset) {
        return Boolean.getBoolean("chunkis.stress.natural") ? WorldPresets.DEFAULT : preset;
    }
}
