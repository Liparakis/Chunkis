# Chunkis Debug Observability Plan

## Goals

- Make chunk load, save, unload, restore, region I/O, palette/mapping resolution, base-chunk capture, and client delta sync traceable with evidence.
- Reuse existing runtime choke points instead of building a parallel debug system.
- Keep debugging disabled by default and near-zero overhead when off.
- Produce structured events that can be exported, filtered, and later consumed by an ImGUI view.
- Anchor Phase 2 instrumentation in actual code paths already present in `core/src/main/java` and `fabric/src/main/java`.

## Non-goals

- No persistence bug fix pass.
- No storage algorithm rewrite.
- No speculative save/load behavior change.
- No full command/watchpoint/snapshot surface in Phase 1.
- No ImGUI implementation in this pass.
- No duplicate log bus beside the eventual structured trace store.

## Existing Runtime Choke Points Found In Audit

- Save interception and vanilla-save suppression:
  `fabric/.../mixin/storage/ThreadedAnvilChunkStorageMixin.java`
  `fabric/.../mixin/storage/StoragePreventionMixin.java`
- Load and proto restore path:
  `fabric/.../mixin/storage/ChunkSerializerMixin.java`
  `fabric/.../mixin/world/WorldChunkMixin.java`
  `fabric/.../world/ChunkRestorer.java`
- Dirty tracking and unload-gap cache:
  `fabric/.../world/GlobalChunkTracker.java`
  `fabric/.../mixin/world/CommonChunkMixin.java`
- Async save queue and deferred base capture:
  `fabric/.../storage/AsyncCisSaveManager.java`
  `fabric/.../storage/BaseChunkCaptureScheduler.java`
  `fabric/.../storage/BaseChunkCaptureUtil.java`
- Core storage and region allocator:
  `core/.../storage/io/CisStorage.java`
  `core/.../storage/io/RegionFile.java`
- Codec / mapping / NBT envelope:
  `core/.../storage/codec/AbstractCisEncoder.java`
  `core/.../storage/codec/AbstractCisDecoder.java`
  `core/.../storage/mapping/CisMapping.java`
  `fabric/.../storage/CisNbtUtil.java`
- Client sync:
  `fabric/.../mixin/network/ChunkHolderMixin.java`
  `fabric/.../network/ChunkisNetworking.java`
  `fabric/.../client/ClientDeltaNetworking.java`
- Existing validation support:
  storage/unit tests in `core/src/test/java`
  network / metadata / snapshot tests in `fabric/src/test/java`
  durability and migration game tests in `fabric/src/gametest/java`

## Architecture Direction

- Phase 2 should add one small structured trace layer, not another logger farm.
- The trace layer should sit beside existing code and be called from the runtime choke points above.
- The trace layer should consist of:
  - debug level enum
  - debug domain enum
  - event type enum
  - severity enum
  - reason enum
  - immutable event record
  - bounded in-memory ring buffer
  - optional JSONL export helper
- Phase 1 conclusion: this core is needed in Phase 2, but not needed yet to complete the audit or design, so it is intentionally not added in this pass.

## Debug Domains

- `CHUNK_LIFECYCLE`
- `DELTA_MUTATION`
- `DIRTY_TRACKING`
- `SAVE_GUARDS`
- `LOAD_GUARDS`
- `UNLOAD_GUARDS`
- `REGION_STORAGE`
- `PALETTE_STORAGE`
- `NBT_SERIALIZATION`
- `BASE_CHUNK_CAPTURE`
- `STRUCTURE_METADATA`
- `CLIENT_SYNC`
- `THREADING`
- `MIXIN_HOOKS`
- `COMMANDS`
- `DURABILITY_TEST`
- `ASSERTIONS`

## Debug Levels

- `OFF`
  No event capture except unavoidable boot-time hard failures.
- `ERRORS_ONLY`
  Assertions, rejects, failed reads/writes, decode failures, client-sync ordering failures.
- `LIFECYCLE`
  Load/save/unload/restore/client-sync timelines.
- `VERBOSE`
  Guard inputs, metadata presence, block/entity counts, palette sizes, queue state.
- `PARANOID`
  Fingerprints, before/after snapshots, stack fragments, watched-chunk deep traces.

## Event Schema

Required baseline fields:

- monotonic event id
- wall-clock timestamp
- thread name
- domain
- event type
- severity
- reason
- source class
- source method

Optional fields only when cheaply available:

- transaction id
- operation id
- world/dimension id
- chunk x/z
- region x/z
- dirty state
- has delta
- has base chunk NBT
- has full block baseline
- block change count
- block entity count
- entity count
- palette size
- storage path
- byte size
- short message

## Transaction / Span Model

- Keep transactions coarse and few.
- Start with transaction ids local to one logical operation.
- Prefer operation-local ids over fake cross-thread continuity.
- Phase 2 recommended transactions:
  - `LOAD_TX_*`
  - `SAVE_TX_*`
  - `RESTORE_TX_*`
  - `REGION_READ_TX_*`
  - `REGION_WRITE_TX_*`
  - `CLIENT_SYNC_TX_*`
  - `DURABILITY_TELEPORT_TX_*`
- Async save worker boundaries should emit both:
  - main-thread queue event
  - worker-thread flush event
  Not a dishonest single uninterrupted span if state is not actually carried through today.

## Command List

Phase 1 decision:

- Do not implement debug commands yet.
- Reuse existing command registration in `ChunkisMod`.
- Reuse existing `DurabilityTestCommand` and `StorageReportCommand` as future integration points.

Phase 2 likely command surface:

- debug on/off
- debug level
- latest events
- clear events
- export jsonl
- assertions
- trace chunk
- trace region

Current Phase 3 command surface:

- `/chunkis debug watch chunk <x> <z>`
- `/chunkis debug watch region <x> <z>`
- `/chunkis debug watch list`
- `/chunkis debug watch pending`
- `/chunkis debug watch latest <count>`
- `/chunkis debug watch clear`
- `/chunkis debug export latest <count>`
- `/chunkis debug export watched <count>`

Commands deferred from Phase 1:

- watch/unwatch
- inspect dirty/pending-saves/loaded
- storage browser style commands

## Watchpoint Model

- Watchpoints are useful, but not necessary to complete Phase 1.
- Watchpoints should be chunk/region keyed filters layered on top of global level/domain gating.
- If added in Phase 2, watched keys should unlock `PARANOID` detail without forcing global `PARANOID`.
- Do not add this before the base event store exists.

Current Phase 3 implementation:

- watchpoints are chunk/region keyed filters over the existing in-memory store
- watchpoints currently affect operator queries only; they do not change trace capture volume
- `watch latest` reuses the base ring buffer and filters by watched chunk/region keys
- `watch pending` snapshots watched-chunk dirty-tracker, async-save-queue, and deferred-base-capture state

## JSONL Export Plan

- JSONL is a Phase 2 requirement.
- Export should serialize exactly what the in-memory store holds.
- One event per line.
- Stable field names.
- No external JSON dependency unless already present.
- Export should target a predictable path under the world or run directory.

Current Phase 3 implementation:

- export now serializes the bounded in-memory store directly as JSONL
- each line contains the same structured event fields already held in memory
- exports are written under `<world>/chunkis/debug/`
- `export latest` dumps the latest `count` events in chronological order
- `export watched` dumps the latest watched events in chronological order

## ImGUI Readiness Plan

Phase 1 decision:

- Document data needs now.
- Do not implement GUI now.

Future windows and minimum data they need:

- Chunk Timeline
  events filtered by chunk key
- Region Timeline
  events filtered by region key
- Assertion List
  assertion/failure subset
- Transaction Viewer
  grouped events by transaction id
- Storage Summary
  region write/read stats and inspector snapshots
- Palette Summary
  mapping/palette events and failures
- Durability Monitor
  durability command events + load/save churn for watched chunks

Recommended future snapshot APIs:

- latest events
- events by chunk
- events by region
- assertion subset
- transaction subset
- storage summary snapshot
- watch registry snapshot

Current Phase 3 queue snapshot surface:

- watched chunk -> dirty tracker presence
- watched chunk -> async pending save operation/generation/dirty state
- watched chunk -> deferred base capture queue presence

## Implementation Phases

### Phase 1

- Complete audit of all production Java source roots in this repository.
- Audit validation sources separately.
- Write the observability docs.
- Decide what to keep, merge, delay, or skip.

### Phase 2

- Add minimal trace-core package.
- Instrument core load/save/unload/restore/storage/client-sync hooks.
- Add assertion events for the highest-value invariants.

### Phase 3

- Add JSONL export.
- Add minimal operator-facing debug commands.
- Add targeted tests and game tests for the new event surface.

### Phase 4

- Add watchpoints and higher-cost fingerprints.
- Add GUI-facing snapshots only after command/export truth is stable.

## Acceptance Criteria

- Every Java file in the configured production roots is classified.
- Validation sources are audited separately as support.
- Every kept idea names exact hook files/methods.
- Every skipped idea has a concrete reason.
- Phase 2 is clearly defined as the first real instrumentation pass.
- No persistence behavior change is introduced in Phase 1.
- No duplicate debug system is proposed.
