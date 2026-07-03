# Developer Guide

## Repository Layout

| Path                 | Purpose                                                                                        |
|----------------------|------------------------------------------------------------------------------------------------|
| `core/`              | Storage engine, CIS codec, mapping, region files, debug model                                  |
| `fabric/`            | Fabric entrypoints, mixins, runtime tracking, restore, commands, networking, offline migration |
| `cismigrator/`       | CIS-to-CIS version planning and storage-backed rewrites                                        |
| `docs/Architecture/` | Implementation-aligned architecture docs                                                       |
| `docs/debug/`        | Focused debugging and migration notes                                                          |

## Setup

Requirements:

- Java 21
- Gradle wrapper in this repository

Useful commands:

```bash
./gradlew build
./gradlew test
./gradlew runGameTest
```

On Windows PowerShell:

```powershell
.\gradlew.bat build
.\gradlew.bat test
```

## Read This Before Editing Persistence Code

The common wrong assumption is that Chunkis is "a sparse delta format." That is incomplete.

What the code does now:

- tracks live mutations incrementally
- rebuilds authoritative save-time snapshots
- persists base chunk metadata when sparse replay alone is unsafe
- blocks vanilla region I/O on the Chunkis-owned path
- restores state through synthetic load NBT plus replay

If you miss that, you will patch the wrong layer.

## Good Entry Files

- [../Architecture/System-Overview.md](../Architecture/System-Overview.md)
- [../Architecture/Startup-And-Lifecycle.md](../Architecture/Startup-And-Lifecycle.md)
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ChunkSerializerMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/world/chunk/WorldChunkMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/core/ChunkRestorer.java`
- `core/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java`
- `core/src/main/java/io/liparakis/chunkis/storage/io/region/RegionFile.java`

## Change Rules

- Put storage-format and codec logic in `core/`.
- Put Minecraft integration in `fabric/`.
- Prefer existing boundaries over new abstractions.
- Do not re-enable vanilla chunk region writes as a fallback.
- Keep thread ownership explicit: server-thread mutation, async persistence, client-thread apply.
- Update the relevant architecture doc in the same change when persistence behavior changes.

## Versioned Storage Changes

When changing persisted format semantics:

1. update `CisConstants.VERSION` if the on-disk format changes
2. update encoder and decoder behavior in `core/`
3. update `cismigrator` if old worlds must move forward
4. add or update tests in the affected module
5. update [../Architecture/Migration-And-Versioning.md](../Architecture/Migration-And-Versioning.md)

## Debug Workflow

For persistence regressions:

1. `/chunkis debug on`
2. reproduce
3. inspect suspects and failures
4. use payload watch when the chunk timeline is too coarse
5. inspect `/chunkis_storage_report` for storage-shape questions

## Documentation Rule

Documentation in this repository is supposed to describe the current implementation. Remove stale behavior instead of
documenting it as if it still exists.
