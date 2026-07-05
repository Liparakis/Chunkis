# `common` Module

## Purpose

`common` contains the loader-agnostic data model and utilities that the other modules build on. It does not own region-file I/O or Minecraft mixins.

## Main Responsibilities

- `Chunkis`: shared mod ID and logger
- `core`: `ChunkDelta`, chunk position helpers, palettes, bit utilities, codecs, and compression helpers
- `core.model`: CIS format constants and chunk/section model types
- `debug`: trace event model, trace store, suspects, payload-watch model, and debug-level gating
- `spi`: adapter interfaces for block registries, block states, properties, and NBT

## Design Boundary

- `common` should stay free of Fabric and Minecraft runtime integration code
- it defines abstractions and value types that `storage`, `migration`, and `fabric` consume
- tests in `common` currently depend on `storage` for some integration coverage, but production code does not

## Key Classes

- `common/src/main/java/io/liparakis/chunkis/Chunkis.java`
- `common/src/main/java/io/liparakis/chunkis/core/ChunkDelta.java`
- `common/src/main/java/io/liparakis/chunkis/core/codec/CisEncoder.java`
- `common/src/main/java/io/liparakis/chunkis/core/codec/CisDecoder.java`
- `common/src/main/java/io/liparakis/chunkis/core/model/CisConstants.java`
- `common/src/main/java/io/liparakis/chunkis/debug/trace/ChunkTraceStore.java`
- `common/src/main/java/io/liparakis/chunkis/debug/watch/ChunkTraceWatchpoints.java`

## Relationships

- `storage` uses `common` for codecs, `ChunkDelta`, mapping-related interfaces, and debug events
- `migration` uses `common` for version constants and delta model types
- `fabric` uses `common` everywhere for runtime delta state, debug events, and network codecs

## Evidence

- `common/build.gradle`
- `common/src/main/java/io/liparakis/chunkis/Chunkis.java`
- `common/src/main/java/io/liparakis/chunkis/core/ChunkDelta.java`
- `common/src/main/java/io/liparakis/chunkis/core/model/CisConstants.java`
- `common/src/main/java/io/liparakis/chunkis/debug/trace/ChunkTraceStore.java`
- `common/src/main/java/io/liparakis/chunkis/debug/watch/ChunkTraceWatchpoints.java`
