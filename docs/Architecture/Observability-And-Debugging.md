# Observability And Debugging

## What This Area Is

Chunkis has a built-in trace/event system, suspect aggregation, payload-watch tracing, and command surfaces for inspecting persistence behavior.

## What Owns It

- debug config and model: `common/debug`
- event storage and suspect tracking: `ChunkTraceStore`, `ChunkTraceSuspectManager`
- watch registration: `ChunkTraceWatchpoints`
- operator commands: `ChunkDebugCommand`, `StorageReportCommand`, `DurabilityTestCommand`

## How It Relates To Other Flows

- save, load, restore, storage, and client-sync code emit trace events
- payload-watch tracing is selective and sits on top of the general event store
- storage reports read region files directly for accounting instead of inferring usage from high-level state

## Key Entry Points

- `common/src/main/java/io/liparakis/chunkis/debug/config/ChunkisDebugConfig.java`
- `common/src/main/java/io/liparakis/chunkis/debug/trace/ChunkTraceStore.java`
- `common/src/main/java/io/liparakis/chunkis/debug/watch/ChunkTraceWatchpoints.java`
- `fabric/src/main/java/io/liparakis/chunkis/command/ChunkDebugCommand.java`
- `fabric/src/main/java/io/liparakis/chunkis/command/StorageReportCommand.java`

## Current Surfaces

- debug level control through `/chunkis debug on|off`
- latest traces, suspects, failures, and exports through `/chunkis debug ...`
- watchpoints for chunks, regions, blocks, block entities, and entities
- storage accounting through `/chunkis_storage_report`
- durability stress test through `/durability_test` and `/durability_test_stop`

## Current Sharp Edges

- `ChunkTraceStore` is a fixed-size ring buffer with overwrite behavior at capacity
- payload-watch events bypass the normal debug-level gate
- these tools are process-local runtime diagnostics, not persistent observability infrastructure

## Where Behavior Is Proven

- command tests
- trace and watchpoint tests in `common`

## Evidence

- `common/src/main/java/io/liparakis/chunkis/debug/config/ChunkisDebugConfig.java`
- `common/src/main/java/io/liparakis/chunkis/debug/trace/ChunkTraceStore.java`
- `common/src/main/java/io/liparakis/chunkis/debug/watch/ChunkTraceWatchpoints.java`
- `fabric/src/main/java/io/liparakis/chunkis/command/ChunkDebugCommand.java`
- `fabric/src/main/java/io/liparakis/chunkis/command/StorageReportCommand.java`
- `fabric/src/main/java/io/liparakis/chunkis/command/DurabilityTestCommand.java`
- `common/src/test/java/io/liparakis/chunkis/debug/trace/ChunkTraceStoreTest.java`
- `common/src/test/java/io/liparakis/chunkis/debug/watch/ChunkTraceWatchpointsTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/command/ChunkDebugCommandTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/command/StorageReportCommandTest.java`
