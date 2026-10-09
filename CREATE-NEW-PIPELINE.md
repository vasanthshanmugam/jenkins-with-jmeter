# Creating a New Jenkins Pipeline – Step by Step

How to create another pipeline job in the lab Jenkins (`http://192.168.0.19:8080`) that runs the
`Jenkinsfile` from GitHub. This is the same flow students follow (docs/08).

Example job name used below: **`shoplite-perf-demo`** – any name works.

---

## Step 1 – Create the job

1. Jenkins dashboard → **+ New Item** (top left).
2. **Item name:** `shoplite-perf-demo`
3. Select **Pipeline** (not *Freestyle project*) → **OK**.

## Step 2 – General section

1. **Description:** `Demo pipeline – JMeter load test of ShopLite API`
2. Leave all other options unticked. Parameters, "no concurrent builds", build retention and triggers
   all come from the `Jenkinsfile`.

## Step 3 – Pipeline section (bottom of the page)

| Field | Value |
|---|---|
| **Definition** | **Pipeline script from SCM** (change it from "Pipeline script") |
| **SCM** | **Git** |
| **Repository URL** | `https://github.com/vasanthshanmugam/jenkins-with-jmeter.git` |
| **Credentials** | `- none -` (the repository is public) |
| **Branch Specifier** | **`*/main`** (change it from the default `*/master` – the most common mistake) |
| **Script Path** | `Jenkinsfile` |
| **Lightweight checkout** | ticked |

If red text appears under *Repository URL*, Jenkins cannot reach the repository: check the URL and that
the repository is still public.

Click **Save** – the page should switch to the job's main page. To confirm it was saved, open
**Configure** again and check the Git URL is still there. Do not click **Build Now** before saving:
an unsaved job fails with `No flow definition, cannot run`.

> **Why "Pipeline script from SCM"?** The pipeline is versioned in Git together with the test it runs.
> A script pasted into the Jenkins UI is not code-reviewed and is lost if the job is deleted.

## Step 4 – First build

1. Click **Build Now**. The first build uses the parameter defaults: 10 users for 120 seconds, about 3 minutes in total.
2. Click the build number (**#1**) → **Console Output** and follow the stages:

| What you should see | Meaning |
|---|---|
| `Checking out Revision …` | Code pulled from GitHub |
| `openjdk version "21…`, the JMeter 5.6.3 banner, `{"status": "UP"}` | Stage 2: Java, JMeter and the API are all available |
| `summary = …` every 10 seconds | Load test running |
| `RESULT : PASS (build -> SUCCESS)` | Performance gate passed |
| `Finished: SUCCESS` | Build complete |

3. Once the first build finishes, **Build Now** changes to **Build with Parameters**.

## Step 5 – Check the results

On the build page:

| Link | What's there |
|---|---|
| **JMeter Dashboard** | HTML report: samples, throughput, response times, percentiles, errors, charts |
| **Build Artifacts** | `results/load.jtl` (raw results), `results/jmeter-*.log`, `results/perf-gate-load.txt` (pass/fail summary), `reports/html/` |
| **Console Output** | Full log with timestamps |

## Step 6 – Run it with your own settings

Click **Build with Parameters** and try these runs:

| Run | Settings | Expected result |
|---|---|---|
| Quick check | `TEST_TYPE=smoke-only` | **SUCCESS** in about 25 seconds; stages 4–8 skipped |
| Lighter load | `THREADS=5`, `DURATION=60` | **SUCCESS**, with fewer requests in the report |
| Force a warning | `GATE_OVERRIDES=p95.ms.warn=10` | **UNSTABLE** (yellow): 95th-percentile response time is above the 10 ms limit |
| Bad input | `THREADS=abc` | **FAILURE** in stage 2 after a few seconds, with no load sent |

All parameters:

| Parameter | Default | Meaning |
|---|---|---|
| `TEST_TYPE` | `load` | `load` = quick check + load test; `smoke-only` = quick check only (1 user, 1 pass) |
| `TARGET_ENV` | `ci` | Which `config/env/<name>.properties` to use (`ci` = the ShopLite API in Docker) |
| `THREADS` | `10` | Virtual users (1–200) |
| `RAMPUP` | `20` | Seconds to start all users (0–600) |
| `DURATION` | `120` | Load test length in seconds (10–1800) |
| `THINK_TIME_MS` | `500` | Pause between requests per user, in ms (0–10000) |
| `GATE_OVERRIDES` | *(empty)* | Change pass/fail limits for one run, e.g. `p95.ms.warn=300 error.pct.fail=2` |

## Things to know

- **Don't run two pipelines at the same time.** Both jobs load the same API, so response times mix and both
  results are misleading. Each job queues its own builds, but not across jobs.
- **Every job builds on every push.** Each job checks GitHub every 5 minutes, so a push to `main` starts a build in
  every job that uses this repository. Delete demo jobs you no longer need: job page → **Delete Pipeline**.
- **"Waiting for next available executor on jmeter"** means another build is using the node. Jenkins runs at most
  2 builds at once here.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `ERROR: No flow definition, cannot run` (build fails instantly) | The Pipeline section was never saved – the job is empty | Job → **Configure** → fill in Step 3 → **Save**; reopen **Configure** to confirm the Git URL is still there |
| `Couldn't find any revision to build` | Branch Specifier is `*/master` | Change it to `*/main` (job → **Configure**) |
| `Repository not found` / authentication error | Wrong URL, or the repository was made private | Fix the URL, or add a read-only GitHub token as a credential (docs/04 §4.3) |
| Build never starts | No node has the label `jmeter` | *Manage Jenkins → Nodes → Built-In Node* → label `jmeter` |
| Stage 2: `Target … is not reachable` | API container stopped | On the Mac: `docker compose start perf-app` |
| Unexpected FAILURE at stage 8 | Errors or delays still switched on in the API | `curl http://192.168.0.19:8081/admin/chaos`, then reset (see docs/07 §7.4) |
| JMeter Dashboard tables are empty | JavaScript in reports is blocked | See docs/09 §9.4 (Content-Security-Policy) |

See also: [BASICS-LEARNING-PATH.md](BASICS-LEARNING-PATH.md) · [docs/08-execution-and-validation.md](docs/08-execution-and-validation.md)
