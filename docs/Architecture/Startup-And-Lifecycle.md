# Startup And Lifecycle

## What This Area Is

This covers mod initialization, integrated-server migration gating, command and payload registration, per-world runtime setup, and shutdown flushing.

## What Owns It

- `ChunkisMod` owns server/common initialization
- `ClientChunkisMod` owns client packet registration and migration status listener wiring
- `MinecraftClientMixin` runs the offline MCA-to-CIS gate before integrated-server start
- `SplashOverlayMixin` paints migration progress on the client splash screen

## How It Relates To Other Flows

- startup can migrate vanilla storage before normal world loading starts
- shutdown flushes pending tracked deltas, closes async save workers, and clears static managers
- normal gameplay save/load behavior is registered here but executed elsewhere

## Key Entry Points

- `fabric/src/main/java/io/liparakis/chunkis/ChunkisMod.java`
- `fabric/src/main/java/io/liparakis/chunkis/ClientChunkisMod.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/client/storage/MinecraftClientMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/client/screen/SplashOverlayMixin.java`

## Current Lifecycle Sequence

1. `ChunkisMod.onInitialize()` installs API instance, payload registration, commands, and lifecycle hooks.
2. `ClientChunkisMod.onInitializeClient()` registers client delta networking and migration-status screen updates.
3. On integrated-server start, `MinecraftClientMixin` calls `PreLaunchMigrationCoordinator.runBeforeIntegratedServerStart(...)`.
4. During shutdown, `ChunkisMod.flushBeforeServerStop(...)` force-saves tracked deltas, flushes async workers, closes portal indexes, and checks pending watch assertions.
5. After server stop, static state is cleared from trackers, save managers, portal managers, replay queues, and migration status.

## Current Sharp Edges

- The repository proves the prelaunch migration hook for integrated-server startup. A dedicated-server startup hook is not shown in the current code.
- Shutdown safety depends on the final synchronous flush, so shutdown is not purely async.

## Evidence

- `fabric/src/main/java/io/liparakis/chunkis/ChunkisMod.java`
- `fabric/src/main/java/io/liparakis/chunkis/ClientChunkisMod.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/client/storage/MinecraftClientMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/client/screen/SplashOverlayMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/integration/migration/offline/PreLaunchMigrationCoordinator.java`

## Unverified Or Runtime-Dependent Notes

- No separate dedicated-server prelaunch migration hook was found in the current repository. That should not be documented as current behavior without runtime confirmation.
