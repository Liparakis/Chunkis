# Migration And Versioning

## Purpose

Chunkis currently has two distinct migration concerns:

- offline MCA-to-CIS translation
- CIS-to-CIS storage version upgrades

They are separate code paths on purpose.

## Main Classes

- `fabric/src/main/java/io/liparakis/chunkis/migration/offline/PreLaunchMigrationCoordinator.java`
- `fabric/src/main/java/io/liparakis/chunkis/migration/offline/OfflineMcaCisTranslator.java`
- `fabric/src/main/java/io/liparakis/chunkis/migration/offline/MigrationValidationResult.java`
- `cismigrator/src/main/java/io/liparakis/chunkis/migrator/CisStorageMigrator.java`
- `cismigrator/src/main/java/io/liparakis/chunkis/migrator/CisVersionMap.java`
- `cismigrator/src/main/java/io/liparakis/chunkis/migrator/CisVersionPath.java`
- `core/src/main/java/io/liparakis/chunkis/storage/model/CisConstants.java`

## Offline MCA To CIS Translation

`PreLaunchMigrationCoordinator` decides whether a dimension needs offline translation before integrated-server startup continues.

`OfflineMcaCisTranslator` then:

1. scans vanilla `.mca` region files
2. deserializes chunk NBT through vanilla `SerializedChunk`
3. builds authoritative Chunkis snapshots
4. writes them with `CisStorage.replace(...)`
5. validates the written CIS payloads
6. retires the source `.mca` file to `.backup` only after successful full-region coverage

Current translated chunks are marked as migrated authoritative snapshots in metadata.

## CIS Version Upgrades

`CisStorageMigrator` performs storage-backed version upgrades using the same `CisStorage` adapters and mappings as runtime code.

Current behavior:

- scans `r.<x>.<z>.cis` files
- loads chunks with `loadWithoutClearing(...)`
- checks `ChunkDelta.sourceVersion`
- plans an upgrade path with `CisVersionMap`
- rewrites outdated chunks by saving them back through the current runtime storage stack
- writes a `.chunkis-cis-version` marker after a clean directory scan

## Version Ownership

- `CisConstants.VERSION` is the current on-disk CIS version.
- `ChunkDelta.sourceVersion` records the version a delta came from or was last saved as.
- `CisVersionMap` is the legal upgrade graph.

## Current Scope

Implemented today:

- integrated-server prelaunch offline MCA translation
- storage-backed CIS version migration
- migration validation helpers and reports

Not implemented here:

- reversal from CIS back to vanilla Anvil
- a generic dedicated-server offline migration orchestrator beyond the code paths in this repository

## Related Docs

- [Storage Format](Storage-Format.md)
- [Snapshots And Metadata](Snapshots-And-Metadata.md)
- [Startup And Lifecycle](Startup-And-Lifecycle.md)
