# JMeter + Jenkins – Learning Path: From Basics to the Full Pipeline

The full lab pipeline (`Jenkinsfile`) combines many ideas at once. This path builds up to it in
**eight small levels**. Each level adds **one** new idea to the previous level and ends with a working build.

| Level | You build | New idea | Time |
|---|---|---|---|
| [0](#level-0--run-jmeter-by-hand) | Run JMeter by hand inside the Jenkins container | Non-GUI mode, the JTL results file | 10 min |
| [1](#level-1--freestyle-job-one-shell-command) | Freestyle job with one shell command | Jenkins just runs your command on a machine | 15 min |
| [2](#level-2--first-pipeline-script-in-jenkins) | First pipeline (script pasted into Jenkins) | Pipeline syntax: `pipeline`, `agent`, `stages`, `steps` | 15 min |
| [3](#level-3--stages-and-keeping-the-results) | Separate stages + keep the results | Stages, `archiveArtifacts` | 15 min |
| [4](#level-4--html-report) | HTML report on the build page | `jmeter -g … -o …`, `publishHTML` | 15 min |
| [5](#level-5--parameters) | Choose the load when you start the build | Build parameters → `-J` → `${__P()}` | 20 min |
| [6](#level-6--passfail-check) | Fail the build when requests fail | JMeter exits 0 even when requests fail | 20 min |
| [7](#level-7--pipeline-from-git-pipeline-as-code) | Read the pipeline from Git | Pipeline as code | 15 min |
| [8](#level-8--the-full-lab-pipeline) | The full lab pipeline | Validation, smoke test, gates, UNSTABLE vs FAILURE | rest of session |

The finished script for every level is in the [`pipelines/`](pipelines/) folder – use it to compare with
your own work or to recover if you get stuck. **Every level was run and verified on the lab Jenkins
(Jenkins 2.580.1, JMeter 5.6.3) before this guide was written**; the "Expected" sections show real output.

---

## Before you start

| Item | Value |
|---|---|
| Jenkins | http://192.168.0.19:8080 (log in with your admin user) |
| Test application (ShopLite API) | `perf-app:8080` from inside Jenkins · http://192.168.0.19:8081 from your laptop |
| Git repository | `https://github.com/vasanthshanmugam/jenkins-with-jmeter.git`, branch `main` |
| Test plan | `test-plans/shoplite-api.jmx` – login → list products → get product → create order → get order |
| JMeter on the Jenkins machine | `/opt/apache-jmeter-5.6.3`, already on the `PATH` as `jmeter` |
| Machine label | The Jenkins node is labelled `jmeter`. Every job in this path asks for that label, so it runs on a machine with JMeter. (While the built-in node is the only machine, Jenkins also runs unlabelled jobs there; once you add agents, the label decides.) |

**How the test plan gets its settings.** The test plan reads every setting from a JMeter *property*
with a default, e.g. `${__P(threads,5)}`. On the command line you change a property with `-Jname=value`.
The ones used in this path:

| Property | Default in the test plan | What we pass from Jenkins |
|---|---|---|
| `host` / `port` | `localhost` / `8081` (laptop view) | `-Jhost=perf-app -Jport=8080` (Jenkins view) |
| `threads` | `5` | number of virtual users |
| `rampup` | `10` | seconds to start all users |
| `duration` | `60` | test length in seconds |

---

## Level 0 – Run JMeter by hand

**Goal:** see that "running JMeter in Jenkins" is just running one command. No Jenkins job yet.

### Steps

1. Open a shell **inside the Jenkins container**. On the Mac (Terminal):
   ```bash
   docker exec -it jenkins bash
   ```
   From the Windows PC: `ssh -t perf-mac "/usr/local/bin/docker exec -it jenkins bash"`
2. Get the test plan:
   ```bash
   cd /tmp
   rm -rf jenkins-with-jmeter
   git clone https://github.com/vasanthshanmugam/jenkins-with-jmeter.git
   cd jenkins-with-jmeter
   ```
3. Check JMeter is there:
   ```bash
   which jmeter        # /opt/apache-jmeter-5.6.3/bin/jmeter
   ```
4. Run a 30-second test with 2 users:
   ```bash
   jmeter -n -t test-plans/shoplite-api.jmx -Jhost=perf-app -Jport=8080 -Jthreads=2 -Jduration=30 -l /tmp/results.jtl
   ```
5. Look at the results file:
   ```bash
   head -3 /tmp/results.jtl
   wc -l /tmp/results.jtl
   ```
6. Leave the container: `exit`

### Every option in the command

| Option | Meaning |
|---|---|
| `-n` | **n**on-GUI mode – no window, maximum load, works on a server |
| `-t test-plans/shoplite-api.jmx` | the **t**est plan to run |
| `-Jhost=perf-app -Jport=8080` | set JMeter properties – where the application is |
| `-Jthreads=2 -Jduration=30` | 2 virtual users for 30 seconds |
| `-l /tmp/results.jtl` | **l**og every request to this results file (JTL) |

### Expected (real output)

```
Created the tree successfully using test-plans/shoplite-api.jmx
Starting standalone test @ 2026 Oct 10 03:53:32 IST
summary +      1 in 00:00:02 =    0.5/s Avg:   129 Min:   129 Max:   129 Err:     0 (0.00%) Active: 1 Started: 1 Finished: 0
summary =     41 in 00:00:30 =    1.4/s Avg:    26 Min:     8 Max:   129 Err:     0 (0.00%)
... end of run
```
```
timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,...
1791584614395,129,01_Login,200,OK,Shopper Journey 1-1,text,true,,212,224,1,1,http://perf-app:8080/api/login,129,0,63
1791584616129,23,02_List_Products,200,OK,Shopper Journey 1-1,text,true,,2661,218,1,1,http://perf-app:8080/api/products,23,0,0
```
`wc -l` shows 42 = 1 header line + 41 requests. Each line is one request: when it ran, how long it took
(`elapsed`, ms), which step (`label`), the HTTP code and whether it passed (`success`).

### Try this

Run the command again **without** `-Jhost=perf-app -Jport=8080`. The test plan falls back to its defaults
(`localhost:8081`), nothing is listening there inside the container, and every request fails.
Verified: `summary = 11 in 00:00:10 … Err: 11 (100.00%)`, and the JTL shows
`Non HTTP response code: org.apache.http.conn.HttpHostConnectException … Connect to localhost:8081 … refused`.
Only `01_Login` appears – the other steps are skipped because login failed (the test plan's If Controller). **Lesson:** the same test plan works anywhere – you only change properties.

---

## Level 1 – Freestyle job: one shell command

**Goal:** let Jenkins run the Level 0 command for you.

### Steps

1. Jenkins → **+ New Item** → name `level-1-freestyle` → **Freestyle project** → **OK**.
2. **General** → tick **Restrict where this project can be run** → Label Expression: `jmeter`
   (the message "Label jmeter matches 1 node" appears).
3. **Source Code Management** → **Git**
   - Repository URL: `https://github.com/vasanthshanmugam/jenkins-with-jmeter.git`
   - Branch Specifier: `*/main`
4. **Build Steps** → **Add build step** → **Execute shell** → paste
   ([`pipelines/level-1-freestyle-shell.sh`](pipelines/level-1-freestyle-shell.sh)):
   ```bash
   # Start with an empty results folder on every build
   rm -rf results
   mkdir results

   # Run the JMeter test in non-GUI mode
   jmeter -n -t test-plans/shoplite-api.jmx \
     -Jhost=perf-app -Jport=8080 \
     -Jthreads=5 -Jrampup=5 -Jduration=60 \
     -l results/results.jtl
   ```
5. **Post-build Actions** → **Add post-build action** → **Archive the artifacts** → Files to archive: `results/*.jtl`
6. **Save** → **Build Now** → click the build **#1** → **Console Output**.

### Expected

```
Checking out Revision 1c8d1193… (refs/remotes/origin/main)
[level-1-freestyle] $ /bin/sh -xe /tmp/jenkins….sh
+ rm -rf results
+ mkdir results
+ jmeter -n -t test-plans/shoplite-api.jmx -Jhost=perf-app -Jport=8080 -Jthreads=5 -Jrampup=5 -Jduration=60 -l results/results.jtl
...
summary =    226 in 00:01:00 =    3.8/s Avg:    23 Min:     8 Max:   103 Err:     0 (0.00%)
... end of run
Archiving artifacts
Finished: SUCCESS
```
On the build page: **Build Artifacts → results.jtl**.

### What you learned

- Jenkins checked out Git into a **workspace** (`/var/jenkins_home/workspace/level-1-freestyle`) and ran
  your shell commands there – the same command as Level 0. That is all "Jenkins runs JMeter" means.
- `+ jmeter …` lines are Jenkins printing each command before running it (`sh -x`).
- Archived files stay with the build; the workspace is overwritten by the next build.

### Try this

Change **Branch Specifier** to `*/master` and build. Expected (verified):
`ERROR: Couldn't find any revision to build. Verify the repository and branch configuration for this job.`
→ **FAILURE** before any test runs. The repository's branch is `main`; GitHub's old default was `master`.
This is the most common first-day mistake. Set it back to `*/main`.

---

## Level 2 – First pipeline (script in Jenkins)

**Goal:** the same thing as Level 1, written as a **pipeline script** – the format used from now on.

### Steps

1. **+ New Item** → name `level-2-first-pipeline` → **Pipeline** → **OK**.
2. Scroll to **Pipeline** → Definition: **Pipeline script** → paste
   ([`pipelines/level-2-first-pipeline.groovy`](pipelines/level-2-first-pipeline.groovy)):
   ```groovy
   pipeline {
       agent { label 'jmeter' }                 // run on a machine that has JMeter

       stages {
           stage('Run JMeter test') {
               steps {
                   // 1. Get the test plan from GitHub
                   git url: 'https://github.com/vasanthshanmugam/jenkins-with-jmeter.git', branch: 'main'

                   // 2. Run JMeter in non-GUI mode (exactly the command you would type in a terminal)
                   sh '''
                       rm -rf results
                       mkdir results
                       jmeter -n -t test-plans/shoplite-api.jmx \
                         -Jhost=perf-app -Jport=8080 \
                         -Jthreads=5 -Jrampup=5 -Jduration=60 \
                         -l results/results.jtl
                   '''
               }
           }
       }
   }
   ```
3. Leave **Use Groovy Sandbox** ticked → **Save** → **Build Now**.

### How to read it

| Line | Meaning |
|---|---|
| `pipeline { }` | Everything inside is one declarative pipeline |
| `agent { label 'jmeter' }` | Same as "Restrict where this project can be run" in Level 1 |
| `stages { stage('…') { } }` | Named steps of the build – shown as columns in **Stage View** |
| `steps { }` | What to do in this stage |
| `git url: …, branch: …` | Same as "Source Code Management → Git" in Level 1 |
| `sh ''' … '''` | Run shell commands – same as "Execute shell" in Level 1. `'''` allows several lines |

### Expected

Stage View shows one green column **Run JMeter test**; the console ends with
`summary =    226 in 00:01:00 =    3.8/s … Err:     0 (0.00%)` and `Finished: SUCCESS`.

### Try this

Change `jmeter -n` to `jmeterr -n` (typo) and build. Expected (verified):
`script.sh.copy: 4: jmeterr: not found` and `ERROR: script returned exit code 127` → **FAILURE** (red). A failing command fails the stage. Fix the typo.

---

## Level 3 – Stages and keeping the results

**Goal:** split the work into stages you can see, and keep the results after the build.

### Steps

1. **+ New Item** → `level-3-stages-and-archive` → **Pipeline** → **OK**.
2. Definition: **Pipeline script** → paste [`pipelines/level-3-stages-and-archive.groovy`](pipelines/level-3-stages-and-archive.groovy).
   What changed from Level 2:
   ```groovy
   stage('Checkout') {
       steps {
           git url: 'https://github.com/vasanthshanmugam/jenkins-with-jmeter.git', branch: 'main'
       }
   }
   stage('Run JMeter test') {
       steps {
           sh '''
               ...same jmeter command...
                 -l results/results.jtl \
                 -j results/jmeter.log          // NEW: JMeter's own log file
           '''
       }
   }
   stage('Archive results') {                  // NEW stage
       steps {
           archiveArtifacts artifacts: 'results/*.jtl, results/*.log'
       }
   }
   ```
3. **Save** → **Build Now**.

### Expected

Stage View: **Checkout ✔ → Run JMeter test ✔ → Archive results ✔**. Build page → **Build Artifacts**:
`results/jmeter.log`, `results/results.jtl`.

### What you learned

- One stage per job step makes it obvious **where** a build failed.
- `-j results/jmeter.log` – JMeter's own log (settings, warnings, errors). First place to look when JMeter fails.
- `archiveArtifacts` copies files to the build record so they survive the next build.

### Try this

Change the pattern to `results/*.csv` and build. Expected (verified): the test runs, then **FAILURE** in
*Archive results* with `ERROR: No artifacts found that match the file pattern "results/*.csv". Configuration error?` Archiving nothing is treated as an error
by default. Put the pattern back.

---

## Level 4 – HTML report

**Goal:** turn the results file into JMeter's HTML dashboard and link it from the build page.

### Steps

1. **+ New Item** → `level-4-html-report` → **Pipeline** → **OK**.
2. Paste [`pipelines/level-4-html-report.groovy`](pipelines/level-4-html-report.groovy). New stage:
   ```groovy
   stage('HTML report') {
       steps {
           // -g = build a report from an existing results file, -o = output folder (must not exist yet)
           sh 'jmeter -g results/results.jtl -o results/html'

           // HTML Publisher plugin: adds a "JMeter Report" link to the build page
           publishHTML(target: [
               reportName : 'JMeter Report',
               reportDir  : 'results/html',
               reportFiles: 'index.html',
               keepAll    : true,
               allowMissing: false,
               alwaysLinkToLastBuild: true
           ])
       }
   }
   ```
3. **Save** → **Build Now** → open the build → **JMeter Report** (left menu).

### Expected

Console: `[htmlpublisher] Archiving at BUILD level /var/jenkins_home/workspace/level-4-html-report/results/html to JMeter_20Report`.
The report shows the *Statistics* table (requests, errors %, average, median, 90th/95th/99th percentile,
throughput per step) and charts under *Charts*.

### What you learned

| Command | Meaning |
|---|---|
| `jmeter -g results/results.jtl` | **g**enerate a report from an existing results file (no test is run) |
| `-o results/html` | **o**utput folder – must not exist or be empty, and its parent folder must exist |
| `publishHTML` | HTML Publisher plugin: keeps a copy of the report with each build and adds the link |

You could also generate the report at the end of the run with `jmeter -n … -l … -e -o results/html`.
A separate stage makes it clear whether the *test* or the *report* failed.

### Try this

In the *Run JMeter test* stage replace the two lines `rm -rf results` and `mkdir results` with
`mkdir -p results` (keep the old folder), then build **twice**. The first build passes; the second fails in
*HTML report* with (verified):
`An error occurred: Cannot write to '/var/jenkins_home/workspace/…/results/html' as folder is not empty`.
The report folder from the previous build is still in the workspace – and `-l` would also have *appended*
to the old `results.jtl`. **Lesson:** start every build with a clean results folder. Put the lines back.

---

## Level 5 – Parameters

**Goal:** choose the number of users, ramp-up and duration when you start the build – no code change.

### Steps

1. **+ New Item** → `level-5-parameters` → **Pipeline** → **OK**.
2. Paste [`pipelines/level-5-parameters.groovy`](pipelines/level-5-parameters.groovy). New parts:
   ```groovy
   parameters {
       string(name: 'THREADS',  defaultValue: '5',  description: 'Number of virtual users')
       string(name: 'RAMPUP',   defaultValue: '5',  description: 'Seconds to start all users')
       string(name: 'DURATION', defaultValue: '60', description: 'Test length in seconds')
   }

   // Copy the parameters into environment variables for the shell ($THREADS ...).
   environment {
       THREADS  = "${params.THREADS}"
       RAMPUP   = "${params.RAMPUP}"
       DURATION = "${params.DURATION}"
   }
   ...
   sh '''
       echo "Load: $THREADS users, ramp-up $RAMPUP s, duration $DURATION s"
       ...
       jmeter -n -t test-plans/shoplite-api.jmx \
         -Jhost=perf-app -Jport=8080 \
         -Jthreads="$THREADS" -Jrampup="$RAMPUP" -Jduration="$DURATION" \
         ...
   '''
   ```
3. **Save** → **Build Now**. The first build uses the defaults and *registers* the parameters.
4. The button is now **Build with Parameters**. Run with `THREADS=10`, `DURATION=30`.

### Why the `environment { }` block?

Jenkins also turns parameters into shell variables automatically – **but not on the very first build**,
because the job does not know its parameters until the pipeline has run once. Verified without the block:
the first build ran `jmeter … -Jthreads= -Jrampup= -Jduration=` (empty values). `params.THREADS` always has a
value (the default on the first build), so copying it into `environment { }` makes `$THREADS` reliable.

### How a value travels

```
Build with Parameters: THREADS=10
  └─▶ params.THREADS = "10"
        └─▶ environment { THREADS = "${params.THREADS}" }   →  shell variable $THREADS = 10
              └─▶ jmeter … -Jthreads="10"                    (JMeter property "threads")
                    └─▶ test plan Thread Group: ${__P(threads,5)}  =  10 users
```

### Expected (verified)

| Build | Console |
|---|---|
| #1 – first build, defaults | `Load: 5 users, ramp-up 5 s, duration 60 s` · `summary =    227 in 00:01:00 … Err: 0 (0.00%)` · SUCCESS |
| #2 – `THREADS=10`, `DURATION=30` | `Load: 10 users, ramp-up 5 s, duration 30 s` · `summary =    214 in 00:00:30 =    7.1/s … Err: 0 (0.00%)` · SUCCESS |

Twice the users for half the time gave about the same number of requests (227 vs 214) at about twice the
rate (3.8/s vs 7.1/s) – the load model behaves as expected.

### Try this – the problem Level 6 solves

1. Switch on errors in the application (from your laptop or the Mac):
   ```bash
   curl -X POST http://192.168.0.19:8081/admin/chaos -H 'Content-Type: application/json' -d '{"errorRate":0.2}'
   ```
   Build with `DURATION=30`. Expected (verified):
   `summary =    105 in 00:00:30 … Err:    22 (20.95%)` – and still **`Finished: SUCCESS`** (green).
2. Switch errors off again:
   ```bash
   curl -X POST http://192.168.0.19:8081/admin/chaos -H 'Content-Type: application/json' -d '{"delayMs":0,"errorRate":0,"endpoint":"all"}'
   ```
3. Build with `THREADS=ten`. Expected (verified): JMeter cannot read "ten" as a number, starts 0 users and
   still exits normally (`summary = 0 in 00:00:00`). The build then fails in *HTML report* with a confusing
   message: `An error occurred: Cannot invoke "…MapResultData.getResult(String)" because "resultData" is null`.
   The real cause is the earlier `summary = 0` line – **always read the console from the top**.

**Lesson: a green build does not mean the test passed.** JMeter's exit code only says "JMeter ran".
Twenty percent of the requests failed and Jenkins showed green.

---

## Level 6 – Pass/fail check

**Goal:** fail the build when no requests ran or any request failed.

### Steps

1. **+ New Item** → `level-6-pass-fail` → **Pipeline** → **OK**.
2. Paste [`pipelines/level-6-pass-fail.groovy`](pipelines/level-6-pass-fail.groovy). Two new checks:

   **Check 1** – at the end of the *Run JMeter test* stage, straight after JMeter:
   ```bash
   # Check 1: did the test record any requests at all?
   total=$(tail -n +2 results/results.jtl | wc -l)
   echo "Requests recorded: $total"
   if [ "$total" -eq 0 ]; then
       echo "FAIL: no requests were recorded - check THREADS/DURATION and results/jmeter.log"
       exit 1
   fi
   ```
   **Check 2** – a new last stage, after archiving and the report:
   ```groovy
   stage('Check results') {
       steps {
           // Check 2: did any request fail?
           // Each line of results.jtl (after the header) is one request.
           // The "success" column is true or false, so failed requests contain ",false,".
           sh '''
               total=$(tail -n +2 results/results.jtl | wc -l)
               failed=$(tail -n +2 results/results.jtl | grep -c ',false,' || true)
               echo "Requests: $total   Failed: $failed"

               if [ "$failed" -gt 0 ]; then
                   echo "FAIL: $failed request(s) failed - open the JMeter Report, Errors table"
                   exit 1
               fi
               echo "PASS: all requests succeeded"
           '''
       }
   }
   ```
3. **Save** → **Build Now**.

### Expected (verified)

| Run | Result | Console |
|---|---|---|
| Normal | **SUCCESS** | `Requests recorded: 224` · `Requests: 224   Failed: 0` · `PASS: all requests succeeded` |
| Errors switched on (`errorRate` 0.2, see Level 5), `DURATION=30` | **FAILURE** in *Check results* | `Requests: 106   Failed: 19` · `FAIL: 19 request(s) failed - open the JMeter Report, Errors table` |
| `THREADS=ten` | **FAILURE** in *Run JMeter test* | `Requests recorded: 0` · `FAIL: no requests were recorded - check THREADS/DURATION and results/jmeter.log` |

Switch the errors off again afterwards (Level 5, "Try this" step 2).

### What you learned

- **Why two checks in two places?** With zero requests there is nothing to report on – the report step would
  crash with a confusing error (Level 5, "Try this" step 3). Check 1 stops the build right after JMeter with a
  clear message. Check 2 runs **after** archiving and the report, so when requests fail you still have the
  results file and the report to investigate.
- `tail -n +2` skips the header line; `wc -l` counts requests; `grep -c ',false,'` counts failed ones.
- `exit 1` makes the shell step fail, which fails the stage and the build. Later stages are skipped
  (`Stage "Archive results" skipped due to earlier failure(s)`).
- This check is all-or-nothing. Real projects allow a small error rate and check response times
  (e.g. 95th percentile) – that is what Level 8 adds.

---

## Level 7 – Pipeline from Git (pipeline as code)

**Goal:** stop pasting scripts into Jenkins. Keep the pipeline in Git, next to the test plan.

> The file `pipelines/level-7-Jenkinsfile` must be in the GitHub repository for this level.

### Steps

1. Look at [`pipelines/level-7-Jenkinsfile`](pipelines/level-7-Jenkinsfile). It is Level 6 with one change:
   ```groovy
   stage('Checkout') {
       steps {
           checkout scm      // same repository and branch as configured in the job
       }
   }
   ```
   The job already knows the repository, so the script does not repeat the URL.
2. **+ New Item** → `level-7-from-scm` → **Pipeline** → **OK**.
3. **Pipeline** section:
   - Definition: **Pipeline script from SCM**
   - SCM: **Git** · Repository URL: `https://github.com/vasanthshanmugam/jenkins-with-jmeter.git`
   - Branch Specifier: `*/main`
   - Script Path: **`pipelines/level-7-Jenkinsfile`**
   - Tick **Lightweight checkout**
4. **Save** → **Build Now** (first build registers the parameters, as in Level 5).

### Expected

Console starts with `Obtained pipelines/level-7-Jenkinsfile from git https://github.com/vasanthshanmugam/jenkins-with-jmeter.git`
and `Checking out Revision …`, then the same stages as Level 6. Verified first build: the parameters already
have their defaults (`-Jthreads=5 -Jrampup=5 -Jduration=60`), `Requests: 226   Failed: 0`,
`PASS: all requests succeeded`, **SUCCESS**.

### Why this matters

| Pipeline script in Jenkins (Levels 2–6) | Pipeline script from SCM (Level 7+) |
|---|---|
| Lives only in the Jenkins job | Lives in Git, next to the test plan |
| Changes are not reviewed or versioned | Every change is a commit – who, when, why |
| Lost if the job is deleted | Recreate the job in 1 minute |
| Copy-paste to every new job | Many jobs can use the same file |
| Good for learning and quick experiments | How real projects do it |

### Try this

Set Script Path to `pipelines/level7-Jenkinsfile` (missing dash) and build. Expected: the build fails
immediately (verified) with `ERROR: Unable to find pipelines/level7-Jenkinsfile from git https://…`.
Fix the path.

---

## Level 8 – The full lab pipeline

You now know every building block. The full pipeline (`Jenkinsfile` in the repository root, job `shoplite-perf`)
adds production-grade practices on top of Level 7:

| Adds | Why | Where to read |
|---|---|---|
| **Validate environment** stage – checks parameters, files, Java, JMeter and that the application is up | Fails in seconds with a clear message instead of a confusing test failure | docs/06 §6.4 |
| **Smoke test** – 1 user, 1 pass before the load test | Don't run a long test against a broken script or environment | docs/06 §6.5 |
| **Property files** (`-q config/…`) instead of many `-J` | Environment settings (ci, qa) in Git, selected by a parameter | docs/05 §5.4 |
| **Performance gate** (`scripts/PerfGate.java`) | Error-rate and 95th-percentile limits per transaction, minimum request count, critical transactions | docs/09 §9.6 |
| **SUCCESS / UNSTABLE / FAILURE** | Warning limits make the build yellow, hard limits make it red | docs/06 §6.10 |
| `options { disableConcurrentBuilds(); timeout(...); buildDiscarder(...) }` | No overlapping tests, no hung builds, disk under control | docs/06 §6.2 |
| `triggers { pollSCM(...) }` | Builds automatically when you push to Git | docs/04 §4.4 |
| Linux **and** Windows agents | Same pipeline on any agent | docs/06 §6.12 |

Next steps: [CREATE-NEW-PIPELINE.md](CREATE-NEW-PIPELINE.md) to create a job for the full pipeline, then
[docs/06-jenkinsfile.md](docs/06-jenkinsfile.md) to read it stage by stage.

---

## Cleaning up

After the session, delete the practice jobs: each job → **Delete Project** / **Delete Pipeline**.
They only use the shared test application, so deleting them does not affect the full lab job `shoplite-perf`.

## Quick troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Couldn't find any revision to build` | Branch Specifier is `*/master` | Set it to `*/main` |
| Build waits for ever: "There are no nodes with the label …" | Label typo (e.g. `jmeterr`) | The label must be exactly `jmeter` |
| `ERROR: No flow definition, cannot run` | Pipeline section not saved | Configure → paste script → **Save** |
| `jmeter: not found` | Typo, or running on a machine without JMeter | Check the command and the label |
| All requests fail, `Connection refused` | Missing `-Jhost=perf-app -Jport=8080`, or the app is stopped | Add the options / on the Mac `docker compose start perf-app` |
| `… results/html … as folder is not empty` | Old report folder still in the workspace | Keep `rm -rf results` + `mkdir results` at the start |
| `"resultData" is null` in the report step | The test recorded 0 requests | Look further up for `summary = 0`; check THREADS/DURATION (Level 6 adds a check for this) |
| Report page shows empty tables | JavaScript blocked in reports | See MAC-LAB-SETUP.md §11 |
| Unexpected failures | Errors/delays still switched on in the app | Reset chaos (Level 5, step 3) |
