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

Chunkis/vanilla ratios from three `bench.sh` sessions (9 runs of each mode) with the recent-chunk cache handing containers to the load. The two later sessions also carried changes that were then dropped and shown to have no effect. Report the range, not one session: this same code gave unload 1.33 and 1.71. Disk after shutdown was measured on `63349320`.

| Metric | Ratio, three sessions | Standing |
|---|---|---|
| Disk, round trip | 0.51 | Ahead |
| Disk, natural flight | 0.50 | Ahead |
| Heap | 0.74, 0.75, 0.75 | Ahead |
| Tick p99 | 0.83, 0.96, 0.81 | Ahead |
| Reload p50 | 0.87, 0.99, 0.98 | Ahead |
| Total CPU | 0.93, 0.99, 1.05 | Even |
| Reload p99 | 1.00, 1.12, 1.21 | Behind |
| Tick p50 | 1.03, 1.12, 1.11 | Behind |
| Server-thread CPU | 1.05, 1.13, 1.17 | Behind |
| Tick p90 | 1.06, 1.19, 1.21 | Behind |
| Late ticks | 1.30, 1.44, 1.56 | Behind |
| Unload p50 | 1.33, 1.34, 1.71 | Behind |

## Kept

| Change | Commit | Result |
|---|---|---|
| Stop the dirty tracker keeping saved deltas | `1340823a` | Retained heap on a 16,384-block flight 900 to 453 MB (vanilla 366); it no longer grows with distance |
| Do not count loading's light toggles as a change | `37f128e3` | On test terrain: identical writes 12,479 to 255, CPU 35 to 24 s |
| Compact at shutdown with 10 % and 64 KiB free | `5c44df1b` | Disk 46.5 to 34.6 MB and 62.6 to 52.5 MB; closing still about 1 s |
| Keep the non-block capture when only entities changed | `89a1d531` | Unload p50 1.22 to 1.10, measured together with the next row against the old baseline |
| Share replaced-whole metadata in save snapshots | `24b08ca0` | Small; about 5 of 884 server samples |
| Tick scenarios from the server-tick hook | `766dd5a5` | Benchmark only: removed a 72,000-entry scan, tick p50 1.69 to 0.86 ms in both modes |
| Give cached containers to the load instead of copying them | Uncommitted | Chunkis reload p50 1,036-1,106 ms before, 840-1,008 ms in all 9 runs after, against vanilla near 1,000; heap 969 to 875 MB |

## Tried and dropped

| Idea | What happened | Do not retry unless |
|---|---|---|
| Defer the region footer to flush (shorter locks) | Failed `bench.sh`: tick p90 +9.1 %, late ticks +30.1 %, unload 1.29 to 1.38. Flush synced twice every time, an untested suspect. | The double sync is removed and it is re-measured |
| Use stored heightmaps for dirty deltas | No effect: 9,800 rebuilds either way. Rebuilds are mostly unfinished chunks, which vanilla rebuilds too | Never for this reason |
| Skip copying sections for the recent-chunk cache | 1 sample in the profile | Never |
| Lower the recent-chunk cache budget | 64 and 256 MB identical: peak 13 MB, no evictions | Heap shows it binding |
| Skip saves where nothing changed | Natural terrain: only 19 % of writes identical. 89 % of light-only rewrites change light values | Light changes between reloads are explained |
| Move restore off the server thread | Convert is under 2 % of server samples; load work on the server thread is already below vanilla's | Never for server CPU |
| Reuse a pre-generated world in benchmarks | Would skew results: leg 0 fills caches leg 1 depends on | Never |
| Run leg 0 unpaced in timing runs | Leg 1 cache hits 883 to 17, and a 300-480 ms first tick | Counting runs only (`-PchunkisStressFastGeneration=true`) |
| Join-time prefetch | Never served a load; removed in `af6db4db` | Never |
| Fast path for one-biome sections, and no palette copy in block-less snapshots | Paired A/B, 4 pairs of 8 rounds: unload p50 162 to 155 ms with differences both ways, unload tick CPU 30.0 to 30.3 ms | Unload save work is shown to limit latency |
| Skip vanilla's entity serialization when its write would be dropped | Entity unload samples 17 to 16: the rest is Chunkis's own capture. No CPU gain. `generation_flow_does_not_claim_chunks` failed in 1 of 7 suite runs with it, cause unknown, and it stops a vanilla write | A cost is shown and that test failure is explained |

## Rejected without trying

| Idea | Why |
|---|---|
| Asynchronous entity saves for unloaded chunks | About 100 ms per two rounds, against a placeholder in the tracker that loading, saving, the shutdown sweep and entity replay would all have to handle. Risks rolled-back or duplicated entities |
| Writing region payloads outside the lock | Needs space reservation and protection against compaction swapping the file mid-write; the waits are small |
| Regenerating unedited chunks from the seed | Worldgen on every load, and it breaks when generation changes between versions |
| Lean proofs | Prove a model, not the Java; no bearing on performance |

## Open

- Net Chunkis cost on the server thread is about 5 % in a clean profile of the measured rounds (663 against 604 samples): save hook 28 against vanilla's serialize 20, convert +16, replay +5, entity capture +2. Capturing light and biomes more cheaply cannot close the gap on its own.
- Why a chunk's light keeps changing between reloads, when no block changed.
- What drives tick p50 and p90: most ticks are in the edit phase, where both modes do nearly the same work.
- Unload p50: not limited by save work, as the A/B showed, and the cause is unknown. Most unload ticks take 0.1 ms and nearly all the time is in one or two ticks of 300-490 ms. In the measured phases, Chunkis had 85-88 server samples against vanilla's 64-67, and chunk level updates were equal. Chunkis's extra samples were in `unloadChunks` (about +12) and entity capture (about +13, including per-chunk `getHeld` and snapshot-safety lookups even for empty entity lists).
- Late ticks are the other clear gap, and no cause has been traced.
- `generation_flow_does_not_claim_chunks` failed once under a loaded `gradlew build`, with the entity skip in the tree. Passed in the 6 other runs. Any save of a settled chunk claims it, so a save during the test could explain it. Not proven.
