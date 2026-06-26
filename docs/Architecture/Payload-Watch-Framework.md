# Payload Watch Framework

## Problem this subsystem solves

Chunk-level traces answer "what happened to this chunk?" but persistence regressions are often payload-local:

- why did this chest disappear?
- why was this block never restored?
- why did this villager vanish?

Payload Watch extends the existing observability pipeline so one watched block, block entity, or entity can be followed across capture, encode, storage, decode, proto attach, live restore, and post-restore visibility.

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
- `WATCH_DECODED_DELTA_STATE`
- `WATCH_PROTO_DELTA_ATTACHED`
- `WATCH_PROTO_DELTA_PRESENT_BEFORE_CONVERSION`
- `WATCH_PROTO_DELTA_PRESENT_AFTER_CONVERSION`
- `WATCH_WORLDCHUNK_DELTA_ATTACHED`
- `WATCH_WORLDCHUNK_DELTA_MISSING`
- `WATCH_WORLD_CHUNK_CONSTRUCTOR_CONSUMED`
- `WATCH_RESTORE_STARTED`
- `WATCH_RESTORE_INSTRUCTION_VISITED`
- `WATCH_RESTORE_APPLY_ATTEMPT`
- `WATCH_RESTORE_SETBLOCK_RETURNED`
- `WATCH_RESTORE_STATE_AFTER_SETBLOCK`
- `WATCH_RESTORE_APPLIED`
- `WATCH_RESTORE_APPLY_FAILED`
- `WATCH_RESTORE_SKIPPED`
- `WATCH_PRESENT_AFTER_RESTORE`
- `WATCH_PRESENT_AFTER_CHUNK_FULL`
- `WATCH_PRESENT_BEFORE_CLIENT_SEND`
- `WATCH_PRESENT_AFTER_CLIENT_SEND`
- `WATCH_CLIENT_SEES_EXPECTED_STATE`
- `WATCH_OVERWRITTEN_AFTER_RESTORE`
- `WATCH_TRACE_INCOMPLETE`
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
3. decoded-delta inspection
4. proto attach
5. world-chunk attach or constructor consumption
6. restore start
7. block-level apply attempts
8. live-world visibility
9. client-send/client-visible checks when available

The important distinction is that `WATCH_DECODED` only proves the payload exists in the decoded `ChunkDelta`. It does not prove the payload reached the live `WorldChunk`.

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
- decoded -> proto attach -> no world-chunk attach -> assertion for missing restore handoff
- decoded -> restore started -> `WATCH_RESTORE_APPLY_FAILED`
- restore applied -> `WATCH_OVERWRITTEN_AFTER_RESTORE`

## Performance model

- No payload summaries are built when no payload watches exist.
- Watch checks are explicit guards at persistence and restore boundaries, plus a narrow set of watched restore/apply probes.
- Matching is expected to stay small because watches are developer-driven, not automatic.

## See also

- [Observability And Debugging](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Observability-And-Debugging.md)
- [Save Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Save-Pipeline.md)
- [Load And Restore Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Load-And-Restore-Pipeline.md)
