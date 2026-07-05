# Delta And Ownership Model

## What This Area Is

`ChunkDelta` is the shared state carrier for runtime mutation, persisted chunk payloads, metadata, block entities, and
pending entities. Ownership helpers decide whether a delta is merely attached state or Chunkis-owned authoritative
state.

## What Owns It

- data structure: `common/src/main/java/io/liparakis/chunkis/core/ChunkDelta.java`
- ownership classification: `ChunkDeltaOwnership`
- runtime tracking: `GlobalChunkTracker`
- vanilla-write suppression handoff: `PendingVanillaSaveDecision`, `StoragePreventionMixin`

## How It Relates To Other Flows

- save only persists Chunkis-owned state
- load only restores deltas with replay payloads or persistence anchors
- tracker can prefer an existing authoritative delta over a weaker replacement

## Key Entry Points

- `common/src/main/java/io/liparakis/chunkis/core/ChunkDelta.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/ownership/ChunkDeltaOwnership.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/state/GlobalChunkTracker.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/StoragePreventionMixin.java`

## Current Rules

- `hasChunkisOwnedState(...)` currently means the delta has an ownership claim
- `hasRestorableChunkisState(...)` is broader: replay payloads or persistence anchors are enough
- `GlobalChunkTracker` keeps dirty deltas by dimension and chunk position, plus an unload cache mirror
- an existing authoritative tracked delta can beat an incoming weaker one
- vanilla writes are cancelled only when the higher-level save path marked the chunk as Chunkis-owned

## Current Sharp Edges

- docs that say "every attached delta is authoritative" would be wrong
- clean unload-cache deltas are invalidated before load and do not override storage

## Where Behavior Is Proven

- `GlobalChunkTrackerTest`
- `ChunkDeltaOwnershipTest`
- `StoragePreventionMixinTest`

## Evidence

- `common/src/main/java/io/liparakis/chunkis/core/ChunkDelta.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/ownership/ChunkDeltaOwnership.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/state/GlobalChunkTracker.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/StoragePreventionMixin.java`
- `fabric/src/test/java/io/liparakis/chunkis/world/tracking/ownership/ChunkDeltaOwnershipTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/mixin/storage/StoragePreventionMixinTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/world/tracking/state/GlobalChunkTrackerTest.java`
