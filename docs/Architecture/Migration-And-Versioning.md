# Migration And Versioning

## Problem this subsystem solves

Chunkis owns both vanilla-to-CIS migration and CIS-to-newer-CIS migration. Those are different problems and the code keeps them separate on purpose.

## Responsibilities

- Import existing `.mca` worlds into CIS.
- Upgrade older CIS payloads to the current CIS version.
- Preserve source bytes during migration-oriented decode paths when needed.
- Keep per-dimension storage directories and mappings consistent.

## What it does not do

- It does not try to be reversible back to vanilla storage.
- It does not mix MCA import logic with CIS version upgrades.
- It does not mutate live game state during migration; it operates on storage.

## Owning classes

- `fabric/.../migration/McaMigrator`
- `fabric/.../migration/CisWorldMigrator`
- `cismigrator/.../migrator/CisStorageMigrator`
- `cismigrator/.../migrator/CisVersionMap`
- `cismigrator/.../migrator/CisVersionPath`

## Two migration pipelines

### MCA -> CIS import

`McaMigrator` runs on world load and:

- scans vanilla region files
- reads chunk NBT through vanilla `RegionFile`
- builds a `ProtoChunk`
- converts it into a `ChunkDelta`
- saves that delta into CIS storage
- backs up the original region file afterward

This is import, not in-place CIS upgrade.

### CIS version upgrade

`CisWorldMigrator` runs on existing Chunkis storage directories and delegates to the standalone `cismigrator` module.

That path:

- resolves old chunk version
- finds a legal path to the current version
- rewrites stored CIS payloads in place as needed

## Version ownership

- `CisConstants.VERSION` is the current storage version.
- `ChunkDelta.sourceVersion` remembers the version a delta was decoded from or last saved as.
- `CisVersionMap` defines the legal migration graph.

## Important entry points

- `ChunkisMod.migrateWorld(...)`
- `McaMigrator.migrateWorld(...)`
- `CisWorldMigrator.migrateWorld(...)`
- `CisStorage.loadWithoutClearing(...)`

`loadWithoutClearing(...)` exists partly for migration/recovery flows where unreadable bytes should not be auto-cleared by the normal self-healing load path.

## Invariants

- MCA migration runs before CIS-to-CIS migration in world load flow.
- Migration must happen before gameplay starts using the storage.
- Storage upgrades are dimension-local.
- Mapping ids and migrated payloads must stay consistent.

## Common debugging locations

- [ChunkisMod.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/ChunkisMod.java)
- [McaMigrator.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/migration/McaMigrator.java)
- [CisWorldMigrator.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/migration/CisWorldMigrator.java)
- [CisStorageMigrator.java](C:/Users/Liparakis/Desktop/Chunkis/cismigrator/src/main/java/io/liparakis/chunkis/migrator/CisStorageMigrator.java)
- [CisVersionMap.java](C:/Users/Liparakis/Desktop/Chunkis/cismigrator/src/main/java/io/liparakis/chunkis/migrator/CisVersionMap.java)

## Common failure modes

- import produces sparse payloads that depend too much on generated terrain
- world starts using storage before migration finished
- decode failure during migration where original bytes should have been preserved
- unsupported version graph path

## See also

- [Storage Format](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Storage-Format.md)
- [Load And Restore Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Load-And-Restore-Pipeline.md)
- [Snapshots And Metadata](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Snapshots-And-Metadata.md)
