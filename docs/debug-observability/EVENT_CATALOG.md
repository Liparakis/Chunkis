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
  world id, chunk key, source, dirty state
- Notes:
  Generation is currently encoded in the message, not a dedicated field.

### `SAVE_FLUSH_STARTED`

- Domain: `REGION_STORAGE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/CisStorage.java`
- Meaning:
  Storage is beginning compression/write of a prepared payload.

### `SAVE_FLUSH_COMPLETED`

- Domain: `REGION_STORAGE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/CisStorage.java`
- Meaning:
  Storage completed a prepared payload write successfully.
- Current fields:
  chunk key, region key, source, byte size

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

### `REGION_WRITE_TX_START`

- Domain: `REGION_STORAGE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/RegionFile.java`
- Meaning:
  Region-file write work started for one chunk slot.

### `REGION_WRITE_TX_END`

- Domain: `REGION_STORAGE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/RegionFile.java`
- Meaning:
  Region-file write work completed for one chunk slot.
- Current fields:
  chunk key, region key, source, byte size

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

### `LOAD_TX_START`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/CisStorage.java`
  `fabric/.../mixin/storage/ChunkSerializerMixin.java`
- Meaning:
  Load resolution started either at the storage boundary or at the Fabric proto-attach boundary.

### `REGION_READ_TX_START`

- Domain: `REGION_STORAGE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/RegionFile.java`
- Meaning:
  Region-file read work started for one chunk slot.

### `REGION_READ_TX_END`

- Domain: `REGION_STORAGE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/RegionFile.java`
- Meaning:
  Region-file read work completed or returned no payload.
- Current reason values:
  `STORAGE_READ`, `MISSING_ENTRY`

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

### `LOAD_TX_END`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO`
- Implemented in:
  `core/.../storage/io/CisStorage.java`
  `fabric/.../mixin/storage/ChunkSerializerMixin.java`
- Meaning:
  Load resolution ended either with an empty result, a stored delta, or an attached delta.

### `RESTORE_TX_START`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `INFO`
- Implemented in:
  `fabric/.../world/ChunkRestorer.java`
- Meaning:
  Restore into a live `WorldChunk` started.

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
  world id, chunk key, source
- Notes:
  Applied block/block-entity/entity counts are currently carried in the message.

### `RESTORE_FAILED`

- Domain: `CHUNK_LIFECYCLE`
- Severity: `ERROR`
- Implemented in:
  `fabric/.../world/ChunkRestorer.java`
- Meaning:
  Restore failed with an exception that is rethrown after tracing.
- Current reason values:
  `RESTORE_EXCEPTION`

## Not implemented yet

- No operation/transaction id threading yet.
- No `BOTH` load-source classification yet.
- No palette/mapping failure events yet.
- No client-sync events yet.
- No watchpoints/export/ImGUI events yet.
- No invariant-failure events are emitted in this pass.
