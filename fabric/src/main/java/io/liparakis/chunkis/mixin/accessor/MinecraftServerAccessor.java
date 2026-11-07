package io.liparakis.chunkis.mixin.accessor;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the immutable save-directory descriptor without touching Minecraft's mutable resource-path cache. */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    /** Returns the server's final save access; callers must not mutate or close it. */
    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess chunkis$getStorageSource();
}
