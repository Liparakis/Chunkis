package io.liparakis.chunkis.gametest.mixin.profile;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Event;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks when vanilla asks for a chunk's stored data, when that data is ready, when the load finishes and when the
 * chunk starts unloading, on the load path of the vanilla chunk system (C2ME replaces it, so these events are
 * missing under C2ME).
 */
@Mixin(ChunkMap.class)
public abstract class VanillaLoadProfileMixin {

    /**
     * Marks the load request.
     *
     * @param pos chunk position
     * @param cir callback
     */
    @Inject(method = "scheduleChunkLoad", at = @At("HEAD"))
    private void chunkis$requested(final ChunkPos pos, final CallbackInfoReturnable<?> cir) {
        StageProfiler.event(Event.REQUESTED, pos.x(), pos.z());
    }

    /**
     * Marks the load done once its future completes, whether it converted stored data or found none.
     *
     * @param pos chunk position
     * @param cir callback holding the load's future
     */
    @Inject(method = "scheduleChunkLoad", at = @At("RETURN"))
    private void chunkis$loadDone(final ChunkPos pos, final CallbackInfoReturnable<CompletableFuture<?>> cir) {
        if (StageProfiler.ENABLED) {
            cir.getReturnValue().whenComplete((ignoredChunk, ignoredError) -> StageProfiler.event(Event.LOAD_DONE, pos.x(), pos.z()));
        }
    }

    /**
     * Ends the chunk's timeline when vanilla starts unloading it.
     *
     * @param pos packed chunk position
     * @param ci  callback
     */
    @Inject(method = "scheduleUnload", at = @At("HEAD"))
    private void chunkis$unloading(final long pos, final net.minecraft.server.level.ChunkHolder holder,
            final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        StageProfiler.unloaded(ChunkPos.getX(pos), ChunkPos.getZ(pos));
    }

    /**
     * Marks the stored data ready once its future completes.
     *
     * @param manager  this manager
     * @param pos      chunk position
     * @param original the call
     * @return the same future
     */
    @WrapOperation(method = "scheduleChunkLoad", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;readChunk(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Optional<CompoundTag>> chunkis$nbtReady(final ChunkMap manager,
            final ChunkPos pos, final Operation<CompletableFuture<Optional<CompoundTag>>> original) {
        final CompletableFuture<Optional<CompoundTag>> future = original.call(manager, pos);
        if (StageProfiler.ENABLED) {
            future.whenComplete((ignoredNbt, ignoredError) -> StageProfiler.event(Event.NBT_READY, pos.x(), pos.z()));
        }
        return future;
    }
}
