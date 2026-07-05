# Payload Watch Framework

## What This Area Is

Payload watch is the targeted tracing layer used when whole-chunk timelines are too coarse. It lets operators watch specific chunks, regions, blocks, block entities, or entity UUIDs and then records focused events across save, load, decode, restore, and client sync.

## What Owns It

- watch registration: `ChunkTraceWatchpoints`
- main routing layer: `PayloadWatchTracer`
- block/entity-specific tracking helpers: `BlockWatchTraceTracker`, `EntityWatchTracker`, `PayloadWatchSummaries`
- command surface: `/chunkis debug watch ...`

## How It Relates To Other Flows

- it piggybacks on normal save/load/restore/network events
- watched payload events bypass the regular debug-level filter
- it complements, rather than replaces, the general `ChunkTraceStore` ring buffer

## Key Entry Points

- `common/src/main/java/io/liparakis/chunkis/debug/watch/ChunkTraceWatchpoints.java`
- `fabric/src/main/java/io/liparakis/chunkis/debug/trace/PayloadWatchTracer.java`
- `fabric/src/main/java/io/liparakis/chunkis/debug/watch/BlockWatchTraceTracker.java`
- `fabric/src/main/java/io/liparakis/chunkis/debug/watch/EntityWatchTracker.java`
- `fabric/src/main/java/io/liparakis/chunkis/command/ChunkDebugCommand.java`

## Current Behavior

- watches are process-local and stored in insertion order
- supported target types are chunk, region, block, block entity, and entity
- watched payloads are traced during capture, decode, storage read/write, restore, and client apply stages
- the framework also records missing-expected-payload conditions for watched targets

## Current Sharp Edges

- the framework is intentionally linear and set-based; it assumes watch lists stay small
- these traces are runtime diagnostics, not a persistent audit log

## Where Behavior Is Proven

- payload-watch tests
- debug command tests

## Evidence

- `common/src/main/java/io/liparakis/chunkis/debug/watch/ChunkTraceWatchpoints.java`
- `fabric/src/main/java/io/liparakis/chunkis/debug/trace/PayloadWatchTracer.java`
- `fabric/src/main/java/io/liparakis/chunkis/debug/watch/BlockWatchTraceTracker.java`
- `fabric/src/main/java/io/liparakis/chunkis/debug/watch/EntityWatchTracker.java`
- `fabric/src/test/java/io/liparakis/chunkis/debug/PayloadWatchTracerTest.java`
- `common/src/test/java/io/liparakis/chunkis/debug/watch/ChunkTraceWatchpointsTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/command/ChunkDebugCommandTest.java`
