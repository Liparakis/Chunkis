# MCA to CIS Migration

## What This Area Is

This note covers the current offline vanilla-region import path that runs before integrated-server startup and converts `.mca` data into authoritative Chunkis CIS snapshots.

## What Owns It

- coordinator: `PreLaunchMigrationCoordinator`
- translator: `OfflineMcaCisTranslator`
- startup hook: `MinecraftClientMixin`

## Current Behavior

- scans vanilla `region/` and `entities/` directories for each configured dimension
- builds one authoritative Chunkis snapshot per present vanilla chunk
- validates the written CIS payload shape after save
- omits empty placeholder chunks so later loads can treat them as absent
- retires a source `.mca` file to `.backup` only when all present chunks were handled and none failed

## Current Validation Rules

- migrated chunks must keep full-baseline and authoritative markers
- non-air block payloads are compared semantically, not by palette order
- block entities are compared with recursive NBT matching
- entity payloads are compared after normalization to sorted string form
- structure and auxiliary metadata must also match

## Current Sharp Edges

- if CIS data is already present, the coordinator can treat it as authoritative and delete stale vanilla source files
- this is a forward migration path; the code does not prove a reversible export back to vanilla storage

## Evidence

- `fabric/src/main/java/io/liparakis/chunkis/mixin/client/storage/MinecraftClientMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/integration/migration/offline/PreLaunchMigrationCoordinator.java`
- `fabric/src/main/java/io/liparakis/chunkis/integration/migration/offline/OfflineMcaCisTranslator.java`
- `fabric/src/test/java/io/liparakis/chunkis/integration/migration/offline/OfflineMcaCisTranslatorTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/integration/migration/offline/PreLaunchMigrationCoordinatorTest.java`
- `fabric/src/gametest/java/io/liparakis/chunkis/gametest/CisFixtureMigrationGameTest.java`
