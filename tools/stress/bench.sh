#!/usr/bin/env bash
# Runs the stress benchmark for Chunkis and vanilla, alternating, and checks the result against the saved
# baseline (tools/stress/guard.py). Alternating keeps a machine that slows down mid-session (another program,
# heat) from favouring either side.
#
# Usage: tools/stress/bench.sh [--quick] [--runs N] [--save-baseline] [TAG]
#   --quick          small area and one round: a few minutes, catches large regressions only
#   --runs N         runs per side (default 3)
#   --save-baseline  store this result as the baseline instead of checking against it
#   TAG              name of the result directory under benchmarks/runs/ (default: current commit)
#
# Close games and other heavy programs first: the benchmark measures this machine as a whole.
set -euo pipefail
cd "$(dirname "$0")/../.."

profile=full runs=3 save="" tag=""
while [ $# -gt 0 ]; do
  case "$1" in
    --quick) profile=quick ;;
    --runs) runs="$2"; shift ;;
    --save-baseline) save="--save-baseline" ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) tag="$1" ;;
  esac
  shift
done
tag="${tag:-$(git rev-parse --short HEAD)}-$profile"

if [ "$profile" = quick ]; then
  scenario=(-PchunkisStressSize=24 -PchunkisStressRounds=1)
else
  scenario=(-PchunkisStressSize=64 -PchunkisStressRounds=2)
fi
gradle=./gradlew
[ -f gradlew.bat ] && [ "${OS:-}" = Windows_NT ] && gradle=./gradlew.bat

out="benchmarks/runs/$tag"
mkdir -p "$out"
rm -f "$out"/*.json

stop_servers() {
  # A timed-out run can leave its game server behind, holding the world and the CPU.
  for pid in $(jps -l 2>/dev/null | grep -i devlaunch | awk '{print $1}'); do
    if [ "${OS:-}" = Windows_NT ]; then taskkill //F //PID "$pid" >/dev/null 2>&1 || true; else kill -9 "$pid" 2>/dev/null || true; fi
  done
}
trap stop_servers EXIT

for i in $(seq 1 "$runs"); do
  for mode in chunkis vanilla; do
    extra=()
    [ "$mode" = vanilla ] && extra=(-PchunkisStressVanilla=true)
    rm -rf fabric/build/run/gameTest/world
    log="$out/$mode$i.log"
    echo "run $i/$runs: $mode"
    timeout 900 "$gradle" :fabric:runGameTest -PchunkisStress=true -PchunkisStressNatural=true \
      -PchunkisStressTouchedPercent=10 "${scenario[@]}" "${extra[@]}" \
      "-PgametestFilter=chunkis-gametest:stress_bench_game_test_round_trip_reload_and_edits" \
      --console=plain >"$log" 2>&1 || echo "  run failed, see $log" >&2
    stop_servers
    report=$(grep -oE "report written to .*\.json" "$log" | sed 's/report written to //' || true)
    if [ -n "$report" ]; then cp "$report" "$out/$mode$i.json"; else echo "  no report in $log" >&2; fi
  done
done

python tools/stress/guard.py "$out" --profile "$profile" $save
