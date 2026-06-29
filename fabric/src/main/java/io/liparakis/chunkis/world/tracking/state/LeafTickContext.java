package io.liparakis.chunkis.world.tracking.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-local context tracker for natural leaf tick operations.
 *
 * <p>Chunkis uses this context to identify block mutations caused by vanilla leaf
 * decay. Those mutations should not be persisted as player edits.</p>
 *
 * <p>The context supports nesting through a primitive depth counter:</p>
 * <ul>
 *   <li>{@code depth == 0}: inactive</li>
 *   <li>{@code depth > 0}: active</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> state is stored per thread through {@link ThreadLocal},
 * so normal access does not require synchronization.</p>
 *
 * <p><b>Memory safety:</b> the thread-local holder is removed when depth returns
 * to zero. This matters for Minecraft/server thread pools, where stale
 * {@link ThreadLocal} values can otherwise survive much longer than the operation
 * that created them.</p>
 *
 * @author Liparakis
 * @version 1.2
 */
public final class LeafTickContext {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LeafTickContext.class);

    /**
     * Per-thread context holder.
     *
     * <p>No initial supplier is used intentionally. This prevents
     * {@link #isActive()} from allocating a holder on threads that only query the
     * context and never enter it.</p>
     */
    private static final ThreadLocal<ContextHolder> CONTEXT = new ThreadLocal<>();

    private LeafTickContext() {
        throw new AssertionError("Utility class");
    }

    /**
     * Enters a leaf tick context and returns an auto-closing handle.
     *
     * <p>Use this when the context can be represented as a normal Java scope.
     * Nested calls are supported; the context remains active until the last handle
     * is closed.</p>
     *
     * @return closeable handle that exits the context
     */
    @SuppressWarnings("unused")
    public static ContextHandle enter() {
        return new ContextHandle(enterInternal());
    }

    /**
     * Enters a leaf tick context without allocating a handle.
     *
     * <p>This is intended for mixin {@code HEAD}/{@code TAIL} pairs where
     * try-with-resources cannot be expressed directly. Callers must guarantee a
     * matching {@link #exitDirect()} on the same thread.</p>
     */
    public static void enterDirect() {
        enterInternal();
    }

    /**
     * Exits a context entered with {@link #enterDirect()}.
     *
     * <p>If the current thread has no active context, an error is logged and the
     * stale state is cleared defensively.</p>
     */
    public static void exitDirect() {
        final ContextHolder holder = CONTEXT.get();

        if (holder == null) {
            logMismatchedExit();
            return;
        }

        exitInternal(holder);
    }

    /**
     * Returns whether the current thread is inside a leaf tick context.
     *
     * <p>This method does not allocate when the context has never been entered on
     * the current thread.</p>
     *
     * @return {@code true} when depth is greater than zero
     */
    public static boolean isActive() {
        final ContextHolder holder = CONTEXT.get();
        return holder != null && holder.depth > 0;
    }

    /**
     * Directly sets the context state for the current thread.
     *
     * <p><b>Deprecated:</b> this does not preserve nesting. Calling
     * {@code set(false)} clears all nested enters for the current thread.</p>
     *
     * @param active {@code true} to set depth to one, {@code false} to clear state
     * @deprecated prefer {@link #enter()} or {@link #enterDirect()}
     */
    @Deprecated
    public static void set(final boolean active) {
        if (!active) {
            CONTEXT.remove();
            return;
        }

        final ContextHolder holder = getOrCreateHolder();
        holder.depth = 1;
    }

    /**
     * Enters the context and returns the current thread's holder.
     *
     * @return current thread context holder
     */
    private static ContextHolder enterInternal() {
        final ContextHolder holder = getOrCreateHolder();
        holder.depth++;
        return holder;
    }

    /**
     * Exits the context represented by the holder.
     *
     * <p>When depth reaches zero, the thread-local value is removed so the current
     * thread does not retain the holder indefinitely.</p>
     *
     * @param holder current thread context holder
     */
    private static void exitInternal(final ContextHolder holder) {
        holder.depth--;

        if (holder.depth > 0) {
            return;
        }

        if (holder.depth < 0) {
            logMismatchedExit();
        }

        CONTEXT.remove();
    }

    /**
     * Returns the current thread's holder, creating it only on enter.
     *
     * @return current thread context holder
     */
    private static ContextHolder getOrCreateHolder() {
        ContextHolder holder = CONTEXT.get();

        if (holder == null) {
            holder = new ContextHolder();
            CONTEXT.set(holder);
        }

        return holder;
    }

    /**
     * Logs a mismatched enter/exit condition.
     */
    private static void logMismatchedExit() {
        LOGGER.error(
                "Leaf tick context exit without matching enter. Resetting context. (thread: {})",
                Thread.currentThread().getName()
                    );

        CONTEXT.remove();
    }

    /**
     * Mutable per-thread context state.
     *
     * <p>Uses a primitive {@code int} depth to avoid boxing and support nested
     * contexts.</p>
     */
    private static final class ContextHolder {

        /**
         * Nesting depth for the current thread.
         */
        private int depth;
    }

    /**
     * Auto-closeable handle returned by {@link #enter()}.
     *
     * <p>The handle is idempotent. Calling {@link #close()} more than once is safe;
     * only the first call exits the context.</p>
     *
     * <p>This handle is not designed to be transferred across threads. It should be
     * closed by the same thread that created it.</p>
     */
    public static final class ContextHandle implements AutoCloseable {

        /**
         * Holder captured when the context was entered.
         */
        private final ContextHolder holder;

        /**
         * Prevents double-close from decrementing depth twice.
         */
        private boolean closed;

        private ContextHandle(final ContextHolder holder) {
            this.holder = holder;
        }

        /**
         * Exits the leaf tick context.
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }

            closed = true;
            exitInternal(holder);
        }
    }
}
