package io.liparakis.chunkis.world;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.storage.ChunkDeltaOwnership;

/**
 * Tracks scoped suppression of mutation ownership during passive chunk lifecycle work.
 */
public final class ChunkMutationTrackingScope {

    public enum Cause {
        NONE,
        PASSIVE_LOAD,
        BASE_APPLY,
        RESTORE
    }

    private int passiveLoadDepth;
    private int baseApplyDepth;
    private int restoreDepth;
    private boolean passiveLoadTraced;
    private boolean baseApplyTraced;
    private boolean restoreTraced;

    public static Cause initialCauseForLoad(final ChunkDelta<?, ?> delta) {
        return ChunkDeltaOwnership.hasChunkisPersistenceAnchor(delta)
                ? Cause.BASE_APPLY
                : Cause.PASSIVE_LOAD;
    }

    public void push(final Cause cause) {
        switch (cause) {
            case PASSIVE_LOAD -> passiveLoadDepth++;
            case BASE_APPLY -> baseApplyDepth++;
            case RESTORE -> restoreDepth++;
            case NONE -> {
            }
        }
    }

    public void pop(final Cause cause) {
        switch (cause) {
            case PASSIVE_LOAD -> {
                passiveLoadDepth = Math.max(0, passiveLoadDepth - 1);
                if (passiveLoadDepth == 0) {
                    passiveLoadTraced = false;
                }
            }
            case BASE_APPLY -> {
                baseApplyDepth = Math.max(0, baseApplyDepth - 1);
                if (baseApplyDepth == 0) {
                    baseApplyTraced = false;
                }
            }
            case RESTORE -> {
                restoreDepth = Math.max(0, restoreDepth - 1);
                if (restoreDepth == 0) {
                    restoreTraced = false;
                }
            }
            case NONE -> {
            }
        }
    }

    public boolean isSuppressed() {
        return currentCause() != Cause.NONE;
    }

    public Cause currentCause() {
        if (restoreDepth > 0) {
            return Cause.RESTORE;
        }
        if (baseApplyDepth > 0) {
            return Cause.BASE_APPLY;
        }
        if (passiveLoadDepth > 0) {
            return Cause.PASSIVE_LOAD;
        }
        return Cause.NONE;
    }

    public boolean shouldTraceSuppression(final Cause cause) {
        if (cause == Cause.NONE) {
            return false;
        }

        return switch (cause) {
            case PASSIVE_LOAD -> markTracedOnce(true);
            case BASE_APPLY -> markTracedOnce(false);
            case RESTORE -> markRestoreTracedOnce();
            case NONE -> false;
        };
    }

    public static ChunkTraceEventType suppressionEventType(final Cause cause) {
        return switch (cause) {
            case RESTORE -> ChunkTraceEventType.MUTATION_SUPPRESSED_RESTORE;
            case BASE_APPLY -> ChunkTraceEventType.MUTATION_SUPPRESSED_BASE_APPLY;
            case PASSIVE_LOAD -> ChunkTraceEventType.MUTATION_SUPPRESSED_PASSIVE_LOAD;
            case NONE -> throw new IllegalArgumentException("NONE has no suppression event type");
        };
    }

    private boolean markTracedOnce(final boolean passive) {
        if (passive) {
            if (passiveLoadTraced) {
                return false;
            }
            passiveLoadTraced = true;
            return true;
        }
        if (baseApplyTraced) {
            return false;
        }
        baseApplyTraced = true;
        return true;
    }

    private boolean markRestoreTracedOnce() {
        if (restoreTraced) {
            return false;
        }
        restoreTraced = true;
        return true;
    }
}
