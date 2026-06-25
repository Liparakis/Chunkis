# Invariants

This file separates Phase 2 invariants into three buckets:

- `IMPLEMENTED_SIGNAL_ONLY`
  The trace now exposes the evidence needed to inspect the condition, but does not yet emit `ASSERTION_FAILED`.
- `NOT_IMPLEMENTED_YET`
  Still deferred because the current code cannot check it cheaply or truthfully in this pass.
- `DEFERRED_BY_DESIGN`
  Intentionally postponed to a later phase because it needs extra infrastructure or would add risky churn.

## `SAVE_REJECT_REASON_PRESENT`

- Status: `IMPLEMENTED_SIGNAL_ONLY`
- Evidence now available:
  `SAVE_REJECTED` with reason `SPARSE_DELTA_REJECTED`
- Current hook points:
  `ThreadedAnvilChunkStorageMixin`
  `BaseChunkCaptureScheduler`
- Notes:
  This pass records the rejection reason directly instead of adding a separate assertion event.

## `DELTA_MARKED_CLEAN_AFTER_SUCCESS`

- Status: `IMPLEMENTED_SIGNAL_ONLY`
- Evidence now available:
  `SAVE_FLUSH_COMPLETED`
  `DELTA_MARKED_CLEAN`
- Current hook points:
  `CisStorage`
  `AsyncCisSaveManager`
  `ChunkDelta`
- Notes:
  The timeline is now inspectable, but there is not yet a hard invariant checker tying the events together.

## `NON_EMPTY_DELTA_RESTORES_SOMETHING`

- Status: `IMPLEMENTED_SIGNAL_ONLY`
- Evidence now available:
  `RESTORE_COMPLETED`
  message includes applied block, block-entity, and entity counts
- Current hook points:
  `ChunkRestorer`
- Notes:
  Zero-result restores are now visible through reason `RESTORE_EMPTY_RESULT`, but not yet escalated to `ASSERTION_FAILED`.

## `POST_RESTORE_FOLLOW_UP_NOT_SILENT`

- Status: `IMPLEMENTED_SIGNAL_ONLY`
- Evidence now available:
  `RESTORE_FAILED`
- Current hook points:
  `ChunkRestorer`
  `WorldChunkMixin`
- Notes:
  Core restore exceptions were already visible.
  The live restore boundary now also emits a failure signal if portal POI or portal index follow-up breaks after replay.

## `LOAD_SOURCE_RECORDED`

- Status: `IMPLEMENTED_SIGNAL_ONLY`
- Evidence now available:
  `LOAD_SOURCE_RESOLVED`
- Current hook points:
  `ChunkSerializerMixin`
- Notes:
  Current truthful outcomes are `TRACKER_MEMORY`, `CHUNKIS_STORAGE`, and `NEITHER`.
  `BOTH` remains deferred because the code does not inspect both sources on the same load path.

## `DURABILITY_REPRODUCER_TIMELINE_VISIBLE`

- Status: `IMPLEMENTED_SIGNAL_ONLY`
- Evidence now available:
  `DURABILITY_TEST_STARTED`
  `DURABILITY_TELEPORT_EXECUTED`
  `DURABILITY_TEST_STOPPED`
  `DURABILITY_TEST_FAILED`
- Current hook points:
  `DurabilityTestCommand`
- Notes:
  This is intentionally a visibility invariant only. It helps correlate disappearing-chunk reports to a concrete reproducer run without asserting persistence correctness.

## `VANILLA_SAVE_CANCELLED_REQUIRES_CHUNKIS_PATH`

- Status: `NOT_IMPLEMENTED_YET`
- What exists now:
  `VANILLA_SAVE_CANCELLED`
  `SAVE_QUEUED`
  `SAVE_FLUSH_*`
  `SAVE_REJECTED`
- Why not enforced yet:
  This pass does not thread a transaction id through cancel, queue, flush, and clean transitions, so a hard per-chunk assertion would still guess.

## `UNLOAD_DIRTY_CHUNK_NOT_SILENT`

- Status: `IMPLEMENTED_SIGNAL_ONLY`
- Evidence now available:
  `TRACKER_STATE_UPDATED`
  reason `TRACKER_CHUNK_UNLOADED`
- Current hook points:
  `GlobalChunkTracker`
- Notes:
  Dirty-map insert/remove, unload-cache put/evict/hit/miss, authoritative-delta keep, stale async completion, and real chunk-unload boundaries are now visible.
  This is still signal only, not a proof that persistence succeeded after unload.

## `PALETTE_LOOKUP_FAILURE_IDENTIFIED`

- Status: `IMPLEMENTED_SIGNAL_ONLY`
- Evidence now available:
  `LOAD_TX_END` with reason `MAPPING_LOOKUP_FAILED`
- Current hook points:
  `CisStorage`
- Notes:
  This currently covers storage-load mapping failures only. Client/network palette failures and deeper decoder-internal classification are still deferred.

## `REGION_WRITE_READABLE_WHEN_VERIFIED`

- Status: `DEFERRED_BY_DESIGN`
- Why deferred:
  Read-back verification would add extra I/O and belongs behind future `PARANOID` or explicit verification mode.

## `BASE_CHUNK_NBT_NOT_SILENTLY_IGNORED`

- Status: `NOT_IMPLEMENTED_YET`
- Why not enforced yet:
  Base-NBT branch reporting is still deferred even though load/restore transaction tracing now exists.

## `CLIENT_DELTA_ORDER_VISIBLE`

- Status: `IMPLEMENTED_SIGNAL_ONLY`
- Evidence now available:
  `CLIENT_SYNC_TX_START`
  `CLIENT_SYNC_TX_END`
  `CLIENT_SYNC_FAILED`
- Current hook points:
  `ChunkisNetworking`
  `ClientDeltaNetworking`
- Notes:
  The top-level send/apply boundaries are now visible, and client malformed-payload failures now distinguish `DECODE_FAILED` vs `MAPPING_LOOKUP_FAILED`.
  There is still no shared cross-wire transaction id.

## `OFF_THREAD_MUTATION_REJECTED`

- Status: `IMPLEMENTED_SIGNAL_ONLY`
- Evidence now available:
  `ASSERTION_FAILED` with reason `OFF_THREAD_MUTATION_REJECTED`
- Current hook points:
  `WorldChunkMixin`
- Notes:
  This is the first actual assertion event implemented because it is cheap and unambiguous.
