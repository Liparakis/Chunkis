# Chunkis Architecture

This folder is the implementation-aligned architecture map for Chunkis.

## Reading Order

1. [System Overview](System-Overview.md)
2. [Startup And Lifecycle](Startup-And-Lifecycle.md)
3. [Delta And Ownership Model](Delta-And-Ownership-Model.md)
4. [Save Pipeline](Save-Pipeline.md)
5. [Load And Restore Pipeline](Load-And-Restore-Pipeline.md)
6. [Storage Format](Storage-Format.md)
7. [Snapshots And Metadata](Snapshots-And-Metadata.md)
8. [Tracking, Guards, And Durability](Tracking-Guards-And-Durability.md)
9. [Networking And Client Sync](Networking-And-Client-Sync.md)
10. [Migration And Versioning](Migration-And-Versioning.md)
11. [Observability And Debugging](Observability-And-Debugging.md)
12. [Payload Watch Framework](Payload-Watch-Framework.md)

## Scope

These documents describe the code that exists in the repository today:

- `core/` owns the storage engine, codecs, mapping, and debug model.
- `fabric/` owns Minecraft integration, runtime tracking, restore, migration entrypoints, commands, and networking.
- `cismigrator/` owns CIS-to-CIS version planning and storage-backed rewrites.

When the architecture changes, update the relevant document here in the same change.
