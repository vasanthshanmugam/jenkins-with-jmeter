#!/usr/bin/env bash
# Runs the same JMeter commands as the Jenkinsfile, from a Linux/macOS terminal.
# Usage (from the repository root):
#   JMETER_HOME=/opt/apache-jmeter-5.6.3 ./scripts/run-local.sh [smoke|load] [env] [threads] [rampup] [duration]
# Examples:
#   ./scripts/run-local.sh smoke local
#   ./scripts/run-local.sh load local 5 10 60
set -euo pipefail

MODE="${1:-smoke}"
TARGET_ENV="${2:-local}"
THREADS="${3:-5}"
RAMPUP="${4:-10}"
DURATION="${5:-60}"
THINK_TIME_MS="${THINK_TIME_MS:-500}"

JMETER="${JMETER_HOME:?Set JMETER_HOME, e.g. export JMETER_HOME=/opt/apache-jmeter-5.6.3}/bin/jmeter"
[ -x "$JMETER" ] || { echo "ERROR: $JMETER not found or not executable"; exit 1; }
cd "$(dirname "$0")/.."

rm -rf results reports && mkdir -p results reports

echo ">>> Smoke test (1 user, 1 iteration) against config/env/$TARGET_ENV.properties"
"$JMETER" -n -t test-plans/shoplite-api.jmx \
  -q config/jmeter-ci.properties -q "config/env/$TARGET_ENV.properties" \
  -Jthreads=1 -Jrampup=1 -Jloops=1 -Jduration=60 -Jthinktime=0 -Jthinktime_range=0 \
  -l results/smoke.jtl -j results/jmeter-smoke.log
java scripts/PerfGate.java --jtl results/smoke.jtl --thresholds config/thresholds-smoke.properties \
  --summary results/perf-gate-smoke.txt
[ "$MODE" = "smoke" ] && exit 0

echo ">>> Load test: $THREADS users, ramp-up ${RAMPUP}s, duration ${DURATION}s"
"$JMETER" -n -t test-plans/shoplite-api.jmx \
  -q config/jmeter-ci.properties -q "config/env/$TARGET_ENV.properties" \
  -Jthreads="$THREADS" -Jrampup="$RAMPUP" -Jduration="$DURATION" -Jloops=-1 \
  -Jthinktime="$THINK_TIME_MS" -Jthinktime_range="$THINK_TIME_MS" \
  -l results/load.jtl -j results/jmeter-load.log

echo ">>> HTML dashboard -> reports/html/index.html"
"$JMETER" -g results/load.jtl -o reports/html -q config/jmeter-ci.properties -j results/jmeter-report.log

set +e
java scripts/PerfGate.java --jtl results/load.jtl --thresholds config/thresholds-load.properties \
  --summary results/perf-gate-load.txt
rc=$?
echo "Gate exit code: $rc  (0=PASS, 1=WARN/UNSTABLE, 2=FAIL, 3=ERROR)"
exit $rc
