# Delta And Ownership Model

## Problem this subsystem solves

Chunkis needs one in-memory representation that can track live edits, carry persisted state through load/restore, and support asynchronous save decisions without forcing the rest of the code to reason about raw NBT or raw CIS bytes.

That representation is `ChunkDelta`.

## Responsibilities

- Store block changes, block entity payloads, entity payloads, and chunk-level metadata for one chunk.
- Track dirty state through `mutationGeneration` and `savedGeneration`.
- Support snapshots for async save work.
- Carry persistence anchors such as base chunk NBT and replay suppression metadata.
- Expose efficient iteration for encode and restore paths.

## What it does not do

- It is not thread-safe for concurrent mutation.
- It does not own global storage or region-file logic.
- It does not decide on its own whether a payload is safe to persist.
- It is not always the exact persisted shape of a chunk during normal saves.

## Owning classes

- `core/.../core/ChunkDelta.java`
- `fabric/.../storage/ChunkDeltaOwnership.java`
- `fabric/.../api/ChunkisDeltaDuck.java`
- `fabric/.../mixin/world/CommonChunkMixin.java`

## Important entry points

- `ChunkDelta.addBlockChange(...)`
- `ChunkDelta.addBlockEntityData(...)`
- `ChunkDelta.setEntities(...)`
- `ChunkDelta.snapshot(...)`
- `ChunkDelta.markDirty()`
- `ChunkDelta.markSaved()` / `markSavedIfGeneration(...)`
- `ChunkDeltaOwnership.hasChunkisOwnedState(...)`
- `ChunkDeltaOwnership.hasChunkisPersistenceAnchor(...)`

## Lifecycle

### 1. Attachment

Every chunk gets a delta through `CommonChunkMixin`. The default attached value is an empty `ChunkDelta`.

### 2. Live mutation tracking

`WorldChunkMixin` writes block, block-entity, and removal changes into the chunk's runtime delta and then registers it with `GlobalChunkTracker`.

### 3. Save-time snapshot rebuild

On the normal save hook, Chunkis does not trust the live sparse runtime shape as the final persisted representation. `CisSnapshotCapture` rebuilds the delta into an authoritative snapshot of the chunk's current block and block-entity state before encode.

### 4. Load and restore

Decoded deltas are attached to proto chunks and then replayed into live chunks during world-chunk construction. The runtime delta is repopulated silently so restore itself does not make the chunk dirty.

## Ownership model

`ChunkDeltaOwnership` answers the question "does this delta represent Chunkis-owned state, or only an empty placeholder?"

Chunkis treats a delta as meaningful when either of these is true:

- it has replay payload: block changes, block entities, or entities
- it has a persistence anchor: persisted base chunk NBT or a full-block baseline flag

This distinction matters because empty placeholder deltas must not suppress vanilla behavior by themselves.

## Invariants

- `mutationGeneration != savedGeneration` means dirty.
- A runtime delta can be meaningful even when clean, because it may still carry persisted anchors.
- Metadata is part of persistence ownership, not just debug garnish.
- Restore must copy data into the runtime delta without turning it into a fresh mutation.

## Common debugging locations

- [ChunkDelta.java](C:/Users/Liparakis/Desktop/Chunkis/core/src/main/java/io/liparakis/chunkis/core/ChunkDelta.java)
- [CommonChunkMixin.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/mixin/world/CommonChunkMixin.java)
- [ChunkDeltaOwnership.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/storage/ChunkDeltaOwnership.java)

## Common failure modes

- Treating an empty clean delta as authoritative state when it is only a placeholder.
- Forgetting that metadata can make a clean delta still meaningful.
- Async save completion marking the wrong generation as saved.
- Persisting sparse replay data without a base anchor.

## See also

- [Save Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Save-Pipeline.md)
- [Snapshots And Metadata](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Snapshots-And-Metadata.md)
- [Tracking, Guards, And Durability](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Tracking-Guards-And-Durability.md)
