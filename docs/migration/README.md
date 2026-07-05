# `migration` Module

## Purpose

`migration` handles CIS-to-CIS upgrades for older Chunkis storage. It does not perform vanilla `.mca` import.

## Main Responsibilities

- declares explicit supported version edges
- plans upgrade paths from one CIS version to another
- scans region directories and rewrites outdated chunk payloads through `CisStorage`
- writes a `.chunkis-cis-version` marker after a clean migration pass

## Design Boundary

- this module is for Chunkis storage that already exists
- offline MCA-to-CIS translation lives in `fabric`
- the migrator is storage-backed, so it uses the caller's configured mapping and adapters instead of a separate import codec stack

## Key Classes

- `migration/src/main/java/io/liparakis/chunkis/migrator/CisVersionMap.java`
- `migration/src/main/java/io/liparakis/chunkis/migrator/CisStorageMigrator.java`
- `migration/src/main/java/io/liparakis/chunkis/migrator/CisMigrationReport.java`

## Relationships

- depends on `common` and `storage`
- used from Fabric-side startup or maintenance flows when existing `.cis` data is below `CisConstants.VERSION`

## Evidence

- `migration/build.gradle`
- `migration/src/main/java/io/liparakis/chunkis/migrator/CisVersionMap.java`
- `migration/src/main/java/io/liparakis/chunkis/migrator/CisStorageMigrator.java`
- `migration/src/test/java/io/liparakis/chunkis/migrator/CisVersionMapTest.java`
- `migration/src/test/java/io/liparakis/chunkis/migrator/CisStorageMigratorTest.java`
