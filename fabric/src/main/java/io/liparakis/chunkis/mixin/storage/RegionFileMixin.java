package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.integration.c2me.C2meSaveBridge;
import io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder;
import io.liparakis.chunkis.integration.migration.state.VanillaRegionPathResolver;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.ChunkCompressionFormat;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks writes to vanilla chunk and entity region files of a dimension Chunkis stores, at the file itself.
 *
 * <p>{@link RegionBasedStorageMixin} blocks vanilla's own region writes, but storage replacements such as C2ME's
 * write {@code RegionFile}s directly and never reach it. Every path ends here, so this catches them all. When C2ME
 * saves can't be routed through Chunkis ({@link C2meSaveBridge#isUnavailable()}), its writes are let through
 * instead: written to vanilla files, the chunks are at least recoverable.</p>
 */
@Mixin(RegionFile.class)
public abstract class RegionFileMixin {

    /**
     * Dimension whose vanilla chunk or entity regions this file belongs to, or {@code null} for any other file.
     */
    @Unique
    private RegistryKey<World> chunkis$guardedDimension;

    /**
     * Not instantiated.
     */
    private RegionFileMixin() {
    }

    /**
     * Classifies the file's directory once; every constructor ends in this one.
     *
     * @param storageKey  storage key
     * @param path        region file path
     * @param directory   region directory
     * @param compressionFormat chunk compression format
     * @param dsync       whether writes are synced
     * @param ci          callback
     */
    @Inject(method = "<init>(Lnet/minecraft/world/storage/StorageKey;Ljava/nio/file/Path;Ljava/nio/file/Path;"
            + "Lnet/minecraft/world/storage/ChunkCompressionFormat;Z)V", at = @At("TAIL"))
    private void chunkis$classify(final StorageKey storageKey, final Path path, final Path directory,
            final ChunkCompressionFormat compressionFormat, final boolean dsync, final CallbackInfo ci) {
        final VanillaRegionPathResolver.Classification type = VanillaRegionPathResolver.classify(directory);
        if (type == VanillaRegionPathResolver.Classification.VANILLA_CHUNK_REGION
                || type == VanillaRegionPathResolver.Classification.VANILLA_ENTITY_REGION) {
            chunkis$guardedDimension = VanillaRegionPathResolver.resolveDimension(directory).orElse(null);
        }
    }

    /**
     * Drops a chunk write to a vanilla region Chunkis stores.
     *
     * @param pos    chunk position
     * @param buffer chunk bytes
     * @param ci     callback
     */
    @Inject(method = "writeChunk", at = @At("HEAD"), cancellable = true)
    private void chunkis$blockWrite(final ChunkPos pos, final ByteBuffer buffer, final CallbackInfo ci) {
        if (chunkis$blocked()) {
            ci.cancel();
        }
    }

    /**
     * Drops a chunk deletion from a vanilla region Chunkis stores.
     *
     * @param pos chunk position
     * @param ci  callback
     */
    @Inject(method = "delete", at = @At("HEAD"), cancellable = true)
    private void chunkis$blockDelete(final ChunkPos pos, final CallbackInfo ci) {
        if (chunkis$blocked()) {
            ci.cancel();
        }
    }

    /**
     * Returns whether writes to this file are blocked now; authority can arrive after the file was opened.
     *
     * @return {@code true} when Chunkis stores this dimension
     */
    @Unique
    private boolean chunkis$blocked() {
        return chunkis$guardedDimension != null
                && MigrationStateServiceHolder.isAuthoritative(chunkis$guardedDimension)
                && !C2meSaveBridge.isUnavailable();
    }
}
