# Chunkis

Chunkis is a Fabric mod that takes ownership of chunk persistence. In the current codebase it intercepts vanilla chunk save and load paths, stores Chunkis-managed chunk data in CIS region files, rebuilds load input from Chunkis state, and sends chunk deltas to clients on a separate networking path.

## Current Modules

- `common`: loader-agnostic chunk model, codecs, compression helpers, debug model, and SPI adapters
- `storage`: CIS storage engine, region files, mapping persistence, and storage inspection helpers
- `migration`: CIS-to-CIS version planning and storage-backed rewrite logic
- `fabric`: Fabric entrypoints, mixins, runtime tracking, restore, commands, networking, portal support, and offline MCA-to-CIS migration

## Current Capabilities

- Tracks chunk-owned runtime state through `ChunkDelta`
- Writes dimension-local `chunkis/regions/r.<x>.<z>.cis` files
- Persists dimension-local `global_ids.json` mapping files
- Uses authoritative save-time snapshots instead of persisting raw live sparse edits directly
- Builds synthetic load NBT and replays Chunkis state during restore
- Blocks vanilla writes only when Chunkis has taken ownership of the save
- Runs integrated-server offline MCA-to-CIS migration before startup
- Provides CIS version migration from older CIS versions to the current format
- Exposes debug tracing, payload watches, durability test commands, and storage reports

## Current Limitations

- The code does not provide a supported path back to vanilla `.mca` storage after Chunkis data becomes authoritative
- Mods or tools that expect vanilla region files to remain authoritative are a bad fit
- Offline MCA-to-CIS migration is proven for the integrated-server startup path, not for a separate dedicated-server prelaunch hook
- Storage size is workload-dependent; the codebase does not prove that CIS is always smaller than vanilla
- Some restore paths still depend on persisted base chunk metadata or a full baseline to make sparse payloads safe

## On-Disk Layout

Overworld:

```text
<world>/
  chunkis/
    global_ids.json
    portal_chunks.nbt
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

```bash
./gradlew build
./gradlew test
./gradlew runGameTest
```

On Windows PowerShell:

```powershell
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat runGameTest
```

## Docs Map

- [Architecture index](Architecture/README.md)
- [Module docs](common/README.md), [storage](storage/README.md), [migration](migration/README.md), [fabric](fabric/README.md)
- [Development guide](Development/Developer-Guide.md)
- [Debug notes](debug/README.md)

## Stale Naming To Avoid

Some current repository docs still refer to `core/` and `cismigrator/`. The active Gradle modules are `common`, `storage`, `migration`, and `fabric`.

## Evidence

- `settings.gradle`
- `README.md`
- `fabric/src/main/java/io/liparakis/chunkis/ChunkisMod.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/StoragePreventionMixin.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/save/ChunkisStoragePaths.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/client/storage/MinecraftClientMixin.java`
