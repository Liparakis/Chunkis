# Save Pipeline

## What This Area Is

The save pipeline captures authoritative chunk state, rejects unsafe sparse payloads, and persists the result to CIS storage while suppressing conflicting vanilla writes.

## What Owns It

- save interception: `ThreadedAnvilChunkStorageMixin`
- snapshot capture: `CisSnapshotCapture`, `BaseChunkCaptureUtil`, `LiveEntitySnapshotCapture`
- persistence safety: `DeltaPersistenceGuard`
- async write path: `AsyncCisSaveManager`, `AsyncCisSaveWorker`
- actual disk write: `FabricCisStorageHelper`, `CisStorage`
- vanilla entity-region cleanup: `VanillaEntityRegionCleanup`

## How It Relates To Other Flows

- feeds the `storage` module with already-owned, already-vetted payloads
- updates `GlobalChunkTracker` only after write success
- leaves vanilla writes in place only when Chunkis did not take ownership

## Key Entry Points

- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/save/AsyncCisSaveManager.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/save/AsyncCisSaveWorker.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java`

## Current Flow

1. The save hook resolves the chunk and attached `ChunkDelta`.
2. Unowned or clean placeholder state is skipped.
3. `CisSnapshotCapture` rebuilds authoritative saved state.
4. Structure metadata and live entity capture are merged into the delta when needed.
5. Sparse payload recovery may capture a base chunk before persistence.
6. `DeltaPersistenceGuard` rejects replay payloads that have neither persisted base metadata nor full baseline metadata.
7. Normal saves queue work through `AsyncCisSaveManager.submit(...)`.
8. Shutdown and load-path safety flushes still use synchronous `FabricCisStorageHelper.saveTrackedDelta(...)`.
9. Authoritative worlds delete stale vanilla `entities/r.*.*.mca` files after unload; the low-level region guard prevents new writes.

## Current Sharp Edges

- save-time snapshot capture can rewrite the delta shape substantially; treating Chunkis as a plain sparse-delta format is wrong
- async completion is generation-guarded, so stale workers do not clear newer dirty state
- empty deltas clear stored entries instead of persisting empty payload bytes

## Where Behavior Is Proven

- `ThreadedAnvilChunkStorageMixin`
- `AsyncCisSaveWorker`
- `ThreadedAnvilChunkStorageMixinSavePathTest`
- `AsyncSaveDataLossGameTest`

## Evidence

- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/capture/CisSnapshotCapture.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/capture/BaseChunkCaptureUtil.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/entity/capture/LiveEntitySnapshotCapture.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/save/AsyncCisSaveWorker.java`
- `fabric/src/gametest/java/io/liparakis/chunkis/gametest/AsyncSaveDataLossGameTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixinSavePathTest.java`
