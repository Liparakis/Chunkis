package io.liparakis.chunkis.gametest.mixin.profile;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Event;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Generation;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts generation steps and times vanilla's full-chunk conversion on the server thread.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.status.ChunkStatusTasks")
public abstract class ChunkGeneratingProfileMixin {

    /**
     * Counts terrain generated from scratch.
     *
     * @param cir callback
     */
    @Inject(method = "generateNoise", at = @At("HEAD"))
    private static void chunkis$noise(final CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        StageProfiler.generated(Generation.NOISE);
    }

    /**
     * Counts feature generation.
     *
     * @param cir callback
     */
    @Inject(method = "generateFeatures", at = @At("HEAD"))
    private static void chunkis$features(final CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        StageProfiler.generated(Generation.FEATURES);
    }

    /**
     * Counts light initialization.
     *
     * @param cir callback
     */
    @Inject(method = "initializeLight", at = @At("HEAD"))
    private static void chunkis$initializeLight(final CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        StageProfiler.generated(Generation.INITIALIZE_LIGHT);
    }

    /**
     * Counts lighting.
     *
     * @param cir callback
     */
    @Inject(method = "light", at = @At("HEAD"))
    private static void chunkis$light(final CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        StageProfiler.generated(Generation.LIGHT);
    }

    /**
     * Times the full-chunk conversion where it runs, on the server thread, and marks the chunk full.
     *
     * @param work     the conversion
     * @param executor server thread executor
     * @param original the call
     * @param chunk    chunk being converted
     * @return the conversion's future
     */
    @WrapOperation(method = "full", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<Object> chunkis$timeFullChunk(final Supplier<Object> work,
            final Executor executor, final Operation<CompletableFuture<Object>> original,
            @Local(argsOnly = true) final ChunkAccess chunk) {
        StageProfiler.generated(Generation.FULL);
        if (!StageProfiler.ENABLED) {
            return original.call(work, executor);
        }
        final ChunkPos pos = chunk.getPos();
        return original.call((Supplier<Object>) () -> {
            StageProfiler.begin(Stage.FULL_CHUNK);
            final Object full = work.get();
            StageProfiler.end(Stage.FULL_CHUNK);
            StageProfiler.event(Event.FULL, pos.x(), pos.z());
            return full;
        }, executor);
    }
}
