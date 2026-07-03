# Delta And Ownership Model

## Purpose

`ChunkDelta` is the central data structure that carries Chunkis state through mutation tracking, persistence, load, restore, and client sync.

## Main Classes

- `core/src/main/java/io/liparakis/chunkis/core/ChunkDelta.java`
- `core/src/main/java/io/liparakis/chunkis/core/ChunkDeltaView.java`
- `core/src/main/java/io/liparakis/chunkis/core/ChunkDeltaSnapshotView.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/ownership/ChunkDeltaOwnership.java`
- `fabric/src/main/java/io/liparakis/chunkis/api/ChunkisDeltaDuck.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/world/chunk/CommonChunkMixin.java`

## What A Delta Contains

- block instructions
- block entity payloads
- entity and pending-entity payloads
- chunk metadata
- ownership reason
- source version
- mutation and saved generations

## Lifecycle

### Attachment

Every chunk gets a `ChunkDelta` through `CommonChunkMixin`.

### Live mutation

`WorldChunkMixin` mutates the runtime delta during live block, block-entity, and entity changes, then routes meaningful state into `GlobalChunkTracker`.

### Save-time reshaping

The attached runtime delta is not automatically the persisted shape. The save hook can rebuild it into an authoritative snapshot through `CisSnapshotCapture`.

### Load and restore

Decoded deltas are attached during the proto stage, then replayed into a live chunk. The runtime delta is repopulated silently after restore.

## Ownership Rules

The current ownership helper is `ChunkDeltaOwnership`.

Important distinctions:

- `hasChunkisOwnedState(delta)` answers whether Chunkis currently claims the state.
- `hasChunkisPersistenceAnchor(delta)` answers whether metadata contains a usable persisted anchor.
- `hasReplayPayload(delta)` answers whether there is actual replay content.
- `hasRestorableChunkisState(delta)` means replay payload or persistence anchor exists.

This matters because an attached empty placeholder delta must not be treated as authoritative Chunkis data by itself.

## Dirty-State Rules

- `mutationGeneration != savedGeneration` means dirty.
- async save completion only wins when the same delta instance and generation are still current
- a clean delta can still be meaningful if it carries persisted metadata anchors

## Threading

`ChunkDelta` is not a concurrent mutation structure. Live mutation is server-thread work. Snapshot views are what cross into async save workers.

## Related Docs

- [Save Pipeline](Save-Pipeline.md)
- [Load And Restore Pipeline](Load-And-Restore-Pipeline.md)
- [Snapshots And Metadata](Snapshots-And-Metadata.md)
- [Tracking, Guards, And Durability](Tracking-Guards-And-Durability.md)
