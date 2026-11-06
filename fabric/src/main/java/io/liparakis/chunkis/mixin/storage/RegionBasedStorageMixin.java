package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.Chunkis;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder;
import io.liparakis.chunkis.integration.migration.state.VanillaRegionPathResolver;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CIS_AUTHORITATIVE guard for vanilla chunk RegionBasedStorage.
 * Uses the constructor's dimension key; the path selects which storage roles are guarded.
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionBasedStorageMixin {

    /**
     * Backing storage directory captured from the vanilla RegionBasedStorage constructor.
     */
    @Unique
    private Path chunkis$directory;

    /**
     * Dimension whose vanilla chunk or entity regions this storage holds, or {@code null}
     * when it is some other region storage (POI, etc.) that Chunkis never blocks.
     */
    @Unique
    private ResourceKey<Level> chunkis$guardedDimension;

    /**
     * Derives the owning MCA filename for a chunk position.
     *
     * @param pos chunk position
     * @return region filename in {@code r.<x>.<z>.mca} form
     */
    @Unique
    private static String chunkis$regionFileName(final ChunkPos pos) {
        return "r." + (pos.x() >> 5) + "." + (pos.z() >> 5) + ".mca";
    }

    /**
     * Captures the storage directory and its explicit owning dimension for later read/write guards.
     *
     * @param storageKey vanilla storage key
     * @param directory  backing storage directory
     * @param sync       whether vanilla opened the storage in sync mode
     * @param ci         mixin callback
     */
    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Z)V", at = @At("RETURN"))
    private void chunkis$captureDirectory(final RegionStorageInfo storageKey,
            final Path directory,
            final boolean sync,
            final CallbackInfo ci) {
        this.chunkis$directory = directory;
        final var cls = VanillaRegionPathResolver.classify(directory);
        final var dimOpt = VanillaRegionPathResolver.resolveDimension(storageKey, directory);
        // The directory never changes, so classify once instead of on every region read/write/scan.
        this.chunkis$guardedDimension = dimOpt.orElse(null);
        final boolean auth = dimOpt.isPresent()
                && MigrationStateServiceHolder.isAuthoritative(dimOpt.get());
        Chunkis.LOGGER.info(
                "REGION_BASED_STORAGE_INIT path={} classifiedAs={} resolvedDimension={} state={} authoritative={}",
                directory, cls,
                dimOpt.map(k -> k.identifier()
                                .toString())
                        .orElse("unknown"),
                dimOpt.map(k ->
                                MigrationStateServiceHolder.isAuthoritative(k)
                                        ? "CIS_AUTHORITATIVE" : "NOT")
                        .orElse("unknown"),
                auth
        );
    }

    /**
     * Cancels vanilla region writes when Chunkis has already taken authoritative ownership
     * of the dimension's chunk or entity storage.
     *
     * @param pos chunk position being written
     * @param nbt vanilla payload that would have been written
     * @param ci  mixin callback
     * @throws IOException if the diagnostic size probe fails
     */
    @Inject(method = "write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At("HEAD"), cancellable = true)
    private void chunkis$blockCisAuthoritativeWrite(
            final ChunkPos pos,
            final CompoundTag nbt,
            final CallbackInfo ci
    ) throws IOException {
        if (chunkis$shouldBlockVanillaStorage()) {
            if (Chunkis.LOGGER.isDebugEnabled()) {
                final Path regionFile = chunkis$directory.resolve(chunkis$regionFileName(pos));
                Chunkis.LOGGER.debug(
                        "POST_CIS_ANVIL_ACCESS path={} pos={} sizeBefore={} operation=RegionBasedStorage.write stack={}",
                        chunkis$directory, pos,
                        Files.exists(regionFile) ? Files.size(regionFile) : -1,
                        Arrays.toString(Thread.currentThread()
                                .getStackTrace())
                );
            }
            ci.cancel();
        }
    }

    /**
     * Cancels vanilla chunk reads once Chunkis storage is authoritative for the dimension.
     *
     * @param pos chunk position being read
     * @param cir mixin callback
     */
    @Inject(method = "read(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("HEAD"), cancellable = true)
    private void chunkis$blockCisAuthoritativeRead(
            final ChunkPos pos,
            final CallbackInfoReturnable<CompoundTag> cir
    ) {
        if (chunkis$shouldBlockVanillaStorage()) {
            Chunkis.LOGGER.debug("CIS read redirection active for {} pos={}", chunkis$directory, pos);
            cir.setReturnValue(null);
        }
    }

    /**
     * Cancels vanilla NBT scan-based chunk reads once Chunkis storage is authoritative.
     *
     * @param pos     chunk position being scanned
     * @param scanner vanilla NBT scanner
     * @param ci      mixin callback
     */
    @Inject(method = "scanChunk(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/StreamTagVisitor;)V",
            at = @At("HEAD"), cancellable = true)
    private void chunkis$blockCisAuthoritativeScan(
            final ChunkPos pos,
            final StreamTagVisitor scanner,
            final CallbackInfo ci
    ) {
        if (chunkis$shouldBlockVanillaStorage()) {
            Chunkis.LOGGER.debug("CIS scan redirection active for {} pos={}", chunkis$directory, pos);
            ci.cancel();
        }
    }

    /**
     * Returns whether the current vanilla storage instance should be blocked because it points
     * at chunk/entity region storage for a CIS-authoritative dimension.
     *
     * @return {@code true} when vanilla region access should be cancelled
     */
    @Unique
    private boolean chunkis$shouldBlockVanillaStorage() {
        // Authority can change after construction (migration finishes), so only it is re-checked.
        return MigrationStateServiceHolder.isAuthoritative(
                chunkis$guardedDimension);
    }
}
