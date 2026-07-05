# Developer Guide

## Repo Navigation

- `common/`: shared model, codecs, compression, debug model, and adapter SPI
- `storage/`: storage engine, region files, mapping persistence, inspection helpers
- `migration/`: CIS version graph and storage-backed rewrite logic
- `fabric/`: entrypoints, mixins, runtime tracking, restore, commands, networking, and offline migration

## Build And Test

```bash
./gradlew build
./gradlew test
./gradlew runGameTest
```

## Good Entry Files

- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ChunkSerializerMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/core/ChunkRestorer.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/state/GlobalChunkTracker.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java`
- `storage/src/main/java/io/liparakis/chunkis/storage/io/region/RegionFile.java`
- `migration/src/main/java/io/liparakis/chunkis/migrator/CisStorageMigrator.java`

## Current Editing Rules From The Codebase

- persistence format and codec changes belong in `common` and `storage`, not in Fabric mixins
- Fabric mixins and world lifecycle behavior belong in `fabric`
- CIS version changes should update migration support when old data must still load
- avoid re-enabling vanilla writes as an undocumented fallback
- when you change persistence semantics, update the matching docs in `docs-v2/`

## What To Read Before Changing Persistence

- the save path is snapshot-based, not just sparse delta persistence
- ownership and restore suppression are part of correctness, not optional extras
- normal runtime load expects current CIS semantics; old formats need migration support

## Current Test Surface

- unit tests in all four modules
- Fabric game tests for reload, migration, metadata extraction, and async-save loss cases

## Evidence

- `build.gradle`
- `common/build.gradle`
- `storage/build.gradle`
- `migration/build.gradle`
- `fabric/build.gradle`
- `fabric/src/gametest/java/io/liparakis/chunkis/gametest/`
- `docs/Development/Developer-Guide.md`
