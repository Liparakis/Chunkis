package io.liparakis.chunkis.world;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.storage.ChunkDeltaOwnership;

/**
 * Tracks scoped suppression of chunk mutation ownership during passive chunk
 * lifecycle work (loading, base-apply, and restore operations).
 *
 * <p>Each cause has an independent re-entrant depth counter so that nested
 * suppressions of the same cause compose correctly. Priority ordering when
 * multiple causes are active is: RESTORE &gt; BASE_APPLY &gt; PASSIVE_LOAD.</p>
 *
 * <p>A "trace once" flag per cause ensures that suppression events are only
 * emitted on the first entry of each scope, preventing log spam from re-entrant
 * or bulk operations.</p>
 *
 * <p><b>Threading:</b> not thread-safe; must be accessed from the owning thread.</p>
 */
public final class ChunkMutationTrackingScope {

    /**
     * Reason a mutation is being suppressed, or {@link #NONE} when suppression
     * is not active.
     */
    public enum Cause {
        NONE,
        PASSIVE_LOAD,
        BASE_APPLY,
        RESTORE
    }

    // Re-entrant depth counters incremented on push, decremented on pop.
    private int passiveLoadDepth;
    private int baseApplyDepth;
    private int restoreDepth;

    // "Trace once" flags cleared when depth returns to zero so the next
    // entry into a scope can emit a trace event again.
    private boolean passiveLoadTraced;
    private boolean baseApplyTraced;
    private boolean restoreTraced;

    /**
     * Determines the initial suppression cause to use when loading a chunk.
     *
     * <p>If the delta already has a Chunkis persistence anchor the load is
     * treated as a base-apply rather than a passive load, because Chunkis
     * data is about to be applied on top of the vanilla chunk.</p>
     *
     * @param delta the chunk delta being loaded
     * @return {@link Cause#BASE_APPLY} if the delta has an anchor, otherwise
     *         {@link Cause#PASSIVE_LOAD}
     */
    public static Cause initialCauseForLoad(final ChunkDelta<?, ?> delta) {
        return ChunkDeltaOwnership.hasChunkisPersistenceAnchor(delta)
                ? Cause.BASE_APPLY
                : Cause.PASSIVE_LOAD;
    }

    /**
     * Enters a suppression scope for the given cause, incrementing its depth.
     *
     * <p>{@link Cause#NONE} is a no-op.</p>
     *
     * @param cause the cause to push
     */
    public void push(final Cause cause) {
        switch (cause) {
            case PASSIVE_LOAD -> passiveLoadDepth++;
            case BASE_APPLY   -> baseApplyDepth++;
            case RESTORE      -> restoreDepth++;
            case NONE         -> { }
        }
    }

    /**
     * Exits a suppression scope for the given cause, decrementing its depth.
     *
     * <p>Depth is clamped at zero as a defensive measure against mismatched
     * push/pop calls (which are bugs elsewhere, but should not corrupt state
     * here). The trace-once flag is cleared when depth reaches zero so the
     * next entry can emit a fresh trace event.</p>
     *
     * <p>{@link Cause#NONE} is a no-op.</p>
     *
     * @param cause the cause to pop
     */
    public void pop(final Cause cause) {
        switch (cause) {
            case PASSIVE_LOAD -> {
                if (--passiveLoadDepth <= 0) {
                    passiveLoadDepth = 0;
                    passiveLoadTraced = false;
                }
            }
            case BASE_APPLY -> {
                if (--baseApplyDepth <= 0) {
                    baseApplyDepth = 0;
                    baseApplyTraced = false;
                }
            }
            case RESTORE -> {
                if (--restoreDepth <= 0) {
                    restoreDepth = 0;
                    restoreTraced = false;
                }
            }
            case NONE -> { }
        }
    }

    /**
     * Returns {@code true} if any suppression cause is currently active.
     *
     * @return {@code true} when {@link #currentCause()} is not {@link Cause#NONE}
     */
    public boolean isSuppressed() {
        return currentCause() != Cause.NONE;
    }

    /**
     * Returns the highest-priority active suppression cause.
     *
     * <p>Priority ordering: RESTORE &gt; BASE_APPLY &gt; PASSIVE_LOAD &gt; NONE.</p>
     *
     * @return the active cause, or {@link Cause#NONE}
     */
    public Cause currentCause() {
        if (restoreDepth > 0)      return Cause.RESTORE;
        if (baseApplyDepth > 0)    return Cause.BASE_APPLY;
        if (passiveLoadDepth > 0)  return Cause.PASSIVE_LOAD;
        return Cause.NONE;
    }

    /**
     * Returns {@code true} and marks the cause as traced if this is the first
     * suppression event for the current scope entry.
     *
     * <p>Subsequent calls with the same cause return {@code false} until the
     * scope is exited (depth returns to zero) and re-entered, at which point
     * the flag is cleared by {@link #pop}.</p>
     *
     * @param cause the active suppression cause; must not be {@link Cause#NONE}
     * @return {@code true} if a trace event should be emitted
     * @throws IllegalArgumentException if {@code cause} is {@link Cause#NONE}
     */
    public boolean shouldTraceSuppression(final Cause cause) {
        return switch (cause) {
            case PASSIVE_LOAD -> markTracedOnce(TracedFlag.PASSIVE_LOAD);
            case BASE_APPLY   -> markTracedOnce(TracedFlag.BASE_APPLY);
            case RESTORE      -> markTracedOnce(TracedFlag.RESTORE);
            case NONE         -> throw new IllegalArgumentException("NONE has no suppression trace");
        };
    }

    /**
     * Returns the {@link ChunkTraceEventType} corresponding to the given cause.
     *
     * @param cause the suppression cause; must not be {@link Cause#NONE}
     * @return the matching trace event type
     * @throws IllegalArgumentException if {@code cause} is {@link Cause#NONE}
     */
    public static ChunkTraceEventType suppressionEventType(final Cause cause) {
        return switch (cause) {
            case RESTORE      -> ChunkTraceEventType.MUTATION_SUPPRESSED_RESTORE;
            case BASE_APPLY   -> ChunkTraceEventType.MUTATION_SUPPRESSED_BASE_APPLY;
            case PASSIVE_LOAD -> ChunkTraceEventType.MUTATION_SUPPRESSED_PASSIVE_LOAD;
            case NONE         -> throw new IllegalArgumentException("NONE has no suppression event type");
        };
    }

    /**
     * Internal flag selector used by {@link #markTracedOnce} to avoid
     * a boolean parameter whose meaning is implicit at the call site.
     */
    private enum TracedFlag { PASSIVE_LOAD, BASE_APPLY, RESTORE }

    /**
     * Sets the traced flag for {@code flag} and returns {@code true} the first
     * time it is called for the current scope entry.
     *
     * <p>Returns {@code false} on all subsequent calls until the flag is cleared
     * by {@link #pop} when the corresponding depth reaches zero.</p>
     *
     * @param flag which traced flag to check and set
     * @return {@code true} if the flag was not already set
     */
    private boolean markTracedOnce(final TracedFlag flag) {
        return switch (flag) {
            case PASSIVE_LOAD -> {
                if (passiveLoadTraced) yield false;
                passiveLoadTraced = true;
                yield true;
            }
            case BASE_APPLY -> {
                if (baseApplyTraced) yield false;
                baseApplyTraced = true;
                yield true;
            }
            case RESTORE -> {
                if (restoreTraced) yield false;
                restoreTraced = true;
                yield true;
            }
        };
    }
}