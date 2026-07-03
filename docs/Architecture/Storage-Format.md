# Storage Format

## Purpose

Chunkis stores chunk data in its own CIS region format instead of vanilla Anvil files.

## Main Classes

- `core/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java`
- `core/src/main/java/io/liparakis/chunkis/storage/io/CompressionContext.java`
- `core/src/main/java/io/liparakis/chunkis/storage/io/RegionFileCache.java`
- `core/src/main/java/io/liparakis/chunkis/storage/io/region/RegionFile.java`
- `core/src/main/java/io/liparakis/chunkis/storage/io/region/CisRegionInspector.java`
- `core/src/main/java/io/liparakis/chunkis/storage/io/CisRegionCompactor.java`
- `core/src/main/java/io/liparakis/chunkis/storage/mapping/CisMapping.java`
- `fabric/src/main/java/io/liparakis/chunkis/world/tracking/save/ChunkisStoragePaths.java`

## Layout

```text
<dimension-root>/chunkis/
  global_ids.json
  regions/
    r.<regionX>.<regionZ>.cis
```

Other dimension-local files may also exist next to `regions/`, including portal metadata.

## Write Pipeline

1. `CisStorage.prepareSave(...)` encodes a `ChunkDeltaView` into raw CIS bytes.
2. `CisMapping` flushes any newly assigned block ids to `global_ids.json`.
3. `CompressionContext` compresses the raw CIS payload.
4. `RegionFile.write(...)` stores or clears the region slot.

## Read Pipeline

1. `RegionFile.read(...)` returns the compressed region payload.
2. `CompressionContext` decompresses it.
3. `CisDecoder` rebuilds a `ChunkDelta`.
4. Normal load paths self-heal corrupt entries by clearing them; migration paths can use `loadWithoutClearing(...)`.

## Current Format Notes

- Current storage version: `CisConstants.VERSION == 11`
- Compression: Zstd for disk payloads
- Empty deltas clear the region entry
- `global_ids.json` is dimension-local

## Region File Layout

`RegionFile` currently stores:

- a fixed 8192-byte header for 1024 chunk slots
- compressed chunk payload bytes
- an optional allocation metadata footer

Writes prefer:

1. in-place overwrite
2. best-fit reuse of free blocks
3. append

Compaction rewrites live payloads contiguously and swaps the file back in.

## Limitations

- CIS data is not readable by vanilla or by tools that assume Anvil region files remain authoritative.
- Mapping consistency depends on preserving the matching `global_ids.json`.

## Related Docs

- [Save Pipeline](Save-Pipeline.md)
- [Load And Restore Pipeline](Load-And-Restore-Pipeline.md)
- [Migration And Versioning](Migration-And-Versioning.md)
- [Networking And Client Sync](Networking-And-Client-Sync.md)
