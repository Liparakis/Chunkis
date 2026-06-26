# Payload Watch Framework

## Problem this subsystem solves

Chunk-level traces answer "what happened to this chunk?" but persistence regressions are often payload-local:

- why did this chest disappear?
- why was this block never restored?
- why did this villager vanish?

Payload Watch extends the existing observability pipeline so one watched block, block entity, or entity can be followed across capture, encode, storage, decode, and restore.

## Responsibilities

- Register world-scoped watches for blocks, block entities, and entities.
- Emit structured trace events for watched payloads at persistence boundaries.
- Reuse the existing trace ring buffer, export path, command surface, and operation ids.
- State the first visible stage where a watched payload stopped progressing.

## What it does not do

- It does not build a second log store or separate debugger.
- It does not retain every payload in memory when no watches are active.
- It does not infer impossible facts from missing data. If a stage cannot prove identity, the next proven stage reports the loss.

## Owning classes

- `core/.../debug/PayloadWatchTarget`
- `core/.../debug/PayloadWatchType`
- `core/.../debug/ChunkTraceWatchpoints`
- `core/.../debug/ChunkTraceEvent`
- `fabric/.../debug/PayloadWatchTracer`
- `fabric/.../command/ChunkDebugCommand`

## Event model

Payload watches use the normal trace store with extra payload fields:

- payload type
- world id
- coordinates or UUID
- payload stage
- payload summary

Primary event types:

- `WATCH_CAPTURED`
- `WATCH_ENCODED`
- `WATCH_SERIALIZED`
- `WATCH_STORAGE_WRITE`
- `WATCH_STORAGE_READ`
- `WATCH_DECODED`
- `WATCH_RESTORE_STARTED`
- `WATCH_RESTORED`
- `WATCH_FAILED`
- `WATCH_SKIPPED`

## Lifecycle

### Save path

1. world capture
2. delta encode
3. raw CIS serialization
4. storage write

### Load path

1. storage read
2. delta decode
3. restore start
4. live-world restore

## Payload summaries

Block summaries include:

- position
- block state string
- section index

Block entity summaries include:

- position
- type id when available
- serialized NBT byte size

Entity summaries include:

- UUID
- entity type id when available
- position list from NBT
- serialized NBT byte size

## Commands

- `/chunkis debug watch block <x> <y> <z>`
- `/chunkis debug watch blockentity <x> <y> <z>`
- `/chunkis debug watch entity <uuid>`
- `/chunkis debug watch clear`
- `/chunkis debug watch list`

Chunk and region watchpoints still exist; payload watches are additive.

## Extension points

- Add new payload summaries in `PayloadWatchTracer`.
- Add new watch stages by emitting new `WATCH_*` events through `ChunkTraceStore`.
- Keep world-specific parsing in Fabric-side code; keep the core watch model generic.

## Debugging a persistence regression

Recommended order:

1. `/chunkis debug on`
2. register the exact payload watch
3. reproduce the save/load problem
4. inspect `/chunkis debug latest <count>` or export watched traces
5. find the last successful watch stage
6. use the first `WATCH_FAILED` or `WATCH_SKIPPED` event as the disappearance boundary

Examples:

- captured -> encoded -> serialized -> storage write -> storage read -> `WATCH_FAILED` at decode
- decoded -> restore started -> `WATCH_SKIPPED` with `missing block state`

## Performance model

- No payload summaries are built when no payload watches exist.
- Watch checks are explicit guards at a few pipeline boundaries, not deep instrumentation everywhere.
- Matching is expected to stay small because watches are developer-driven, not automatic.

## See also

- [Observability And Debugging](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Observability-And-Debugging.md)
- [Save Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Save-Pipeline.md)
- [Load And Restore Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Load-And-Restore-Pipeline.md)
