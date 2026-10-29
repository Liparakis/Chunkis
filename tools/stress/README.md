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
