# Payload Watch Framework

## Purpose

Chunk timelines answer "what happened to this chunk?" Payload watch answers "what happened to this specific block, block entity, or entity as it moved through Chunkis?"

## Main Classes

- `core/src/main/java/io/liparakis/chunkis/debug/model/watch/PayloadWatchTarget.java`
- `core/src/main/java/io/liparakis/chunkis/debug/model/watch/PayloadWatchType.java`
- `core/src/main/java/io/liparakis/chunkis/debug/watch/ChunkTraceWatchpoints.java`
- `fabric/src/main/java/io/liparakis/chunkis/debug/trace/PayloadWatchTracer.java`
- `fabric/src/main/java/io/liparakis/chunkis/command/ChunkDebugCommand.java`

## What It Tracks

Payload watch emits trace events around:

- capture
- encode
- serialization
- storage write and read
- decode
- proto attach
- live-world restore
- post-restore visibility
- client send and client-visible state

The important limit is that a successful earlier event does not prove a later stage happened. For example, `WATCH_DECODED` only proves the payload exists in the decoded `ChunkDelta`.

## Commands

- `/chunkis debug watch block <x> <y> <z>`
- `/chunkis debug watch blockentity <x> <y> <z>`
- `/chunkis debug watch entity <uuid>`
- `/chunkis debug watch clear`
- `/chunkis debug watch list`

## Practical Use

Use payload watch when chunk-level traces are too coarse and you need to know the exact boundary where one payload disappeared or diverged.

## Related Docs

- [Observability And Debugging](Observability-And-Debugging.md)
- [Save Pipeline](Save-Pipeline.md)
- [Load And Restore Pipeline](Load-And-Restore-Pipeline.md)
