# 7. Supporting Scripts

| Script | Runs on | Purpose |
|---|---|---|
| [`scripts/PerfGate.java`](../scripts/PerfGate.java) | any agent with Java 11+ | Evaluates a JTL against thresholds → exit 0/1/2/3 |
| [`scripts/run-local.sh`](../scripts/run-local.sh) | macOS / Linux laptop | Same JMeter + gate commands as Jenkins, for local rehearsal |
| [`scripts/run-local.ps1`](../scripts/run-local.ps1) | Windows laptop | Same, in PowerShell |
| [`setup/mac-setup.sh`](../setup/mac-setup.sh) | Lab Mac | Builds images, starts containers, health checks, prints unlock password |
| [`app/server.py`](../app/server.py) | `perf-app` container | System under test incl. chaos controls |

## 7.1 PerfGate.java

### Why a script and why Java?

- The decision rules must be **explicit, reviewable and versioned** – a script in Git is all three.
- Java is already required by JMeter on every agent. Java 11+ runs a single source file directly
  (`java PerfGate.java …`), so there is nothing to compile, install or approve in Jenkins' script sandbox,
  and the same command works on Linux, macOS and Windows.

### Usage

```bash
java scripts/PerfGate.java --jtl results/load.jtl \
     --thresholds config/thresholds-load.properties \
     [--summary results/perf-gate-load.txt] [key=value ...]
```

`key=value` arguments override the thresholds file (that is what the Jenkins `GATE_OVERRIDES` parameter feeds in).

### What it does

1. Reads the thresholds file, applies overrides.
2. Streams the JTL (CSV with header) with a small RFC-4180 parser – JMeter quotes fields that contain commas
   or quotes, e.g. a `responseMessage` like `"Bad Request, missing field"`.
3. Per label and in total: count, errors, error %, average, median, p90, p95, p99, throughput.
   Percentiles use the same interpolation as Apache Commons Math (used by JMeter's dashboard), so the gate's numbers
   match `reports/html/statistics.json`. Verified in this lab: dashboard Total p95 = 38 ms, gate p95 = 38 ms;
   `01_Login` p99 = 110 ms in both.
4. Applies the rules, prints a table, writes the same text to `--summary`, exits with the verdict.

### Rules

| Key | Breach ⇒ | Example |
|---|---|---|
| `min.samples` | FAIL | `200` – fewer samples means the test did not really run |
| `error.pct.fail` / `error.pct.warn` | FAIL / WARN | `5.0` / `1.0` (percent) |
| `p95.ms.fail` / `p95.ms.warn` | FAIL / WARN | `1500` / `500` (overall 95th percentile, ms) |
| `p95.ms.label.<label>` | WARN | `p95.ms.label.01_Login=400` |
| `critical.labels` + `critical.max.errors` | FAIL | `01_Login,04_Create_Order` with `0` |

### Exit codes

`0` PASS · `1` WARN · `2` FAIL · `3` ERROR (missing/empty JTL, JTL without header, missing thresholds file, non-numeric threshold).

### Sample output (real output, verification build #4: 5 % errors injected on `/api/orders*`, which also hits `05_Get_Order`)

```
==================== PERFORMANCE GATE ====================
JTL        : results/load.jtl
Thresholds : config/thresholds-load.properties  overrides=[min.samples=100]
Label                   Samples    Err%      Avg   Median      P90      P95      P99     Req/s
01_Login                    119   0.00%       29       29       41       42       76      3.07
02_List_Products            119   0.00%       21       21       31       32       41      3.08
03_Get_Product              118   0.00%       20       21       30       33       51      3.06
04_Create_Order             116   5.17%       29       30       41       43       73      3.04
05_Get_Order                108   7.41%       23       23       32       34       40      2.93
TOTAL                       580   2.41%       25       24       38       41       45     14.75
(times in ms)
----------------------------------------------------------
[FAIL] Critical 04_Create_Order errors 6 > critical.max.errors 0
[WARN] Error rate 2.41% > error.pct.warn 1.00%
[PASS] Sample count 580 >= min.samples 100
[PASS] Overall p95 41 ms <= p95.ms.warn 500 ms
[PASS] 01_Login p95 42 ms <= 400 ms
[PASS] 04_Create_Order p95 43 ms <= 600 ms
[PASS] Critical 01_Login errors 0 <= critical.max.errors 0
RESULT     : FAIL (build -> FAILURE)
==========================================================
```

### Student exercise

Add a rule `avg.ms.fail` (overall average response time). Hints: read it with `num(t, "avg.ms.fail", Double.MAX_VALUE)`,
compare with `total.avg()`, add to `fails`. Test it locally with an override (`avg.ms.fail=1`) before committing.

## 7.2 run-local.sh / run-local.ps1

Rehearse exactly what Jenkins will do – same JMX, same `-q` files, same gate:

```bash
export JMETER_HOME=/opt/apache-jmeter-5.6.3
./scripts/run-local.sh smoke local                # smoke only
./scripts/run-local.sh load local 5 10 60         # smoke + 5 users, 10 s ramp-up, 60 s
open reports/html/index.html                      # macOS (xdg-open on Linux)
```
```powershell
$env:JMETER_HOME = "C:\tools\apache-jmeter-5.6.3"
.\scripts\run-local.ps1 -Mode load -TargetEnv local -Threads 5 -RampUp 10 -Duration 60
Start-Process reports\html\index.html
```
If PowerShell refuses to run scripts: `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

"Works locally, fails in Jenkins" is easier to debug when the local command is literally the same; the only
difference left is the environment (`local` vs `ci` properties and the machine) – see docs/10 scenario 12.

## 7.3 setup/mac-setup.sh

Run once on the Mac (`./setup/mac-setup.sh`). It checks Docker is running, copies `.env.example` → `.env`,
runs `docker compose up -d --build jenkins perf-app`, waits for Jenkins, runs the health checks from docs/03 §3.2
and prints the URL and initial admin password.

## 7.4 ShopLite chaos controls (for exercises)

```bash
# 800 ms (±20 %) delay on every API call
curl -X POST http://192.168.0.19:8081/admin/chaos -H 'Content-Type: application/json' -d '{"delayMs":800}'
# 5 % HTTP 500 on order endpoints only
curl -X POST http://192.168.0.19:8081/admin/chaos -H 'Content-Type: application/json' -d '{"errorRate":0.05,"endpoint":"/api/orders"}'
# back to normal
curl -X POST http://192.168.0.19:8081/admin/chaos -H 'Content-Type: application/json' -d '{"delayMs":0,"errorRate":0,"endpoint":"all"}'
```
Restarting the container also resets it: `docker compose restart perf-app`.
