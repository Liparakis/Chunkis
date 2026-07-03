# Chunkis

Chunkis is a Fabric mod that replaces vanilla chunk region persistence with a Chunkis-owned CIS storage pipeline. It intercepts the normal save/load path, stores chunk state in `chunkis/regions/*.cis`, and rebuilds vanilla load inputs from Chunkis data on the way back in.

This is a persistence-layer mod. It changes how chunk state is stored and restored. It does not target FPS gains, and it does not guarantee smaller storage than vanilla in every world.

## Status

| Item | Current state |
| --- | --- |
| Mod version | `4.0.0` |
| Minecraft | `1.21.11` |
| Loader | Fabric |
| Java | 21 |
| Storage owner | Chunkis, not vanilla `.mca`, once a chunk is persisted by Chunkis |
| Storage format | CIS v11 |

## What Exists Today

- Chunkis-owned chunk save/load hooks through Fabric mixins.
- Dimension-local CIS storage under `chunkis/regions/` with `global_ids.json`.
- Authoritative save-time chunk snapshot capture.
- Sparse-payload guards backed by persisted base chunk metadata when needed.
- Async per-dimension save workers with generation checks.
- Synthetic load NBT plus restore-time replay into live `WorldChunk` instances.
- Parallel client delta sync layered on top of vanilla chunk packets.
- Offline MCA-to-CIS translation before integrated-server startup.
- CIS-to-CIS version migration through the `cismigrator` module.
- Debug tracing, payload watch tooling, durability commands, and storage inspection commands.

## Current Limitations

- Chunkis is not reversible back to vanilla storage. Back up worlds before adopting it.
- Mods that read or write vanilla `.mca` region files directly are incompatible.
- Storage efficiency depends on world shape and safety anchors. Some worlds will grow compared with vanilla or older Chunkis releases.
- Some cross-chunk natural-structure restores can still show seams when neighboring chunk context is missing during regeneration-backed restore.

## On-Disk Layout

Overworld:

```text
<world>/
  chunkis/
    global_ids.json
    portal_links.nbt
    regions/
      r.<regionX>.<regionZ>.cis
```

Other dimensions:

```text
<world>/
  dimensions/
    <namespace>/
      <path>/
        chunkis/
          global_ids.json
          portal_chunks.nbt
          regions/
            r.<regionX>.<regionZ>.cis
```

## Build And Test

From the repository root:

```bash
./gradlew build
./gradlew test
```

Main output:

```text
fabric/build/libs/chunkis-fabric-<version>.jar
```

## Developer Entry Points

- Architecture index: [docs/Architecture/README.md](docs/Architecture/README.md)
- Developer guide: [docs/Development/Developer-Guide.md](docs/Development/Developer-Guide.md)
- Core storage module: [core/README.md](core/README.md)
- Fabric integration module: [fabric/README.md](fabric/README.md)
- CIS migration module: [cismigrator/README.md](cismigrator/README.md)

Recommended architecture reading order:

1. [docs/Architecture/System-Overview.md](docs/Architecture/System-Overview.md)
2. [docs/Architecture/Startup-And-Lifecycle.md](docs/Architecture/Startup-And-Lifecycle.md)
3. [docs/Architecture/Delta-And-Ownership-Model.md](docs/Architecture/Delta-And-Ownership-Model.md)
4. [docs/Architecture/Save-Pipeline.md](docs/Architecture/Save-Pipeline.md)
5. [docs/Architecture/Load-And-Restore-Pipeline.md](docs/Architecture/Load-And-Restore-Pipeline.md)

## Commands

Examples of the current operator-facing surface:

- `/chunkis debug on`
- `/chunkis debug suspects`
- `/chunkis debug watch block <x> <y> <z>`
- `/chunkis durability ...`
- `/chunkis_storage_report`

## Compatibility Notes

Known bad fits:

- World-editing tools that directly manipulate `.mca` region files.
- Mods with their own chunk serialization pipeline.
- Mods that assume vanilla region files remain authoritative after Chunkis takes over a chunk.

If you are unsure, test on a copy of the world and inspect both gameplay behavior and storage output.

## Future Work

Planned work that is not implemented in this repository today:

- Better storage efficiency for worlds that currently require many safety anchors.
- Improved restore behavior for cross-chunk natural structures.
- Any broader migration workflow beyond the integrated-server prelaunch translator and the existing CIS version migrator.

## Contributing

Documentation changes should track implementation, not planned design. If you change persistence semantics, update the matching architecture doc in the same change.
