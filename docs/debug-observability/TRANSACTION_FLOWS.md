# Transaction Flows

This file reflects the Phase 2 implementation as it exists now, not the larger future design.

Current note:

- The traced save/load/restore path now carries operation ids across the participating events below.
- `/chunkis debug latest <count>` now prints `op=...` so those timelines are visible in command output.

## Normal async save

1. `SAVE_TX_START`
2. optional `SAVE_REJECTED`
3. `SAVE_QUEUED`
4. `SAVE_FLUSH_STARTED`
5. `REGION_WRITE_TX_START`
6. `REGION_WRITE_TX_END`
7. `SAVE_FLUSH_COMPLETED`
8. `DELTA_MARKED_CLEAN`

## Synchronous save path

1. `SAVE_TX_START`
2. `SAVE_FLUSH_STARTED`
3. `REGION_WRITE_TX_START`
4. `REGION_WRITE_TX_END`
5. `SAVE_FLUSH_COMPLETED`
6. `DELTA_MARKED_CLEAN`

## Save rejected by sparse guard

1. `SAVE_TX_START`
2. `SAVE_REJECTED`

Notes:

- This can currently happen from `ThreadedAnvilChunkStorageMixin` or `BaseChunkCaptureScheduler`.
- There is not yet a linked hard assertion that cancelled vanilla save plus rejection equals data-loss proof.

## Vanilla save cancellation

1. `VANILLA_SAVE_CANCELLED`

Notes:

- This is recorded at the region-write cancellation hook.
- Correlating it to a specific queued/flushed Chunkis save is still a future transaction-correlation step.

## Dirty tracker activity

1. `DELTA_MARKED_DIRTY`
2. `TRACKER_STATE_UPDATED reason=TRACKER_DIRTY_MAP_PUT`
3. optional later `TRACKER_STATE_UPDATED reason=TRACKER_MARK_SAVED`
4. optional `TRACKER_STATE_UPDATED reason=STALE_GENERATION_IGNORED`

## Unload-cache lookup

1. active dirty delta missing
2. `TRACKER_STATE_UPDATED reason=TRACKER_UNLOAD_CACHE_HIT|TRACKER_UNLOAD_CACHE_MISS`

## Unload-cache lifecycle

1. `TRACKER_STATE_UPDATED reason=TRACKER_UNLOAD_CACHE_PUT`
2. optional `TRACKER_STATE_UPDATED reason=AUTHORITATIVE_DELTA_KEPT`
3. optional `TRACKER_STATE_UPDATED reason=TRACKER_UNLOAD_CACHE_EVICT`

## Live chunk unload

1. `TRACKER_STATE_UPDATED reason=TRACKER_CHUNK_UNLOADED`
2. optional later `TRACKER_STATE_UPDATED reason=TRACKER_UNLOAD_CACHE_HIT`

Notes:

- `dirty=true` means the live world chunk unloaded while a dirty delta still existed in the tracker.
- This is correlation evidence only; it does not claim that a save had already flushed.

## Load from tracker memory

1. `LOAD_TX_START`
2. `LOAD_SOURCE_RESOLVED reason=TRACKER_MEMORY`
3. `LOAD_TX_END`
4. `RESTORE_TX_START`
5. `RESTORE_COMPLETED` or `RESTORE_FAILED`

Notes:

- When a proto chunk carries a Chunkis delta into `WorldChunkMixin`, restore now reuses the same `load-*` operation id rather than minting a separate restore id.

## Load from Chunkis storage

1. `LOAD_TX_START`
2. `REGION_READ_TX_START`
3. `REGION_READ_TX_END`
4. `LOAD_SOURCE_RESOLVED reason=CHUNKIS_STORAGE`
5. `LOAD_TX_END`
6. `RESTORE_TX_START`
7. `RESTORE_COMPLETED` or `RESTORE_FAILED`

Notes:

- The same load operation id now survives the proto-attach boundary into the live restore call.

## Load with no meaningful Chunkis data

1. `LOAD_TX_START`
2. optional `REGION_READ_TX_START`
3. optional `REGION_READ_TX_END reason=MISSING_ENTRY`
4. `LOAD_SOURCE_RESOLVED reason=NEITHER`
5. `LOAD_TX_END`

Notes:

- `BOTH` is not emitted in the current implementation.

## Load with unreadable stored bytes

1. `LOAD_TX_START`
2. `REGION_READ_TX_START`
3. `REGION_READ_TX_END`
4. `LOAD_TX_END reason=DECOMPRESSION_FAILED|DECODE_FAILED|MAPPING_LOOKUP_FAILED`

Notes:

- `CisStorage` clears the unreadable stored entry after tracing the failure and returns an empty delta.

## Restore result interpretation

1. `RESTORE_TX_START`
2. `RESTORE_COMPLETED`

Meaning of `RESTORE_COMPLETED` right now:

- reason `NONE`
  some meaningful block, block-entity, or entity application happened
- reason `RESTORE_EMPTY_RESULT`
  restore ran but aggregate applied counts were zero
- `RESTORE_FAILED` can still appear later from `WorldChunkMixin` if portal POI or portal index follow-up breaks after the core restore already completed

## Region read

1. `REGION_READ_TX_START`
2. `REGION_READ_TX_END`

Current outcomes:

- payload found
- missing entry

## Region write

1. `REGION_WRITE_TX_START`
2. `REGION_WRITE_TX_END`

Current outcomes:

- normal write completion
- clear/overwrite path still ends as one region-write transaction

## Durability timeline usage

For the current disappearing-chunk investigation, the minimum useful timeline is:

1. `SAVE_TX_START`
2. `VANILLA_SAVE_CANCELLED` if it happened
3. `SAVE_REJECTED` or `SAVE_QUEUED`
4. `SAVE_FLUSH_*`
5. `DELTA_MARKED_CLEAN`
6. `LOAD_SOURCE_RESOLVED`
7. `RESTORE_COMPLETED`

The new `/chunkis debug latest <count>` command is sufficient to inspect this timeline in-memory during a durability run.

## Durability reproducer command

1. `DURABILITY_TEST_STARTED`
2. repeated `DURABILITY_TELEPORT_EXECUTED`
3. `DURABILITY_TEST_STOPPED` or `DURABILITY_TEST_FAILED`

Notes:

- These events use their own `durability-*` operation ids so a reproducer run can be separated from save/load/restore operations in the same trace store.
- A stop event currently means one of three truthful outcomes only: normal completion, manual stop, or replacement by a newer run.

## Client sync

1. `CLIENT_SYNC_TX_START`
2. `CLIENT_SYNC_TX_END` or `CLIENT_SYNC_FAILED`
3. client-side `CLIENT_SYNC_TX_START`
4. client-side `CLIENT_SYNC_TX_END` or `CLIENT_SYNC_FAILED reason=INVALID_PAYLOAD|CLIENT_WORLD_UNAVAILABLE|CHUNK_NOT_DELTA_CAPABLE|DECODE_FAILED|MAPPING_LOOKUP_FAILED|IO_EXCEPTION`

Notes:

- Current client-sync operation ids are local to each side; they are not carried over the wire.
- This is enough to separate "server sent nothing" from "client received/applied badly" without changing packet format.
