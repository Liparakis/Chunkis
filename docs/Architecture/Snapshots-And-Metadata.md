# Snapshots And Metadata

## What This Area Is

Chunkis stores more than block changes. It also stores metadata used to decide restore baselines, structure data, auxiliary preserved NBT, portal flags, and migration markers.

## What Owns It

- metadata construction and extraction: `CisNbtUtil`
- save-time snapshot capture: `CisSnapshotCapture`, `BaseChunkCaptureUtil`
- direct structure metadata extraction: `StructureMetadataExtractor`
- migrated authoritative metadata: `OfflineMcaCisTranslator`

## How It Relates To Other Flows

- save uses metadata to make sparse payloads restorable
- load uses metadata to choose between persisted-base-backed NBT and synthetic empty-shell NBT
- migration marks imported authoritative snapshots differently from runtime saves

## Key Entry Points

- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/nbt/CisNbtUtil.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/capture/CisSnapshotCapture.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/capture/BaseChunkCaptureUtil.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/nbt/StructureMetadataExtractor.java`

## Current Metadata Shapes

- `chunkis` envelope under chunk metadata
- persisted base chunk NBT or raw base chunk payload
- full block baseline flag
- suppress-initial-repopulation flag
- portal chunk flag
- migrated-authoritative-chunk flag
- preserved auxiliary vanilla chunk NBT

## Current Sharp Edges

- persisted base chunk metadata and full baseline metadata are not interchangeable in all paths; the code uses them for different restore decisions
- metadata-only anchors can still matter even when live block replay is sparse

## Where Behavior Is Proven

- `CisNbtUtilTest`
- `CisSnapshotCaptureTest`
- structure metadata game tests

## Evidence

- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/nbt/CisNbtUtil.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/capture/CisSnapshotCapture.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/capture/BaseChunkCaptureUtil.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/nbt/StructureMetadataExtractor.java`
- `fabric/src/test/java/io/liparakis/chunkis/world/restoration/nbt/CisNbtUtilTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/world/restoration/capture/CisSnapshotCaptureTest.java`
- `fabric/src/gametest/java/io/liparakis/chunkis/gametest/StructureMetadataExtractorGameTest.java`
