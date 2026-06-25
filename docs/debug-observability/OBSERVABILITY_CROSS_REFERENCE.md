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

### Does the live restore boundary still emit failure if post-restore follow-up breaks?

- Decision: `IMPLEMENTED`
- Evidence:
  `RESTORE_FAILED`
- Files:
  `ChunkRestorer`
  `WorldChunkMixin`
- Notes:
  Core snapshot replay failures were already emitted from `ChunkRestorer`.
  `WorldChunkMixin` now also emits `RESTORE_FAILED` when the later portal POI or portal index follow-up breaks after the core restore already succeeded.

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

### Durability reproducer timeline

- Decision: `IMPLEMENTED`
- Evidence:
  `DURABILITY_TEST_STARTED`
  `DURABILITY_TELEPORT_EXECUTED`
  `DURABILITY_TEST_STOPPED`
  `DURABILITY_TEST_FAILED`
- Files:
  `DurabilityTestCommand`
- Notes:
  This does not claim persistence correctness. It only makes the reproducer run itself visible so save/load/restore events can be interpreted against an actual teleport-churn timeline.

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
  Load and restore now stay on the same operation id when a proto chunk actually carries a Chunkis delta into `WorldChunkMixin`.

### Dirty tracker state visibility

- Decision: `IMPLEMENTED_WITH_LIMITATION`
- Evidence:
  `TRACKER_STATE_UPDATED`
- Files:
  `GlobalChunkTracker`
- Notes:
  Dirty-map put/remove, unload-cache put/evict/hit/miss, authoritative-delta keep, stale async completion, and real chunk-unload notifications are now visible.
  The remaining limitation is that unload events are still tracker-level evidence, not a hard persistence assertion.

### Live mutation origin visibility

- Decision: `PARTIALLY_IMPLEMENTED`
- Evidence:
  `TRACKER_STATE_UPDATED reason=TRACKER_DIRTY_MAP_PUT`
- Files:
  `WorldChunkMixin`
  `GlobalChunkTracker`
- Notes:
  The first dirty-tracker transition now carries the actual `WorldChunkMixin` caller in `source`, which is enough to separate block-state, block-entity-set, and block-entity-remove entrypoints.
  It is still intentionally coarse and does not emit per-mutation event spam.

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

### Client-side malformed payload classification

- Decision: `PARTIALLY_IMPLEMENTED`
- Evidence:
  `CLIENT_SYNC_FAILED`
- Files:
  `ClientDeltaNetworking`
- Notes:
  The client apply boundary now distinguishes malformed network payload decode failures as `DECODE_FAILED` or `MAPPING_LOOKUP_FAILED`.
  It still does not expose deeper per-stage decoder internals, shared cross-wire operation ids, or richer apply-side failure taxonomy.

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
  This is intentionally only a send/apply flight recorder.
  Client-side malformed payload decode failures are now classified at the top-level boundary.
  Compression state is now exposed at the send/apply boundary messages.
  It still does not thread one shared operation id across the wire.

### Export/watchpoints/ImGUI

- Decision: `PARTIALLY_IMPLEMENTED`
- Files:
  `ChunkDebugCommand`
  `ChunkTraceStore`
  `ChunkTraceWatchpoints`
- Notes:
  Focused chunk/region watchpoints are now implemented as operator-side filters over the existing in-memory store.
  JSONL export and GUI surfaces remain deferred.

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
- `DurabilityTestCommandTest`
  teleport target to chunk-key mapping and manual-stop trace emission for durability events
- `GlobalChunkTrackerTest`
  tracker unload event emission, dirty-state payload, and mutation-origin source propagation
- `WorldChunkMixinTest`
  restore operation-id handoff and post-restore follow-up failure trace emission
- `ClientDeltaNetworkingTest`
  client malformed-payload failure classification
- `ChunkisNetworkingTest`
  outgoing client-sync compression summary formatting

### Next validation additions after this pass

- Extend durability game-test coverage to assert a trace timeline around an actual disappearing-chunk reproducer.
- Add targeted failure-path tests for save rejection, async flush failure, and zero-result restore timelines.
