# Tracking, Guards, And Durability

## What This Area Is

This area covers dirty tracking, unload-cache mirroring, sparse-payload rejection, restore suppression, and the durability stress command used to exercise the pipeline.

## What Owns It

- tracker state: `GlobalChunkTracker`
- sparse-payload policy: `DeltaPersistenceGuard`
- restore/save suppression: `PendingChunkMutationSuppression`, `ChunkMutationTrackingScope`
- durability operator command: `DurabilityTestCommand`

## How It Relates To Other Flows

- tracking decides what should be saved or reloaded from memory
- guards prevent payloads that cannot safely restore
- suppression prevents restore-time writes from being recorded as fresh mutation

## Key Entry Points

- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/state/GlobalChunkTracker.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/ownership/DeltaPersistenceGuard.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/suppression/PendingChunkMutationSuppression.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/suppression/ChunkMutationTrackingScope.java`
- `fabric/src/main/java/io/liparakis/chunkis/command/DurabilityTestCommand.java`

## Current Rules

- dirty deltas are tracked by dimension and chunk position
- unload cache mirrors tracked deltas across chunk unload boundaries
- a replay payload with neither persisted base metadata nor full baseline metadata is rejected
- restore paths use suppression scopes so replay work does not look like live edits
- durability testing repeatedly teleports a player between positions to stress load/save transitions

## Current Sharp Edges

- the tracker may keep an existing authoritative delta over a weaker replacement
- clean unload-cache deltas do not override stored data
- the durability command is a runtime stress harness, not a proof of long-term storage correctness by itself

## Where Behavior Is Proven

- `GlobalChunkTrackerTest`
- `DeltaPersistenceGuardTest`
- suppression tests
- durability command tests

## Evidence

- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/state/GlobalChunkTracker.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/ownership/DeltaPersistenceGuard.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/suppression/PendingChunkMutationSuppression.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/suppression/ChunkMutationTrackingScope.java`
- `fabric/src/main/java/io/liparakis/chunkis/command/DurabilityTestCommand.java`
- `fabric/src/test/java/io/liparakis/chunkis/world/tracking/ownership/DeltaPersistenceGuardTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/world/tracking/suppression/PendingChunkMutationSuppressionTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/world/tracking/suppression/ChunkMutationTrackingScopeTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/command/DurabilityTestCommandTest.java`
