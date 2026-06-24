# Next Tasks

## Current phase status

- [x] Phase 1 audit and observability planning
- [x] Phase 2 pass 1: minimal trace runtime and first save/load/restore flight recorder

## Phase 2 completed in this pass

- [x] Added shared minimal trace runtime under `core/.../debug/*`
  - `ChunkisDebugLevel`
  - `ChunkisDebugDomain`
  - `ChunkTraceEventType`
  - `ChunkTraceSeverity`
  - `ChunkTraceReason`
  - `DebugChunkKey`
  - `DebugRegionKey`
  - `ChunkTraceEvent`
  - `ChunkTraceStore`
  - `ChunkisDebugConfig`
- [x] Kept debug `OFF` by default with near-zero disabled-path overhead
- [x] Instrumented first save-path evidence
  - save requested
  - vanilla save cancelled
  - save rejected
  - save queued
  - save flush started/completed/failed
  - region write start/end
  - delta marked dirty/clean
- [x] Instrumented first load/restore evidence
  - load start/end
  - load source resolved as `TRACKER_MEMORY`, `CHUNKIS_STORAGE`, or `NEITHER`
  - region read start/end
  - restore start/completed/failed
- [x] Added minimal operator commands
  - `/chunkis debug on`
  - `/chunkis debug off`
  - `/chunkis debug latest <count>`
  - `/chunkis debug clear`
- [x] Added focused validation
  - `ChunkTraceStoreTest`
  - `ChunkDeltaTraceTest`
  - `CisStorageTraceTest`
  - `ChunkDebugCommandTest`

## Phase 2 still remaining

- [ ] Finish `GlobalChunkTracker` and unload-adjacent lifecycle coverage
  - Goal:
    make dirty chunk disappearance across unload/cache transitions visible
- [ ] Finish `WorldChunkMixin`
  - Goal:
    tie live mutation and restore-to-live-chunk boundaries into the same trace story
- [ ] Add transaction correlation ids if the timeline becomes ambiguous
  - Goal:
    extend the new save/load/restore operation ids across the remaining lifecycle edges
  - [ ] Add decoder/mapping failure events
  - Goal:
    distinguish persistence loss from decode corruption
- [ ] Add client-sync instrumentation
  - Goal:
    rule out client-only ghost failures separately from persistence failures

## Phase 3 candidates

- [ ] Add actual invariant enforcement with `ASSERTION_FAILED`
- [ ] Add JSONL export or other durable trace dump
- [ ] Add queue snapshots/watchpoints for selected chunks only
- [ ] Add durability game-test assertions against trace timelines
- [ ] Add `PARANOID` read-back verification for storage success

## Explicitly deferred from this pass

- [x] No persistence bug fixes
- [x] No architecture rewrite
- [x] No ImGUI
- [x] No broad command surface beyond `on/off/latest/clear`
- [x] No large watchpoint/export system
- [x] No forced `BOTH` load-source classification

## Small Phase 2 improvement completed in this pass

- [x] Added operation ids to the current traced save/load/restore path
  - Files:
    `ChunkTraceStore`, `CisStorage`, `RegionFile`, `AsyncCisSaveManager`, `ThreadedAnvilChunkStorageMixin`, `ChunkSerializerMixin`, `ChunkRestorer`, `WorldChunkMixin`
  - Goal:
    keep one timeline coherent across hook, queue, storage, and region-file boundaries
- [x] Added first tracker and lifecycle-boundary evidence
  - Files:
    `GlobalChunkTracker`, `WorldChunkMixin`, `ChunkDebugCommand`
  - Goal:
    make dirty-map transitions visible, expose unload-cache hit/miss, surface stale async completions, and show `op=` in command timelines

## Verification completed for this pass

- [x] `./gradlew :core:test --tests "io.liparakis.chunkis.debug.ChunkTraceStoreTest" --tests "io.liparakis.chunkis.debug.ChunkDeltaTraceTest" --tests "io.liparakis.chunkis.storage.io.CisStorageTraceTest"`
- [x] `./gradlew :fabric:compileJava :fabric:compileTestJava -x :fabric:runGameTest`
- [x] `./gradlew :fabric:test --tests "io.liparakis.chunkis.command.StorageReportCommandTest" --tests "io.liparakis.chunkis.command.ChunkDebugCommandTest" -x :fabric:runGameTest`

## Known repo-level noise during verification

- `:fabric:runGameTest` is not part of this pass's green bar. A broader Fabric run hit existing migration/game-test failures unrelated to this trace-core diff.
