package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.Chunkis;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.world.storage.RegionBasedStorage;
import net.minecraft.world.storage.StorageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CIS_AUTHORITATIVE guard for vanilla chunk RegionBasedStorage.
 * Uses filesystem path instead of worldKey because RegionBasedStorage has no dimension context.
 */
@Mixin(RegionBasedStorage.class)
public abstract class RegionBasedStorageMixin {

    /**
     * Backing storage directory captured from the vanilla RegionBasedStorage constructor.
     */
    @Unique
    private Path chunkis$directory;

    /**
     * Captures the storage directory so later read/write guards can infer which dimension
     * the vanilla storage instance belongs to.
     *
     * @param storageKey vanilla storage key
     * @param directory  backing storage directory
     * @param sync       whether vanilla opened the storage in sync mode
     * @param ci         mixin callback
     */
    @Inject(method = "<init>(Lnet/minecraft/world/storage/StorageKey;Ljava/nio/file/Path;Z)V", at = @At("RETURN"))
    private void chunkis$captureDirectory(final StorageKey storageKey,
            final Path directory,
            final boolean sync,
            final CallbackInfo ci) {
        this.chunkis$directory = directory;
        final var cls = io.liparakis.chunkis.migration.state.VanillaRegionPathResolver.classify(directory);
        final var dimOpt = io.liparakis.chunkis.migration.state.VanillaRegionPathResolver.resolveDimension(directory);
        final boolean auth = dimOpt.isPresent()
                && io.liparakis.chunkis.migration.state.MigrationStateServiceHolder.isAuthoritative(dimOpt.get());
        Chunkis.LOGGER.info(
                "REGION_BASED_STORAGE_INIT path={} classifiedAs={} resolvedDimension={} state={} authoritative={}",
                directory, cls,
                dimOpt.map(k -> k.getValue()
                                .toString())
                        .orElse("unknown"),
                dimOpt.map(k -> io.liparakis.chunkis.migration.state.MigrationStateServiceHolder.isAuthoritative(k)
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
    @Inject(method = "write(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/NbtCompound;)V",
            at = @At("HEAD"), cancellable = true)
    private void chunkis$blockCisAuthoritativeWrite(
            final net.minecraft.util.math.ChunkPos pos,
            final net.minecraft.nbt.NbtCompound nbt,
            final CallbackInfo ci
    ) throws IOException {
        if (chunkis$shouldBlockVanillaStorage()) {
            final Path regionFile = chunkis$directory.resolve(chunkis$regionFileName(pos));
            Chunkis.LOGGER.error(
                    "POST_CIS_ANVIL_ACCESS path={} pos={} sizeBefore={} operation=RegionBasedStorage.write stack={}",
                    chunkis$directory, pos,
                    java.nio.file.Files.exists(regionFile) ? java.nio.file.Files.size(regionFile) : -1,
                    java.util.Arrays.toString(Thread.currentThread()
                            .getStackTrace())
            );
            ci.cancel();
        }
    }

    /**
     * Derives the owning MCA filename for a chunk position.
     *
     * @param pos chunk position
     * @return region filename in {@code r.<x>.<z>.mca} form
     */
    @Unique
    private static String chunkis$regionFileName(final net.minecraft.util.math.ChunkPos pos) {
        return "r." + (pos.x >> 5) + "." + (pos.z >> 5) + ".mca";
    }

    /**
     * Cancels vanilla chunk reads once Chunkis storage is authoritative for the dimension.
     *
     * @param pos chunk position being read
     * @param cir mixin callback
     */
    @Inject(method = "getTagAt(Lnet/minecraft/util/math/ChunkPos;)Lnet/minecraft/nbt/NbtCompound;",
            at = @At("HEAD"), cancellable = true)
    private void chunkis$blockCisAuthoritativeRead(
            final net.minecraft.util.math.ChunkPos pos,
            final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.nbt.NbtCompound> cir
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
    @Inject(method = "scanChunk(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/scanner/NbtScanner;)V",
            at = @At("HEAD"), cancellable = true)
    private void chunkis$blockCisAuthoritativeScan(
            final net.minecraft.util.math.ChunkPos pos,
            final net.minecraft.nbt.scanner.NbtScanner scanner,
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
        if (chunkis$directory == null) {
            return false;
        }
        final io.liparakis.chunkis.migration.state.VanillaRegionPathResolver.Classification classification =
                io.liparakis.chunkis.migration.state.VanillaRegionPathResolver.classify(chunkis$directory);
        if (classification
                != io.liparakis.chunkis.migration.state.VanillaRegionPathResolver.Classification.VANILLA_CHUNK_REGION
                && classification
                != io.liparakis.chunkis.migration.state.VanillaRegionPathResolver.Classification.VANILLA_ENTITY_REGION) {
            return false;
        }
        final java.util.Optional<net.minecraft.registry.RegistryKey<net.minecraft.world.World>> dimOpt =
                io.liparakis.chunkis.migration.state.VanillaRegionPathResolver.resolveDimension(chunkis$directory);
        return dimOpt.isPresent()
                && io.liparakis.chunkis.migration.state.MigrationStateServiceHolder.isAuthoritative(dimOpt.get());
    }
}
