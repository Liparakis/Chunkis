# Bug Hunt Notes

## Observed Symptom

- Chunks vanish during durability testing.
- The mod currently cancels vanilla chunk persistence globally and relies on Chunkis save/load/restore paths.

## Hypotheses Already Tried

- Speculative persistence fixes outside a full traceable model.
- Targeted changes without enough evidence about where the data was lost.

## Why Speculative Fixing Failed

- The runtime path spans mixins, tracker memory, async save queue, base-chunk capture, region storage, metadata envelopes, and client sync.
- Existing logs are sparse and inconsistent.
- A failed chunk can disappear because of:
  - rejected save
  - queued but unflushed save
  - successful write but broken read/decode
  - restore applying nothing useful
  - client ordering confusion
  - dirty state leaving lifecycle without persistence

## Codebase Findings That Matter

- Vanilla chunk writes are cancelled in `StoragePreventionMixin`.
- Save interception happens in `ThreadedAnvilChunkStorageMixin`.
- Async writes are coalesced and generation-checked in `AsyncCisSaveManager`.
- Dirty state is split across `ChunkDelta`, `CommonChunkMixin`, `WorldChunkMixin`, and `GlobalChunkTracker`.
- Load reconstruction uses both tracker-memory and disk paths via `ChunkSerializerMixin` and `ThreadedAnvilChunkStorageMixin`.
- Restore is authoritative and clears chunks to air before replay in `ChunkRestorer`.
- Base chunk capture can be deferred and flushed later through `BaseChunkCaptureScheduler`.

## What The New Debugging System Must Reveal

- whether the chunk was ever marked dirty
- whether save was requested
- whether vanilla save was cancelled
- whether Chunkis save was rejected, queued, flushed, or failed
- whether the delta was marked clean before real persistence
- whether region storage contained the chunk after write
- whether load used tracker memory, storage, both, or neither
- whether restore applied the stored payload meaningfully
- whether client delta ordering can confuse the symptom

## Trace Questions To Answer

- Was the chunk ever saved?
- Was vanilla save cancelled?
- Was Chunkis save queued?
- Was Chunkis save flushed?
- Was the delta marked clean before flush?
- Did the region file contain the chunk after write?
- Did load find vanilla data, Chunkis data, both, or neither?
- Did restore apply zero blocks from non-empty data?
- Did palette lookup fail?
- Did client receive delta before/after base chunk?
- Did unload happen while dirty?
- Did any helper method silently drop metadata?
- Did any mixin hook run in an unexpected order?
- Did a background thread touch world/chunk state unsafely?
- Did any storage operation report success without read-back-verifiable payload?

## Phase 1 Conclusion

- The mod does not need more ad hoc fixes right now.
- It needs a single structured trace path rooted in the existing lifecycle choke points.
- Phase 2 should instrument save/load/restore/storage/client-sync first.

## Phase 2 Current State

- The first structured flight recorder now exists.
- Save requested / cancelled / rejected / queued / flushed / clean transitions are now observable.
- Load source is now observable as `TRACKER_MEMORY`, `CHUNKIS_STORAGE`, or `NEITHER`.
- Restore now reports completion/failure plus aggregate applied counts.
- `BOTH` load-source classification is still intentionally deferred because current code does not observe both sources truthfully in one load pass.
- No persistence behavior was intentionally changed in this pass.
