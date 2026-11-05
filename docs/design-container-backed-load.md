# Design: loading a cached chunk without block instructions

Status: built and measured. Two changes from the proposal below, both forced by game tests:

- The fallback builds the instructions from the attached containers, never from storage. A test reloaded a chunk while its entry was being written again, and the re-decode refused a load the old path handled. The containers are the blocks this load decoded and gave vanilla, so instructions built from them always match the chunk.
- The payload-watch tracer (debug only) reads the attached containers when a delta has none, or its decode and restore checks fail.

## Problem

A reload of a recently unloaded chunk takes its blocks from `RecentChunkBlocks`: section containers whose content the cache has just verified against the stored entry's block part hash. The containers go to vanilla unchanged. But `blocksFor` also fills the delta with one 8-byte instruction per non-air block (`cache_fill`). A natural chunk holds about 60,000 non-air blocks, so a fill allocates about 590 KB per chunk.

Measured, pinned, one round of 4,096 chunk reloads:

| | Chunkis | Vanilla |
|---|---|---|
| Load-worker allocation per reload round | 4,178 MB, 2,460 MB of it `cache_fill` | 1,994 MB |
| GCs during reload, 7 rounds | 15-18 | 7-11 |
| Late reload ticks over 80 ms | 11, every one a GC | 6, every one a GC |

The restore releases those instructions straight after it finishes (`ChunkRestorer` `releaseAfterFinish`), so they live a few ticks and only feed the young generation. Without them, the load workers would allocate less than vanilla.

## Why not "start released"

A released delta (`isBlockPayloadReleased`) means the blocks live in the stored entry. That is true here, but the load path treats a released delta as unusable on purpose:

- `ChunkRestorer.java:163` throws when asked to restore one.
- `ChunkSerializerMixin.java:575` drops a released hand-off and decodes the entry again, so the cost comes back.
- `ChunkSerializerMixin.java:743` (`chunkis$isDeltaAbsent`) treats it as absent and skips the restore, losing block entities and metadata.
- `ChunkRestorer.java:172` (`hasInvalidBlockEntityOnlyPayloadWithoutBase`) skips the restore for any chunk with block entities and zero instructions.
- The two fallbacks that rebuild blocks from instructions would silently produce air:
  - `PreloadedBlockSections.embed` falls back to `buildSectionIndices`, which with no instructions embeds nothing and still reports "preloaded".
  - `ChunkRestorer` with `consume == false` calls `clearChunkToAir` and replays nothing.

Reusing that state would mean weakening checks that exist to stop real data loss. This design leaves them alone.

## Proposal: a container-backed delta, with re-decode as the only fallback

1. **Opt in for chunk loads only.** `blocksFor` serves every `storage.load*`. Those include:
   - `StoredBlocksSnapshotView.readStoredBlocks` (saves of released deltas)
   - `SyntheticLoadNbtProvider.rebuildFromStoredBlocks`
   - `MigrationChunkValidator`
   - debug commands

   They all need instructions and keep today's full fill. Only the cold chunk-load call asks for the new form.

2. **A new state, not `released`.** The delta gets the attached `LoadedSections`, zero instructions and a flag, `blocksInLoadedSections`. Every existing released check stays exactly as it is.

3. **Every reader of instructions either does not run, or re-decodes first.** One helper, `ensureInstructions(storage, pos)`, decodes the entry with instructions into the delta and clears the flag. It is used wherever the code would otherwise read instructions:
   - `PreloadedBlockSections.embed`: if `takeLoadedBlocks()` is null or `embedLoadedSections` fails, call it before `buildSectionIndices`. This runs on the load worker.
   - `ChunkRestorer`: if `consume` is false for a flagged delta, call it before `clearChunkToAir` and the replay. This runs on the server thread, slow but correct, and should be rare.
   - `ChunkRestorer.java:172`: a flagged delta has its blocks, so it must not count as "block entities only". The check reads the flag.
   - `CisSnapshotCapture`, `SaveDeltaGuard`, `DeltaPersistenceGuard` and the ownership checks: the restore releases the delta before any save, so a flagged delta never reaches them. An assertion in each makes that guarantee checkable, not assumed.

4. **Any change voids the shortcut.** A mutation between fill and restore changes the stamp, so `takeLoadedBlocks` returns null, and step 3 re-decodes. Proto chunks are not edited in that window today, so this is a guard, not a hot path.

5. **The end state is unchanged.** After the restore, the delta is released exactly as today, so everything after the restore sees the same state as now.

## Failure modes and their handling

| Case | Today | With this design |
|---|---|---|
| Cache content check fails | Full decode | Full decode, unchanged |
| Containers cannot be embedded | Rebuild from instructions | Re-decode, then rebuild |
| Vanilla does not take every prebuilt container (`pending > 0`) | Clear and replay instructions | Re-decode, then clear and replay |
| Delta mutated before restore | Rebuild from instructions | Re-decode, then rebuild |
| A non-load caller gets a cache hit | Full fill | Full fill, unchanged (opt-in) |
| Shutdown or crash between fill and restore | Delta discarded, entry on disk | Same: the delta was never written |

No path writes a flagged delta to storage, so no stored data can change.

## Tests

- Game tests:
  - A cached reload has identical blocks, block entities and light, against a full decode of the same chunk.
  - Forced fallbacks each give the right blocks: an embed failure, `pending > 0` and a mutation before restore.
  - A chunk with block entities and no other changes, reloaded from the cache, keeps them. This covers the `:172` path.
  - `staleWriteToAnUnloadedChunkNeverReachesItsReload` still passes.
- The assertions in step 3 run in the game tests, so a flagged delta reaching a save path fails loudly.
- Measurement: the stage profiler's `cache_fill` allocation, GC count and late reload ticks in a pinned A/B.

## Expected gain, to be measured

- About 2.4 GB less allocation per reload round, and about half the reload GCs. This targets the late tick count (1.43).
- About 300 us of load-worker CPU per chunk, about 40 % of `chunkis_provide`. This targets reload p50 and p99 (1.12, 1.16).

## Risks

- The fallbacks are what keep this safe, so a missed reader is the main risk. The research above covers the readers found in the code. Profile and gametest mixins around `blocksFor` and `PreloadedBlockSections` are not yet checked.
- A re-decode on the server thread in the restore fallback costs about 600 us. It must stay rare. The A/B would show it as more late ticks if not.
- Like all load-path work, this touches the format's reader side but not the format. CIS v12 is unchanged.
