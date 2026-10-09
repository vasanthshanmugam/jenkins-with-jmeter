# 6. The Jenkinsfile and JMeter Command-Line Execution

The complete file is [`Jenkinsfile`](../Jenkinsfile) in the repository root. This chapter walks through it
block by block. Read it side-by-side with the file – it is not repeated in full here so the two can never drift apart.

## 6.1 Top-level structure

```groovy
def run(String unixCmd, String windowsCmd) { ... }       // sh on Linux/macOS, bat on Windows, returns exit code
def perfGate(name, jtl, thresholds, summary, overrides) { ... }   // runs PerfGate.java, maps exit code -> result
def readKeyValues(String path) { ... }                   // reads config/env/*.properties without a plugin

pipeline {
    agent { label 'jmeter' }
    parameters { ... }   options { ... }   triggers { ... }   environment { ... }
    stages { 1 … 8 }
    post { always / failure / unstable / success }
}
```

- **Helper methods above `pipeline {}`** are plain Groovy methods, allowed in a declarative Jenkinsfile. They keep
  each stage short and put the Linux/Windows difference in one place.
- `run()` uses `returnStatus: true`: the step never fails by itself; **the stage decides** what an exit code means.
  That is the key to separating "JMeter crashed" from "thresholds breached".

## 6.2 `agent`, `parameters`, `options`, `triggers`, `environment`

| Block | Content | Why |
|---|---|---|
| `agent { label 'jmeter' }` | Any node with the `jmeter` label | Pipeline does not care which machine; the label is the contract |
| `parameters` | `TEST_TYPE` (load/smoke-only), `TARGET_ENV` (ci/qa), `THREADS`, `RAMPUP`, `DURATION`, `THINK_TIME_MS`, `GATE_OVERRIDES` | Same pipeline, different load profiles, no code change |
| `disableConcurrentBuilds()` | Queue instead of running in parallel | Overlapping load tests corrupt each other's results |
| `buildDiscarder(logRotator(...'30'...'15'))` | Keep 30 builds, artifacts for 15 | JTLs and dashboards fill the disk |
| `timeout(time: 45, unit: 'MINUTES')` | Abort a hung build | Frees the agent; max DURATION is 30 min |
| `timestamps()` | Time on every console line | Correlate console with JMeter log and server logs |
| `skipDefaultCheckout(true)` | No implicit checkout | We checkout in a visible stage 1 |
| `pollSCM('H/5 * * * *')` | Check Git every ~5 min | The lab Mac is not reachable by GitHub webhooks |
| `environment` | `JMX`, `CI_PROPS`, and every parameter copied to an env var | Shell commands use `$THREADS` / `%THREADS%`, never Groovy interpolation |

> **Gotcha found while building this lab:** an empty string assigned in `environment {}` arrives in the shell as
> the literal text `null`. That is why `GATE_OVERRIDES` (empty by default) is read from `params` directly.

On the very first build Jenkins does not yet know the parameters; declarative pipelines use the
**defaults** for that run and show *Build with Parameters* afterwards.

## 6.3 Stage 1 – Checkout

```groovy
checkout scm
currentBuild.description = "${env.TEST_TYPE} | ${env.THREADS}u x ${env.DURATION}s | ${env.TARGET_ENV} | ${commit}"
```

- **Does:** clones the commit that triggered the build into `$WORKSPACE`; labels the build with load profile + commit.
- **Expected:** `Checking out Revision c59340a… (refs/remotes/origin/main)`; build list shows `load | 10u x 120s | ci | c59340a`.
- **Diagnose:** `Couldn't find any revision to build` → wrong branch spec (`*/main` vs `*/master`).
  `Authentication failed` / `Repository not found` → missing or wrong credential on a private repo.
  `dubious ownership` → git safe.directory issue on a re-used workspace (`git config --global --add safe.directory '*'` for the agent user).

## 6.4 Stage 2 – Validate environment

Four checks, each with its own failure message, **before** any load is generated:

1. **Parameters**: integers within limits (`THREADS` 1–200, `RAMPUP` 0–600, `DURATION` 10–1800, `THINK_TIME_MS` 0–10000), `GATE_OVERRIDES` is `key=value` pairs.
2. **Files**: JMX, property files, thresholds, CSV and `PerfGate.java` exist in the workspace (`fileExists`, works on every OS).
3. **Tools on this agent**: `java -version`, `JMETER_HOME` set, launcher present, `jmeter --version`.
   Exit codes 10/11/12 identify which one is missing.
4. **Target reachable**: `curl -fsS --max-time 10 http://perf-app:8080/health` (host/port from `config/env/<TARGET_ENV>.properties`).
   A down environment is reported as an *environment problem*, not a performance result.

Then it deletes and recreates `results/` and `reports/` – **every build starts clean**, so a stale JTL from the
previous build can never be evaluated by mistake.

**Expected output (excerpt):**
```
Agent: built-in   Workspace: /var/jenkins_home/workspace/shoplite-perf
openjdk version "21.0.12.1" 2026-08-18 LTS
    _    ____   _    ____ _   _ _____       _ __  __ _____ _____ _____ ____
...  5.6.3
{"status": "UP"}
```

**Diagnose:** the `ERROR:` line names the exact problem: `Invalid parameter THREADS='abc'…`, `Missing files in workspace…`,
`JMETER_HOME is not set on this agent`, `Target http://perf-app:8080/health is not reachable from agent…`.

## 6.5 Stage 3 – Smoke test

```bash
"$JMETER_HOME/bin/jmeter" -n -t "$JMX" \
  -q "$CI_PROPS" -q "config/env/$TARGET_ENV.properties" \
  -Jthreads=1 -Jrampup=1 -Jloops=1 -Jduration=60 -Jthinktime=0 -Jthinktime_range=0 \
  -l results/smoke.jtl -j results/jmeter-smoke.log
```
then `perfGate('Smoke gate', 'results/smoke.jtl', 'config/thresholds-smoke.properties', …)`.

- **Does:** one user, one pass through the journey (5 requests), zero errors allowed, all critical transactions must succeed.
- **Expected:** `summary = 5 in 00:00:01 … Err: 0 (0.00%)` and `RESULT : PASS (build -> SUCCESS)`.
- **Why:** if login is broken or the CSV is missing, we find out in 20 seconds instead of after a 2-minute load test
  full of errors – and we do not hammer a broken environment.
- **Diagnose:** `JMeter smoke run exited with code 1` → open `results/jmeter-smoke.log` (archived).
  `Smoke gate: FAIL threshold breached` → open `results/perf-gate-smoke.txt` – shows which label failed.
  `gate could not evaluate the results (exit 3)` → the JTL is missing or has no samples (see docs/10 scenarios 4–6).

## 6.6 Stage 4 – Load test (non-GUI)

```bash
"$JMETER_HOME/bin/jmeter" -n -t "$JMX" \
  -q "$CI_PROPS" -q "config/env/$TARGET_ENV.properties" \
  -Jthreads="$THREADS" -Jrampup="$RAMPUP" -Jduration="$DURATION" -Jloops=-1 \
  -Jthinktime="$THINK_TIME_MS" -Jthinktime_range="$THINK_TIME_MS" \
  -l results/load.jtl -j results/jmeter-load.log
```

- **Does:** runs the CI load profile; `-Jloops=-1` = iterate until `duration` ends.
- **Expected:** a `summary +` line every 10 s, e.g. `summary = 1439 in 00:02:00 = 12.0/s Avg: 23 … Err: 0 (0.00%)`.
- **Then:** fails the build if JMeter's exit code ≠ 0 or `results/load.jtl` was not created.
- **Diagnose:** console `summary` lines show errors and throughput live; `results/jmeter-load.log` shows exceptions
  (connection refused, OOM, script errors).

## 6.7 Stage 5 – Archive raw results

```groovy
archiveArtifacts artifacts: 'results/*.jtl, results/*.log', fingerprint: true
```
Copies JTLs and logs from the agent to the controller (*Build → Build Artifacts*). Archiving happens **before**
report generation so the raw data survives even if the report step fails. Fingerprints let you trace which
build produced a file.

## 6.8 Stage 6 – Generate the HTML dashboard

```bash
rm -rf reports/html
"$JMETER_HOME/bin/jmeter" -g results/load.jtl -o reports/html -q "$CI_PROPS" -j results/jmeter-report.log
```

- Wrapped in `catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE')`: a broken report marks the build
  UNSTABLE and the pipeline **continues to the gate**, because the gate reads the JTL, not the report.
- **Expected:** `reports/html/index.html`, `statistics.json`, `content/`, `sbadmin2-1.0.7/`.
- **Diagnose:** `results/jmeter-report.log` – typical: `Cannot write to '…/reports/html' as folder does not exist and parent folder is not writable`
  (parent `reports/` missing) or `… is not empty` (folder reused).

## 6.9 Stage 7 – Publish the report

```groovy
archiveArtifacts artifacts: 'reports/html/**'          // plugin-independent fallback
publishHTML(target: [reportName: 'JMeter Dashboard', reportDir: 'reports/html', reportFiles: 'index.html',
                     keepAll: true, alwaysLinkToLastBuild: true, allowMissing: false])
```
Runs only if `reports/html/index.html` exists. Details, CSP and security in docs/09.

## 6.10 Stage 8 – Performance gate

```groovy
perfGate('Load gate', 'results/load.jtl', 'config/thresholds-load.properties',
         'results/perf-gate-load.txt', params.GATE_OVERRIDES ?: '')
```

| PerfGate exit | Meaning | Pipeline action | Build result |
|---|---|---|---|
| 0 | all thresholds met | `echo` | SUCCESS |
| 1 | a *warn* threshold breached | `unstable(...)` | UNSTABLE |
| 2 | a *fail* threshold breached | `error(...)` | FAILURE |
| 3 | JTL missing/empty/unreadable, bad thresholds | `error(...)` | FAILURE |

## 6.11 `post`

- `always`: archive `results/*.log` and `results/perf-gate-*.txt` – logs exist for every build, including one that died in stage 3.
- `failure`: also archive whatever `*.jtl` exists (stage 5 may not have been reached).
- `unstable` / `success`: one-line human summary.

## 6.12 Windows agent version of each command

The Jenkinsfile already contains these – `run()` picks them when `isUnix()` is false.

| Stage | Windows (`bat`) |
|---|---|
| Tools | `java -version` · `if "%JMETER_HOME%"=="" (… exit /b 11)` · `call "%JMETER_HOME%\bin\jmeter.bat" --version < NUL` |
| Health | `curl.exe -fsS --max-time 10 "%TARGET_HEALTH_URL%"` (curl ships with Windows 10 1803+) |
| Clean | `if exist results rmdir /s /q results` … `mkdir results reports` |
| Smoke/Load | `call "%JMETER_HOME%\bin\jmeter.bat" -n -t "%JMX%" ^` … `-l results\load.jtl -j results\jmeter-load.log < NUL` |
| Report | `call "%JMETER_HOME%\bin\jmeter.bat" -g results\load.jtl -o reports\html … < NUL` |
| Gate | `java scripts\PerfGate.java --jtl "%GATE_JTL%" …` |

Why `call` and `< NUL`? Without `call`, a batch file that runs another batch file never returns to the rest of the
script. `jmeter.bat` ends with `pause` when JMeter fails; in Jenkins that would wait for a key press, so we feed it
`NUL` – the pause returns immediately and the error level (e.g. `1`) is passed back to Jenkins.

In Groovy `'''…'''` strings a backslash is an escape character, so the file contains `\\` where cmd needs `\`.

## 6.13 JMeter command-line reference

```
jmeter -n -t test-plan.jmx -l results.jtl -e -o report
```

| Option | Meaning | Precondition / note |
|---|---|---|
| `-n` | non-GUI mode | Mandatory for CI and for any real load |
| `-t <file>` | test plan to run | Path relative to the current directory (the workspace) |
| `-l <file>` | write sample results (JTL) | Appends if the file exists → always start from a clean `results/` |
| `-j <file>` | JMeter's own log file | Default `jmeter.log` in the current dir; name it per run |
| `-e` | generate the HTML dashboard at the end of the run | Needs `-o` |
| `-o <dir>` | dashboard output folder | Must **not exist or be empty**, and its **parent must exist** |
| `-g <jtl>` | generate the dashboard from an existing JTL only (no test) | Used in stage 6, separated from the run |
| `-J name=value` | set a JMeter property | Read with `${__P(name,default)}` |
| `-q <file>` | load an additional property file (repeatable) | Later files override earlier ones |
| `-G name=value` / `-R host1,host2` | global property / remote engines for distributed testing | docs/11 |
| `-f` | force-delete existing JTL and report folder before starting | Convenient locally; in CI we delete explicitly so it is visible |

**Why `-g` in a separate stage instead of `-e -o` in stage 4?** Separate exit codes. With `-e -o` a report failure
and a test failure both look like "JMeter failed", and a long test result could be lost behind a report problem.

### Exit codes

| Situation | Exit code | Detected by |
|---|---|---|
| Test ran (even with 100 % failed requests) | **0** | gate (error rate) |
| JMX not found / cannot be parsed | 1 | stage 3/4 `rc != 0` |
| `-o` folder not empty / parent missing | 1 | stage 6 |
| Invalid numeric property (`-Jthreads=ten`) | **0** – log says `Starting 0 threads for group Shopper Journey` | gate (`JTL has a header but no samples`, exit 3) |
| CSV file missing | **0** – log: `File nope.csv must exist and be readable` | smoke gate (no samples) |
| JVM out of memory | ≠ 0 or killed | stage 4 + log |

**A successful JMeter exit code only means "JMeter ran".** It says nothing about whether the application worked
or met its performance criteria. That is the gate's job.

### Console output and logs

- The `summary +` / `summary =` lines (from `summariser.interval=10` in `config/jmeter-ci.properties`) are the live view.
- `results/jmeter-*.log` contains startup settings (`Setting JMeter property: threads=10`), warnings and stack traces.

### Why not the GUI for load generation

The GUI renders every listener in Swing on the same JVM that generates load: CPU and heap go to drawing
tables instead of sending requests, View Results Tree keeps every response in memory, and a GUI cannot be
started headless by Jenkins or reproduced exactly. The GUI is for building and debugging with 1–2 threads;
`-n` is for measuring. JMeter itself prints this warning when you open the GUI.
