# `storage` Module

## Purpose

`storage` owns the CIS persistence engine. It takes encoded `ChunkDelta` state, stores it in region files, manages free-space reuse, and exposes storage-level inspection helpers.

## Main Responsibilities

- `CisStorage`: save, replace, load, deferred write, and corruption-clearing behavior
- `storage.io.region`: region file layout, allocation metadata footer, compaction, and recovery
- `storage.mapping`: `global_ids.json` mapping persistence and block/state lookup
- storage diagnostics through `CisRegionInspector`

## Design Boundary

- `storage` depends on `common`
- it does not know about Minecraft lifecycle hooks, mixins, or runtime ownership policy
- it can self-heal corrupt stored entries on normal load, but migration can opt into `loadWithoutClearing(...)` to preserve original bytes

## Key Classes

- `storage/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/RegionFileCache.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/region/RegionFile.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/region/CisRegionInspector.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/mapping/CisMapping.java`

## Relationships

- `fabric` creates one `CisStorage` per world dimension through `FabricCisStorageHelper`
- `migration` reuses `CisStorage` for storage-backed CIS upgrades
- `common` provides the codec and delta model types that `storage` writes

## Evidence

- `storage/build.gradle`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/region/RegionFile.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/region/CisRegionInspector.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/mapping/CisMapping.java`
- `storage/src/test/java/io/liparakis/chunkis/storage/io/CisStorageCompactionTest.java`
- `storage/src/test/java/io/liparakis/chunkis/storage/io/region/RegionFileFreeListTest.java`
