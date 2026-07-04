package io.liparakis.chunkis.debug.trace;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;

import java.util.Set;

/**
 * Predicate helpers that encode structural invariants about chunk trace events
 * and chunk deltas.
 *
 * <p>These checks are evaluated by {@link ChunkTraceStore} on every recorded
 * event. Violations produce a synthetic {@link ChunkTraceEventType#ASSERTION_FAILED}
 * event so they surface in the trace log without crashing the server.</p>
 */
public final class ChunkTraceInvariants {

    /**
     * The set of {@link ChunkTraceReason} values that are valid when an event
     * of type {@link ChunkTraceEventType#LOAD_SOURCE_RESOLVED} is recorded.
     */
    private static final Set<ChunkTraceReason> LOAD_SOURCE_REASONS = Set.of(
            ChunkTraceReason.TRACKER_MEMORY,
            ChunkTraceReason.CHUNKIS_STORAGE,
            ChunkTraceReason.BOTH,
            ChunkTraceReason.NEITHER
    );

    private ChunkTraceInvariants() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns {@code true} if a restore that applied zero blocks/entities from a
     * non-empty delta should be treated as an assertion failure.
     *
     * <p>A delta with instructions that produced no applied blocks indicates
     * either a corrupt delta or a bug in the restore logic.</p>
     *
     * @param delta        the delta that was restored; may be {@code null}
     * @param appliedCount number of blocks/entities actually written
     * @return {@code true} if the situation violates the restore invariant
     */
    public static boolean shouldAssertNonEmptyRestore(
            final ChunkDelta<?, ?> delta,
            final int appliedCount
    ) {
        if (delta == null || appliedCount != 0) {
            return false;
        }
        return delta.getBlockChangesCount() > 0 || !delta.getBlockEntities()
                .isEmpty();
    }

    /**
     * Returns {@code true} if the delta contains block-entity data but no block
     * instructions and there is no persisted base chunk to anchor them to.
     *
     * <p>Block-entity-only payloads without a base chunk are unloadable: the
     * block states they reference do not exist in vanilla storage.</p>
     *
     * @param delta                 the delta to inspect; may be {@code null}
     * @param hasPersistedBaseChunk whether a vanilla base chunk exists on disk
     * @return {@code true} if the payload is in an invalid state
     */
    public static boolean hasInvalidBlockEntityOnlyPayloadWithoutBase(
            final ChunkDelta<?, ?> delta,
            final boolean hasPersistedBaseChunk
    ) {
        if (delta == null || hasPersistedBaseChunk) {
            return false;
        }
        return delta.getBlockChangesCount() == 0 && !delta.getBlockEntities()
                .isEmpty();
    }

    /**
     * Returns {@code true} if a restore that applied zero blocks should be
     * reported as an empty-result event.
     *
     * <p>For snapshot-backed restores, an empty result is only noteworthy when
     * the delta itself contained instructions (otherwise the snapshot provided
     * all data and zero is expected). For non-snapshot restores any zero-apply
     * result is reportable.</p>
     *
     * @param delta                 the delta being restored; may be {@code null}
     * @param appliedCount          number of blocks/entities actually written
     * @param snapshotBackedRestore whether the restore was backed by a snapshot
     * @return {@code true} if the empty result should be reported
     */
    public static boolean shouldReportRestoreEmptyResult(
            final ChunkDelta<?, ?> delta,
            final int appliedCount,
            final boolean snapshotBackedRestore
    ) {
        if (delta == null || appliedCount != 0) {
            return false;
        }
        // Non-snapshot restores always report zero-apply as noteworthy.
        if (!snapshotBackedRestore) {
            return true;
        }
        // Snapshot-backed: only report if the delta had its own instructions.
        return delta.getBlockChangesCount() > 0 || !delta.getBlockEntities()
                .isEmpty();
    }

    /**
     * Returns a human-readable description of the structural violation in
     * {@code event}, or {@code null} if no violation is detected.
     *
     * <p>Violations are checked by event type. Missing required fields for a
     * given type are reported as violation messages. The first detected violation
     * is returned; events are not expected to have multiple simultaneously.</p>
     *
     * <p><b>Note for save-lifecycle events:</b> {@link ChunkTraceStore#OPERATION_IDS} is checked
     * first because it is considered more critical than chunk coordinates; a
     * missing chunk key on an event that is also missing its operation ID will
     * not be separately reported.</p>
     *
     * @param event the event to validate
     * @return violation description, or {@code null} if the event is structurally valid
     */
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
