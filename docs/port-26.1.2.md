# Minecraft 26.1.2 port

Release: **4.2.0**, branch `26.1.2`. The Minecraft 1.21.11 release remains v4.1.1.

The release checks and entity-save repair are recorded in [26.1.2 validation](validation-26.1.2.md).
The sections below retain the earlier port and investigation results.

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

- Full build and Checkstyle passed; all 236 unit tests passed.
- Filesystem fixtures run Minecraft's actual dimension file fixer through its copy-on-write filesystem.
  Overworld, Nether, and End vanilla region, entity, and POI files move to the new paths while existing
  CIS regions, mapping files, unfinished chunks, and portal indexes remain readable at their original paths.
- All 43 game tests passed without C2ME.
- All 43 passed with C2ME 0.4.0-alpha.0.65+26.1.2, including the actual promotion hook.
  During the cleanup recheck, generation-flow ownership failed once; two fresh-world repeats passed all 43
  without changing the test, including a final run after native-resource cleanup.
- A temporary client probe reached normal client ticks and shut down successfully.
- One full-size stress pair completed: 4,096 natural-terrain chunks, two measured rounds per storage mode.
  Reports are under `benchmarks/runs/port-26.1.2-full/`. There is no saved 26.1.2 baseline, so the run does not
  establish a before/after performance regression verdict and does not replace the published 1.21.11 chart.
  The PC was in active use during this run, so its timings are inconclusive and must not be treated as a
  performance comparison. It confirms workload completion only. Its CIS directory was 40.9 MB; the total
  disk figure also included 71.2 MB of vanilla files already present before measurement, with zero measured
  growth in those files. Performance and whole-world disk accounting need an idle-machine baseline before
  release claims.

The initial port checks were followed by copied-world upgrade/save/reopen checks; see the release validation
report linked above for their scope and limits. Never downgrade a world opened by Minecraft 26.1.2 back to
1.21.11.

## References

- [Fabric's 26.1 porting notes](https://fabricmc.net/2026/03/14/261.html)
- [Migrating Yarn to Mojang mappings](https://docs.fabricmc.net/develop/porting/mappings/)
- [Minecraft 26.1 world-storage changes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1)


## Code-analysis cleanup

The cleanup updates Zstd JNI to 1.5.7-25 without changing CIS v12. Previously written codec and migration
fixtures remain part of validation. Minecraft Dev now recognizes mixin entrypoints, so mixins do not carry
unused-code suppressions. Narrow annotations remain for deliberate storage contracts and server-owned
resources. The mod logo belongs to Fabric resources and retains its packaged `assets/logo.png` path.

Reusable compression contexts now register native cleanup against a Java owner. A terminated worker's
thread-local compressor can release its native state; repeated use and idempotent explicit cleanup have
dedicated tests. Compression settings and the on-disk format are unchanged.

Migration cancellation now joins workers before closing shared storage. Interrupted callers retain their
interrupt status; shutdown can wait for an in-progress worker instead of closing its storage after a timeout.

The post-cleanup stress pair also completed for both storage modes: 4,096 natural-terrain chunks and two
measured rounds per mode. Reports are in `benchmarks/runs/port-26.1.2-analysis-cleanup-full/`. The machine
was in active use and no 26.1.2 baseline was saved, so this is workload-completion evidence only.

The follow-up unused-code pass removes the dormant outgoing-payload metrics subsystem, the unconsumed
clean-site profiler map and its injection hooks, unused snapshot/save overloads, and the section source's
unused type parameter. Active restore counters and profiler data consumed by reports remain.

Load and save diagnostics share one metadata description. Reload fixtures share section placement,
snapshot copying and complete block comparisons. Flight reports separate fixed settings, pop-in counters
and loader counters; the overwritten numeric `legs` property was removed. No duplicate-code or
method-extraction suppressions are needed for these paths. The public Fabric API and discovered test
entrypoints remain even where an IDE reports no direct caller.

The structural cleanup passes 236 unit tests and all 43 game tests with and without C2ME. The quick
stress pair (576 chunks, one measured round per mode) completed under
`benchmarks/runs/port-26.1.2-real-cleanup-quick/`; it has no saved baseline and supplies no performance
verdict. The follow-up IDE scan leaves five visible contract notices: two public API methods, a discovered
game test, and the two Minecraft optional-game-rule parameters. None is suppressed to obtain a zero count.


## Dimension guards and Minecraft data-version investigation

Region storage guards now use `RegionStorageInfo.dimension()` supplied by Minecraft's constructors.
The directory still identifies chunk/entity storage versus POI/internal storage; it no longer determines
which dimension owns it. Previously, `dimensions/example/world/caves/region` was classified as Overworld
rather than `example:world/caves`. Guessing from ancestor names also cannot reliably distinguish a save
parent or dimension path containing `dimensions`. Both `RegionFileStorage` and the low-level `RegionFile`
guard use the explicit key now. The CIS layout and stored format are unchanged. Nineteen parameterized
cases cover nested identifiers, misleading directory names, legacy and normalized layouts, alternate
paths, and storage that must stay unguarded.

The CIS load path remains CIS decode -> Chunkis reconstruction -> vanilla parsing/deserialization.
Parsing is separate from data fixing: vanilla's `ChunkMap.readChunk` normally calls `upgradeChunkTag`
before `SerializableChunkData.parse`. The CIS injection replaces that entire method and returns its
synthetic root directly. Vanilla fallback and unfinished-chunk reads explicitly call `upgradeChunkTag`;
the CIS branch does not. `SyntheticLoadNbtProvider` supplies the current game data version to the NBT
builder, which uses it for newly synthesized roots, including roots carrying older preserved auxiliary
metadata. An existing persisted base root can retain its old version, but the CIS branch still bypasses
the upgrade call. Entity replay independently passes saved NBT into `EntityType.loadEntityRecursive`,
which does not run the data fixer. The offline MCA translator also reads raw NBT before parsing it.
These are verified paths; they do not by themselves demonstrate lost data in a 1.21.11 world.

The 26.1.2 mapped `DataFixers` registers `VillagerSetVillagerDataFinalized` and
`ZombieVillagerSetVillagerDataFinalized` at schema 4763. The compatibility game tests use schema fixtures
from the 1.21.11 era (data version 4671, verified in that release's bundled `version.json`). Minecraft's
actual `ENTITY_CHUNK` fixer adds `VillagerDataFinalized=true`; direct decoding receives no such field.
Both actual 26.1.2 entity loaders nevertheless preserve the fixture's explicit desert type, librarian
profession and level 4, and agree with the data-fixed controls. The mapped loaders explicitly accept
`VillagerData` even without the new flag.

Thus the missing automatic upgrade is confirmed, but the earlier suggestion of inventory/entity data
loss on this specific version transition is not proven. These fixtures test decoding only: they are
not copied real worlds and do not cover ticking, trading, curing, every payload, or older source versions.
Subsequent copied-world upgrade/save/reopen checks are documented in the release validation report;
they cover specific fixtures rather than every possible world or modded payload.


Validation of the dimension guard change: the full build, Checkstyle, all 250 unit tests and all 45
required game tests passed. All 45 also passed with C2ME 0.4.0-alpha.0.65+26.1.2. The new villager
compatibility fixtures passed in both game-test runs. The build logs and comparison output are retained
locally under `.gradle/chunkis-port26/dimension-guard-build.log` and `dimension-guard-c2me.log`.
The quick storage workload also completed in Chunkis and vanilla modes (576 natural-terrain chunks,
one measured round each), with reports under `benchmarks/runs/port-26.1.2-dimension-guard-quick/`.
There is no saved quick baseline; this supplies workload-completion evidence, not a performance verdict
or a substitute for the full idle-machine regression run.


### Broader old-runtime payload comparison

A subsequent investigation exported payloads from actual Minecraft 1.21.11 running the released
`29c79d31` checkout, then loaded and re-saved them on 26.1.2 with and without Minecraft's data fixer.
All 153 entity samples and 48 block-entity samples produced identical direct/fixed NBT. All 1,504
item-stack samples retained their source encodings, and all 29,671 registered block states retained
their block identifiers and property values. No direct/fixed differences or unstable controls occurred.

The source-versus-resaved audit found no removed NBT fields. The only existing-value differences were
attribute-list order for slime and magma cube; IDs and values are unchanged. Other differences are new
default fields. Entity fixtures were first reloaded by 1.21.11 itself to exclude constructor defaults
that the old loader also normalizes. Containers held populated inventories, living entities carried
equipment, and item stacks included names and damage.

**No data loss was reproduced for this transition.** Bypassing data fixing remains a code-path fact,
not a demonstrated compatibility bug or a reason by itself to apply a speculative migration patch.
The diagnostic covers vanilla payload samples, not every state/component combination, modded payloads,
a whole real-world upgrade, CIS disk round trips or interrupted migration. The detailed local report,
fixture hash, source and reproduction steps are in
`.gradle/chunkis-port26/data-loss-investigation.md`; raw comparisons are in `data-loss-probe.json`.
