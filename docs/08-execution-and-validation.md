# 8. Step-by-Step Execution and Validation

Prerequisite: docs/03 completed – Jenkins at `http://192.168.0.19:8080` is unlocked, the Built-In Node has the label
`jmeter`, and your repository is on GitHub (docs/04 §4.3).

## Step 1 – Rehearse locally (5 min)

```bash
./scripts/run-local.sh smoke local          # Windows: .\scripts\run-local.ps1 -Mode smoke
```
✅ Checkpoint: the last line is `RESULT     : PASS (build -> SUCCESS)` and `results/smoke.jtl` has 6 lines (header + 5 samples).

## Step 2 – Create the pipeline job

1. Jenkins → **New Item** → name `shoplite-perf` → **Pipeline** → OK.
2. *General*: leave as is – concurrency, retention, parameters and triggers all come from the Jenkinsfile.
3. *Pipeline* section:
   - Definition: **Pipeline script from SCM**
   - SCM: **Git**
   - Repository URL: `https://github.com/<you>/jenkins-with-jmeter.git`
   - Credentials: `github-readonly` (private repo) or *none* (public repo)
   - Branch Specifier: `*/main`
   - Script Path: `Jenkinsfile`
   - **Lightweight checkout**: ticked (Jenkins reads only the Jenkinsfile first)
4. **Save**.

Why *from SCM* and not *Pipeline script* pasted in the UI? The pipeline must be versioned with the test it runs.
A pasted script is invisible to code review and lost when the job is deleted.

## Step 3 – First build

Click **Build Now**. The first build uses the parameter defaults (`load`, `ci`, 10 users, 20 s ramp-up, 120 s, 500 ms think time).
After it finishes the button becomes **Build with Parameters**.

Expected stage view (≈ 3 minutes):

```
1. Checkout ✔ │ 2. Validate environment ✔ │ 3. Smoke test ✔ │ 4. Load test ✔ │ 5. Archive ✔ │ 6. HTML ✔ │ 7. Publish ✔ │ 8. Gate ✔
```

Real console excerpts from the verification run:

```
Checking out Revision c59340aa0e970fc90081fd57bef3c0d726d53c17 (refs/remotes/origin/main)
Agent: built-in   Workspace: /var/jenkins_home/workspace/shoplite-perf
openjdk version "21.0.12.1" 2026-08-18 LTS
{"status": "UP"}
summary =      5 in 00:00:01 =    4.7/s Avg:    71 Min:    28 Max:   104 Err:     0 (0.00%)
RESULT     : PASS (build -> SUCCESS)                       <- smoke gate
summary =   1439 in 00:02:00 =   12.0/s Avg:    23 Min:     6 Max:   113 Err:     0 (0.00%)
[htmlpublisher] Archiving HTML reports...
RESULT     : PASS (build -> SUCCESS)                       <- load gate
Finished: SUCCESS
```

Throughput sanity check: 10 users, each request followed by 500–1000 ms think time plus ~25 ms response time, gives
roughly 10 / 0.78 s ≈ 13 req/s at steady state – the console shows 12.8–13.4/s. **Always check that the achieved
load matches the intended model**; if it does not, the test is measuring something else.

## Step 4 – Inspect what Jenkins kept

On the build page (`#1`):

| Link | Contents |
|---|---|
| **JMeter Dashboard** | HTML report (HTML Publisher) |
| **Build Artifacts** | `results/load.jtl`, `smoke.jtl`, `jmeter-*.log`, `perf-gate-*.txt`, `reports/html/**` |
| **Console Output** | full log with timestamps |
| Build description | `load | 10u x 120s | ci | c59340a` |

✅ Checkpoint: open `perf-gate-load.txt` from the artifacts and find the same p95 value as in the dashboard's
*Statistics* table (Total row, *95th pct*).

## Step 5 – Run with parameters

*Build with Parameters* → `THREADS=5`, `DURATION=40`, `THINK_TIME_MS=200` → Build.
Verify in the console of stage 2/4:

```
Setting JMeter property: threads=5          (in results/jmeter-load.log)
-Jthreads=5 -Jrampup=20 -Jduration=40 …     (in the console command line)
```

## Step 6 – Prove each build result (the most important exercise)

| Do | Expected result | Where to see why |
|---|---|---|
| Default build | **SUCCESS** | `[PASS]` lines in stage 8 |
| `GATE_OVERRIDES=p95.ms.warn=10` | **UNSTABLE** (yellow) | `[WARN] Overall p95 40 ms > p95.ms.warn 10 ms` |
| Inject 5 % order errors (docs/07 §7.4), default build | **FAILURE** in stage 8 | `[FAIL] Critical 04_Create_Order errors 6 > critical.max.errors 0` |
| `THREADS=abc` | **FAILURE** in stage 2 after ~7 s, no load generated | `Invalid parameter THREADS='abc': must be an integer between 1 and 200` |
| `TEST_TYPE=smoke-only` | **SUCCESS** in ~25 s, stages 4–8 shown as skipped | `Stage "4. Load test (non-GUI)" skipped due to when conditional` |

All five were executed while building this lab and produced exactly these results.

## Step 7 – Trigger from Git

1. Change `THINK_TIME_MS` default or a threshold, commit and push.
2. Within ~5 minutes a new build starts; its cause reads *Started by an SCM change*.
3. ✅ Checkpoint: the build description shows your new commit hash.

## Validation checklist (students tick each)

- [ ] I can show `java -version` and `jmeter --version` output **from the Jenkins console**, not from my laptop.
- [ ] My JTL is archived and has a header row.
- [ ] My dashboard opens from the build page with charts (JavaScript working).
- [ ] I produced SUCCESS, UNSTABLE and FAILURE on purpose and can explain each from the log.
- [ ] I can say which commit and which parameters each build used.
