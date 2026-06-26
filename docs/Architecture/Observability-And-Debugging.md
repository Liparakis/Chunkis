# Observability And Debugging

## Problem this subsystem solves

Chunk persistence bugs are usually timeline bugs: a chunk was mutated, queued, unloaded, restored, or rejected at the wrong time. Chunkis therefore has an in-memory event recorder focused on save/load/restore/durability timelines, not generic application logging.

## Responsibilities

- Record bounded in-memory trace events.
- Attach operation ids to save/load/restore/client-sync flows.
- Surface high-value invariant failures as explicit assertion events.
- Promote suspicious chunks into retained suspect snapshots.
- Expose debug, watchpoint, export, and durability commands.
- Follow one watched payload through decode, proto attach, live restore, and client visibility.

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
- proto attach / world-chunk attach / restore-apply boundaries
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

Payload Watch adds a second layer on top of that chunk timeline: it can now prove the difference between:

- payload exists in decoded storage data
- payload is only attached to a proto chunk
- payload reached a live `WorldChunk`
- payload was applied and later overwritten
- payload is correct on the server but not on the client

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

For watched payloads, ask these in order:

1. did `WATCH_DECODED` prove the payload exists in the decoded delta?
2. did `WATCH_PROTO_DELTA_ATTACHED` prove the payload reached the proto chunk?
3. did `WATCH_WORLDCHUNK_DELTA_ATTACHED` or `WATCH_WORLD_CHUNK_CONSTRUCTOR_CONSUMED` prove the payload reached a live chunk handoff?
4. did `WATCH_RESTORE_APPLIED` prove live replay?
5. did `WATCH_OVERWRITTEN_AFTER_RESTORE` prove a later overwrite?
6. did client-send or client-visible events diverge from server state?

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
- treating `WATCH_DECODED` as proof of live-world restore; it only proves decoded-delta contents

## See also

- [Tracking, Guards, And Durability](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Tracking-Guards-And-Durability.md)
- [Save Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Save-Pipeline.md)
- [Load And Restore Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Load-And-Restore-Pipeline.md)
- [Payload Watch Framework](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Payload-Watch-Framework.md)
