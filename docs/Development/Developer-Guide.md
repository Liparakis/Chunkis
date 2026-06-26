# Developer Guide

## Repository overview

- `core/`
  storage engine, CIS codec, mapping, region files, bit utilities, debug event model
- `fabric/`
  Fabric entrypoints, mixins, runtime tracking, restore logic, commands, networking, migration
- `cismigrator/`
  CIS version-upgrade machinery and tests
- `docs/Architecture/`
  permanent architecture docs; start there before editing persistence code

## Read this before changing anything

The easy wrong assumption is that Chunkis is "a sparse delta save format". That is incomplete.

What the code actually does today:

- live mutations are tracked incrementally in `ChunkDelta`
- the normal save hook rebuilds an authoritative snapshot before persisting
- persisted base chunk NBT exists as a safety anchor for sparse replay paths
- vanilla `.mca` I/O is blocked, so Chunkis owns correctness end to end

If you miss that, you will patch the wrong layer.

## How data flows

1. `CommonChunkMixin` attaches a `ChunkDelta` to each chunk.
2. `WorldChunkMixin` writes live edits into that runtime delta.
3. `GlobalChunkTracker` tracks dirty and recently unloaded deltas.
4. `ThreadedAnvilChunkStorageMixin` intercepts save/load.
5. Save path captures an authoritative snapshot, encodes it, compresses it, and writes to CIS storage.
6. Load path builds synthetic NBT, attaches a decoded delta to a proto chunk, and restores into the promoted world chunk.

## How to debug

For persistence bugs:

1. enable `/chunkis debug on`
2. reproduce
3. inspect `/chunkis debug failures`
4. inspect `/chunkis debug suspects`
5. if needed, add watchpoints and use `/chunkis debug watch pending`

For storage-shape questions:

- use `/chunkis_storage_report`

For durability churn:

- use the durability command and correlate its events with save/load/restore traces

## How to add a subsystem

- Put shared format/storage logic in `core`, not `fabric`.
- Put Minecraft integration in `fabric`.
- Prefer adding to an existing boundary over inventing a new layer.
- If the feature changes persistence semantics, update the architecture docs in `docs/Architecture/` in the same change.

## How to add a storage version

1. update `CisConstants.VERSION`
2. update the encoder/decoder behavior in `core`
3. add a migration edge/path in `cismigrator`
4. add tests in `cismigrator` and any affected codec tests
5. update [Migration And Versioning](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Migration-And-Versioning.md)

Do not change persisted semantics without also defining how old worlds move forward.

## How to add a trace event

1. add the enum entry in the debug model
2. emit it at a real architectural boundary, not every tiny helper
3. give it a machine-meaningful `reason` where relevant
4. decide whether it should participate in suspect promotion or invariant checks
5. update [Observability And Debugging](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Observability-And-Debugging.md)

If a trace event is only useful as a temporary implementation note, do not add it.

## How to diagnose persistence bugs

Ask these in order:

1. Did the runtime delta become dirty?
2. Did the tracker keep the authoritative delta?
3. Did Chunkis reject the save?
4. Was async save queued and flushed for the same generation?
5. Did storage decode succeed on the next load?
6. Was the source memory, storage, or neither?
7. Did restore apply meaningful data?
8. Did post-restore follow-up fail?

That sequence is usually more useful than staring at the codec first.

## Coding conventions and architectural principles

- Prefer existing helpers and boundaries over new abstractions.
- Keep persistence logic explicit. Hidden magic is dangerous here.
- Do not re-enable vanilla region writes as a casual fallback.
- Keep thread ownership obvious. Main-thread mutation and async persistence are intentionally separated.
- When a shortcut has a correctness ceiling, document the ceiling in code.
- Update docs when architecture changes. Do not leave the code as the only source of truth.

## Good entry files

- [System Overview](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/System-Overview.md)
- [ThreadedAnvilChunkStorageMixin.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixin.java)
- [ChunkSerializerMixin.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ChunkSerializerMixin.java)
- [WorldChunkMixin.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/mixin/world/WorldChunkMixin.java)
- [CisStorage.java](C:/Users/Liparakis/Desktop/Chunkis/core/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java)
- [RegionFile.java](C:/Users/Liparakis/Desktop/Chunkis/core/src/main/java/io/liparakis/chunkis/storage/io/RegionFile.java)
