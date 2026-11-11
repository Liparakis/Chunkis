# Minecraft 26.2 port

Release branch: `26.2`, based on released Chunkis 4.2.0 (`ed630c33`).
The mod version is **4.2.1** after the entity replay repair.
Release: [4.2.1 for Minecraft 26.2](https://github.com/Liparakis/Chunkis/releases/tag/v4.2.1-mc26.2).
The release assets include `mc26.2` in their filenames; the mod metadata targets Minecraft `~26.2`.
Minecraft's official version manifest lists **26.2** as the sole stable release in the 26.2 line.

## Runtime and tooling

| Component | Version |
|:---|:---|
| Minecraft | 26.2 |
| Java | 25 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.161.0+26.2 |
| Fabric Loom | 1.17.21 |
| Gradle | 9.8.2 |

The game and test mod manifests target `~26.2`. Client, server and game-test run directories are
`fabric/run-26.2/`, `fabric/run-server-26.2/` and `fabric/build/run/gameTest-26.2/` respectively.
The 26.1.2 run directories remain separate and ignored. Run `genSources` separately from compilation:
Loom 1.17.21 and Gradle 9.8.2 report an implicit task dependency if both are requested in the same invocation.

## Code changes

Minecraft changed the CompoundTag overload of `EntityType.loadEntityRecursive` to require an
`EntitySpawnRequest`. Bulk and queued CIS entity replay now pass
`new EntitySpawnRequest(EntitySpawnReason.LOAD, false)`, matching the request constructed by vanilla's
ValueInput overload. Entity-load checks remain enabled; passenger traversal uses Minecraft's own loader.
The legacy villager compatibility tests use the same request.

The copied-world cold reopen exposed concurrent first calls to MinecraftServer.getWorldPath: its
LevelStorageAccess cache is an ordinary HashMap, and concurrent worker initialization in multiple
dimensions threw ConcurrentModificationException. Chunkis now reads the server's final storage access
through a small accessor and resolves paths from its immutable LevelDirectory descriptor. This preserves
Minecraft's exact path resolution without touching the mutable cache, adding locks or maintaining another
cache. A regression test resolves roots on four workers while an unrelated thread fills the native
resource cache; ROOT must remain absent from that cache. The failure was observed on this port; the test
does not establish that the underlying unsafe access was introduced by Minecraft 26.2.

Built-in entity constants moved from `EntityType` to `EntityTypes`; game-test and stress fixtures now
use the new owner. Generic types and entity loading APIs remain on `EntityType`.

CIS remains **version 12**. The entity-only snapshot envelope remains **version 1**. This port does not
change their layouts, storage locations, compression settings or ownership rules. The targeted C2ME
promotion deadlock repair and the 4.2.0 entity-only persistence repair are retained. Performance
optimization is deferred while version support catches up.

## Validation

- Full Gradle build and Checkstyle passed; the project has 253 unit tests and all **47 game tests passed**.
- All **47 game tests passed** with C2ME **0.4.2-alpha.0.60+26.2**, the latest build listed for 26.2 at test time.
  This exercises the actual promotion hook; other C2ME versions and additional mod combinations are unverified.
- An isolated client loaded successfully, displayed the actual migration screen, rendered it for forty client
  ticks and exited normally. This also loads the client mixins; the default OpenGL backend was used.
- A copy of the saved 26.1.2/Chunkis 4.2.0 fixture world upgraded on 26.2 with storage guards enabled for
  Overworld, Nether and End. All fifteen full-chunk hashes, twelve chest inventories and persistent entity
  UUIDs matched before edits. New marker and inventory edits were saved, and a cold reopen after the startup fix
  matched all of them. All 235 files in the original 26.1.2 source retained their hashes.
- The quick storage workload completed in both Chunkis and vanilla modes: 576 naturally generated chunks,
  one measured round per mode, with edits and persistence checks. The quick regression guard ran, but no saved
  quick baseline exists. These runs used four cores and a 4 GB heap while another client was active, so they
  establish functional completion only. The full idle-machine performance benchmark remains deferred.

Raw logs and copied-world probes are kept locally under the ignored `.gradle/chunkis-port26.2/` directory.
The prior [26.1.2 validation report](validation-26.1.2.md) records how the source fixture was constructed
and its earlier controls. These checks do not establish compatibility for every modded payload, manually
exercise portal travel, or validate the experimental Vulkan backend.

## References

- [Mojang version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
- [Fabric for Minecraft 26.2](https://fabricmc.net/2026/06/15/262.html)
- [Fabric porting guide](https://docs.fabricmc.net/develop/porting/)
