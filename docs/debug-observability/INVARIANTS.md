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

## `LOAD_SOURCE_RECORDED`

- Status: `IMPLEMENTED_SIGNAL_ONLY`
- Evidence now available:
  `LOAD_SOURCE_RESOLVED`
- Current hook points:
  `ChunkSerializerMixin`
- Notes:
  Current truthful outcomes are `TRACKER_MEMORY`, `CHUNKIS_STORAGE`, and `NEITHER`.
  `BOTH` remains deferred because the code does not inspect both sources on the same load path.

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

- Status: `NOT_IMPLEMENTED_YET`
- Why not enforced yet:
  `GlobalChunkTracker` and unload-adjacent lifecycle hooks are not instrumented in this pass.

## `PALETTE_LOOKUP_FAILURE_IDENTIFIED`

- Status: `NOT_IMPLEMENTED_YET`
- Why not enforced yet:
  Decoder/mapping failure surfaces were explicitly left out of this first save/load/restore slice.

## `REGION_WRITE_READABLE_WHEN_VERIFIED`

- Status: `DEFERRED_BY_DESIGN`
- Why deferred:
  Read-back verification would add extra I/O and belongs behind future `PARANOID` or explicit verification mode.

## `BASE_CHUNK_NBT_NOT_SILENTLY_IGNORED`

- Status: `NOT_IMPLEMENTED_YET`
- Why not enforced yet:
  Base-NBT branch reporting is still deferred even though load/restore transaction tracing now exists.

## `CLIENT_DELTA_ORDER_VISIBLE`

- Status: `NOT_IMPLEMENTED_YET`
- Why not enforced yet:
  Client-sync instrumentation is intentionally outside this Phase 2 slice.
