package io.liparakis.chunkis.gametest.mixin.profile;

import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.gametest.stress.StageProfiler;
import io.liparakis.chunkis.gametest.stress.StageProfiler.CacheCounter;
import io.liparakis.chunkis.gametest.stress.StageProfiler.Stage;
import io.liparakis.chunkis.world.tracking.save.RecentChunkBlocks;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts the recent-chunk cache's traffic: lookups by outcome, stores, evictions and the most it held.
 */
@Mixin(value = RecentChunkBlocks.class, remap = false)
public abstract class RecentChunkBlocksProfileMixin {

    /**
     * Cached entries.
     */
    @Shadow
    @SuppressWarnings("rawtypes")
    private LinkedHashMap entries;

    /**
     * Estimated bytes held.
     */
    @Shadow
    private long bytes;

    /**
     * Entries and bytes when the current store began, and the bytes of the entry it replaces; stores run under the
     * cache's lock, so one set serves every cache.
     */
    @Unique
    private static long chunkis$entriesBefore;

    /**
     * Bytes when the current store began.
     */
    @Unique
    private static long chunkis$bytesBefore;

    /**
     * Bytes of the entry the current store replaces.
     */
    @Unique
    private static long chunkis$replacedBytes;

    /**
     * Whether the current lookup found an entry.
     */
    @Unique
    private static final ThreadLocal<Boolean> CHUNKIS$FOUND = new ThreadLocal<>();

    /**
     * Accessor of a cache entry's estimated bytes.
     */
    @Unique
    private static Method chunkis$entryBytes;

    /**
     * Estimates a chunk's container memory.
     *
     * @param sections containers
     * @return bytes
     */
    @Shadow
    private static long estimate(final PalettedContainer<BlockState>[] sections) {
        throw new AssertionError();
    }

    /**
     * Records the cache before an unload save's entry is stored.
     *
     * @param pos chunk position
     * @param ci  callback
     */
    @Inject(method = "put", at = @At("HEAD"))
    private void chunkis$beforePut(final CisChunkPos pos, final long[] blockPartHash, final long[] content,
            final PalettedContainer<BlockState>[] sections, final int bottomY, final CallbackInfo ci) {
        chunkis$before(pos, CacheCounter.PUTS);
    }

    /**
     * Records evictions after an unload save's entry was stored.
     *
     * @param ci callback
     */
    @Inject(method = "put", at = @At("RETURN"))
    private void chunkis$afterPut(final CisChunkPos pos, final long[] blockPartHash, final long[] content,
            final PalettedContainer<BlockState>[] sections, final int bottomY, final CallbackInfo ci) {
        chunkis$after(sections);
    }

    /**
     * Records the cache before an unverified entry is stored.
     *
     * @param pos chunk position
     * @param ci  callback
     */
    @Inject(method = "putToVerify", at = @At("HEAD"))
    private void chunkis$beforePutToVerify(final CisChunkPos pos, final long[] blockPartHash,
            final long[] expectedContent, final PalettedContainer<BlockState>[] sections, final int bottomY,
            final CallbackInfo ci) {
        chunkis$before(pos, CacheCounter.PUTS_TO_VERIFY);
    }

    /**
     * Records evictions after an unverified entry was stored.
     *
     * @param ci callback
     */
    @Inject(method = "putToVerify", at = @At("RETURN"))
    private void chunkis$afterPutToVerify(final CisChunkPos pos, final long[] blockPartHash,
            final long[] expectedContent, final PalettedContainer<BlockState>[] sections, final int bottomY,
            final CallbackInfo ci) {
        chunkis$after(sections);
    }

    /**
     * Opens a lookup and notes whether an entry exists.
     *
     * @param pos chunk position
     * @param cir callback
     */
    @Inject(method = "blocksFor", at = @At("HEAD"))
    private void chunkis$beginLookup(final CisChunkPos pos, final long[] blockPartHash,
            final CallbackInfoReturnable<?> cir) {
        if (!StageProfiler.ENABLED) {
            return;
        }
        StageProfiler.begin(Stage.CACHE_LOOKUP);
        synchronized (this) {
            CHUNKIS$FOUND.set(entries.containsKey(chunkis$key(pos)));
        }
    }

    /**
     * Closes a lookup and counts its outcome.
     *
     * @param cir callback holding the fill, or {@code null}
     */
    @Inject(method = "blocksFor", at = @At("RETURN"))
    private void chunkis$endLookup(final CisChunkPos pos, final long[] blockPartHash,
            final CallbackInfoReturnable<?> cir) {
        if (!StageProfiler.ENABLED) {
            return;
        }
        StageProfiler.end(Stage.CACHE_LOOKUP);
        StageProfiler.cache(cir.getReturnValue() != null ? CacheCounter.HITS
                : Boolean.TRUE.equals(CHUNKIS$FOUND.get()) ? CacheCounter.INVALIDATED : CacheCounter.MISSES, 1);
    }

    /**
     * Notes the cache's state before a store.
     *
     * @param pos   chunk position
     * @param store kind of store
     */
    @Unique
    private void chunkis$before(final CisChunkPos pos, final CacheCounter store) {
        if (!StageProfiler.ENABLED) {
            return;
        }
        StageProfiler.cache(store, 1);
        chunkis$entriesBefore = entries.size();
        chunkis$bytesBefore = bytes;
        final Object replaced = entries.get(chunkis$key(pos));
        chunkis$replacedBytes = replaced == null ? 0 : chunkis$bytesOf(replaced);
        if (replaced != null) {
            chunkis$entriesBefore--;
        }
    }

    /**
     * Counts what a store evicted, from the cache's state before and after it.
     *
     * @param sections the stored containers
     */
    @Unique
    private void chunkis$after(final PalettedContainer<BlockState>[] sections) {
        if (!StageProfiler.ENABLED) {
            return;
        }
        final long added = estimate(sections);
        final long evicted = chunkis$entriesBefore + 1 - entries.size();
        if (evicted > 0) {
            StageProfiler.cache(CacheCounter.EVICTIONS, evicted);
            StageProfiler.cache(CacheCounter.EVICTED_BYTES,
                    chunkis$bytesBefore - chunkis$replacedBytes + added - bytes);
        }
        StageProfiler.cachePeak(CacheCounter.PEAK_BYTES, bytes);
        StageProfiler.cachePeak(CacheCounter.PEAK_ENTRIES, entries.size());
    }

    /**
     * Reads a cache entry's estimated bytes.
     *
     * @param entry the entry
     * @return its bytes
     */
    @Unique
    private static long chunkis$bytesOf(final Object entry) {
        try {
            if (chunkis$entryBytes == null) {
                chunkis$entryBytes = entry.getClass().getDeclaredMethod("bytes");
                chunkis$entryBytes.setAccessible(true);
            }
            return (long) chunkis$entryBytes.invoke(entry);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("cache entry layout changed", e);
        }
    }

    /**
     * Packs a position like the cache does.
     *
     * @param pos chunk position
     * @return key
     */
    @Unique
    private static long chunkis$key(final CisChunkPos pos) {
        return ((long) pos.x() << 32) | (pos.z() & 0xFFFFFFFFL);
    }
}
