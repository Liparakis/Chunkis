# Observability And Debugging

## Purpose

Chunkis persistence bugs are usually timeline bugs, not single-method bugs. The observability stack records the boundaries that matter when save, load, restore, or client sync goes wrong.

## Main Classes

- `core/src/main/java/io/liparakis/chunkis/debug/trace/ChunkTraceStore.java`
- `core/src/main/java/io/liparakis/chunkis/debug/trace/ChunkTraceInvariants.java`
- `core/src/main/java/io/liparakis/chunkis/debug/model/ChunkTraceEvent.java`
- `core/src/main/java/io/liparakis/chunkis/debug/watch/ChunkTraceWatchpoints.java`
- `core/src/main/java/io/liparakis/chunkis/debug/trace/ChunkTraceJsonl.java`
- `fabric/src/main/java/io/liparakis/chunkis/debug/trace/PayloadWatchTracer.java`
- `fabric/src/main/java/io/liparakis/chunkis/command/ChunkDebugCommand.java`
- `fabric/src/main/java/io/liparakis/chunkis/command/DurabilityTestCommand.java`
- `fabric/src/main/java/io/liparakis/chunkis/command/StorageReportCommand.java`

## Current Model

The trace store records bounded in-memory events around:

- save requested, queued, flushed, failed
- load started, source resolved, completed, failed
- region reads and writes
- restore started, applied, completed, failed
- tracker transitions
- client sync send and apply
- assertion failures

`ChunkTraceInvariants` only checks cheap, high-confidence rules. It does not attempt full causal proof.

## Suspects And Watches

`ChunkTraceStore` promotes high-value failures into retained suspects so the ring buffer can rotate without immediately losing the interesting case.

Watch tooling is layered on top:

- chunk watchpoints
- region watchpoints
- payload watches for blocks, block entities, and entities

See [Payload Watch Framework](Payload-Watch-Framework.md).

## Commands

Current operator-facing commands include:

- `/chunkis debug on|off`
- `/chunkis debug latest <count>`
- `/chunkis debug failures [count]`
- `/chunkis debug suspects`
- `/chunkis debug suspect <id>`
- `/chunkis debug watch ...`
- `/chunkis durability ...`
- `/chunkis_storage_report`

## Practical Debug Order

For a persistence bug:

1. enable debug
2. reproduce
3. inspect suspect and failure timelines
4. correlate save request, queue/flush, load source, restore, and client sync
5. inspect pending async saves or payload watches when the chunk timeline alone is too coarse

## Limits

- Debugging is bounded and mostly in-memory, not a permanent trace database.
- A clean delta is not proof of a successful flush without matching timeline evidence.
- Payload watches prove stage boundaries, not impossible facts between stages.

## Related Docs

- [Tracking, Guards, And Durability](Tracking-Guards-And-Durability.md)
- [Payload Watch Framework](Payload-Watch-Framework.md)
- [Save Pipeline](Save-Pipeline.md)
- [Load And Restore Pipeline](Load-And-Restore-Pipeline.md)
