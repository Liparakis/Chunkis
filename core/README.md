# Core Module

## Purpose

`core/` contains the implementation-independent storage and debug model used by the Fabric integration layer.

## Main Responsibilities

- `ChunkDelta` data model
- CIS encode and decode
- region file storage and compaction
- compression helpers
- dimension-local block-id mapping
- trace and watch event model

## Design Boundary

`core/` should not depend on Minecraft runtime classes outside the generic adapter surface it already exposes.

## Key Areas

- `src/main/java/io/liparakis/chunkis/core/`
- `src/main/java/io/liparakis/chunkis/storage/`
- `src/main/java/io/liparakis/chunkis/debug/`
- `src/main/java/io/liparakis/chunkis/spi/`

## Relationships

- `fabric/` uses `core/` to persist, load, and trace chunk state.
- `cismigrator/` uses `core/` storage primitives for CIS version upgrades.
