package io.liparakis.chunkis.integration.c2me;

import java.util.function.Supplier;

/**
 * Identifies synchronous construction inside C2ME's {@code ServerAccessible.toFullChunk}.
 * A chunk restored there has not been published; its initial packet must come from normal publication.
 */
public final class C2meChunkPromotion {

    /**
     * Construction stack on this worker, absent outside C2ME promotion.
     */
    private static final ThreadLocal<Target> CURRENT = new ThreadLocal<>();

    /**
     * Not instantiated.
     */
    private C2meChunkPromotion() {
    }

    /**
     * Runs C2ME's original construction without changing its thread, result or exception.
     * Always restores the previous scope, including when nested construction fails.
     *
     * @param world identity of the owning server world
     * @param chunkX target chunk X
     * @param chunkZ target chunk Z
     * @param construction original synchronous construction
     * @param <T> construction result type
     * @return original result
     */
    public static <T> T construct(final Object world, final int chunkX, final int chunkZ,
            final Supplier<T> construction) {
        final Target previous = CURRENT.get();
        CURRENT.set(new Target(world, chunkX, chunkZ, previous));
        try {
            return construction.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * Reports whether the current thread is constructing a not-yet-published chunk through C2ME.
     *
     * @param world identity of the owning server world
     * @param chunkX chunk X
     * @param chunkZ chunk Z
     * @return true only for a chunk currently being constructed on this thread
     */
    public static boolean isConstructing(final Object world, final int chunkX, final int chunkZ) {
        for (Target target = CURRENT.get(); target != null; target = target.previous()) {
            if (target.world() == world && target.chunkX() == chunkX && target.chunkZ() == chunkZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * One unpublished chunk and its enclosing construction, if any.
     *
     * @param world owning world identity
     * @param chunkX chunk X
     * @param chunkZ chunk Z
     * @param previous enclosing construction
     */
    private record Target(Object world, int chunkX, int chunkZ, Target previous) {
    }
}
