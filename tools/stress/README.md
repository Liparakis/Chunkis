# Stress benchmark

The stress game test (`fabric/src/gametest/.../stress`) loads a fixed area of natural terrain, then repeatedly unloads it, reloads it, edits 10% of its chunks and saves. It reports tick times, chunk load and unload latency, CPU, heap and disk use. With `-PchunkisStressVanilla=true` it runs the same scenario with vanilla storage, which gives the baseline Chunkis is measured against.

## Checking a change for regressions

```bash
tools/stress/bench.sh            # 3 Chunkis + 3 vanilla runs, about 20 minutes
tools/stress/bench.sh --quick    # small area and one round, a few minutes; large regressions only
```

`bench.sh` alternates Chunkis and vanilla runs, so a machine that slows down partway through a session affects both sides. Runs go to `benchmarks/runs/<commit>-<profile>/`. It then runs `guard.py`, which averages each side and compares the result with the saved baseline. It exits with status 1 when a metric got worse than its margin.

- Timing metrics are compared as Chunkis/vanilla ratios from the same session, which cancels most background load.
- Heap and disk are compared as Chunkis's own values, which barely vary between runs.
- Margins are set in `METRICS` in `guard.py`. They are wider for tails and late-tick counts, which are noisier.

To store a baseline, run the benchmark on a known-good commit:

```bash
tools/stress/bench.sh --save-baseline
```

The baseline is `benchmarks/baseline-<profile>.json` and only means something on the machine that recorded it.

## Getting reliable numbers

- Close games and other heavy programs first. The benchmark measures the whole machine; with a game running alongside, tick times rose by about 15%.
- Run it for changes to saving, loading, the codec or storage. Small changes elsewhere don't need it.
- Don't trust a single run. Even on the fixed area, the 99th-percentile tick time varies by about 10% between runs.

`compare.py` diffs two single reports field by field, for when you want every number side by side.

## Flight scenario

`stress_bench_game_test_flight_out_and_back` moves a window of forced chunks out along a straight line and back, as a player exploring would: leg 0 generates and stores the terrain, legs 1 and 2 load it again while saving what they leave behind. It crosses many region files, which the fixed-area scenario never does, and reports pop-in: chunks that come into view before they are usable.

```bash
gradlew :fabric:runGameTest -PchunkisStress=true -PgametestFilter=chunkis-gametest:stress_bench_game_test_flight_out_and_back
```

- `-PchunkisStressFlightSpeed=<blocks per tick>` (default 3) and `-PchunkisStressFlightBlocks=<blocks per leg>` (default 4096).
- `-PchunkisStressStages=true` adds the load pipeline's stages to the report: wall time and allocations of each stage (region read, decompression, decode, NBT build, vanilla parse and convert, full-chunk steps), a per-chunk timeline from load request to usable, generation work and the recent-chunk cache's traffic per leg. CPU per stage is not in it, since thread CPU time on Windows only advances in 15.6 ms steps; record the run with `-PchunkisStressJfr=<file.jfr>` for that. The stage hooks follow the vanilla chunk system, so with C2ME installed the timeline events are missing.
- `-PchunkisStressProbeWrites=true` counts region writes that store exactly what is already on disk. It reads every entry before writing it, so its timings mean nothing.
- `-PchunkisStressFastGeneration=true` runs leg 0 without real tick pacing, several times faster. It changes the cache contents and the pending work leg 1 starts with, so use it only for runs that count things, never to compare timings.
- `-PchunkisRecentChunkCacheMb=<mb>` sets the recent-chunk cache's budget.
- `-PchunkisStressCountWrites=true` logs, when the server stops, how many region writes each store took and their bytes: the payload, and the bytes it occupies on disk (vanilla rounds every chunk up to 4 KiB sectors). Works for both scenarios and both storage modes.

The flight runs on the game-test world preset, which is nearly empty, unless `-PchunkisStressNatural=true` is given. Its chunks are tiny, so its disk figures say little about real worlds; pass the flag for those.
