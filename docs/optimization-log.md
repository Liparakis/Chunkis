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
- Check background CPU, not only games. Legcord on a call took about a core: tick p50 read 28-40 ms on code that gives 26, and two 4-pair A/Bs were unusable. Pinning fixes it: other programs on logical CPUs 14-15, the run on 0-11 (children inherit the launching shell's affinity). Unload then held within 155-167 ms across 8 runs, against 140-221 unpinned. Pinned figures are slower overall (tick p50 about 32 ms), so compare them only with each other.
- **A paired A/B has a noise floor; better in 4 of 4 is not proof.** The same code against itself, pinned, 4 pairs, off then on: tick p50 -1.7 %, tick p90 -4.0 % in 4/4, tick p99 +7.1 %, reload tick CPU +12.2 %, unload +6.0 % in 0/4, reload p99 +5.7 %, late ticks -4.4 %, server CPU -1.7 %, total CPU -0.6 %. A change inside those figures shows nothing. With a dozen rows, some land 4/4 by chance, and `on` always ran second, so an order bias is not ruled out either. Vary the order within pairs: alternating it turned the unload row from +6.0 % to -5.0 % with nothing else changed that could move it. When a change only removes work, confirm it with a timer around that work.
- Start an A/B with 2 pairs and extend only if it looks useful. `-PchunkisStressSkipSizes=true` skips the size table, about 10 s, which an A/B does not read.
- Fewer JFR samples are not a result on their own. Unload save samples fell from 85 to 49 in one pair of runs, and the A/B then showed no change in latency or tick CPU.
- Cut profiles by phase with the millisecond time in the `step` log line. `unloadChunks` frames also appear during autosaves, and samples after `DONE` are teardown, where vanilla mode's hook skips no longer apply. Both produced false findings.

## Latest unpinned comparison

The table below is the regression summary. See the [complete grouped report](benchmark-2026-11-06.md) for all 144 raw numeric fields, derived totals, and scenario settings.

On 6 November 2026, `bench.sh` ran three alternating pairs at `d43d6a82` on a Ryzen 7 5700X at 4.6 GHz with 32 GB DDR4-3600 RAM. The 64 × 64 natural-terrain scenario had two measured rounds per run. Vanilla mode still had Chunkis loaded. The run was unpinned, and background load was not recorded.

| Metric | Chunkis | Vanilla | C/V |
|:---|---:|---:|---:|
| Tick p50 | 31.0 ms | 27.3 ms | 1.14 |
| Tick p90 | 44.4 ms | 35.4 ms | 1.25 |
| Tick p99 | 195.4 ms | 314.3 ms | 0.62 |
| Late ticks | 17.3 | 15.7 | 1.11 |
| Total lateness | 1,399.3 ms | 2,031.7 ms | 0.69 |
| Unload p50 | 222.9 ms | 162.8 ms | 1.37 |
| Reload p50 | 891.5 ms | 976.5 ms | 0.91 |
| Reload p99 | 1,058.8 ms | 1,162.4 ms | 0.91 |
| Process CPU time | 52.6 s | 62.3 s | 0.84 |
| Server-thread CPU time | 11.3 s | 10.0 s | 1.14 |
| Retained heap | 874.0 MB | 1,173.4 MB | 0.74 |
| Disk during benchmark | 42.9 MB | 61.7 MB | 0.69 |

The local regression check failed on tick p90: its ratio was 12.2% worse than the saved baseline, beyond the 8% margin. All other checked rows passed; total lateness had no saved baseline entry. Treat one session as a measurement, not proof of a code regression. The disk measurement is before shutdown compaction.

## Earlier pinned comparison

From one `bench.sh` session at `c137bdbd`, pinned to logical CPUs 0-11, so every time is slower than an unpinned run and only the ratios compare. Ratios move about 10 % between sessions on identical code; vanilla's unload p50 alone read 226 ms in the previous pinned session and 144 ms in this one. Disk after shutdown was measured before the save-thread priority changes.

| Metric | Chunkis | Vanilla | Ratio | Standing |
|---|---|---|---|---|
| Disk after shutdown, round trip | 32.8 MB | 70.0 MB | 0.47 | Ahead |
| Disk after shutdown, natural flight | 50.0 MB | 100.4 MB | 0.50 | Ahead |
| Tick p99 | 166 ms | 276 ms | 0.60 | Ahead |
| Total lateness | 1,342 ms | 1,979 ms | 0.68 | Ahead |
| Disk during benchmark | 42.6 MB | 61.7 MB | 0.69 | Ahead |
| Heap | 866 MB | 1,163 MB | 0.75 | Ahead |
| Total CPU | 48.2 s | 57.0 s | 0.85 | Ahead |
| Reload p50 | 818 ms | 915 ms | 0.89 | Ahead |
| Reload p99 | 1,005 ms | 1,059 ms | 0.95 | Ahead |
| Tick p50 | 28.2 ms | 27.3 ms | 1.03 | Behind |
| Server-thread CPU | 10.2 s | 9.5 s | 1.07 | Behind |
| Tick p90 | 36.0 ms | 32.5 ms | 1.11 | Behind |
| Late tick count | 17.7 | 13.3 | 1.32 | Behind |
| Unload p50 | 206 ms | 144 ms | 1.43 | Behind |

Late ticks are a count: a tick 6 ms late and one 300 ms late weigh the same. Total lateness over two 8-round pairs is 5,985 ms for Chunkis against 6,817 ms for vanilla, a ratio of 0.88. A later 2-round `bench.sh` session gave 2,089 against 2,053 ms, 1.02, so treat it as about even. By phase, over the two pairs: unload 3,400 against 2,880, reload 1,061 against 1,014, edit 278 against 381, save 1,245 against 2,543 ms. Reports hold `latenessMillisByPhase`; the current `guard.py` checks total lateness when the saved baseline includes it.

## Kept

Judge the small results here against the noise floor in How to measure: the wake-one row, the below-normal row and the entity early-exit row of `2383a14e` sit inside it. They cost little and are not proven gains.

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
| Encode one-biome sections without repacking them | `ab59e64f` | Paired A/B, 4 pairs: tick p99 -11.3 % in all 4, reload p50 -3.6 %, total CPU -2.0 %, server CPU -1.8 %. An earlier run judged it only on unload latency, where it does nothing |
| Run save threads below normal priority | `a78ac7fe`, `adcfd7b4` | Lowest priority, 4 pairs: tick p50 -3.3 %, server CPU -3.5 %, total CPU -1.7 % in all 4, reload p99 -15.2 % in 3, unload +11.3 % in all 4. Below normal against lowest, 4 pairs: unload -6.4 %, ticks unchanged |
| Wake one save thread per submitted save | `f5e1f2d4` | Paired A/B, 4 pairs: tick p50 -1.3 % and tick p90 -2.1 % in all 4. Reload tick CPU read +14.3 % with reload latency flat |
| Return early from live entity capture for an empty chunk with an empty delta | `7210f1a3` | Timer around the step, one run each: 32-35 to 25-27 ms per unload round, clean saves about 70-120 to 57 ms. It only skips work that returned the same delta unchanged. The rest is vanilla's section lookup; the paired A/B could not see it (all rows inside the floor apart from one slow run) |
| Raise the unload backlog vanilla forces through in one tick, 2,000 to 8,192, so the tick budget spreads a mass unload | `bd142b62` | Paired A/B pinned, 4 alternating pairs, every run on better than every run off: unload lateness 3,202-4,201 to 1,937-2,213 ms (-44 %), worst tick 377-537 to 319-347 ms, unload p99 491-687 to 411-477 ms, phase 291-389 to 234-293 ms, tick p99 -17.9 %, late ticks -9.6 %, unload p50 unchanged. Authoritative worlds only, so vanilla-mode runs keep 2,000. An overloaded server has no tick budget, so it drains only past 8,192; not tested |
| Reserve a cached reload's block instructions once, from the non-air count stored with the cache entry | `b6fc0997` | Probes first: every late reload tick over 80 ms was a GC pause, and load workers allocated 4,178 MB a reload round against vanilla's 1,994, 2,460 MB of it `cache_fill` at 592 KB a chunk (one 8-byte instruction per non-air block, plus the arrays grown section by section). After: 266 KB a chunk. Paired A/B pinned, 4 alternating pairs: GC count 35-39 to 30-33, reload p50 821-857 to 761-783 ms (both in all 4 with no overlap), reload p99 -17.8 % in 4/4, total CPU -4.6 % in 4/4, late reload ticks -11.5 % in 3/4; tick rows unchanged |
| Load a cached chunk without building block instructions: the delta keeps the containers, the load gives them to vanilla, and the restore releases the delta unread | `f92156a0` | Design in `docs/design-container-backed-load.md`. A counter first: of 36,000 loads, 31,579 took cached containers, and the three fallbacks that need instructions ran 0 times. `cache_fill` 266 KB and 245 us to 0.1 KB and 1 us a chunk; `chunkis_provide` 717 to 435 us. Paired A/B pinned, 4 alternating pairs, no overlap between sides: reload p50 819-987 to 730-808 ms (-15.1 %), reload p99 1,107-1,373 to 1,006-1,075 ms (-16.9 %), late ticks 74-76 to 63-68 (-11.7 %); in 4/4: tick p99 -11.6 %, GC count -12.8 %, total CPU -8.6 %. Unload +5.9 %, inside its floor. Three of four off runs were BUSY, but the one BUSY on run still beat every off run |

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
| No palette copy in block-less snapshots | Paired A/B together with the biome path showed no unload change; not tested alone | Tested alone |
| Replay queue keyed by UUID: no scan per acknowledge, no queue copy per tick, UUID checked once | Paired A/B, 3 pairs: tick p50 -0.7 %, p90 +0.4 %, reload tick CPU +6.3 % and reload p99 +11 % in all 3 pairs. The 48 edit-phase samples are spawn work, not queue overhead | A world shows the queue itself in a profile |
| Drop the 2 ms replay budget so entities return in a burst, as in vanilla | Paired A/B, 3 pairs: edit tick mean +4.3 %, tick p90 +4.1 %, late ticks +12.5 %, and no tick spike. The extra cost is those entities ticking sooner. Budget kept: it only binds when thousands of entities return together, as this benchmark forces. Tick figures here undercount entity cost for the first 40 ticks. | Real play shows entities late |
| Keep the block fingerprint across a rebuild during load | Saves with no fingerprint fell from about 500 per round to almost none, mostly into mismatches, since those chunks had real edits. Paired A/B, 3 pairs: server CPU +6.9 % and total CPU +4.1 % in all 3, reload p50 +9.8 %. Likely more clean unloads copying sections on the server thread | Reuse is shown to outweigh that copy |
| Drop the 2 ms replay budget again, with entities chunk-ordered | Paired A/B, 3 pairs: late ticks +24 % in all 3, nothing else past noise | Real play shows entities late |
| Blame world-wide saves for the `generation_flow_does_not_claim_chunks` failure | A save forced mid-test did not claim the chunk, and the old assertion still passed, so the theory is wrong | Never |
| With the queue sorted: one chunk lookup per chunk, UUIDs checked once, `acknowledge` through an index | Paired A/B, 4 pairs: every row within 2 %, reload p50 and p99 +1.0 % | A world shows the queue itself limiting ticks |
| Two save threads instead of four | Paired A/B, 4 pairs: reload p99 +12.1 % in all 4; tick p99 -7.3 %, unload -2.8 %. Reload p99 follows how fast saves finish | Reload stops depending on save speed |
| Six save threads instead of four | Paired A/B, 4 pairs: reload p99 -4.5 %, unload +7.7 %. A trade, not a gain | Unload no longer depends on save threads |
| Blame a lock or thread wake-ups for unload getting slower with low-priority saves | Server-thread lock waits of 1 ms or more total 26 ms over all unload rounds; one wake per save did not move unload. Why unload follows save-thread speed is still unknown | The cause is measured |
| Search a section's first 16 states by reference before hashing, in the encoder | Output byte-identical, and no gain: save-thread CPU in `prepareSave` per 2,000 saves, 4 alternating pairs, 598-688 ms against 613-698 ms. JFR put those hash lookups at 17 % of save-thread samples, which was not real. An earlier A/B looked good only inside the noise floor | A timer shows the lookups cost something |
| Look up entity sections by key over the world's height instead of vanilla's tree and stream, about 7 us per chunk | Not built: entities can sit above the build limit, in sections such a range misses, and would be dropped from the save | Never |
| Blame the entity fingerprint for serializing entities twice at unload | Timer, one run: `fingerprintSavable` about 0 ms per 1,000 calls; chunks reaching it hold almost no entities. `capture` itself is 20-49 ms per 1,000 calls over 1,100-2,400 entities, and that serialize is needed. `loadStoredDelta` reads storage on the server thread, usually 2-20 ms per 1,000 calls with one round at 108 ms | Never |
| Prepare more of the load off the server thread, to beat vanilla's `convert` | Timer, one run: Chunkis's part of `convert` is about 10 us per chunk once warm, 9 of them the restore. Server-thread load work is already level with vanilla (about 208 against 220 ms in a round, entity spawning moved to the replay queue). About 40 ms a round to win at most | A timer shows a larger part |
| Cut entity replay to the time the tick has left (2 ms at most, 0.25 ms at least) | A probe showed about half the late reload ticks at 55-59 ms with replay spending its full 2-2.8 ms, but no save completion runs during reload and restores take 1-5 ms a tick. Paired A/B pinned, 4 alternating pairs: late reload ticks 32-35 to 33-40, worse in all 4; other rows inside the floor. Replay ran in those ticks without making them late | The large early reload ticks are explained first |
| Read container internals through constant `VarHandle`s instead of `Field.get` | Stage runs, 2 each, alternating: cache content check 113 and 110 against 110 and 102 us, clean save hook 16.3 and 10.1 against 10.8 and 12.1 us; repeats of the same code differ more (load-worker total 468 and 317 us). Since JDK 18 `Field.get` runs on method handles and the JIT inlines it | A timer shows the reads cost something |
| Shorten the late reload ticks of 55-80 ms | Vanilla's own tick scheduling: when the server runs a queued task while waiting for the next tick, it keeps going until the tick's end plus a full tick (`MinecraftServer.shouldKeepTicking`, `tickEndTimeNanos`), so the interval is the tick's work plus 50 ms. A probe naming those tasks found vanilla's chunk pipeline (`ServerChunkManager` lambdas, `CompletableFuture` stages) in both modes, in the same mix; Chunkis gets such ticks in more of its reload ticks (17 against 8 in one run). Reload GCs are now level: 8 against 7, 550 against 736 ms | Changing vanilla's tick-end scheduling is acceptable |
| Make entity replay cheaper | Timer per step, warm: about 60-80 ms per 2,000 replays, of which vanilla's own spawn 33-55 ms and the payload lookup 16-24 ms (each replay scans about 300 pending payloads, building a UUID string for each). About 2,000 replays a round, so replay is level with vanilla's `load_entities` (81 ms a round); the "0.1 ms each" in Open was a cold figure. The lookup fix would save about 20 ms a round, under 1 % of server CPU | Replay shows in a profile of a real world |
| Look for Chunkis code in edit-phase ticks | Server-thread JFR samples inside edit windows, both modes: 18 of 713 Chunkis samples attributed to Chunkis, all the benchmark scenario; the rest is vanilla ticking (fluids, entities, chunk holders) in the same mix as vanilla mode. The per-phase tick CPU gap is suspect: Windows reports thread CPU in 15.6 ms steps (tick CPU p50 reads 31.25 ms) | A finer CPU clock shows a gap |
| Lower the unload backlog limit to 4,096 | Paired A/B pinned, 4 pairs: unload p50 +11.6 %, lateness +11.7 % in 0/4, tick p99 +7.4 %. More unloads forced into one tick again; 8,192 stays | Never below 8,192 |
| Drop the save hook's entity capture for unloading chunks, leaving vanilla's entity save to capture them | Not built. Vanilla saves entities on its own schedule, before, with or ticks after the chunk save. After the chunk's write completes its delta leaves memory, so the entity hook would read the stored chunk from disk on the server thread and write it again, and the stale fingerprint would send most unloads with moving mobs down that path. A failed read loses the entities with only a warning. It would save about 25 ms a round of entity lookups. `movedEntitySurvivesUnloadAndReloadOnce` now covers the round trip | The entity hook can reach the delta without a disk read |
| Run both sides of a paired A/B at the same time, in two worktrees | Same code on both sides, 3 pairs: tick p90 +9.2 %, server and total CPU worse in all 3 on one side, late ticks about 60 to 190, tick p90 about 30 to 55. Contention distorts the run and cancels no noise, and a pair is only 38 % faster (214 against 346 s) | Never for latency or ticks |
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
| Defer the block capture of freshly generated chunks | The largest flight server cost, 74 of 138 save-hook samples, and the likely cause of flight's tick p99. Their blocks exist nowhere else until the first write, so an unload-then-reload before that write would lose them. Needs a design that closes that window |
| Per-section block reuse | A chunk shares one global palette and one coded stream, so it needs per-section palettes, giving up disk. It targets save threads, which do not touch ticks, for an estimated 5 % of total CPU at most |
| Storing blocks and light as separate records | Already done: the block part is separate and reused when its fingerprint matches, about 60 % of saves |

## Flight

Natural flight on the code before the save-thread priority changes, two runs each. It behaves differently from the round trip, so measure both.

| Metric | Chunkis | Vanilla | Ratio |
|---|---|---|---|
| Disk after shutdown | 50.0 MB | 100.4 MB | 0.50 |
| Total CPU | 98.4 s | 109.4 s | 0.90 |
| Tick p50 | 2.5 ms | 2.5 ms | 1.00 |
| Server-thread CPU | 12.8 s | 11.9 s | 1.07 |
| Peak heap | 2,761 MB | 2,475 MB | 1.12 |
| Tick p90 | 8.0 ms | 6.4 ms | 1.24 |
| Retained heap | 571 MB | 431 MB | 1.32 |
| Tick p99 | 16.7 ms | 11.4 ms | 1.46 |
| Late ticks | 4.5 | 2.5 | 1.80 |

Retained heap is the recent-chunk cache: with it off, 447 MB. It held 5,090 chunks and served 5,057 hits with no evictions. Every tick here is far under 50 ms.

## Release review for 4.1.0

An independent review of every change since the unofficial v4.2.0 build (`fcda8773`) found no critical or high defect; the container-only cached load was traced end to end. Fixed from it, each with a test that fails without the fix:

- `c6cd3b24`: a compaction during the unlocked part of releasing freed region slots could hand the old layout's slots back out and overwrite a live chunk (`RegionFileReleaseRaceTest` reproduced it). A layout counter now discards them.
- `a3524eaa`: if C2ME renamed its save method, the optional mixin would silently not apply while Chunkis kept blocking C2ME's region writes, so its unloaded chunks were saved nowhere. A startup check now finds the method or lets C2ME's writes through and logs an error.
- `3c738dc3`: a save skipped because the chunk could not be snapshotted still cleared vanilla's save flag, so the change was never captured; the flag is now kept. Also fixed: payload-watch tracing read a stored-blocks save view on the server thread before the worker had read its blocks, and crashed.
- `9edd44fe`: with no tick time left, unloads under the 8,192 backlog never ran; vanilla has the same stall under its 2,000. A minimum of 64 forced unloads a tick drains it (`OverloadedUnloadGameTest`). Its cost on tick p99 and lateness is not yet measured; two A/B attempts were interrupted.

Not changed: containers taken by the clean-unload branch from a chunk vanilla has not yet marked loaded (vanilla marks it in the same server-thread step that builds it, and the cache checks content before any use), and a chunk stored without light being relit on every reload (performance only).

C2ME `0.4.0-alpha.0.28` for 1.21.11: all 42 game tests pass with it loaded (Java 25, loader 0.18.3), and the startup check finds its save method.

## C2ME exploration freeze (6 November 2026)

Two live thread dumps with C2ME `0.4.0-alpha.0.28+1.21.11` showed workers constructing chunks in
`ServerAccessible.toFullChunk` blocked in Chunkis's restoration resend check. The lookup waited
for the same chunk's unfinished promotion; the server then waited for chunk futures too.

`C2meServerAccessibleMixin` now wraps C2ME's original `toFullChunk` call with a thread-local scope
identifying the world and coordinates under construction. Chunkis skips the resend check only
for that unpublished target. Normal C2ME publication sends the initial restored chunk packet.
The original constructor, worker scheduling, restoration, lighting and storage behavior remain
in place. There is no queued resend or new restoration API.

The scope restores its previous value in `finally`, including nested calls and exceptions. Unit
tests cover world/coordinate matching, thread isolation, nesting, result identity and exception
propagation. `C2mePromotionGameTest` checks the hook against C2ME's actual conversion method when
C2ME is installed. The hook requires its target method; renamed C2ME internals must be retested.

Validation: the full build passed, including 43 game tests; all 43 passed again with C2ME `.28`
on Java 25, including the actual-hook test. One quick stress pair (576 chunks, one measured round
per side) completed with C2ME loaded. There was no saved quick baseline, so it provides no
before/after regression verdict. It also showed worse total lateness for Chunkis than vanilla
(192 ms versus 27 ms); this single pair cannot attribute that difference to the hook. The reporter's
subsequent IntelliJ exploration retest appeared successful.

## Open

- Tick p50 and p90 are partly the price of spreading. In the save phase the mean tick is lower than vanilla's (42-45.5 against 45-48.7 ms) and the p50 higher (34-36 against 27-32 ms): vanilla does its saves in a few large ticks, Chunkis spreads them. The dirty capture costs about 36 us a chunk (copy 4-5 us of it) against vanilla's serialize at about 78 us, so the zero-copy idea would win about 5 us a chunk. Save completions cost 0.8 ms a save tick, about 8 us each. Chunkis also spends less on GC: 538-656 against 834-904 ms a run.
- Late ticks: the extra ones are in the reload phase, about 10 against 5 a run. Probed per tick: no save completion runs then, restores take 1-5 ms a tick, replay up to 2.8 ms, and fitting replay to the tick made it worse. The largest late reload ticks (90-130 ms) are the first of each reload, in work the probe does not cover: vanilla's chunk handling, light, ticket updates. Probe those next.
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
- A JFR sample share is a lead, not a cost. Time the code before acting on one: see the encoder row in Tried and dropped.
- JFR samples a few threads per cycle, so a server thread among many busy threads is under-sampled compared with vanilla. Sample counts across modes mislead when other threads are busy; stage timers do not.
- Allocation looks large only with warm-up included: 37.8 against 25.9 GB, but 18.3 against 16.7 GB in the measured rounds.
- Measure with stage timers as well as JFR: it hid both `convert` and the clean-save copy.
- Why a chunk's light keeps changing between reloads, when no block changed.
