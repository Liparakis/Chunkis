"""Summarize alternating Chunkis/vanilla stress runs and check them against a saved baseline.

Usage:
    python tools/stress/guard.py RUN_DIR [--profile full|quick] [--save-baseline]

RUN_DIR holds chunkis1.json, vanilla1.json, ... as written by tools/stress/bench.sh. Each metric is averaged
per side. Timing metrics are checked as Chunkis/vanilla ratios from the same session, which cancels most of
whatever else the machine was doing; heap and disk are checked as Chunkis's own values, which barely vary.
The baseline lives in benchmarks/baseline-<profile>.json and belongs to this machine: it is not comparable
across hardware. Exit status 1 means a metric got worse than its margin allows.
"""

import argparse
import glob
import json
import os
import statistics
import sys

# name, path in the report, how it is checked, allowed worsening. Margins come from run-to-run spread on a
# fixed area: disk and heap move by about 1%, ticks by a few percent, tails and late-tick counts by more.
METRICS = [
    ("tick p50", ("tickMillis", "all", "p50"), "ratio", 0.08),
    ("tick p90", ("tickMillis", "all", "p90"), "ratio", 0.08),
    ("tick p99", ("tickMillis", "all", "p99"), "ratio", 0.15),
    ("late ticks", ("tickIntervalMillis", "lateTicks"), "ratio", 0.30),
    ("unload p50", ("chunkLatencyMillis", "unload", "p50"), "ratio", 0.12),
    ("reload p50", ("chunkLatencyMillis", "reload", "p50"), "ratio", 0.10),
    ("reload p99", ("chunkLatencyMillis", "reload", "p99"), "ratio", 0.15),
    ("cpu", ("cpu", "processCpuSeconds"), "ratio", 0.08),
    ("server cpu", ("cpu", "serverThreadCpuSeconds"), "ratio", 0.08),
    ("heap MB", ("memory", "retainedHeapMb"), "chunkis", 0.05),
    # Both sides' whole world storage: Chunkis's directory plus vanilla's region and entity directories.
    ("disk MB", lambda r: r["disk"]["chunkisDirMb"] + r["disk"]["vanillaDirsMb"], "chunkis", 0.05),
]


def value(report, path):
    if callable(path):
        return float(path(report))
    for key in path:
        report = report[key]
    return float(report)


def load(run_dir, mode):
    files = sorted(glob.glob(os.path.join(run_dir, f"{mode}*.json")))
    return [json.load(open(f, encoding="utf-8")) for f in files]


def summarize(run_dir):
    chunkis, vanilla = load(run_dir, "chunkis"), load(run_dir, "vanilla")
    if not chunkis or not vanilla:
        sys.exit(f"{run_dir}: need at least one chunkis and one vanilla report "
                 f"(found {len(chunkis)} and {len(vanilla)})")
    summary = {"runs": [len(chunkis), len(vanilla)], "metrics": {}}
    for name, path, _, _ in METRICS:
        c = [value(r, path) for r in chunkis]
        v = [value(r, path) for r in vanilla]
        mc, mv = statistics.mean(c), statistics.mean(v)
        summary["metrics"][name] = {"chunkis": mc, "vanilla": mv,
                                    "ratio": mc / mv if mv else None,
                                    "chunkisRuns": c, "vanillaRuns": v}
    return summary


def checked(entry, how):
    return entry["ratio"] if how == "ratio" else entry["chunkis"]


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("run_dir")
    parser.add_argument("--profile", default="full")
    parser.add_argument("--save-baseline", action="store_true")
    args = parser.parse_args()

    summary = summarize(args.run_dir)
    with open(os.path.join(args.run_dir, "summary.json"), "w", encoding="utf-8") as out:
        json.dump(summary, out, indent=2)
    baseline_path = os.path.join("benchmarks", f"baseline-{args.profile}.json")

    if args.save_baseline:
        os.makedirs(os.path.dirname(baseline_path), exist_ok=True)
        with open(baseline_path, "w", encoding="utf-8") as out:
            json.dump(summary, out, indent=2)
        print(f"saved as baseline: {baseline_path}")
    baseline = None
    if os.path.exists(baseline_path) and not args.save_baseline:
        baseline = json.load(open(baseline_path, encoding="utf-8"))["metrics"]

    print(f"{'metric':<12} {'chunkis':>9} {'vanilla':>9} {'C/V':>6}   {'baseline':>9} {'change':>7}  verdict")
    worse = []
    for name, _, how, margin in METRICS:
        entry = summary["metrics"][name]
        line = f"{name:<12} {entry['chunkis']:9.1f} {entry['vanilla']:9.1f} {entry['ratio']:6.2f}"
        if baseline and name in baseline:
            before, now = checked(baseline[name], how), checked(entry, how)
            change = now / before - 1 if before else 0.0
            verdict = "WORSE" if change > margin else "ok"
            if verdict == "WORSE":
                worse.append(name)
            label = "C/V" if how == "ratio" else "value"
            line += f"   {before:9.2f} {change:+7.1%}  {verdict} ({label}, margin {margin:.0%})"
        print(line)

    if baseline is None and not args.save_baseline:
        print(f"\nno baseline yet: rerun with --save-baseline to store one ({baseline_path})")
    if worse:
        print(f"\nworse than baseline: {', '.join(worse)}")
        sys.exit(1)


if __name__ == "__main__":
    main()
