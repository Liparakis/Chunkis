# Event Catalog

This file lists only Phase 2 events that are actually implemented.

Debug defaults to `OFF`. When disabled, call sites use `ChunkTraceStore.trace(...)` so event construction is skipped.

## Implemented events

### `SAVE_TX_START`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/CisStorage.java`
  `fabric/.../mixin/storage/ThreadedAnvilChunkStorageMixin.java`
- Meaning:
  A save was requested at either the storage boundary or the main Fabric save hook.
- Current fields:
  chunk key, optional region key, source, dirty state when available

### `VANILLA_SAVE_CANCELLED`

- Domain: `SAVE_GUARDS`
- Severity: `INFO`
- Implemented in:
  `fabric/.../mixin/storage/StoragePreventionMixin.java`
- Meaning:
  Chunkis cancelled a vanilla region write.
- Current fields:
  chunk key, source

### `SAVE_REJECTED`

- Domain: `SAVE_GUARDS`
- Severity: `WARN`
- Implemented in:
  `fabric/.../mixin/storage/ThreadedAnvilChunkStorageMixin.java`
  `fabric/.../storage/BaseChunkCaptureScheduler.java`
- Meaning:
  Chunkis rejected a sparse delta without safe persisted base state.
- Current reason values:
  `SPARSE_DELTA_REJECTED`

### `SAVE_QUEUED`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO`
- Implemented in:
  `fabric/.../storage/AsyncCisSaveManager.java`
- Meaning:
  The async save manager accepted a save submission.
- Current fields:
  world id, chunk key, source, operation id, dirty state
- Notes:
  Generation is currently encoded in the message, not a dedicated field.

### `SAVE_FLUSH_STARTED`

- Domain: `REGION_STORAGE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/CisStorage.java`
- Meaning:
  Storage is beginning compression/write of a prepared payload.
- Current fields:
  chunk key, region key, source, operation id, byte size

### `SAVE_FLUSH_COMPLETED`

- Domain: `REGION_STORAGE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/CisStorage.java`
- Meaning:
  Storage completed a prepared payload write successfully.
- Current fields:
  chunk key, region key, source, operation id, byte size

### `SAVE_FLUSH_FAILED`

- Domain: `REGION_STORAGE`
- Severity: `ERROR`
- Implemented in:
  `core/.../storage/io/CisStorage.java`
  `fabric/.../storage/AsyncCisSaveManager.java`
- Meaning:
  A synchronous or async save flush failed.
- Current reason values:
  `IO_EXCEPTION`
- Current fields:
  chunk key, source, operation id

### `REGION_WRITE_TX_START`

- Domain: `REGION_STORAGE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/RegionFile.java`
- Meaning:
  Region-file write work started for one chunk slot.
- Current fields:
  chunk key, region key, source, operation id, byte size

### `REGION_WRITE_TX_END`

- Domain: `REGION_STORAGE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/RegionFile.java`
- Meaning:
  Region-file write work completed for one chunk slot.
- Current fields:
  chunk key, region key, source, operation id, byte size

### `DELTA_MARKED_DIRTY`

- Domain: `DIRTY_TRACKING`
- Severity: `INFO`
- Implemented in:
  `core/.../core/ChunkDelta.java`
- Meaning:
  A delta transitioned from clean to dirty.
- Current reason values:
  `DELTA_BECAME_DIRTY`

### `DELTA_MARKED_CLEAN`

- Domain: `DIRTY_TRACKING`
- Severity: `INFO`
- Implemented in:
  `core/.../core/ChunkDelta.java`
- Meaning:
  A delta transitioned from dirty to clean through `markSaved*`.
- Current reason values:
  `DELTA_MARKED_SAVED`

### `TRACKER_STATE_UPDATED`

- Domain: `DIRTY_TRACKING`
- Severity: `INFO`
- Implemented in:
  `fabric/.../world/GlobalChunkTracker.java`
- Meaning:
  The dirty tracker changed meaningful state or answered an unload-cache lookup.
- Current reason values:
  `TRACKER_DIRTY_MAP_PUT`
  `TRACKER_MARK_SAVED`
  `TRACKER_CHUNK_UNLOADED`
  `TRACKER_UNLOAD_CACHE_PUT`
  `TRACKER_UNLOAD_CACHE_EVICT`
  `TRACKER_UNLOAD_CACHE_HIT`
  `TRACKER_UNLOAD_CACHE_MISS`
  `AUTHORITATIVE_DELTA_KEPT`
  `STALE_GENERATION_IGNORED`
- Current fields:
  world id, chunk key, optional dirty state
- Notes:
  `TRACKER_CHUNK_UNLOADED` is the first true live-world unload correlation hook.
  `dirtyState=true` means the chunk left the live world while a dirty delta was still actively tracked.
  `TRACKER_DIRTY_MAP_PUT` now carries the live mutation entrypoint in `source` for `WorldChunkMixin` paths such as `setBlockState`, `setBlockEntity`, and `removeBlockEntity`.

### `ASSERTION_FAILED`

- Domain: `ASSERTIONS`
- Severity: `ERROR`
- Implemented in:
  `fabric/.../mixin/world/WorldChunkMixin.java`
  `fabric/.../world/ChunkRestorer.java`
- Meaning:
  A cheap, high-confidence invariant failed.
- Current reason values:
  `OFF_THREAD_MUTATION_REJECTED`
  `RESTORE_EMPTY_RESULT`
- Notes:
  This is still intentionally narrow. Current enforcement covers off-thread live mutation rejection and zero-result restore replay when block/block-entity payload existed.

### `LOAD_TX_START`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/CisStorage.java`
  `fabric/.../mixin/storage/ChunkSerializerMixin.java`
- Meaning:
  Load resolution started either at the storage boundary or at the Fabric proto-attach boundary.
- Current fields:
  chunk key, optional region key, source, operation id

### `REGION_READ_TX_START`

- Domain: `REGION_STORAGE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/RegionFile.java`
- Meaning:
  Region-file read work started for one chunk slot.
- Current fields:
  chunk key, region key, source, operation id

### `REGION_READ_TX_END`

- Domain: `REGION_STORAGE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/RegionFile.java`
- Meaning:
  Region-file read work completed or returned no payload.
- Current reason values:
  `STORAGE_READ`, `MISSING_ENTRY`
- Current fields:
  chunk key, region key, source, operation id, optional byte size

### `LOAD_SOURCE_RESOLVED`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO`
- Implemented in:
  `fabric/.../mixin/storage/ChunkSerializerMixin.java`
- Meaning:
  The proto-attach load path resolved its meaningful source.
- Current reason values:
  `TRACKER_MEMORY`, `CHUNKIS_STORAGE`, `NEITHER`
- Notes:
  `BOTH` is intentionally not emitted yet because current code does not observe both sources truthfully in one pass.
  Current traced load paths now carry one shared operation id from serializer to storage read.

### `LOAD_TX_END`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO` or `ERROR`
- Implemented in:
  `core/.../storage/io/CisStorage.java`
  `fabric/.../mixin/storage/ChunkSerializerMixin.java`
- Meaning:
  Load resolution ended either with an empty result, a stored delta, or an attached delta.
- Current reason values:
  `NEITHER`
  `CHUNKIS_STORAGE`
  `DECOMPRESSION_FAILED`
  `DECODE_FAILED`
  `MAPPING_LOOKUP_FAILED`
- Current fields:
  chunk key, optional region key, source, operation id
- Notes:
  `CisStorage` now distinguishes "stored bytes were unreadable" from "no meaningful Chunkis data" at the storage boundary.

### `RESTORE_TX_START`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO`
- Implemented in:
  `fabric/.../world/ChunkRestorer.java`
- Meaning:
  Restore into a live `WorldChunk` started.
- Current fields:
  world id, chunk key, source, operation id
- Notes:
  On the normal proto-delta path, `WorldChunkMixin` now reuses the existing load operation id instead of creating a second restore-only id.

### `RESTORE_COMPLETED`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO`
- Implemented in:
  `fabric/.../world/ChunkRestorer.java`
- Meaning:
  Restore completed with aggregate counts.
- Current reason values:
  `NONE`, `RESTORE_EMPTY_RESULT`
- Current fields:
  world id, chunk key, source, operation id
- Notes:
  Applied block/block-entity/entity counts are currently carried in the message.
  The operation id now stays aligned with the preceding load/attach path when restore came from a proto-carried Chunkis delta.

### `RESTORE_FAILED`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `ERROR`
- Implemented in:
  `fabric/.../world/ChunkRestorer.java`
  `fabric/.../mixin/world/WorldChunkMixin.java`
- Meaning:
  Restore failed either during core snapshot replay or during the later live-chunk follow-up after replay.
- Current reason values:
  `RESTORE_EXCEPTION`
- Current fields:
  world id, chunk key, source, operation id
- Notes:
  `ChunkRestorer` emits the core restore failure path.
  `WorldChunkMixin` now emits the post-restore follow-up failure path when portal POI/index resync fails after the core restore already completed.

### `CLIENT_SYNC_TX_START`

- Domain: `CLIENT_SYNC`
- Severity: `INFO`
- Implemented in:
  `fabric/.../network/ChunkisNetworking.java`
  `fabric/.../client/ClientDeltaNetworking.java`
- Meaning:
  A server-side delta send or client-side delta apply started.
- Current fields:
  chunk key, source, operation id, optional world id, optional dirty state, optional byte size
- Notes:
  Client-side start messages now include whether the payload was compressed on the wire and the decoded byte count.

### `CLIENT_SYNC_TX_END`

- Domain: `CLIENT_SYNC`
- Severity: `INFO`
- Implemented in:
  `fabric/.../network/ChunkisNetworking.java`
  `fabric/.../client/ClientDeltaNetworking.java`
- Meaning:
  A server-side delta send or client-side delta apply completed.
- Current fields:
  chunk key, source, operation id, optional world id, optional dirty state, byte size
- Notes:
  Server-side end messages now include raw vs wire byte counts and whether compression was used.
  Client-side end messages now include whether the payload arrived compressed and the decoded byte count.

### `CLIENT_SYNC_FAILED`

- Domain: `CLIENT_SYNC`
- Severity: `WARN` or `ERROR`
- Implemented in:
  `fabric/.../network/ChunkisNetworking.java`
  `fabric/.../client/ClientDeltaNetworking.java`
- Meaning:
  The sync path was skipped or failed at a top-level boundary.
- Current reason values:
  `EMPTY_DELTA`
  `PLAYER_UNAVAILABLE`
  `PAYLOAD_TOO_LARGE`
  `INVALID_PAYLOAD`
  `CHUNK_NOT_DELTA_CAPABLE`
  `CLIENT_WORLD_UNAVAILABLE`
  `DECODE_FAILED`
  `MAPPING_LOOKUP_FAILED`
  `IO_EXCEPTION`
- Notes:
  `ClientDeltaNetworking` now classifies malformed client payload decode failures at the top-level apply boundary without adding deeper codec instrumentation.

### `DURABILITY_TEST_STARTED`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO`
- Implemented in:
  `fabric/.../command/DurabilityTestCommand.java`
- Meaning:
  The teleport-churn durability reproducer started a new run.
- Current fields:
  source, world id when available, operation id

### `DURABILITY_TELEPORT_EXECUTED`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO`
- Implemented in:
  `fabric/.../command/DurabilityTestCommand.java`
- Meaning:
  One durability teleport step was queued onto the server thread.
- Current fields:
  source, world id, chunk key, operation id
- Notes:
  This is intentionally one event per queued teleport step, not a deeper per-block or per-save trace.

### `DURABILITY_TEST_STOPPED`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO`
- Implemented in:
  `fabric/.../command/DurabilityTestCommand.java`
- Meaning:
  The durability run stopped because it completed, was manually stopped, or was replaced by a newer run.
- Current fields:
  source, optional world id, operation id

### `DURABILITY_TEST_FAILED`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `ERROR`
- Implemented in:
  `fabric/.../command/DurabilityTestCommand.java`
- Meaning:
  The reproducer failed on its scheduler thread before finishing normally.
- Current reason values:
  `IO_EXCEPTION`
- Notes:
  The current reason bucket is intentionally coarse because this path is about reproducer lifecycle evidence, not failure taxonomy.

## Not implemented yet

- No cross-system transaction id threading for vanilla-cancel -> Chunkis-save correlation yet.
- No `BOTH` load-source classification yet.
- No dedicated decoder internals or per-stage client-network palette failure events yet.
- No dedicated watchpoint/export event types; operator watchpoints and JSONL dump commands now reuse the base store without extra trace noise.
- No broad invariant-failure enforcement yet beyond the implemented off-thread assertion.
