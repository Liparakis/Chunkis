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

Chunkis/vanilla ratios from `bench.sh`. Session 4 is the current code. Sessions 1-3 are from before the heightmap fix, with the recent-chunk cache handing containers to the load; 2 and 3 also carried changes that were later dropped. Report the range, not one session: unload was 1.33 and 1.71 on near-identical code. Disk after shutdown was measured on `63349320`.

| Metric | Session 1 | Session 2 | Session 3 | Session 4 | Standing |
|---|---|---|---|---|---|
| Disk after shutdown, round trip | | | | 0.51 | Ahead |
| Disk after shutdown, natural flight | | | | 0.50 | Ahead |
| Disk during benchmark | 0.70 | 0.70 | 0.69 | 0.70 | Ahead |
| Heap | 0.74 | 0.75 | 0.75 | 0.75 | Ahead |
| Tick p99 | 0.83 | 0.96 | 0.81 | 0.90 | Ahead |
| Reload p50 | 0.87 | 0.99 | 0.98 | 1.02 | Even |
| Total CPU | 0.93 | 0.99 | 1.05 | 0.98 | Even |
| Tick p50 | 1.03 | 1.12 | 1.11 | 1.07 | Behind |
| Reload p99 | 1.00 | 1.12 | 1.21 | 1.11 | Behind |
| Server-thread CPU | 1.05 | 1.13 | 1.17 | 1.14 | Behind |
| Tick p90 | 1.06 | 1.19 | 1.21 | 1.17 | Behind |
| Unload p50 | 1.33 | 1.34 | 1.71 | 1.27 | Behind |
| Late ticks | 1.30 | 1.44 | 1.56 | 1.45 | Behind |

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

## Tried and dropped

| Idea | What happened | Do not retry unless |
|---|---|---|
| Defer the region footer to flush (shorter locks) | Failed `bench.sh`: tick p90 +9.1 %, late ticks +30.1 %, unload 1.29 to 1.38. Flush synced twice every time, an untested suspect. | The double sync is removed and it is re-measured |
| Use stored heightmaps for dirty deltas | No effect: 9,800 rebuilds either way. The reason given then, that vanilla rebuilds them too, is wrong: on reload `populateHeightmaps` is 29 server samples under Chunkis and 0 under vanilla (see Open) | The rebuilds are counted by cause first |
| Skip copying sections for the recent-chunk cache | 1 sample in the profile | Never |
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
| Mark heightmaps current in `ChunkRestorer` after a release | Rebuilds unchanged at about 600 | The remaining rebuilds are explained |
| Skip vanilla's entity serialization when its write would be dropped | Entity unload samples 17 to 16: the rest is Chunkis's own capture. No CPU gain. `generation_flow_does_not_claim_chunks` failed in 1 of 7 suite runs with it, cause unknown, and it stops a vanilla write | A cost is shown and that test failure is explained |

## Rejected without trying

| Idea | Why |
|---|---|
| Asynchronous entity saves for unloaded chunks | About 100 ms per two rounds, against a placeholder in the tracker that loading, saving, the shutdown sweep and entity replay would all have to handle. Risks rolled-back or duplicated entities |
| Writing region payloads outside the lock | Needs space reservation and protection against compaction swapping the file mid-write; the waits are small |
| Regenerating unedited chunks from the seed | Worldgen on every load, and it breaks when generation changes between versions |
| Lean proofs | Prove a model, not the Java; no bearing on performance |
| Per-section block reuse | A chunk shares one global palette and one coded stream, so it needs per-section palettes, giving up disk. It targets save threads, which do not touch ticks, for an estimated 5 % of total CPU at most |
| Storing blocks and light as separate records | Already done: the block part is separate and reused when its fingerprint matches, about 60 % of saves |

## Open

- Late ticks: the whole gap is in the reload phase. Three runs: 11-14 against 5 per run there, other phases level. Reports now split them (`lateTicksByPhase`).
- `SerializedChunk.convert` on the server thread: 1,392 ms against vanilla's 370 ms over a 4-round run; `full_chunk` with `load_entities` gives back about 440 ms. JFR hid this: use the stage profiler. After the heightmap fix `populateHeightmaps` is still 25 of 53 convert samples, from about 600 rebuilds per run. In a two-round probe those chunks hold full block instructions, come from `rebuildFromStoredBlocks` and have a non-current stamp. Why is unknown.
- Unload p50: the phase is one giant tick that drops about 2,975 of 4,096 chunks, then idle ticks while the rest wait on tickets. That tick is about 283 ms against vanilla's 125 ms, about 90 against 42 us per chunk. Cutting save samples did not move it, so what fills that tick still needs stage timing per phase.
- Save hook on the server thread: 1,767 ms against vanilla's 1,359 ms. 367 ms of it is clean saves, which vanilla does not make; those keep blocks for fast reloads.
- About 35 % of saves re-encode blocks. They are about 60 % of save-thread samples, and a guard's `scan` is another 19 %.
- Entities reappear late after a bulk reload: about 2,100 by tick 40 of the edit phase, against vanilla's 2,300 by tick 3-4. Spawn work on a 2 ms budget (see the replay rows above).
- Vanilla's `tickEntity` runs longer under Chunkis in the save phase (276 against 211 samples) with equal entity counts. One profile pair. `getChunkFuture` under entity ticks (25 against 0) is a lead.
- Ruled out for tick p50 and p90: duplicate or extra entities (0 duplicates, counts match), slower block reads (11 % of entity-tick leaves in both modes), lock waits on the server thread, and GC (Chunkis pauses less).
- `generation_flow_does_not_claim_chunks` failed once under a loaded `gradlew build`, with the entity skip in the tree. Passed in every run since. Any save of a settled chunk claims it, so a save during the test could explain it. Not proven.
- Why a chunk's light keeps changing between reloads, when no block changed.
