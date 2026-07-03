# Snapshots And Metadata

## Purpose

Chunkis separates runtime mutation tracking from the persisted information needed to restore a chunk safely later.

## Main Classes

- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/capture/CisSnapshotCapture.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/capture/BaseChunkCaptureUtil.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/nbt/CisNbtUtil.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/nbt/ChunkLoadNbtBuilder.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/nbt/StructureMetadataExtractor.java`

## Two Snapshot Shapes

### Authoritative save snapshot

`CisSnapshotCapture.capture(...)` rebuilds the persisted chunk shape used by normal saves.

Current behavior:

- if block entities are present, Chunkis captures a persisted base chunk snapshot and marks suppression metadata
- otherwise it captures a full authoritative block baseline directly into the delta

### Persisted base chunk snapshot

`BaseChunkCaptureUtil.captureBaseChunk(...)` stores a vanilla-compatible base chunk snapshot inside chunk metadata. This is used as a restore anchor when sparse replay alone would be unsafe.

## Metadata Envelope

`CisNbtUtil` owns the current metadata envelope. Important fields include:

- `structures`
- `base_chunk_nbt` or packed `base_chunk_payload`
- nested `chunkis`
  - `suppress_initial_repopulation`
  - `full_block_baseline`
  - `portal_chunk`
  - `migrated_authoritative_chunk`
- `preserved_auxiliary_chunk_nbt`

## Load-Side Use

`CisNbtUtil.buildLoadChunkNbt(...)` decides whether load should use:

- the persisted base chunk as the vanilla deserialization baseline
- or a synthetic empty-shell chunk root

That decision depends on metadata, especially whether the stored CIS payload already represents the authoritative block baseline.

## Structural Metadata

Chunkis currently preserves:

- vanilla structure metadata
- portal chunk markers
- auxiliary vanilla chunk data that Chunkis does not explicitly model
- migration markers for offline authoritative imports

## Invariants

- Persisted base capture is idempotent once present.
- Full block baseline and persisted base chunk are different anchors with different load semantics.
- Metadata is not optional decoration; it carries restore policy.

## Related Docs

- [Delta And Ownership Model](Delta-And-Ownership-Model.md)
- [Save Pipeline](Save-Pipeline.md)
- [Load And Restore Pipeline](Load-And-Restore-Pipeline.md)
- [Migration And Versioning](Migration-And-Versioning.md)
