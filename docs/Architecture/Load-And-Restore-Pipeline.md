# Load And Restore Pipeline

## What This Area Is

This is the path that resolves Chunkis-owned chunk state during load, builds temporary NBT for vanilla deserialization, attaches the decoded delta, and restores chunk contents into live `WorldChunk` instances.

## What Owns It

- source resolution and synthetic NBT: `ThreadedAnvilChunkStorageMixin`, `CisNbtUtil`
- proto attach and conversion hooks: `ChunkSerializerMixin`
- live replay: `ChunkRestorer`
- mutation suppression during restore: `PendingChunkMutationSuppression`, `ChunkMutationTrackingScope`

## How It Relates To Other Flows

- may flush dirty in-memory deltas before load so tracker and disk do not drift
- uses vanilla deserialization as a carrier instead of replacing it completely
- restore repopulates runtime delta state without treating replay as fresh player mutation

## Key Entry Points

- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ChunkSerializerMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/nbt/CisNbtUtil.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/core/ChunkRestorer.java`

## Current Flow

1. Load resolves a delta from tracker memory, unload cache, a completed CIS prefetch, or CIS storage.
2. `CisNbtUtil.buildLoadChunkNbt(...)` chooses either persisted-base-backed NBT or a synthetic empty-shell root.
3. Vanilla converts the NBT to `SerializedChunk` and then `ProtoChunk`.
4. `ChunkSerializerMixin` attaches the `ChunkDelta` to the proto chunk and applies restore-suppression setup.
5. On promotion to a live chunk, `ChunkRestorer.restore(...)` replays blocks, recalculates touched section block occupancy counts (to ensure correct results for block entity lookups), and then replays block entities and pending entities.
6. Derived state such as lighting, heightmaps, and follow-up networking is refreshed after replay.

Entity payloads are deliberately removed from synthetic vanilla chunk NBT before deserialization. `EntityReplayCoordinator` is the sole server-side materializer for CIS-owned entities; leaving them in vanilla's `entities` list would load every payload twice.

## Current Sharp Edges

- block-entity-only sparse payloads without a persisted base are rejected during restore
- full-baseline CIS snapshots and persisted-base-backed sparse payloads take different load baselines
- restore may happen through a wrapped full-chunk path or normal promotion path, and docs should not collapse those into one idealized flow
- confirmed CIS entries are decoded on the prefetch worker before the normal load consumes them; misses retain the existing synchronous fallback

## Where Behavior Is Proven

- `ChunkSerializerMixin`
- `ChunkRestorer`
- `PersistedBaseChunkReloadGameTest`
- `ChunkRestorerTest`

## Evidence

- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ChunkSerializerMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/nbt/CisNbtUtil.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/core/ChunkRestorer.java`
- `fabric/src/gametest/java/io/liparakis/chunkis/gametest/PersistedBaseChunkReloadGameTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/world/restoration/core/ChunkRestorerTest.java`
