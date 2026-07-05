# `fabric` Module

## Purpose

`fabric` is the runtime integration layer. It owns the Fabric entrypoints, mixins, save/load interception, runtime delta tracking, restoration, commands, client sync, portal integration, and offline vanilla-world migration.

## Main Responsibilities

- registers the mod entrypoints, payloads, commands, and lifecycle hooks
- intercepts vanilla save/load flows through mixins
- tracks Chunkis-owned dirty state in live worlds
- captures authoritative save-time snapshots and runs restore-time replay
- provides client delta networking and client apply logic
- runs integrated-server prelaunch MCA-to-CIS migration

## Design Boundary

- `fabric` depends on `common`, `storage`, and `migration`
- it is the only module that should depend on Fabric API and Minecraft runtime classes
- it owns policy decisions about ownership, save suppression, restore suppression, and operator-facing commands

## Key Areas

- `fabric/src/main/java/io/liparakis/chunkis/ChunkisMod.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/`
- `fabric/src/main/java/io/liparakis/chunkis/world/restoration/`
- `fabric/src/main/java/io/liparakis/chunkis/network/`
- `fabric/src/main/java/io/liparakis/chunkis/integration/migration/offline/`
- `fabric/src/main/java/io/liparakis/chunkis/command/`

## Relationships

- creates and owns runtime use of `CisStorage`
- calls into `migration` for CIS version upgrades
- consumes `common` debug and codec types on both server and client paths

## Evidence

- `fabric/build.gradle`
- `fabric/src/main/resources/fabric.mod.json`
- `fabric/src/main/resources/chunkis.mixins.json`
- `fabric/src/main/java/io/liparakis/chunkis/ChunkisMod.java`
- `fabric/src/main/java/io/liparakis/chunkis/ClientChunkisMod.java`
