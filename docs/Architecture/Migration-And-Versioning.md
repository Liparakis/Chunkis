# Migration And Versioning

## What This Area Is

Chunkis has two separate migration concerns in the current code:

- CIS-to-CIS version upgrades in `migration`
- vanilla MCA-to-CIS import at integrated-server startup in `fabric`

## What Owns It

- CIS version graph and storage-backed rewrites: `migration`
- prelaunch MCA import and retirement rules: `fabric/integration/migration/offline`

## How It Relates To Other Flows

- normal runtime load expects current CIS semantics
- old CIS versions must migrate forward before normal runtime use
- offline MCA import produces authoritative Chunkis snapshots before world start

## Key Entry Points

- `migration/src/main/java/io/liparakis/chunkis/migrator/CisVersionMap.java`
- `migration/src/main/java/io/liparakis/chunkis/migrator/CisStorageMigrator.java`
- `fabric/src/main/java/io/liparakis/chunkis/integration/migration/offline/PreLaunchMigrationCoordinator.java`
- `fabric/src/main/java/io/liparakis/chunkis/integration/migration/offline/OfflineMcaCisTranslator.java`

## Current CIS Version Facts

- latest runtime CIS version is `11`
- explicit supported version edges are `7->8`, `8->9`, `9->10`, and `10->11`
- downgrades are not supported
- a clean full-directory CIS migration writes `.chunkis-cis-version`

## Current Offline MCA Import Facts

- runs before integrated-server startup through `MinecraftClientMixin`
- reads both vanilla `region/` and `entities/` directories
- parallelizes independent region files while bounding worker count by CPU and heap budget
- translates one authoritative snapshot per present chunk
- validates migrated payload shape after writing
- retires a source `.mca` file to `.backup` only when all present chunks were handled and none failed

## Current Sharp Edges

- if CIS data already exists, prelaunch logic can mark it authoritative and delete stale vanilla source files
- docs must not describe offline MCA import as reversible; the current code only documents forward takeover

## Where Behavior Is Proven

- `CisVersionMapTest`
- `CisStorageMigratorTest`
- `OfflineMcaCisTranslatorTest`
- `PreLaunchMigrationCoordinatorTest`
- migration game tests using `migration/src/test/resources/V8`

## Evidence

- `migration/src/main/java/io/liparakis/chunkis/migrator/CisVersionMap.java`
- `migration/src/main/java/io/liparakis/chunkis/migrator/CisStorageMigrator.java`
- `fabric/src/main/java/io/liparakis/chunkis/integration/migration/offline/PreLaunchMigrationCoordinator.java`
- `fabric/src/main/java/io/liparakis/chunkis/integration/migration/offline/OfflineMcaCisTranslator.java`
- `migration/src/test/java/io/liparakis/chunkis/migrator/CisVersionMapTest.java`
- `migration/src/test/java/io/liparakis/chunkis/migrator/CisStorageMigratorTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/integration/migration/offline/OfflineMcaCisTranslatorTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/integration/migration/offline/PreLaunchMigrationCoordinatorTest.java`
- `fabric/src/gametest/java/io/liparakis/chunkis/gametest/CisFixtureMigrationGameTest.java`
