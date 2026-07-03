# Fabric Module

## Purpose

`fabric/` is the Minecraft-specific integration layer for Chunkis.

## Main Responsibilities

- Fabric entrypoints
- save/load mixins
- runtime chunk tracking
- snapshot capture and restore
- client sync
- commands and debugging hooks
- offline MCA translation

## Design Boundary

Minecraft and Fabric integration belongs here. Shared storage behavior belongs in `core/`.

## Key Areas

- `src/main/java/io/liparakis/chunkis/mixin/`
- `src/main/java/io/liparakis/chunkis/world/`
- `src/main/java/io/liparakis/chunkis/network/`
- `src/main/java/io/liparakis/chunkis/client/`
- `src/main/java/io/liparakis/chunkis/migration/`
- `src/main/java/io/liparakis/chunkis/command/`

## Relationships

- depends on `core/` for storage, codec, mapping, and debug primitives
- depends on `cismigrator/` for CIS version-planning and storage-backed upgrades
