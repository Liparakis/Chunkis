# Observability Cross Reference

This file records the final Phase 2 decision/status for the first flight-recorder slice.

## Implemented now

### Minimal trace core

- Decision: `IMPLEMENTED`
- Files:
  `core/.../debug/*`
- Notes:
  Shared placement in `core` was the right call because both storage-only code and Fabric hooks need the same event model.

### Was save requested?

- Decision: `IMPLEMENTED`
- Evidence:
  `SAVE_TX_START`
- Files:
  `ThreadedAnvilChunkStorageMixin`
  `CisStorage`

### Was vanilla save cancelled?

- Decision: `IMPLEMENTED`
- Evidence:
  `VANILLA_SAVE_CANCELLED`
- Files:
  `StoragePreventionMixin`

### Was Chunkis save rejected?

- Decision: `IMPLEMENTED`
- Evidence:
  `SAVE_REJECTED`
- Files:
  `ThreadedAnvilChunkStorageMixin`
  `BaseChunkCaptureScheduler`
- Notes:
  Current implemented rejection reason is the sparse-delta guard path.

### Was Chunkis save queued?

- Decision: `IMPLEMENTED`
- Evidence:
  `SAVE_QUEUED`
- Files:
  `AsyncCisSaveManager`

### Was Chunkis save flushed?

- Decision: `IMPLEMENTED`
- Evidence:
  `SAVE_FLUSH_STARTED`
  `REGION_WRITE_TX_START`
  `REGION_WRITE_TX_END`
  `SAVE_FLUSH_COMPLETED`
  `SAVE_FLUSH_FAILED`
- Files:
  `CisStorage`
  `RegionFile`
  `AsyncCisSaveManager`

### Was delta marked clean?

- Decision: `IMPLEMENTED`
- Evidence:
  `DELTA_MARKED_CLEAN`
- Files:
  `ChunkDelta`

### Did load find tracker memory, Chunkis storage, or neither?

- Decision: `IMPLEMENTED_WITH_LIMITATION`
- Evidence:
  `LOAD_SOURCE_RESOLVED`
- Files:
  `ChunkSerializerMixin`
- Notes:
  Truthful values implemented now are `TRACKER_MEMORY`, `CHUNKIS_STORAGE`, and `NEITHER`.

### Did restore apply meaningful data?

- Decision: `IMPLEMENTED_WITH_LIMITATION`
- Evidence:
  `RESTORE_COMPLETED`
- Files:
  `ChunkRestorer`
- Notes:
  Applied counts are currently carried in the event message rather than dedicated numeric fields.

### Minimal command support

- Decision: `IMPLEMENTED`
- Files:
  `ChunkDebugCommand`
  `ChunkisMod`
- Commands:
  `/chunkis debug on`
  `/chunkis debug off`
  `/chunkis debug latest <count>`
  `/chunkis debug clear`

### Operation ids for traced timelines

- Decision: `IMPLEMENTED`
- Files:
  `ChunkTraceStore`
  `CisStorage`
  `RegionFile`
  `AsyncCisSaveManager`
  `ThreadedAnvilChunkStorageMixin`
  `ChunkSerializerMixin`
  `ChunkRestorer`
  `WorldChunkMixin`
- Notes:
  The current save/load/restore path now carries operation ids through the traced boundaries that actually participate in the first flight recorder.

### Dirty tracker state visibility

- Decision: `PARTIALLY_IMPLEMENTED`
- Evidence:
  `TRACKER_STATE_UPDATED`
- Files:
  `GlobalChunkTracker`
- Notes:
  Dirty-map put/remove, unload-cache hit/miss, and stale async completion are now visible.
  Full unload-hook correlation is still deferred.

### Off-thread world mutation rejection

- Decision: `IMPLEMENTED`
- Evidence:
  `ASSERTION_FAILED reason=OFF_THREAD_MUTATION_REJECTED`
- Files:
  `WorldChunkMixin`
- Notes:
  This is a real assertion, but intentionally limited to the cheapest unambiguous case.

### Stored-bytes decode failure classification

- Decision: `PARTIALLY_IMPLEMENTED`
- Evidence:
  `LOAD_TX_END`
- Files:
  `CisStorage`
- Notes:
  The storage boundary now distinguishes `DECOMPRESSION_FAILED`, `DECODE_FAILED`, and `MAPPING_LOOKUP_FAILED`.
  It still does not expose deeper decoder-internal failure stages or client/network decode failures.

## Deferred intentionally

### `BOTH` load-source resolution

- Decision: `DEFERRED`
- Reason:
  Current code does not inspect both tracker memory and storage truthfully on one load path, so emitting `BOTH` now would guess.

### Hard assertion enforcement

- Decision: `DEFERRED`
- Reason:
  This still comes after lifecycle correlation is stronger beyond the currently traced save/load/restore path.

### Client-sync top-level boundaries

- Decision: `PARTIALLY_IMPLEMENTED`
- Evidence:
  `CLIENT_SYNC_TX_START`
  `CLIENT_SYNC_TX_END`
  `CLIENT_SYNC_FAILED`
- Files:
  `ChunkisNetworking`
  `ClientDeltaNetworking`
- Notes:
  This is intentionally only a send/apply flight recorder. It does not yet thread one shared operation id across the wire or expose compression decisions.

### Export/watchpoints/ImGUI

- Decision: `DEFERRED`
- Reason:
  Not required to produce the first reliable in-memory timeline.

## Validation linkage

### Unit tests now covering observability

- `ChunkTraceStoreTest`
  bounded store and debug default OFF
- `ChunkDeltaTraceTest`
  dirty/clean event emission
- `CisStorageTraceTest`
  save/load storage events
- `ChunkDebugCommandTest`
  readable timeline formatting

### Next validation additions after this pass

- Extend durability game-test coverage to assert a trace timeline around an actual disappearing-chunk reproducer.
- Add targeted failure-path tests for save rejection, async flush failure, and zero-result restore timelines.
