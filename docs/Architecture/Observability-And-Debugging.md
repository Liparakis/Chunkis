# Observability And Debugging

## Problem this subsystem solves

Chunk persistence bugs are usually timeline bugs: a chunk was mutated, queued, unloaded, restored, or rejected at the wrong time. Chunkis therefore has an in-memory event recorder focused on save/load/restore/durability timelines, not generic application logging.

## Responsibilities

- Record bounded in-memory trace events.
- Attach operation ids to save/load/restore/client-sync flows.
- Surface high-value invariant failures as explicit assertion events.
- Promote suspicious chunks into retained suspect snapshots.
- Expose debug, watchpoint, export, and durability commands.
- Follow one watched payload through the persistence pipeline.

## What it does not do

- It does not persist an always-on long-term trace database.
- It does not prove all multi-event invariants automatically.
- It does not instrument every micro-step of encoding and decoding.

## Owning classes

- `core/.../debug/ChunkTraceStore`
- `core/.../debug/ChunkTraceInvariants`
- `core/.../debug/ChunkTraceEvent*`
- `core/.../debug/ChunkTraceWatchpoints`
- `core/.../debug/ChunkTraceJsonl`
- `fabric/.../command/ChunkDebugCommand`
- `fabric/.../command/DurabilityTestCommand`
- `fabric/.../command/StorageReportCommand`
- `fabric/.../debug/PayloadWatchTracer`

## Event model

The event model is built around boundaries that matter when persistence goes wrong:

- save requested / queued / flushed / failed
- vanilla save cancelled
- load started / source resolved / ended
- region read/write
- restore started / completed / failed
- tracker state transitions
- client sync boundaries
- assertion failures

## Assertion model

`ChunkTraceInvariants` only asserts cheap, high-confidence rules. Current examples:

- `SAVE_REJECTED` must have a real reason
- `LOAD_SOURCE_RESOLVED` must use a valid source reason
- save queue/flush events must carry operation ids and chunk keys
- `DELTA_MARKED_CLEAN` must report `dirtyState=false`

That is intentionally narrower than full causal proof.

## Suspect model

`ChunkTraceStore` promotes chunks to suspects when it sees high-value signals such as:

- assertion failure
- save rejection
- save flush failure
- restore failure
- load resolved to `NEITHER` after prior stored payload existed
- delta marked clean before confirmed flush

Each suspect keeps a copied compact timeline so the ring buffer can rotate without losing the interesting case immediately.

## Commands

Main operational commands:

- `/chunkis debug on|off`
- `/chunkis debug latest <count>`
- `/chunkis debug suspects`
- `/chunkis debug suspect <id>`
- `/chunkis debug suspect timeline <id>`
- `/chunkis debug failures [count]`
- `/chunkis debug export latest <count>`
- `/chunkis debug watch chunk|region ...`
- `/chunkis debug watch block|blockentity|entity ...`
- `/chunkis debug watch pending`
- `/chunkis durability ...`
- `/chunkis_storage_report`

## Debugging a persistence bug

The usual order is:

1. enable debug
2. reproduce
3. inspect suspect/failure timelines
4. correlate save request, vanilla cancellation, queue/flush, load source, and restore result
5. check pending-save and deferred-base-capture snapshots for watched chunks

## Invariants

- Debug off means most event construction is skipped, not merely hidden.
- Event ids, operation ids, and suspect ids are independent.
- Suspect retention is bounded.
- Assertion events are additive; they do not change runtime persistence decisions by themselves.

## Common debugging locations

- [ChunkTraceStore.java](C:/Users/Liparakis/Desktop/Chunkis/core/src/main/java/io/liparakis/chunkis/debug/ChunkTraceStore.java)
- [ChunkTraceInvariants.java](C:/Users/Liparakis/Desktop/Chunkis/core/src/main/java/io/liparakis/chunkis/debug/ChunkTraceInvariants.java)
- [ChunkDebugCommand.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/command/ChunkDebugCommand.java)
- [DurabilityTestCommand.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/command/DurabilityTestCommand.java)
- [StorageReportCommand.java](C:/Users/Liparakis/Desktop/Chunkis/fabric/src/main/java/io/liparakis/chunkis/command/StorageReportCommand.java)

## Common failure modes

- reading only the last event instead of the whole operation timeline
- assuming `VANILLA_SAVE_CANCELLED` proves a Chunkis save completed
- assuming a clean delta implies a confirmed flush without the matching operation id
- missing unload-cache or deferred-base-capture state while debugging durability
- watching the chunk when the bug is really payload-local; use a payload watch first

## See also

- [Tracking, Guards, And Durability](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Tracking-Guards-And-Durability.md)
- [Save Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Save-Pipeline.md)
- [Load And Restore Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Load-And-Restore-Pipeline.md)
- [Payload Watch Framework](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Payload-Watch-Framework.md)
