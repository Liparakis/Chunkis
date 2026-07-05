# System Overview

## What This Area Is

Chunkis replaces the authoritative chunk persistence path with a Chunkis-managed pipeline. The code today covers mutation tracking, save-time snapshot capture, CIS storage, synthetic load NBT, restore replay, client delta sync, and migration tooling.

## What Owns It

- module ownership: `common`, `storage`, `migration`, `fabric`
- runtime orchestration: `fabric`
- persistence mechanics: `storage`

## How It Relates To Other Flows

- save interception and shutdown flush live in `ChunkisMod` and storage mixins
- load interception and restore route through `ThreadedAnvilChunkStorageMixin`, `ChunkSerializerMixin`, and `ChunkRestorer`
- client delta sync is layered on top of vanilla chunk packets through `ChunkHolderMixin` and `ChunkisNetworking`

## Key Entry Points

- `fabric/src/main/java/io/liparakis/chunkis/ChunkisMod.java`
- `fabric/src/main/java/io/liparakis/chunkis/ClientChunkisMod.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ChunkSerializerMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/core/ChunkRestorer.java`

## Current Sharp Edges

- old docs still use `core` and `cismigrator`; the active modules are different
- save ownership is selective; vanilla reads remain available, but vanilla writes are cancelled only for Chunkis-owned saves
- runtime safety depends on ownership claims, persisted bases, and restore suppression being correct together

## Where Behavior Is Proven

- mixins and entrypoints in `fabric`
- storage and migration tests
- game tests around reload, migration, and async-save loss cases

## Evidence

- `fabric/src/main/java/io/liparakis/chunkis/ChunkisMod.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ChunkSerializerMixin.java`
- `fabric/src/gametest/java/io/liparakis/chunkis/gametest/PersistedBaseChunkReloadGameTest.java`
- `fabric/src/gametest/java/io/liparakis/chunkis/gametest/AsyncSaveDataLossGameTest.java`
