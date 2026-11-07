# Minecraft 26.3 port

Release branch: `26.3`, based on the completed [26.2 port](port-26.2.md) (`b42a871`).
The mod version remains **4.2.0**. The Minecraft dependency distinguishes this build from the
26.1.2 and 26.2 releases.
Release: [4.2.0 for Minecraft 26.3](https://github.com/Liparakis/Chunkis/releases/tag/v4.2.0-mc26.3).
Release asset filenames include `mc26.3`.

## Runtime and tooling

| Component | Version |
|:---|:---|
| Minecraft | 26.3 |
| Java | 25 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.2+26.3 |
| Fabric Loom | 1.17.21 |
| Gradle | 9.8.2 |

The game and test manifests target `~26.3`. Client, server and game-test directories are
`fabric/run-26.3/`, `fabric/run-server-26.3/` and `fabric/build/run/gameTest-26.3/`.
Earlier versions retain their own run directories. Run source generation separately from compilation,
as described in the 26.2 port notes.

## Code changes

- Minecraft removed `ChunkAccess.hasAnyStructureReferences()`. Its previous implementation was
  `!getAllReferences().isEmpty()`; the direct structure serializer now checks the already fetched map.
  Its game test compares starts/references and reference-only payloads with vanilla, and checks that
  empty metadata is omitted.
- The benchmark generation hook targets `ChunkStatusTasks.buildTerrain`, replacing `generateNoise`.
  Its historical `NOISE` counter now counts terrain-build invocations, so generation-stage counts
  should not be compared directly with older Minecraft versions.
- The initial mob-population hook now accepts vanilla's spawn-source `BlockPos` instead of a biome
  holder. Restored chunks still suppress initial repopulation, preventing duplicate passive mobs.
- Test fixture entities use `setPermanentlyInvulnerable`, the renamed persistent invulnerability setter.

CIS stays **version 12**, and the entity-only snapshot envelope stays **version 1**. Their stored layouts
are unchanged. The 26.2 world-path cache repair, targeted C2ME promotion fix and entity-only persistence
repair are retained. Performance optimization remains deferred while version support catches up.

## Validation

- Full Gradle build, Checkstyle and **253 unit tests** passed. All **47 game tests passed**, including
  the expanded structure metadata comparison against vanilla.
- All **47 game tests passed** with C2ME **0.4.2-alpha.0.116+26.3**, the latest listed 26.3 build at test time.
  Other C2ME builds and additional mod combinations have not been verified on this target.
- The quick natural-terrain storage workload passed in Chunkis and vanilla modes: 576 chunks and one
  measured round per mode, with edits and persistence checks. The quick regression guard ran, but no
  saved quick baseline exists. These isolated runs used four cores and a 4 GB heap and do not establish
  an idle-machine performance comparison. The full timing benchmark remains deferred.
- A saved 26.2/Chunkis 4.2.0 fixture copy loaded on 26.3 with storage guards enabled in all three dimensions.
  All fifteen full-chunk hashes, twelve chest inventories and persistent entity UUIDs matched before edits.
  The probe canonicalizes block-state NBT field names because vanilla changed `Name`/`Properties` to
  `id`/`properties`; a separate comparison confirmed all **1,212,416 block states and their properties**
  were identical. The first raw-NBT hash check failed solely on these key names; a fresh-copy repeat
  with canonical hashes passed. New netherite markers and inventory edits were then saved.
- A cold reopen matched all fifteen chunk hashes, twelve inventories and entity UUIDs after the new
  edits. All 235 files in the original 26.2 source retained their hashes.
- An isolated client loaded through the SDL window backend, displayed the actual migration screen,
  rendered it for forty client ticks and exited normally. The run used OpenGL; Vulkan is unverified.

The fixture originated on 26.1.2 and was saved/reopened on 26.2 before this upgrade; see the
[26.2 validation notes](port-26.2.md). These checks do not cover every modded payload or manually
exercise portal travel.

Raw logs and isolated probes are local under the ignored `.gradle/chunkis-port26.3/` directory.

## References

- [Mojang version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
- [Fabric for Minecraft 26.3](https://fabricmc.net/2026/09/15/263.html)
- [Fabric porting guide](https://docs.fabricmc.net/develop/porting/)
