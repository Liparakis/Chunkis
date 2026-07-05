# Architecture

This folder documents the implementation that exists in the current repository, using the active module names: `common`, `storage`, `migration`, and `fabric`.

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

- `common` defines the data model, codecs, and debug model
- `storage` owns persistence mechanics
- `migration` owns CIS version transitions
- `fabric` owns Minecraft integration and runtime behavior

## Evidence

- `settings.gradle`
- `docs/Architecture/README.md`
- `common/build.gradle`
- `storage/build.gradle`
- `migration/build.gradle`
- `fabric/build.gradle`
