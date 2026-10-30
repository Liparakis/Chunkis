package io.liparakis.chunkis.gametest.mixin.profile;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Event;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks when vanilla asks for a chunk's stored data, when that data is ready, when the load finishes and when the
 * chunk starts unloading, on the load path of the vanilla chunk system (C2ME replaces it, so these events are
 * missing under C2ME).
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class VanillaLoadProfileMixin {

    /**
     * Marks the load request.
     *
     * @param pos chunk position
     * @param cir callback
     */
    @Inject(method = "loadChunk", at = @At("HEAD"))
    private void chunkis$requested(final ChunkPos pos, final CallbackInfoReturnable<?> cir) {
        StageProfiler.event(Event.REQUESTED, pos.x, pos.z);
    }

    /**
     * Marks the load done once its future completes, whether it converted stored data or found none.
     *
     * @param pos chunk position
     * @param cir callback holding the load's future
     */
    @Inject(method = "loadChunk", at = @At("RETURN"))
    private void chunkis$loadDone(final ChunkPos pos, final CallbackInfoReturnable<CompletableFuture<?>> cir) {
        if (StageProfiler.ENABLED) {
            cir.getReturnValue().whenComplete((chunk, error) -> StageProfiler.event(Event.LOAD_DONE, pos.x, pos.z));
        }
    }

    /**
     * Ends the chunk's timeline when vanilla starts unloading it.
     *
     * @param pos packed chunk position
     * @param ci  callback
     */
    @Inject(method = "tryUnloadChunk", at = @At("HEAD"))
    private void chunkis$unloading(final long pos, final net.minecraft.server.world.ChunkHolder holder,
            final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        StageProfiler.unloaded(ChunkPos.getPackedX(pos), ChunkPos.getPackedZ(pos));
    }

    /**
     * Marks the stored data ready once its future completes.
     *
     * @param manager  this manager
     * @param pos      chunk position
     * @param original the call
     * @return the same future
     */
    @WrapOperation(method = "loadChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/"
            + "ServerChunkLoadingManager;getUpdatedChunkNbt(Lnet/minecraft/util/math/ChunkPos;)"
            + "Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Optional<NbtCompound>> chunkis$nbtReady(final ServerChunkLoadingManager manager,
            final ChunkPos pos, final Operation<CompletableFuture<Optional<NbtCompound>>> original) {
        final CompletableFuture<Optional<NbtCompound>> future = original.call(manager, pos);
        if (StageProfiler.ENABLED) {
            future.whenComplete((nbt, error) -> StageProfiler.event(Event.NBT_READY, pos.x, pos.z));
        }
        return future;
    }
}
