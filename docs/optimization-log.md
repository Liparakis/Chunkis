# Optimization log

What has been tried to beat vanilla, what it did, and why it was kept or dropped. Read this before proposing an optimization, and add to it after every experiment, including the ones that failed.

The goal is to beat vanilla on every metric, without weakening data safety.

## How to measure

- Compare with `tools/stress/bench.sh`: 3 Chunkis and 3 vanilla runs, natural terrain. Ratios are Chunkis/vanilla from the same session. Lower is better for Chunkis.
- Use `-PchunkisStressNatural=true` for the flight benchmark. Without it, it flies over nearly empty test terrain, and disk figures mean nothing.
- Disk after shutdown is not what `bench.sh` reports: it measures before shutdown compaction.
- Close games first. Tick p50, p90 and late ticks move between sessions on identical code (late ticks 1.04 to 1.40), so one session cannot show a small change. A single stage or JFR run cannot either: convert ranged from 56 to 120 us per chunk on near-identical code.
- JFR execution samples miss blocked threads. Check monitor events too.
- Profile the measured rounds only. Most of the expensive save capture is warm-up.
- Vanilla mode runs with Chunkis loaded. Since `63349320` it skips the base capture, save hook and entity capture, but block-edit hooks still run. Baselines from before that commit are not comparable.
- A probe must not change what it measures. The write counter once made deletes fail, which produced a false "chunks stored twice" finding.
- For one metric, use a paired A/B: the patch off and on, alternating, with `-PchunkisStressRounds=8`. `bench.sh` runs vary about 10 % within a session, so three runs cannot see a small change. Session ratios can move through vanilla alone: unload went 1.34 to 1.71 while Chunkis stayed flat.
- Fewer JFR samples are not a result on their own. Unload save samples fell from 85 to 49 in one pair of runs, and the A/B then showed no change in latency or tick CPU.
- Cut profiles by phase with the millisecond time in the `step` log line. `unloadChunks` frames also appear during autosaves, and samples after `DONE` are teardown, where vanilla mode's hook skips no longer apply. Both produced false findings.

## Current standing

From one `bench.sh` session on the current code, the latest of several. Ratios move about 10 % between sessions on identical code, so a paired A/B decides a change, not this table. Disk after shutdown was measured on `63349320`.

| Metric | Chunkis | Vanilla | Ratio | Standing |
|---|---|---|---|---|
| Disk after shutdown, natural flight | 52.5 MB | 104.8 MB | 0.50 | Ahead |
| Disk after shutdown, round trip | 34.6 MB | 68.1 MB | 0.51 | Ahead |
| Disk during benchmark | 42.6 MB | 61.8 MB | 0.69 | Ahead |
| Heap | 872 MB | 1,189 MB | 0.73 | Ahead |
| Tick p99 | 264 ms | 324 ms | 0.81 | Ahead |
| Total lateness | 1,979 ms | 2,115 ms | 0.94 | Ahead |
| Total CPU | 61.3 s | 64.5 s | 0.95 | Ahead |
| Reload p50 | 984 ms | 998 ms | 0.99 | Even |
| Tick p50 | 30.4 ms | 29.5 ms | 1.03 | Even |
| Server-thread CPU | 11.2 s | 10.7 s | 1.05 | Behind |
| Tick p90 | 40.4 ms | 36.9 ms | 1.09 | Behind |
| Reload p99 | 1,230 ms | 1,104 ms | 1.11 | Behind |
| Unload p50 | 236 ms | 197 ms | 1.20 | Behind |
| Late tick count | 20.0 | 14.7 | 1.36 | Behind |

Late ticks are a count: a tick 6 ms late and one 300 ms late weigh the same. Total lateness over two 8-round pairs is 5,985 ms for Chunkis against 6,817 ms for vanilla, a ratio of 0.88. A later 2-round `bench.sh` session gave 2,089 against 2,053 ms, 1.02, so treat it as about even. By phase, over the two pairs: unload 3,400 against 2,880, reload 1,061 against 1,014, edit 278 against 381, save 1,245 against 2,543 ms. Reports now hold `latenessMillisByPhase`; `guard.py` does not check it yet.

## Kept

| Change | Commit | Result |
|---|---|---|
| Stop the dirty tracker keeping saved deltas | `1340823a` | Retained heap on a 16,384-block flight 900 to 453 MB (vanilla 366); it no longer grows with distance |
| Do not count loading's light toggles as a change | `37f128e3` | On test terrain: identical writes 12,479 to 255, CPU 35 to 24 s |
| Compact at shutdown with 10 % and 64 KiB free | `5c44df1b` | Disk 46.5 to 34.6 MB and 62.6 to 52.5 MB; closing still about 1 s |
| Keep the non-block capture when only entities changed | `89a1d531` | Unload p50 1.22 to 1.10, measured together with the next row against the old baseline |
| Share replaced-whole metadata in save snapshots | `24b08ca0` | Small; about 5 of 884 server samples |
| Tick scenarios from the server-tick hook | `766dd5a5` | Benchmark only: removed a 72,000-entry scan, tick p50 1.69 to 0.86 ms in both modes |
| Give cached containers to the load instead of copying them | `78764234` | Chunkis reload p50 1,036-1,106 ms before, 840-1,008 ms in all 9 runs after, against vanilla near 1,000; heap 969 to 875 MB |
| Keep stored heightmaps for a delta that is dirty only because its save is in flight | `0f9789c3` | Full-chunk rebuilds about 1,600 to 600 per run. Paired A/B, 3 pairs: total CPU -3.2 % and tick p99 -8.2 % in all 3, reload p50 -3.9 % and p99 -5.0 % in 2 of 3. A block-edit stamp decides, so a real edit still rebuilds |
| Keep captured heightmaps current across a block release | `1e78ce7b` | Rebuilds about 600 to 1 per run. `convert` 1,392 to 771 ms, JFR samples 53 to 15 against vanilla's 10. Paired A/B, 3 pairs: reload p50 -8.7 % and p99 -5.9 % in all 3; reload tick CPU +7.8 %, tick p99 +6.4 % |
| Take a clean-unloading chunk's containers for the recent-chunk cache instead of copying them | `112eade1` | Paired A/B, 3 pairs, better in all 3: unload p50 -11.3 %, reload p99 -12.1 %, tick p90 -8.6 %, tick p99 -7.2 %, late ticks -12.0 %. An earlier JFR run showed this copy as 1 sample and it was wrongly written off. The cache's content check protects it; `staleWriteToAnUnloadedChunkNeverReachesItsReload` fails without that check |
| Return early from entity capture for an empty list when the delta holds no entities | `2383a14e` | Paired A/B, 3 pairs: server CPU -2.1 %, tick p90 -1.7 % in all 3. Small, and nothing got worse |
| Skip vanilla's entity serialization when the region guard would drop its write | `2383a14e` | Paired A/B, 3 pairs: late ticks -9.1 % and tick p50 -1.3 % in all 3, reload p99 -8.3 % in 2 of 3. An earlier sample count (17 to 16) wrongly dropped it. No data lost: vanilla's write never reached disk. Vanilla's write stays when either guard could let it through |
| Replay entities in chunk order: a queue sorted by chunk | `71c20adf` | Paired A/B, 4 pairs, better in 3: tick p50 -7.3 %, tick p90 -9.3 %, tick p99 -8.6 %, late ticks -20.9 %, unload p50 -8.3 %, server CPU -5.5 %. The hash-ordered queue left vanilla's entity list in random order, so entities ticked across the world, missing the small chunk cache and memory locality. Sorting the queue every tick cost reload tick CPU +14.6 %; a sorted map does not |

## Tried and dropped

| Idea | What happened | Do not retry unless |
|---|---|---|
| Defer the region footer to flush (shorter locks) | Failed `bench.sh`: tick p90 +9.1 %, late ticks +30.1 %, unload 1.29 to 1.38. Flush synced twice every time, an untested suspect. | The double sync is removed and it is re-measured |
| Use stored heightmaps for dirty deltas | No effect: 9,800 rebuilds either way. The reason given then, that vanilla rebuilds them too, is wrong: on reload `populateHeightmaps` is 29 server samples under Chunkis and 0 under vanilla (see Open) | The rebuilds are counted by cause first |
| Lower the recent-chunk cache budget | 64 and 256 MB identical: peak 13 MB, no evictions | Heap shows it binding |
| Skip saves where nothing changed | Natural terrain: only 19 % of writes identical. 89 % of light-only rewrites change light values | Light changes between reloads are explained |
| Move restore off the server thread | Convert is under 2 % of server samples; load work on the server thread is already below vanilla's | Never for server CPU |
| Reuse a pre-generated world in benchmarks | Would skew results: leg 0 fills caches leg 1 depends on | Never |
| Run leg 0 unpaced in timing runs | Leg 1 cache hits 883 to 17, and a 300-480 ms first tick | Counting runs only (`-PchunkisStressFastGeneration=true`) |
| Join-time prefetch | Never served a load; removed in `af6db4db` | Never |
| Fast path for one-biome sections, and no palette copy in block-less snapshots | Paired A/B, 4 pairs of 8 rounds: unload p50 162 to 155 ms with differences both ways, unload tick CPU 30.0 to 30.3 ms | Unload save work is shown to limit latency |
| Replay queue keyed by UUID: no scan per acknowledge, no queue copy per tick, UUID checked once | Paired A/B, 3 pairs: tick p50 -0.7 %, p90 +0.4 %, reload tick CPU +6.3 % and reload p99 +11 % in all 3 pairs. The 48 edit-phase samples are spawn work, not queue overhead | A world shows the queue itself in a profile |
| Drop the 2 ms replay budget so entities return in a burst, as in vanilla | Paired A/B, 3 pairs: edit tick mean +4.3 %, tick p90 +4.1 %, late ticks +12.5 %, and no tick spike. The extra cost is those entities ticking sooner. Budget kept: it only binds when thousands of entities return together, as this benchmark forces. Tick figures here undercount entity cost for the first 40 ticks. | Real play shows entities late |
| Keep the block fingerprint across a rebuild during load | Saves with no fingerprint fell from about 500 per round to almost none, mostly into mismatches, since those chunks had real edits. Paired A/B, 3 pairs: server CPU +6.9 % and total CPU +4.1 % in all 3, reload p50 +9.8 %. Likely more clean unloads copying sections on the server thread | Reuse is shown to outweigh that copy |
| Drop the 2 ms replay budget again, with entities chunk-ordered | Paired A/B, 3 pairs: late ticks +24 % in all 3, nothing else past noise | Real play shows entities late |
| Blame world-wide saves for the `generation_flow_does_not_claim_chunks` failure | A save forced mid-test did not claim the chunk, and the old assertion still passed, so the theory is wrong | Never |
| Mark heightmaps current in `ChunkRestorer` after a release | Rebuilds unchanged at about 600. The real cause was the release itself moving the stamp | Never |

## Rejected without trying

| Idea | Why |
|---|---|
| Asynchronous entity saves for unloaded chunks | About 100 ms per two rounds, against a placeholder in the tracker that loading, saving, the shutdown sweep and entity replay would all have to handle. Risks rolled-back or duplicated entities |
| Writing region payloads outside the lock | Needs space reservation and protection against compaction swapping the file mid-write; the waits are small |
| Regenerating unedited chunks from the seed | Worldgen on every load, and it breaks when generation changes between versions |
| Lean proofs | Prove a model, not the Java; no bearing on performance |
| Cutting dirty-save cost or allocation | Dirty saves already beat vanilla's serialize on the server thread: 1,284 against 1,359 ms over a run, and Chunkis pauses less for GC |
| Replay entities along a Z-order curve instead of row by row | Over a 64 by 64 area both walks total 8,064 chunks, and Z-order makes 511 jumps over 2 chunks against row order's 63, so row order is already the better walk |
| Skip `cache_fill` on a clean cached load | The fill is already bulk, so only skipping it helps, and it is at most about 3 % of total CPU (1,722 ms of about 54 s) on workers, not ticks. The restore refuses a released delta on purpose, because an empty delta clears the chunk, and `consume` can fall back to the instructions after the containers went to vanilla. Lazy filling means guarding every reader of the instructions. A missed reader wipes a chunk |
| Per-section block reuse | A chunk shares one global palette and one coded stream, so it needs per-section palettes, giving up disk. It targets save threads, which do not touch ticks, for an estimated 5 % of total CPU at most |
| Storing blocks and light as separate records | Already done: the block part is separate and reused when its fingerprint matches, about 60 % of saves |

## Open

- Late tick count: most of the extra count is in the reload phase, 11-14 against 5 per run, but those are small overruns (see Current standing). Decide whether `guard.py` should judge total lateness instead.
- `SerializedChunk.convert` on the server thread is now 771 ms against vanilla's 370 ms over a 4-round run; `full_chunk` with `load_entities` gives back about 440 ms, leaving about 180 ms. Use the stage profiler: JFR hid this gap.
- Unload p50: the phase is one giant tick that drops about 2,975 of 4,096 chunks, then idle ticks while the rest wait on tickets. By stage per round, before the container handoff: dirty saves 41-80 ms against vanilla's serialize 37-67 ms, clean saves 71-78 ms, entity capture 24-74 ms. Entity capture is the largest part left.
- Unload stages per round on the current code, one clean run, against vanilla's serialize 35-64 ms: dirty saves 49-185 ms, clean saves 62-100 ms, entity capture 27-68 ms. Dirty saves were 41-80 ms in the run before the handoff, and clean saves did not fall with it, though its A/B improved unload. Not explained.
- About 35 % of saves re-encode blocks. They are about 60 % of save-thread samples, and a guard's `scan` is another 19 %.
- Entities reappear late after a bulk reload: about 2,100 by tick 40 of the edit phase, against vanilla's 2,300 by tick 3-4. Spawn work on a 2 ms budget (see the replay rows above).
- Re-profile `tickEntity` now that replay is chunk-ordered. It ran longer under Chunkis with equal entity counts; order explains at least part of it. Entities also load by chunk completion order, not spatial order, in both modes.
- Ruled out for tick p50 and p90: duplicate or extra entities (0 duplicates, counts match), slower block reads (11 % of entity-tick leaves in both modes), lock waits on the server thread, and GC (Chunkis pauses less).
- `generation_flow_does_not_claim_chunks` failed once under a loaded `gradlew build` with the entity skip in the tree: 1 of 13 suite runs with it, 0 without. Any save of a settled chunk claims it, so a save during the test could explain it. Not proven.
- Look at each run before believing a session. One Chunkis run started straight after a build, with a status check during it, and gave 156 late ticks against 23 and 39 for the next two, which flagged four rows on unchanged code. A re-run on a settled machine passed.
- A paired A/B can be split by the machine: runs fell into a fast band (about 27 ms) and a slow band (about 34 ms). Check bands before reading a pair.
- Reload workers, one clean stage run over 4,393 provides: `chunkis_provide` 889 us each, of which `cache_fill` 405, `cache_lookup` 167, `decode_without_blocks` 91, `embed` 72, `io` 30, `decompress` 25. `parse` is 543 against vanilla's 475 us. `cache_fill` turns cached containers into delta instructions the load also receives as containers; see Rejected without trying. Vanilla's read time is not in this stage data.
- Measure with stage timers as well as JFR: it hid both `convert` and the clean-save copy.
- Why a chunk's light keeps changing between reloads, when no block changed.
