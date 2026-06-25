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
- [x] Added first client-sync timeline boundaries
  - server send start/end/failure
  - client apply start/end/failure
  - payload byte size on traced sync events
- [x] Added first client-side decode failure classification
  - malformed network payloads now distinguish `DECODE_FAILED` vs `MAPPING_LOOKUP_FAILED`
- [x] Added first storage decode-failure classification
  - decompression failure vs generic decode failure vs mapping lookup failure
- [x] Added first unload-cache lifecycle evidence
  - cache put
  - authoritative delta kept over weaker replacement
  - cache eviction
- [x] Added focused validation
  - `ChunkTraceStoreTest`
  - `ChunkDeltaTraceTest`
  - `CisStorageTraceTest`
  - `ChunkDebugCommandTest`
- [x] Added durability reproducer timeline evidence
  - `DURABILITY_TEST_STARTED`
  - `DURABILITY_TELEPORT_EXECUTED`
  - `DURABILITY_TEST_STOPPED`
  - `DURABILITY_TEST_FAILED`
  - dedicated `durability-*` operation ids from the reproducer command
- [x] Added focused durability validation
  - `DurabilityTestCommandTest`
- [x] Added true unload-hook correlation
  - `TRACKER_CHUNK_UNLOADED`
  - dirty-state payload on unload events
  - Fabric chunk-unload registration in `ChunkisMod`
- [x] Added first live-mutation origin visibility
  - `TRACKER_DIRTY_MAP_PUT` now carries `WorldChunkMixin` entrypoint source for block state and block-entity mutation hooks

## Phase 2 still remaining

- [ ] Finish `WorldChunkMixin`
  - Goal:
    finish the remaining restore-to-live-chunk story beyond the newly added live-mutation-origin visibility
- [ ] Add transaction correlation ids if the timeline becomes ambiguous
  - Goal:
    extend the new save/load/restore operation ids across any remaining lifecycle edges beyond the now-threaded load -> restore handoff
- [ ] Extend decoder/mapping failure evidence beyond storage-load classification
  - Goal:
    cover deeper decoder internals beyond the current storage-load and client-network boundary classifications only if the current signal proves insufficient
- [ ] Extend client-sync instrumentation beyond top-level boundaries
  - Goal:
    add payload compression/ordering evidence only if the current send/apply timeline is insufficient
- [ ] Add durability reproducer assertions beyond chunk-target mapping
  - Goal:
    cover start/stop/failure event emission with a command-level or scheduler-harness test only if that can be done without invasive command refactoring

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
- [x] Extended tracker cache lifecycle evidence
  - Files:
    `GlobalChunkTracker`
  - Goal:
    show when deltas enter the unload cache, when weaker replacements are refused, and when cache capacity evicts old entries
- [x] Added true chunk-unload correlation
  - Files:
    `GlobalChunkTracker`, `ChunkisMod`
  - Goal:
    show when a live world chunk actually unloads and whether a dirty delta was still actively tracked at that boundary
- [x] Added first live mutation-origin evidence
  - Files:
    `WorldChunkMixin`, `GlobalChunkTracker`
  - Goal:
    show which live world mutation entrypoint first dirtied a chunk without adding per-edit event spam
- [x] Threaded load operation ids into live restore
  - Files:
    `ChunkisDeltaDuck`, `CommonChunkMixin`, `ChunkSerializerMixin`, `WorldChunkMixin`
  - Goal:
    keep one coherent operation id from proto load resolution through the later live restore call
- [x] Tightened client delta failure evidence without deeper protocol churn
  - Files:
    `ClientDeltaNetworking`
  - Goal:
    classify malformed client payload decode failures truthfully and avoid redundant re-decode work on the client apply path

## Verification completed for this pass

- [x] `./gradlew :core:test --tests "io.liparakis.chunkis.debug.ChunkTraceStoreTest" --tests "io.liparakis.chunkis.debug.ChunkDeltaTraceTest" --tests "io.liparakis.chunkis.storage.io.CisStorageTraceTest"`
- [x] `./gradlew :fabric:compileJava :fabric:compileTestJava -x :fabric:runGameTest`
- [x] `./gradlew :fabric:test --tests "io.liparakis.chunkis.command.StorageReportCommandTest" --tests "io.liparakis.chunkis.command.ChunkDebugCommandTest" -x :fabric:runGameTest`
- [x] `./gradlew :fabric:test --tests "io.liparakis.chunkis.command.DurabilityTestCommandTest" --tests "io.liparakis.chunkis.command.ChunkDebugCommandTest" -x :fabric:runGameTest`
- [x] `./gradlew :fabric:test --tests "io.liparakis.chunkis.world.GlobalChunkTrackerTest" -x :fabric:runGameTest`
- [x] `./gradlew :fabric:test --tests "io.liparakis.chunkis.mixin.world.WorldChunkMixinTest" -x :fabric:runGameTest`
- [x] `./gradlew :fabric:test --tests "io.liparakis.chunkis.client.ClientDeltaNetworkingTest" -x :fabric:runGameTest`

## Known repo-level noise during verification

- `:fabric:runGameTest` is not part of this pass's green bar. A broader Fabric run hit existing migration/game-test failures unrelated to this trace-core diff.
