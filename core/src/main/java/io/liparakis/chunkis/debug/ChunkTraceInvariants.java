package io.liparakis.chunkis.debug;

import io.liparakis.chunkis.core.ChunkDelta;

import java.util.Set;

public final class ChunkTraceInvariants {

    private static final Set<ChunkTraceReason> LOAD_SOURCE_REASONS = Set.of(
            ChunkTraceReason.TRACKER_MEMORY,
            ChunkTraceReason.CHUNKIS_STORAGE,
            ChunkTraceReason.BOTH,
            ChunkTraceReason.NEITHER
    );

    private ChunkTraceInvariants() {
        throw new AssertionError("Utility class");
    }

    public static boolean shouldAssertNonEmptyRestore(
            final ChunkDelta<?, ?> delta,
            final int appliedCount
    ) {
        return shouldAssertNonEmptyRestore(delta, appliedCount, false);
    }

    public static boolean shouldAssertNonEmptyRestore(
            final ChunkDelta<?, ?> delta,
            final int appliedCount,
            final boolean snapshotBackedRestore
    ) {
        if (delta == null || appliedCount != 0) {
            return false;
        }

        if (snapshotBackedRestore) {
            return !delta.getBlockInstructions().isEmpty() || !delta.getBlockEntities().isEmpty();
        }

        return !delta.getBlockInstructions().isEmpty() || !delta.getBlockEntities().isEmpty();
    }

    public static boolean hasInvalidBlockEntityOnlyPayloadWithoutBase(
            final ChunkDelta<?, ?> delta,
            final boolean hasPersistedBaseChunk
    ) {
        if (delta == null || hasPersistedBaseChunk) {
            return false;
        }

        return delta.getBlockInstructions().isEmpty() && !delta.getBlockEntities().isEmpty();
    }

    public static boolean shouldReportRestoreEmptyResult(
            final ChunkDelta<?, ?> delta,
            final int appliedCount,
            final boolean snapshotBackedRestore
    ) {
        if (delta == null) {
            return false;
        }
        if (appliedCount != 0) {
            return false;
        }

        if (!snapshotBackedRestore) {
            return true;
        }

        return !delta.getBlockInstructions().isEmpty() || !delta.getBlockEntities().isEmpty();
    }

    public static String describeEventViolation(final ChunkTraceEvent event) {
        return switch (event.eventType()) {
            case SAVE_REJECTED -> event.reason() == ChunkTraceReason.NONE
                    ? "save rejected without a machine-readable reason"
                    : null;
            case LOAD_SOURCE_RESOLVED -> !LOAD_SOURCE_REASONS.contains(event.reason())
                    ? "load source resolved without a valid source reason"
                    : null;
            case VANILLA_SAVE_CANCELLED -> event.chunkKey() == null
                    ? "vanilla save cancellation missing chunk coordinates"
                    : null;
            case SAVE_QUEUED, SAVE_FLUSH_STARTED, SAVE_FLUSH_COMPLETED, SAVE_FLUSH_FAILED -> {
                if (event.operationId() == null) {
                    yield "save lifecycle event missing operation id";
                }
                yield event.chunkKey() == null
                        ? "save lifecycle event missing chunk coordinates"
                        : null;
            }
            case DELTA_MARKED_CLEAN -> !Boolean.FALSE.equals(event.dirtyState())
                    ? "delta marked clean without dirty=false state"
                    : null;
            default -> null;
        };
    }
}
