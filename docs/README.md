<p align="center">
  <img src="../common/src/main/resources/assets/logo.png" width="150" alt="Chunkis logo">
</p>

<h1 align="center">Chunkis</h1>

<p align="center">
  A Fabric mod for safe, compact, and predictable Minecraft chunk persistence.
</p>

Chunkis adds a chunk storage pipeline built around a sparse delta format called
CIS. It records Chunkis-managed changes relative to a chunk baseline, stores
those changes in dimension-local CIS region files, and reconstructs the chunk
when it is loaded.

Chunkis is primarily a disk-I/O and persistence-format project. It is not an
FPS, TPS, rendering, or gameplay optimization mod.

## At a glance

| Area          | Current behavior                                                                             |
|---------------|----------------------------------------------------------------------------------------------|
| Platform      | Fabric on Minecraft 1.21.11                                                                  |
| Runtime       | Java 21 or newer                                                                             |
| Storage       | CIS region files, mapping files, and optional metadata files                                 |
| Ownership     | Chunkis selectively takes ownership of saves; unowned vanilla writes remain available        |
| Size impact   | Workload-dependent; the project does not guarantee a fixed reduction                         |
| Data safety   | Ownership guards, authoritative snapshots, restore suppression, and corruption-aware loading |
| Reversibility | Treat authoritative CIS storage as one-way; keep a tested backup before installation         |

## How it works

Vanilla chunk persistence writes Anvil region data. Chunkis intercepts relevant
save and load paths through Fabric mixins:

1. Runtime changes are tracked in a `ChunkDelta`.
2. Save-time capture rebuilds authoritative state when needed instead of blindly
   persisting raw live edits.
3. The encoded delta is written through `CisStorage` into CIS region files.
4. Load paths rebuild the input data and replay Chunkis state during restoration.
5. Vanilla writes are suppressed only when Chunkis has actually claimed the
   save, so ownership decisions remain explicit.

The common codec stores a palette, bit-packed chunk instructions, compression
data, block-entity payloads, entity data, and preserved metadata as supported by
the current format version.

## On-disk layout

For the overworld, Chunkis data is stored below the world directory:

```text
<world>/
└── chunkis/
    ├── global_ids.json
    ├── portal_chunks.nbt
    ├── portal_links.nbt
    └── regions/
        ├── r.0.0.cis
        ├── r.0.1.cis
        └── ...
```

Other dimensions use their dimension directory under `<world>/dimensions/`.
The current CIS format version is tracked by the shared `CisConstants` model,
and older CIS data can be upgraded through the migration module.

## Compatibility and limitations

Chunkis is a poor fit for tools or mods that assume vanilla `.mca` files remain
the authoritative source of every chunk. Test carefully if a mod:

- reads or writes Anvil region files directly;
- performs its own chunk serialization or migration;
- applies chunk changes after world generation;
- replaces the normal world, save, or chunk lifecycle.

There is no supported general conversion from authoritative CIS data back to
vanilla Anvil storage. Offline MCA-to-CIS migration currently belongs to the
Fabric integrated-server startup path; it is not a general dedicated-server
prelaunch migration command.

Always test on a copy of the world and keep a backup that can be restored
without Chunkis before enabling authoritative storage.

## Reporting issues

Before opening an issue:

- reproduce it on a copy or backup of the world;
- check the compatibility limitations above;
- confirm the issue occurs on the current project version;
- collect the Minecraft version, Fabric Loader version, Fabric API version, and
  complete mod list.

Include `latest.log` or a crash report, exact reproduction steps, and any
relevant CIS files only when it is safe to share them.

## Wiki

Architecture, development, build instructions, module details, and contributor
guidance will live in the [project wiki](../../wiki).
