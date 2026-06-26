# Snapshots And Metadata

## Problem this subsystem solves

Chunkis cannot rely on world generation staying identical forever. It needs durable metadata that explains how to restore a chunk safely, and it needs a snapshot strategy that separates "live edits right now" from "what must exist on disk to reproduce the chunk later".

## Responsibilities

- Capture authoritative save-time snapshots.
- Capture persisted base chunk NBT when sparse replay alone would be unsafe.
- Preserve structure metadata, replay suppression flags, portal markers, and baseline flags.
- Build synthetic load NBT from those persisted metadata anchors.

## What it does not do

- It does not store metadata as an external side table.
- It does not treat structure metadata as optional decoration.
- It does not assume worldgen can reconstruct old chunks correctly without anchors.

## Owning classes

- `fabric/.../storage/CisSnapshotCapture`
- `fabric/.../storage/BaseChunkCaptureUtil`
- `fabric/.../storage/BaseChunkCaptureScheduler`
- `fabric/.../storage/CisNbtUtil`
- `fabric/.../storage/StructureMetadataExtractor`

## Two snapshot concepts

### Save-time authoritative snapshot

`CisSnapshotCapture.capture(...)` rebuilds a delta from a live `WorldChunk` by:

- clearing old block/block-entity payloads
- scanning chunk sections for non-air blocks
- capturing block entities
- rebuilding metadata with structure data and existing persisted base if present

This is the normal persisted save shape.

### Persisted base chunk snapshot

`BaseChunkCaptureUtil.captureBaseChunk(...)` serializes a vanilla-compatible chunk NBT snapshot and stores it under metadata key `base_chunk_nbt`.

This is a durability anchor used when sparse replay must be based on a stable serialized baseline rather than on future terrain generation.

## Metadata envelope

`CisNbtUtil` builds the metadata envelope. Important pieces are:

- `structures`
- `base_chunk_nbt`
- nested `chunkis` metadata
  - `suppress_initial_repopulation`
  - `full_block_baseline`
  - `portal_chunk`

## Why this exists

Without persisted metadata:

- structures can disappear or repopulate incorrectly
- sparse block entities can be restored onto the wrong block grid
- one-time worldgen side effects can replay when they should not
- portal support can drift from restored block contents

## Load-side usage

`CisNbtUtil.buildLoadChunkNbt(...)` chooses between:

- persisted base chunk NBT as the deserialization baseline
- synthetic empty-shell NBT as the regeneration baseline

That choice is the pivot between snapshot-backed restore and regenerate-then-replay restore.

## Invariants

- Base chunk capture is idempotent once persisted; later saves must not overwrite the original baseline casually.
- Structure metadata must survive save/load even when block replay is sparse.
- Replay suppression lives in metadata because it is restore policy, not a transient runtime toggle.
- Portal presence is metadata because portal indexing must survive persistence.

## Common debugging locations

- [CisSnapshotCapture.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/storage/CisSnapshotCapture.java)
- [BaseChunkCaptureUtil.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/storage/BaseChunkCaptureUtil.java)
- [BaseChunkCaptureScheduler.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/storage/BaseChunkCaptureScheduler.java)
- [CisNbtUtil.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/storage/CisNbtUtil.java)
- [StructureMetadataExtractor.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/storage/StructureMetadataExtractor.java)

## Common failure modes

- block-entity-only sparse payload without persisted base
- base chunk existed in metadata but was skipped or failed during synthetic load construction
- structure metadata lost because a metadata rewrite path ignored existing structures
- base capture deferred too long and only recovered at a later guard/shutdown path

## See also

- [Delta And Ownership Model](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Delta-And-Ownership-Model.md)
- [Save Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Save-Pipeline.md)
- [Load And Restore Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Load-And-Restore-Pipeline.md)
