# Minecraft 26.1.2 port

Development branch: `codex/port-26.1.2`. The published 1.21.11 release remains v4.1.1.

## Runtime and build

- Java 25 for Gradle, compilation, and Minecraft. The daemon criteria select Java 25 from installed JDKs.
- Fabric Loader 0.20.1, Fabric API 0.155.3+26.1.2, Loom 1.15.5, Gradle 9.8.2.
- Official, unobfuscated Minecraft names replace Yarn. The non-remapping Loom plugin produces the mod with `jar`.
- Client files live in `fabric/run-26.1.2/`; dedicated development servers use `fabric/run-server-26.1.2/`.
- Game tests use `fabric/build/run/gameTest-26.1.2/`. Clear its `world` directory before repeating tests.

## Storage and migration

CIS remains v12. The common codec, storage, and format-migration source code is unchanged by the mapping migration.

Minecraft 26.1 moved vanilla Overworld, Nether, and End storage into
`dimensions/<namespace>/<dimension>/`. Vanilla region and entity import paths now follow that layout.
Minecraft's file fixer runs before the client reaches Chunkis's pre-launch migration gate.

Chunkis's own paths are deliberately retained: Overworld CIS stays under `<world>/chunkis/`, and other
dimensions keep `<world>/dimensions/<namespace>/<dimension>/chunkis/`. Changing Minecraft versions must not
make existing CIS entries, mapping files, unfinished chunks, or portal indexes disappear from lookup.

The client migration gate retains Minecraft's new optional game-rule overrides while conversion runs and
passes them back unchanged when startup resumes. Client migration rendering uses the new extraction API.

## Validation

- Full build and Checkstyle passed; all 234 unit tests passed.
- Filesystem fixtures run Minecraft's actual dimension file fixer through its copy-on-write filesystem.
  Overworld, Nether, and End vanilla region, entity, and POI files move to the new paths while existing
  CIS regions, mapping files, unfinished chunks, and portal indexes remain readable at their original paths.
- All 43 game tests passed without C2ME.
- All 43 passed with C2ME 0.4.0-alpha.0.65+26.1.2, including the actual promotion hook.
- A temporary client probe reached normal client ticks and shut down successfully.
- One full-size stress pair completed: 4,096 natural-terrain chunks, two measured rounds per storage mode.
  Reports are under `benchmarks/runs/port-26.1.2-full/`. There is no saved 26.1.2 baseline, so the run does not
  establish a before/after performance regression verdict and does not replace the published 1.21.11 chart.
  Chunkis had higher tick p90 (72.5 vs 37.0 ms), reload p50 (1,730.8 vs 1,091.8 ms), and total lateness
  (5,445 vs 2,147 ms) than vanilla in this pair. Its CIS directory was 40.9 MB; the total disk figure also
  included 71.2 MB of vanilla files already present before measurement, with zero measured growth in those
  files. Performance and whole-world disk accounting need their own baseline before release claims.

Client startup and fixture tests do not replace a manual upgrade of a copy of a real 1.21.11 world. Verify
containers, entities, dimensions, saving, and reopening before publishing this port. Never downgrade a world
opened by Minecraft 26.1.2 back to 1.21.11.

## References

- [Fabric's 26.1 porting notes](https://fabricmc.net/2026/03/14/261.html)
- [Migrating Yarn to Mojang mappings](https://docs.fabricmc.net/develop/porting/mappings/)
- [Minecraft 26.1 world-storage changes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1)
