package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.world.entity.capture.VanillaEntitySaveCapture;
import io.liparakis.chunkis.world.entity.capture.EntityFallbackStore;
import io.liparakis.chunkis.Chunkis;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.storage.TagValueInput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands vanilla's per-chunk entity save to {@link VanillaEntitySaveCapture}.
 *
 * <p>Runs at {@code HEAD}, before entities are unloaded, so the snapshot sees
 * them live. When the region guard would drop vanilla's write anyway, vanilla
 * does not serialize the entities a second time just to have them dropped; an
 * empty list goes on to vanilla, which only records that the chunk has none.</p>
 */
@Mixin(EntityStorage.class)
public abstract class EntityChunkDataAccessMixin {

    /**
     * World whose entities this storage persists.
     */
    @Shadow
    @Final
    private ServerLevel level;

    /**
     * Chunks vanilla knows to hold no entities.
     */
    @Shadow
    @Final
    private LongSet emptyChunks;

    /** Native data fixing configuration. */
    @Shadow
    @Final
    private SimpleRegionStorage simpleRegionStorage;

    /** Native queue preserves the entity loader's thread affinity. */
    @Shadow
    @Final
    private ConsecutiveExecutor entityDeserializerQueue;

    /** Loads the complete fallback list before native empty-cache or CIS replay can hide it. */
    @Inject(method = "loadEntities", at = @At("HEAD"), cancellable = true)
    private void chunkis$loadFallback(final ChunkPos pos,
            final CallbackInfoReturnable<CompletableFuture<ChunkEntities<Entity>>> cir) {
        if (!EntityFallbackStore.owns(level, pos)) {
            return;
        }
        try {
            final CompoundTag saved = EntityFallbackStore.read(EntityFallbackStore.path(level, pos), pos);
            cir.setReturnValue(CompletableFuture.completedFuture(saved).thenApplyAsync(payload -> {
                final CompoundTag upgraded = simpleRegionStorage.upgradeChunkTag(payload, -1);
                try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(
                        ChunkAccess.problemPath(pos), Chunkis.LOGGER)) {
                    return new ChunkEntities<>(pos, EntityType.loadEntitiesRecursive(
                            TagValueInput.create(reporter, level.registryAccess(), upgraded)
                                    .childrenListOrEmpty("Entities"), level, EntitySpawnReason.LOAD).toList());
                }
            }, entityDeserializerQueue::schedule));
        } catch (final IOException failure) {
            final String message = "Cannot load entity fallback for " + pos + " in " + level.dimension().identifier()
                    + " at " + EntityFallbackStore.path(level, pos);
            Chunkis.LOGGER.error(message, failure);
            cir.setReturnValue(CompletableFuture.failedFuture(new UncheckedIOException(message, failure)));
        }
    }

    /**
     * Captures the saved entities into the Chunkis delta.
     *
     * @param dataList chunk position and the entities vanilla is saving
     * @param ci       mixin callback
     */
    @Inject(method = "storeEntities", at = @At("HEAD"), cancellable = true)
    private void chunkis$captureSavedEntities(final ChunkEntities<Entity> dataList, final CallbackInfo ci) {
        VanillaEntitySaveCapture.capture(level, dataList.getPos(), dataList.getEntities()
                .toList());
        if (!dataList.isEmpty() && VanillaEntitySaveCapture.isVanillaWriteBlocked(level)) {
            emptyChunks.remove(dataList.getPos().pack());
            ci.cancel();
        }
    }
}
