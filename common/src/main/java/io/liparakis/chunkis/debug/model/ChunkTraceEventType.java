package io.liparakis.chunkis.debug.model;

public enum ChunkTraceEventType {
    /**
     * Stores save tx start.
     */
    SAVE_TX_START,
    /**
     * Stores save queue requested.
     */
    SAVE_QUEUE_REQUESTED,
    /**
     * Stores base capture on save guard.
     */
    BASE_CAPTURE_ON_SAVE_GUARD,
    /**
     * Stores save retry after base capture.
     */
    SAVE_RETRY_AFTER_BASE_CAPTURE,
    /**
     * Stores vanilla save cancelled.
     */
    VANILLA_SAVE_CANCELLED,
    /**
     * Stores save rejected.
     */
    SAVE_REJECTED,
    /**
     * Stores save queued.
     */
    SAVE_QUEUED,
    /**
     * Stores save flush started.
     */
    SAVE_FLUSH_STARTED,
    /**
     * Stores region write tx start.
     */
    REGION_WRITE_TX_START,
    /**
     * Stores region write tx end.
     */
    REGION_WRITE_TX_END,
    /**
     * Stores save flush completed.
     */
    SAVE_FLUSH_COMPLETED,
    /**
     * Stores save flush failed.
     */
    SAVE_FLUSH_FAILED,
    /**
     * Stores delta marked dirty.
     */
    DELTA_MARKED_DIRTY,
    /**
     * Stores delta marked clean.
     */
    DELTA_MARKED_CLEAN,
    /**
     * Stores tracker state updated.
     */
    TRACKER_STATE_UPDATED,
    /**
     * Stores load tx start.
     */
    LOAD_TX_START,
    /**
     * Stores region read tx start.
     */
    REGION_READ_TX_START,
    /**
     * Stores region read tx end.
     */
    REGION_READ_TX_END,
    /**
     * Stores load source resolved.
     */
    LOAD_SOURCE_RESOLVED,
    /**
     * Stores chunkis ownership decision.
     */
    CHUNKIS_OWNERSHIP_DECISION,
    /**
     * Stores first dirty mutation.
     */
    FIRST_DIRTY_MUTATION,
    /**
     * Stores suppression context started.
     */
    SUPPRESSION_CONTEXT_STARTED,
    /**
     * Stores suppression context ended.
     */
    SUPPRESSION_CONTEXT_ENDED,
    /**
     * Stores mutation suppressed restore.
     */
    MUTATION_SUPPRESSED_RESTORE,
    /**
     * Stores mutation suppressed base apply.
     */
    MUTATION_SUPPRESSED_BASE_APPLY,
    /**
     * Stores mutation suppressed passive load.
     */
    MUTATION_SUPPRESSED_PASSIVE_LOAD,
    /**
     * Stores mutation accepted real edit.
     */
    MUTATION_ACCEPTED_REAL_EDIT,
    /**
     * Stores base capture requested.
     */
    BASE_CAPTURE_REQUESTED,
    /**
     * Stores base capture started.
     */
    BASE_CAPTURE_STARTED,
    /**
     * Stores base metadata attached.
     */
    BASE_METADATA_ATTACHED,
    /**
     * Stores base capture completed.
     */
    BASE_CAPTURE_COMPLETED,
    /**
     * Stores base nbt capture started.
     */
    BASE_NBT_CAPTURE_STARTED,
    /**
     * Stores base nbt captured.
     */
    BASE_NBT_CAPTURED,
    /**
     * Stores base nbt capture skipped.
     */
    BASE_NBT_CAPTURE_SKIPPED,
    /**
     * Stores watch captured.
     */
    WATCH_CAPTURED,
    /**
     * Stores watch block setstate entered.
     */
    WATCH_BLOCK_SETSTATE_ENTERED,
    /**
     * Stores watch encoded.
     */
    WATCH_ENCODED,
    /**
     * Stores watch serialized.
     */
    WATCH_SERIALIZED,
    /**
     * Stores watch storage write.
     */
    WATCH_STORAGE_WRITE,
    /**
     * Stores watch storage read.
     */
    WATCH_STORAGE_READ,
    /**
     * Stores watch decoded.
     */
    WATCH_DECODED,
    /**
     * Stores watch decoded delta state.
     */
    WATCH_DECODED_DELTA_STATE,
    /**
     * Stores watch proto delta attached.
     */
    WATCH_PROTO_DELTA_ATTACHED,
    /**
     * Stores watch proto delta present before conversion.
     */
    WATCH_PROTO_DELTA_PRESENT_BEFORE_CONVERSION,
    /**
     * Stores watch proto delta present after conversion.
     */
    WATCH_PROTO_DELTA_PRESENT_AFTER_CONVERSION,
    /**
     * Stores watch worldchunk delta attached.
     */
    WATCH_WORLDCHUNK_DELTA_ATTACHED,
    /**
     * Stores watch worldchunk delta missing.
     */
    WATCH_WORLDCHUNK_DELTA_MISSING,
    /**
     * Stores watch world chunk constructor consumed.
     */
    WATCH_WORLD_CHUNK_CONSTRUCTOR_CONSUMED,
    /**
     * Stores watch restore started.
     */
    WATCH_RESTORE_STARTED,
    /**
     * Stores watch restore instruction visited.
     */
    WATCH_RESTORE_INSTRUCTION_VISITED,
    /**
     * Stores watch restore apply attempt.
     */
    WATCH_RESTORE_APPLY_ATTEMPT,
    /**
     * Stores watch restore setblock returned.
     */
    WATCH_RESTORE_SETBLOCK_RETURNED,
    /**
     * Stores watch restore state after setblock.
     */
    WATCH_RESTORE_STATE_AFTER_SETBLOCK,
    /**
     * Stores watch restore applied.
     */
    WATCH_RESTORE_APPLIED,
    /**
     * Stores watch restore apply failed.
     */
    WATCH_RESTORE_APPLY_FAILED,
    /**
     * Stores watch restore skipped.
     */
    WATCH_RESTORE_SKIPPED,
    /**
     * Stores watch present after restore.
     */
    WATCH_PRESENT_AFTER_RESTORE,
    /**
     * Stores watch live chunk state after restore.
     */
    WATCH_LIVE_CHUNK_STATE_AFTER_RESTORE,
    /**
     * Stores watch live chunk state before base capture.
     */
    WATCH_LIVE_CHUNK_STATE_BEFORE_BASE_CAPTURE,
    /**
     * Stores watch present after chunk full.
     */
    WATCH_PRESENT_AFTER_CHUNK_FULL,
    /**
     * Stores watch present before client send.
     */
    WATCH_PRESENT_BEFORE_CLIENT_SEND,
    /**
     * Stores watch present after client send.
     */
    WATCH_PRESENT_AFTER_CLIENT_SEND,
    /**
     * Stores watch present in client world.
     */
    WATCH_PRESENT_IN_CLIENT_WORLD,
    /**
     * Stores watch client sees expected state.
     */
    WATCH_CLIENT_SEES_EXPECTED_STATE,
    /**
     * Stores watch entity removed.
     */
    WATCH_ENTITY_REMOVED,
    /**
     * Stores watch overwritten after restore.
     */
    WATCH_OVERWRITTEN_AFTER_RESTORE,
    /**
     * Stores watch chunk instance id.
     */
    WATCH_CHUNK_INSTANCE_ID,
    /**
     * Stores watch chunk status.
     */
    WATCH_CHUNK_STATUS,
    /**
     * Stores watch trace incomplete.
     */
    WATCH_TRACE_INCOMPLETE,
    /**
     * Stores watch failed.
     */
    WATCH_FAILED,
    /**
     * Stores watch skipped.
     */
    WATCH_SKIPPED,
    /**
     * Stores base nbt decode started.
     */
    BASE_NBT_DECODE_STARTED,
    /**
     * Stores base nbt decode completed.
     */
    BASE_NBT_DECODE_COMPLETED,
    /**
     * Stores base nbt decode failed.
     */
    BASE_NBT_DECODE_FAILED,
    /**
     * Stores base snapshot apply started.
     */
    BASE_SNAPSHOT_APPLY_STARTED,
    /**
     * Stores base snapshot apply completed.
     */
    BASE_SNAPSHOT_APPLY_COMPLETED,
    /**
     * Stores base snapshot apply failed.
     */
    BASE_SNAPSHOT_APPLY_FAILED,
    /**
     * Stores base nbt found.
     */
    BASE_NBT_FOUND,
    /**
     * Stores base nbt applied.
     */
    BASE_NBT_APPLIED,
    /**
     * Stores base nbt skipped.
     */
    BASE_NBT_SKIPPED,
    /**
     * Stores base nbt missing.
     */
    BASE_NBT_MISSING,
    /**
     * Stores base metadata present after decode.
     */
    BASE_METADATA_PRESENT_AFTER_DECODE,
    /**
     * Stores base metadata missing after decode.
     */
    BASE_METADATA_MISSING_AFTER_DECODE,
    /**
     * Stores base metadata attached to live delta.
     */
    BASE_METADATA_ATTACHED_TO_LIVE_DELTA,
    /**
     * Stores base metadata lost during restore.
     */
    BASE_METADATA_LOST_DURING_RESTORE,
    /**
     * Stores base metadata present before save guard.
     */
    BASE_METADATA_PRESENT_BEFORE_SAVE_GUARD,
    /**
     * Stores base metadata missing before save guard.
     */
    BASE_METADATA_MISSING_BEFORE_SAVE_GUARD,
    /**
     * Stores proto chunk sections before restore.
     */
    PROTO_CHUNK_SECTIONS_BEFORE_RESTORE,
    /**
     * Stores proto chunk sections after base.
     */
    PROTO_CHUNK_SECTIONS_AFTER_BASE,
    /**
     * Stores proto chunk sections after delta.
     */
    PROTO_CHUNK_SECTIONS_AFTER_DELTA,
    /**
     * Stores load tx end.
     */
    LOAD_TX_END,
    /**
     * Stores restore tx start.
     */
    RESTORE_TX_START,
    /**
     * Stores snapshot backed restore.
     */
    SNAPSHOT_BACKED_RESTORE,
    /**
     * Stores sparse delta applied.
     */
    SPARSE_DELTA_APPLIED,
    /**
     * Stores restore completed.
     */
    RESTORE_COMPLETED,
    /**
     * Stores restore failed.
     */
    RESTORE_FAILED,
    /**
     * Stores client sync tx start.
     */
    CLIENT_SYNC_TX_START,
    /**
     * Stores chunk sent to client summary.
     */
    CHUNK_SENT_TO_CLIENT_SUMMARY,
    /**
     * Stores client sync tx end.
     */
    CLIENT_SYNC_TX_END,
    /**
     * Stores client sync failed.
     */
    CLIENT_SYNC_FAILED,
    /**
     * Stores durability test started.
     */
    DURABILITY_TEST_STARTED,
    /**
     * Stores durability teleport executed.
     */
    DURABILITY_TELEPORT_EXECUTED,
    /**
     * Stores durability test stopped.
     */
    DURABILITY_TEST_STOPPED,
    /**
     * Stores durability test failed.
     */
    DURABILITY_TEST_FAILED,
    /**
     * Stores assertion failed.
     */
    ASSERTION_FAILED,
    /**
     * Stores entity replay scheduled.
     */
    ENTITY_REPLAY_SCHEDULED,
    /**
     * Stores entity replay materialization.
     */
    ENTITY_REPLAY_MATERIALIZATION,
    /**
     * Stores entity replay drain.
     */
    ENTITY_REPLAY_DRAIN,
    /**
     * Represents the completed entity replay drain event.
     */
    ENTITY_REPLAY_DRAIN_FINISHED
}
