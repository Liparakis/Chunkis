# Tracking, Guards, And Durability

## Purpose

Once Chunkis disables vanilla chunk persistence, losing a dirty chunk is a real durability bug. This subsystem keeps runtime ownership explicit and prevents stale or unsafe saves.

## Main Classes

- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/state/GlobalChunkTracker.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/state/GlobalChunkUnloadCache.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/ownership/DeltaPersistenceGuard.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/capture/BaseChunkCaptureUtil.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/save/AsyncCisSaveManager.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/save/AsyncCisSaveWorker.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/state/LeafTickContext.java`

## Tracker Model

`GlobalChunkTracker` maintains:

- `dirtyDeltas`: the current authoritative dirty deltas
- unload cache state: recently unloaded deltas that may still be more authoritative than storage

The tracker also refuses weaker replacements when an existing delta still carries the authoritative anchor.

## Guard Model

`DeltaPersistenceGuard` rejects payloads that still contain replay content but have neither:

- persisted base chunk metadata
- full block baseline metadata

The most explicit invalid case is block-entity-only payload without a base.

## Async Durability Model

`AsyncCisSaveManager` snapshots the delta and records its generation.

`AsyncCisSaveWorker` only marks the live delta saved when:

- the same live delta instance is still current
- the generation still matches

Otherwise the async completion is ignored as stale.

## Natural-Mutation Exception

`LeafTickContext` is a narrow policy escape hatch so natural leaf-decay churn does not become tracked meaningful mutation.

It is not a general permission to ignore world changes.

## Invariants

- Dirty meaningful deltas must enter the tracker.
- Clean unload-cache placeholders are not authoritative and are invalidated on lookup.
- Stale async completions must not clean newer state.
- Unsafe sparse payloads must be repaired or rejected before persistence.

## Related Docs

- [Save Pipeline](Save-Pipeline.md)
- [Load And Restore Pipeline](Load-And-Restore-Pipeline.md)
- [Observability And Debugging](Observability-And-Debugging.md)
