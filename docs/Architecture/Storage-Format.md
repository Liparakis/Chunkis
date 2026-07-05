# Storage Format

## What This Area Is

This describes the current CIS on-disk layout: per-dimension region files, mapping files, and region-internal allocation metadata.

## What Owns It

- format constants: `CisConstants`
- storage engine: `CisStorage`
- region layout: `RegionFile`
- mapping persistence: `CisMapping`
- path resolution: `ChunkisStoragePaths`

## How It Relates To Other Flows

- save/write paths eventually call `CisStorage.prepareSave(...)` and `writePrepared(...)`
- load paths decode the bytes back into `ChunkDelta`
- migration relies on the same storage engine instead of a separate file parser

## Key Entry Points

- `common/src/main/java/io/liparakis/chunkis/core/model/CisConstants.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/region/RegionFile.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/save/ChunkisStoragePaths.java`

## Current Layout

- one dimension uses one `chunkis/regions/` directory
- region filenames are `r.<regionX>.<regionZ>.cis`
- `global_ids.json` persists the block/state mapping beside the region directory
- `RegionFile` stores:
  - a fixed 8192-byte header of 1024 `(offset,length)` entries
  - live chunk payload bytes
  - an optional allocation metadata footer for reusable holes and reuse counters

## Current Versioning Facts

- `CisConstants.MAGIC` is `0x43495334`
- `CisConstants.VERSION` is `11`
- region compaction is supported and validated before swap
- normal load clears corrupt stored entries after decode failure; migration can bypass that clearing path

## Current Sharp Edges

- empty deltas delete stored entries instead of writing placeholder payloads
- allocation metadata footer is optional on old files; open falls back to header-based reconstruction
- storage format changes are not just codec changes; docs also need to track migration impact

## Where Behavior Is Proven

- storage and region-file tests
- `CisRegionInspector` and storage-report command path

## Evidence

- `common/src/main/java/io/liparakis/chunkis/core/model/CisConstants.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/region/RegionFile.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/region/CisRegionInspector.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/save/ChunkisStoragePaths.java`
- `storage/src/test/java/io/liparakis/chunkis/storage/io/CisStorageTraceTest.java`
- `storage/src/test/java/io/liparakis/chunkis/storage/io/region/RegionFileFreeListTest.java`
