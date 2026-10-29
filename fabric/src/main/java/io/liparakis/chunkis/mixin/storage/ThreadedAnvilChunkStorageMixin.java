package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder;
import io.liparakis.chunkis.world.tracking.save.UnfinishedChunkStore;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.world.tracking.load.SyntheticLoadNbtProvider;
import io.liparakis.chunkis.world.tracking.save.ChunkSaveCoordinator;
import io.liparakis.chunkis.world.tracking.save.ChunkSaveCoordinatorHolder;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Util;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes {@link ServerChunkLoadingManager} chunk load and save through Chunkis' CIS
 * delta storage instead of vanilla MCA files.
 *
 * <p>Thin hooks only: the save path lives in {@link ChunkSaveCoordinator} and the
 * synthetic load path in {@link SyntheticLoadNbtProvider}, one of each per world.</p>
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ThreadedAnvilChunkStorageMixin implements ChunkSaveCoordinatorHolder {

    /**
     * The server world that owns this chunk loading manager.
     */
    @Shadow
    @Final
    ServerWorld world;

    /**
     * Save path for {@link #world}, created on first use.
     */
    @Unique
    private ChunkSaveCoordinator chunkis$saveCoordinator;

    /**
     * Synthetic load path for {@link #world}, created on first use.
     */
    @Unique
    private SyntheticLoadNbtProvider chunkis$loadProvider;

    /**
     * Default constructor for ThreadedAnvilChunkStorageMixin.
     */
    public ThreadedAnvilChunkStorageMixin() {
    }

    /**
     * Returns this world's save coordinator, creating it on first use.
     *
     * @return the save coordinator
     */
    @Override
    public ChunkSaveCoordinator chunkis$saveCoordinator() {
        if (chunkis$saveCoordinator == null) {
            chunkis$saveCoordinator = new ChunkSaveCoordinator(world);
        }
        return chunkis$saveCoordinator;
    }

    /**
     * Returns this world's synthetic load provider, creating it on first use.
     *
     * @return the load provider
     */
    @Unique
    private SyntheticLoadNbtProvider chunkis$loadProvider() {
        if (chunkis$loadProvider == null) {
            chunkis$loadProvider = new SyntheticLoadNbtProvider(world);
        }
        return chunkis$loadProvider;
    }

    /**
     * Flushes Chunkis saves and closes CIS storage during server shutdown.
     *
     * <p>Injected at {@code TAIL} so Minecraft's own final save pass has already run
     * while Chunkis storage is still open.</p>
     *
     * @param ci the Mixin {@link CallbackInfo}
     */
    @Inject(method = "close", at = @At("TAIL"))
    private void chunkis$onClose(final CallbackInfo ci) {
        chunkis$saveCoordinator().close();
    }

    /**
     * Provides CIS-backed NBT for chunk loading, replacing vanilla MCA loading when
     * Chunkis has state for the chunk.
     *
     * <p>Vanilla dispatches chunk loads one after another and only expects a future back, so
     * the storage read, decode and NBT build run on a worker: done in place, every Chunkis
     * load would hold up every load queued behind it. If Chunkis turns out to have nothing
     * restorable, the future falls back to vanilla's own read.</p>
     *
     * @param chunkPos the chunk position being loaded
     * @param cir      callback; replaced when Chunkis handles the load
     */
    @Inject(
            method = "getUpdatedChunkNbt(Lnet/minecraft/util/math/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true)
    private void chunkis$onGetUpdatedChunkNbt(
            final ChunkPos chunkPos,
            final CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>> cir) {
        final SyntheticLoadNbtProvider provider = chunkis$loadProvider();
        final boolean authoritative = MigrationStateServiceHolder.isAuthoritative(world.getRegistryKey());
        if (!provider.mayProvide(chunkPos)) {
            if (authoritative) {
                cir.setReturnValue(chunkis$unfinishedOrVanillaChunkNbt(chunkPos));
            }
            return;
        }
        cir.setReturnValue(CompletableFuture
                .supplyAsync(() -> provider.provide(chunkPos), Util.getMainWorkerExecutor().named("chunkisLoad"))
                .thenCompose(root -> root != null
                        ? CompletableFuture.completedFuture(Optional.of(root))
                        : authoritative ? chunkis$unfinishedOrVanillaChunkNbt(chunkPos)
                        : chunkis$vanillaChunkNbt(chunkPos)));
    }

    /**
     * Reads a chunk an authoritative world keeps unfinished, upgraded like vanilla's own reads, or else falls back
     * to vanilla's region files.
     *
     * @param chunkPos chunk to read
     * @return the chunk's NBT, if any
     */
    @Unique
    private CompletableFuture<Optional<NbtCompound>> chunkis$unfinishedOrVanillaChunkNbt(final ChunkPos chunkPos) {
        return chunkis$saveCoordinator().unfinishedChunks()
                .read(chunkPos)
                .thenCompose(nbt -> nbt.isPresent()
                        ? CompletableFuture.supplyAsync(() -> nbt.map(this::updateChunkNbt)
                                .map(UnfinishedChunkStore::markIfGenerating),
                        Util.getMainWorkerExecutor().named("upgradeChunk"))
                        : chunkis$vanillaChunkNbt(chunkPos));
    }

    /**
     * Vanilla's own {@code getUpdatedChunkNbt}, for a load Chunkis declined after all.
     *
     * @param chunkPos the chunk position being loaded
     * @return the region-file NBT, upgraded to the current version
     */
    @Unique
    private CompletableFuture<Optional<NbtCompound>> chunkis$vanillaChunkNbt(final ChunkPos chunkPos) {
        return ((VersionedChunkStorage) (Object) this).getNbt(chunkPos)
                .thenApplyAsync(nbt -> nbt.map(this::updateChunkNbt), Util.getMainWorkerExecutor().named("upgradeChunk"));
    }

    /**
     * Upgrades region-file chunk NBT to the current data version.
     *
     * @param nbt stored chunk NBT
     * @return upgraded NBT
     */
    @Shadow
    private native NbtCompound updateChunkNbt(NbtCompound nbt);

    /**
     * Saves chunks Chunkis owns through CIS storage.
     *
     * <p>Hooks {@code save(Chunk)}, the single funnel that autosave (whose holder
     * overload keeps vanilla's throttle and eligibility checks), chunk unload and the
     * shutdown flush all reach.</p>
     *
     * @param chunkToSave the chunk vanilla is saving
     * @param cir         callback; set when Chunkis handled the save
     */
    @Inject(
            method = "save(Lnet/minecraft/world/chunk/Chunk;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void chunkis$onSave(
            final Chunk chunkToSave,
            final CallbackInfoReturnable<Boolean> cir) {
        switch (chunkis$saveCoordinator().save(chunkToSave)) {
            case VANILLA -> {
                // Not Chunkis-owned: vanilla saves it.
            }
            case HANDLED_DIRTY -> cir.setReturnValue(Boolean.TRUE);
            case HANDLED_CLEAN -> cir.setReturnValue(Boolean.FALSE);
        }
    }

    /**
     * Dispatches entity tracking traces during chunk transfers.
     *
     * @param entity  target transferring entity
     * @param stage   lifecycle stage name
     * @param source  class/method trace source trigger label
     * @param message description detail text
     */
    @Unique
    private void chunkis$traceWatchedEntityTransfer(
            final Entity entity,
            final String stage,
            final String source,
            final String message
    ) {
        if (entity == null) {
            return;
        }
        PayloadWatchTracer.traceEntityChunkTransfer(
                world,
                entity,
                null,
                stage,
                source,
                message
        );
    }

    /**
     * Replays pending restore entities inside WorldChunk if applicable.
     *
     * @param chunk target world chunk
     */
    @Unique
    @SuppressWarnings("unchecked")
    private void chunkis$replayPendingEntities(final WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return;
        }
        if (deltaDuck.chunkis$getRestoreOperationId() != null) {
            return;
        }
        if (!(deltaDuck.chunkis$getDelta() instanceof ChunkDelta<?, ?> rawDelta)) {
            return;
        }
        final ChunkDelta<BlockState, NbtCompound> delta =
                (ChunkDelta<BlockState, NbtCompound>) rawDelta;
        if (delta.countPendingEntities() == 0) {
            return;
        }
        io.liparakis.chunkis.world.restoration.core.ChunkRestorer.replayPendingEntitiesIfNeeded(
                world,
                chunk,
                delta,
                deltaDuck.chunkis$getRestoreOperationId()
        );
    }

    /**
     * Injects at head of loadEntity to trace watched entity transfers.
     *
     * @param entity target loaded entity
     * @param ci     callback info helper
     */
    @Inject(method = "loadEntity", at = @At("HEAD"))
    private void chunkis$traceWatchedEntityLoad(final Entity entity, final CallbackInfo ci) {
        chunkis$traceWatchedEntityTransfer(
                entity,
                "entity-manager-load",
                "ThreadedAnvilChunkStorageMixin#chunkis$traceWatchedEntityLoad",
                "watched entity entered ServerChunkLoadingManager.loadEntity"
        );
    }

    /**
     * Injects at head of unloadEntity to trace watched entity transfers.
     *
     * @param entity target unloaded entity
     * @param ci     callback info helper
     */
    @Inject(method = "unloadEntity", at = @At("HEAD"))
    private void chunkis$traceWatchedEntityUnload(final Entity entity, final CallbackInfo ci) {
        chunkis$traceWatchedEntityTransfer(
                entity,
                "entity-manager-unload",
                "ThreadedAnvilChunkStorageMixin#chunkis$traceWatchedEntityUnload",
                "watched entity entered ServerChunkLoadingManager.unloadEntity"
        );
    }

    /**
     * Injects at return of makeChunkEntitiesTickable to trace re-entry check statuses.
     *
     * @param holder chunk holder instance
     * @param cir    callback info returnable wrapper
     */
    @Inject(method = "makeChunkEntitiesTickable", at = @At("RETURN"))
    private void chunkis$traceChunkEntitiesTickable(
            final ChunkHolder holder,
            final CallbackInfoReturnable<CompletableFuture<net.minecraft.server.world.OptionalChunk<WorldChunk>>> cir
    ) {
        cir.getReturnValue()
                .thenAccept(optionalChunk ->
                        optionalChunk.ifPresent(chunk -> {
                            chunkis$replayPendingEntities(chunk);
                            PayloadWatchTracer.traceEntityChunkReentry(
                                    world,
                                    chunk,
                                    "chunk-entities-tickable",
                                    "ThreadedAnvilChunkStorageMixin#chunkis$traceChunkEntitiesTickable",
                                    "chunk became entity-tickable"
                            );
                        }));
    }

    /**
     * Injects at return of makeChunkTickable to trace re-entry check statuses.
     *
     * @param holder chunk holder instance
     * @param cir    callback info returnable wrapper
     */
    @Inject(method = "makeChunkTickable", at = @At("RETURN"))
    private void chunkis$traceChunkTickable(
            final ChunkHolder holder,
            final CallbackInfoReturnable<CompletableFuture<net.minecraft.server.world.OptionalChunk<WorldChunk>>> cir
    ) {
        cir.getReturnValue()
                .thenAccept(optionalChunk ->
                        optionalChunk.ifPresent(chunk -> {
                            chunkis$replayPendingEntities(chunk);
                            PayloadWatchTracer.traceEntityChunkReentry(
                                    world,
                                    chunk,
                                    "chunk-tickable",
                                    "ThreadedAnvilChunkStorageMixin#chunkis$traceChunkTickable",
                                    "chunk became tickable"
                            );
                        }));
    }
}
