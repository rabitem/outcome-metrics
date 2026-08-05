#!/usr/bin/env bash
# Formal JMH runner for outcome-metrics.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BENCH_DIR="$ROOT/benchmarks"
RESULTS_DIR="$BENCH_DIR/results"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_JSON="$RESULTS_DIR/jmh-${STAMP}.json"
OUT_TEXT="$RESULTS_DIR/jmh-${STAMP}.txt"

mkdir -p "$RESULTS_DIR"

echo "==> Installing outcome-metrics (skipTests)"
(cd "$ROOT" && ./mvnw -B -ntp install -DskipTests)

echo "==> Packaging JMH uber-jar"
(cd "$BENCH_DIR" && "$ROOT/mvnw" -B -ntp -f pom.xml -Dmaven.build.cache.enabled=false clean package)

JAR="$BENCH_DIR/target/benchmarks.jar"
if [[ ! -f "$JAR" ]]; then
  echo "Missing $JAR" >&2
  exit 1
fi

echo "==> Machine / JVM fingerprint"
{
  echo "timestamp_utc=$STAMP"
  echo "uname=$(uname -a)"
  echo "java=$(java -version 2>&1 | tr '\n' ' | ')"
  sysctl -n machdep.cpu.brand_string 2>/dev/null | sed 's/^/cpu=/' || true
  echo "cwd=$ROOT"
} | tee "$RESULTS_DIR/environment-${STAMP}.txt"

echo "==> Running JMH (this takes several minutes)"
# Extra args after the script name are forwarded to JMH (e.g. -f 1 -wi 1).
java -jar "$JAR" \
  -rf json -rff "$OUT_JSON" \
  -o "$OUT_TEXT" \
  "$@"

echo "==> Wrote:"
echo "  $OUT_TEXT"
echo "  $OUT_JSON"
echo "  $RESULTS_DIR/environment-${STAMP}.txt"
echo "Summarize with: python3 benchmarks/scripts/summarize-jmh.py $OUT_JSON"
