"""Compare two Chunkis stress benchmark reports.

Usage:
    python tools/stress/compare.py BASELINE.json CANDIDATE.json

Reports are written by the stress gametest to fabric/build/run/gameTest/chunkis-stress/.
Every numeric field is listed with its change; for these metrics lower is better, so a
negative change is an improvement.
"""

import json
import sys


def flatten(node, prefix=""):
    """Yield (dotted.path, number) for every numeric leaf."""
    if isinstance(node, dict):
        for key, value in node.items():
            yield from flatten(value, f"{prefix}.{key}" if prefix else key)
    elif isinstance(node, (int, float)) and not isinstance(node, bool):
        yield prefix, float(node)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    with open(sys.argv[1], encoding="utf-8") as handle:
        baseline = json.load(handle)
    with open(sys.argv[2], encoding="utf-8") as handle:
        candidate = json.load(handle)

    if baseline.get("config") != candidate.get("config"):
        print("warning: the reports used different scenario settings\n")

    base = dict(flatten({k: v for k, v in baseline.items() if k != "config"}))
    cand = dict(flatten({k: v for k, v in candidate.items() if k != "config"}))
    width = max(len(name) for name in base.keys() | cand.keys())
    print(f"{'metric':<{width}}  {'baseline':>12}  {'candidate':>12}  {'change':>8}")
    for name in sorted(base.keys() | cand.keys()):
        before, after = base.get(name), cand.get(name)
        if before is None or after is None:
            print(f"{name:<{width}}  {before if before is not None else '-':>12}  "
                  f"{after if after is not None else '-':>12}  {'n/a':>8}")
            continue
        change = "0.0%" if before == after else (f"{(after - before) / before * 100:+.1f}%" if before else "new")
        print(f"{name:<{width}}  {before:>12.3f}  {after:>12.3f}  {change:>8}")


if __name__ == "__main__":
    main()
