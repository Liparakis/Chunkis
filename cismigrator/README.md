# CIS Migrator Module

## Purpose

`cismigrator/` contains the storage-backed CIS version migration logic.

## Main Responsibilities

- define legal CIS version upgrade paths
- scan existing `*.cis` region storage
- load old payloads without destructive self-healing
- rewrite outdated chunks through the current storage stack
- report migration progress and failures

## Design Boundary

This module is for CIS-to-CIS upgrades only. Offline MCA-to-CIS translation lives in `fabric/`.

## Key Areas

- `src/main/java/io/liparakis/chunkis/migrator/CisStorageMigrator.java`
- `src/main/java/io/liparakis/chunkis/migrator/CisVersionMap.java`
- `src/main/java/io/liparakis/chunkis/migrator/CisVersionPath.java`

## Relationships

- uses `core/` storage primitives
- is called from the Fabric-side migration flow when existing CIS data must be upgraded
