package io.liparakis.chunkis.integration.c2me;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.world.tracking.save.ChunkSaveCoordinator;
import io.liparakis.chunkis.world.tracking.save.ChunkSaveCoordinatorHolder;
import java.lang.reflect.Method;
import net.minecraft.world.chunk.Chunk;

/**
 * Routes chunk saves made by C2ME's chunk system through Chunkis.
 *
 * <p>C2ME unloads chunks through its own {@code ReadFromDisk.asyncSave}, which serializes the chunk and writes it
 * to vanilla region files itself instead of calling vanilla's {@code save(Chunk)}, where Chunkis takes over saves.
 * Without this bridge every chunk C2ME unloads is written to vanilla files Chunkis no longer reads, and Chunkis's
 * own changes wait for the shutdown sweep. The bridge offers each such save to Chunkis's coordinator first.</p>
 *
 * <p>C2ME's types are reached reflectively, so Chunkis neither depends on C2ME nor breaks without it. If that ever
 * fails (a C2ME update renames something), the bridge stops intercepting and {@link #isUnavailable()} turns true,
 * so the region-file guard lets C2ME's own writes through rather than dropping them.</p>
 */
public final class C2meSaveBridge {

    /**
     * {@code ChunkLoadingContext.tacs()}, resolved on first use.
     */
    private static volatile Method tacsAccessor;

    /**
     * {@code Completable.complete()}, resolved on first use.
     */
    private static volatile Object completed;

    /**
     * Set once resolving C2ME's types failed; from then on C2ME saves are left to C2ME.
     */
    private static volatile boolean unavailable;

    /**
     * Not instantiated.
     */
    private C2meSaveBridge() {
    }

    /**
     * Offers a chunk C2ME is about to save to Chunkis.
     *
     * @param loadingContext C2ME's {@code ChunkLoadingContext} for the chunk
     * @param chunk          the chunk being saved
     * @return the completed C2ME save result to return in place of C2ME's own save when Chunkis handled the chunk,
     *         or {@code null} when C2ME should save it as usual
     */
    public static Object saveThroughChunkis(final Object loadingContext, final Chunk chunk) {
        if (unavailable) {
            return null;
        }
        final Object done;
        final Object chunkLoadingManager;
        try {
            done = completed(loadingContext);
            chunkLoadingManager = tacs(loadingContext);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            unavailable = true;
            Chunkis.LOGGER.error("Chunkis: Cannot route C2ME chunk saves through Chunkis (C2ME internals changed?); "
                    + "C2ME will keep saving chunks to vanilla region files, which Chunkis does not load", e);
            return null;
        }
        if (!(chunkLoadingManager instanceof ChunkSaveCoordinatorHolder holder)) {
            return null;
        }
        final ChunkSaveCoordinator.SaveOutcome outcome = holder.chunkis$saveCoordinator().save(chunk);
        return outcome == ChunkSaveCoordinator.SaveOutcome.VANILLA ? null : done;
    }

    /**
     * Returns whether C2ME saves can't be routed through Chunkis, in which case they must not be blocked either.
     *
     * @return {@code true} once routing failed
     */
    public static boolean isUnavailable() {
        return unavailable;
    }

    /**
     * Calls {@code ChunkLoadingContext.tacs()}.
     *
     * @param loadingContext the context
     * @return the chunk loading manager it belongs to
     * @throws ReflectiveOperationException when the accessor is missing
     */
    private static Object tacs(final Object loadingContext) throws ReflectiveOperationException {
        Method accessor = tacsAccessor;
        if (accessor == null) {
            accessor = loadingContext.getClass().getMethod("tacs");
            tacsAccessor = accessor;
        }
        return accessor.invoke(loadingContext);
    }

    /**
     * Returns RxJava's completed {@code Completable}, from C2ME's own class loader.
     *
     * @param loadingContext any C2ME object, for its class loader
     * @return the completed result
     * @throws ReflectiveOperationException when RxJava is missing
     */
    private static Object completed(final Object loadingContext) throws ReflectiveOperationException {
        Object done = completed;
        if (done == null) {
            final Class<?> completable = Class.forName("io.reactivex.rxjava3.core.Completable", false,
                    loadingContext.getClass().getClassLoader());
            done = completable.getMethod("complete").invoke(null);
            completed = done;
        }
        return done;
    }
}
