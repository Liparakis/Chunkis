<p align="center">
  <img src="common/src/main/resources/assets/logo.png" width="150" alt="Chunkis logo">
</p>

<p align="center">
  A Fabric mod for safe, compact, and predictable Minecraft chunk persistence.
</p>

Chunkis replaces vanilla chunk storage with its own format, CIS. Chunks are saved as compact, versioned
CIS entries in dimension-local region files and rebuilt from them on load. The goal is storage that is
smaller, lighter on memory and the server thread, and never silently loses data.

Chunkis is a persistence project. It is not a rendering or FPS mod, and it does not change gameplay.

## At a glance

| Area          | Current behavior                                                                              |
|---------------|-----------------------------------------------------------------------------------------------|
| Platform      | Fabric on Minecraft 1.21.11, Fabric API required                                              |
| Runtime       | Java 21 or newer                                                                              |
| Format        | CIS version 12: palettes, packed and context-coded block data, Zstd compression               |
| Storage       | CIS region files per dimension, a block-id mapping file, portal index files                   |
| Ownership     | Chunkis takes over a chunk's saves explicitly; vanilla writes are suppressed only for those   |
| Data safety   | Ownership guards, undecodable entries never overwritten, corruption-aware loading             |
| Reversibility | Treat Chunkis storage as one-way and keep a tested backup before installing                   |

## How it works

Chunkis hooks the save and load paths through Fabric mixins:

1. Changes to a chunk are tracked in a `ChunkDelta`.
2. On save, the chunk's blocks are copied on the server thread and encoded on background save workers.
   Blocks unchanged since the last save reuse their stored bytes instead of being encoded again.
3. The encoded entry is compressed with Zstd and written through `CisStorage` into region files. Each entry
   takes exactly its own size, not a whole 4 KiB sector as in vanilla's `.mca` files.
4. On load, the entry is decoded off the server thread. Vanilla receives finished block containers instead
   of NBT to parse, and recently unloaded chunks reload from memory without decoding at all.
5. Chunks that are still generating are kept, compactly, instead of being generated again on the next load.

## Performance

Measured with the stress benchmark in [`tools/stress`](tools/stress/README.md): a fixed 64×64-chunk area of
natural terrain, unloaded, reloaded, 10% of chunks edited and saved, over several rounds, with vanilla run
alternately on the same machine. Ratios are Chunkis divided by vanilla, so lower is better:

| Metric                          | Chunkis ÷ vanilla |
|---------------------------------|-------------------|
| Disk use                        | 0.69              |
| Retained heap                   | 0.72              |
| Chunk unload time (median)      | 0.74              |
| Worst tick times (99th pct.)    | 0.76              |
| Total CPU                       | 0.91              |
| Chunk reload time               | about 1.00        |
| Typical tick times (median/90th) | 0.99–1.05         |

Results depend on the world, the workload and the hardware. Typical tick times range from even with vanilla
to somewhat behind on a quiet machine: Chunkis spreads save work evenly across ticks, while vanilla does it
in fewer, larger spikes.

## On-disk layout

For the overworld, Chunkis data lives below the world directory:

```text
<world>/
└── chunkis/
    ├── global_ids.json        block-id mapping
    ├── portal_chunks.nbt      portal index
    ├── portal_links.nbt
    ├── regions/               CIS region files: r.<x>.<z>.cis
    └── unfinished/            chunks still generating, in the same region format
```

Other dimensions use their own directory under `<world>/dimensions/`. The format version is
`CisConstants.VERSION`; older CIS data is upgraded by the `migration` module. Worlds that kept unfinished
chunks in vanilla `.mca` files under `unfinished/` are still read, and those entries move to the CIS files
as they load.

## Configuration

Chunkis needs no configuration. Optional JVM system properties:

| Property                             | Default | Effect                                                         |
|--------------------------------------|---------|----------------------------------------------------------------|
| `-Dchunkis.recentChunkCacheMb=<mb>`  | 256     | Memory for keeping recently unloaded chunks' blocks; 0 disables |
| `-Dchunkis.portal.fallbackRadius=<n>` | 20      | Search radius for a safe portal arrival spot                   |

## Compatibility and limitations

Chunkis is a poor fit for tools or mods that assume vanilla `.mca` files hold every chunk. Test carefully
if a mod:

- reads or writes Anvil region files directly;
- serializes or migrates chunks itself;
- changes chunks after world generation;
- replaces the normal world, save or chunk lifecycle.

There is no supported conversion from Chunkis storage back to vanilla Anvil files. Migrating an existing
world into Chunkis currently runs on integrated-server startup; there is no dedicated-server migration
command yet.

Always test on a copy of the world, and keep a backup that can be restored without Chunkis.

## Building

```bash
./gradlew build    # Windows: gradlew.bat build
```

This compiles every module and runs the unit tests, checkstyle and the Fabric game tests. The mod jar is
written to `fabric/build/libs/`. See [AGENTS.md](AGENTS.md) for the module layout and project rules.

## Reporting issues

Before opening an issue:

- reproduce it on a copy or backup of the world;
- check the compatibility limitations above;
- confirm it happens on the current version;
- collect the Minecraft, Fabric Loader and Fabric API versions and the complete mod list.

Include `latest.log` or the crash report and exact reproduction steps. Attach CIS files only when they are
safe to share.

## Contributing

Contributions are welcome, including ones written with AI tools, under these rules:

- **A human reviews all code.** Generated code gets the same scrutiny as handwritten code, and the person
  opening the pull request is responsible for every line of it.
- **Describe the change.** A pull request explains what behavior changed, how it was implemented and why
  this approach was chosen, including what it means for stored data and compatibility.
- **Be able to explain it.** Whoever opens the pull request must be able to describe and answer questions
  about the changes without falling back to "the AI wrote it".
- **Keep it focused and tested.** One concern per pull request. `./gradlew build` must pass, and changes to
  saving, loading, the codec or storage should include a run of the stress regression check.

Project rules for both people and AI agents are in [AGENTS.md](AGENTS.md).

## Use of AI in development

Chunkis is developed with AI assistance. AI coding assistants were used to write and refactor code, write
tests, profile and benchmark, and draft documentation. Every change is reviewed, tested and owned by the
maintainer, under the same rules that apply to contributors.

## License

MIT
