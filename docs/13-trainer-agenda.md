# 13. Trainer Agenda and Student Assessment

**Format:** 3 hours, live, hands-on. Trainer demonstrates on the lab Mac (`192.168.0.19`); students work on their own
laptops and their own Jenkins job (one job per student, e.g. `shoplite-perf-<name>`, each pointing at the student's
own GitHub repository).

## Before the session (trainer)

- [ ] Mac: `./setup/mac-setup.sh` done, Jenkins unlocked, Built-In Node labelled `jmeter` with **4–6 executors**
      (one per two students; `disableConcurrentBuilds` only serializes builds *within* a job).
      For > 12 students start a second agent (docs/11 §11.1) or lower default `DURATION` to 60.
- [ ] One Jenkins user per student (*Manage Jenkins → Users*), or a shared trainer login for the demo only.
- [ ] Docker Desktop resources raised (≥ 4 CPU, ≥ 6 GB); `docker stats` open on a second screen.
- [ ] Students received: install checklist (docs/03 §3.5–3.7) and the repository zip, **24 h before**.
- [ ] Chaos endpoint reset: `curl -X POST http://192.168.0.19:8081/admin/chaos -H 'Content-Type: application/json' -d '{"delayMs":0,"errorRate":0,"endpoint":"all"}'`.

## Session objectives (show on the first slide)

1. Explain the Developer → Git → Jenkins → JMeter → JTL → dashboard → gate → build status flow.
2. Build and parameterize a realistic JMeter plan and validate it before committing.
3. Create a Jenkins pipeline that runs it in non-GUI mode, keeps results, publishes the dashboard and decides the build status.
4. Diagnose failures by category and explain them in an interview.

## Agenda

The session runs in two parts. **Part A** builds the pipeline up from nothing, one idea per level
([BASICS-LEARNING-PATH.md](../BASICS-LEARNING-PATH.md)); **Part B** moves to the full lab pipeline.
Students should finish the laptop installs (docs/03 §3.5–3.7) and the JMeter GUI walkthrough
(docs/05 §5.5) **before** the session as pre-work.

### Part A – Basics (1 h 45 min)

| Time | Min | Level | Trainer demo | Students do | Checkpoint |
|---|---|---|---|---|---|
| 0:00 | 10 | Why perf tests in CI; objectives | Show a finished full-pipeline build: stage view, dashboard, gate summary – "by the end you will build this" | – | – |
| 0:10 | 10 | **Level 0** – JMeter by hand | `docker exec -it jenkins bash`, run the command, `head` the JTL | Same | ✅ JTL with a header + requests; can explain `-n -t -J -l` |
| 0:20 | 10 | **Level 1** – Freestyle job | Git + Execute shell + archive | Build their own | ✅ SUCCESS; finds the JTL under Build Artifacts |
| 0:30 | 10 | **Level 2** – First pipeline | Map each Level 1 setting to a pipeline line | Paste, build, then the `jmeterr` typo | ✅ can say why the typo build is red |
| 0:40 | 10 | **Level 3** – Stages + archive | Stage View columns | Build; `*.csv` pattern exercise | ✅ three green stages |
| 0:50 | 10 | **Level 4** – HTML report | Walk the dashboard Statistics table | Build; open **JMeter Report** | ✅ reads p95 and throughput from the report |
| 1:00 | 10 | **Break** + troubleshooting desk | – | – | – |
| 1:10 | 15 | **Level 5** – Parameters | `-J` → `${__P()}` chain; switch on chaos errors → **green build with 20 % errors** | Build with `THREADS=10`; `THREADS=ten` | ✅ explains why the build is green |
| 1:25 | 10 | **Level 6** – Pass/fail | The `grep ',false,'` check | Normal / errors / `THREADS=ten` builds | ✅ green, red, red – each explained |
| 1:35 | 10 | **Level 7** – Pipeline from Git | Script Path, `checkout scm` | Create job from SCM | ✅ console shows `Obtained pipelines/level-7-Jenkinsfile from git` |

### Part B – The full lab pipeline (1 h 15 min)

| Time | Min | Section | Trainer demo | Students do | Checkpoint |
|---|---|---|---|---|---|
| 1:45 | 10 | Architecture (docs/02) + what Level 8 adds (BASICS §Level 8) | Diagram; `Jenkinsfile` stage by stage at a glance | – | Students name what each extra stage protects against |
| 1:55 | 15 | Full pipeline job (CREATE-NEW-PIPELINE.md) | Create job, first build | Same, own job | ✅ own build SUCCESS; dashboard opens |
| 2:10 | 15 | Dashboard + gates (docs/09) | Percentiles, throughput sanity check; thresholds file | Produce SUCCESS, UNSTABLE (`GATE_OVERRIDES=p95.ms.warn=10`), FAILURE (`THREADS=abc`) | ✅ three builds with three colours, each explained |
| 2:25 | 15 | Failure scenarios (docs/10) | Inject chaos delay → threshold breach; the 4 failure kinds | Pairs pick one scenario, break, diagnose, fix | ✅ each pair presents one root cause in 1 minute |
| 2:40 | 15 | Final challenge | – | See below | ✅ demonstrated to trainer |
| 2:55 | 5 | Wrap-up: extensions (docs/11), interview drill (docs/12) | Dedicated agent = move the label | 2 random interview questions | – |
| 3:00 | | End | | | |

**Shorter session (2 h):** run Part A Levels 0, 2, 4, 5, 6, 7 only (skip 1 and 3), then Part B without the
failure-scenario block.

## Common mistakes to watch for (troubleshooting breaks)

| Mistake | Quick tell | Fix |
|---|---|---|
| JMX saved with View Results Tree enabled | Large memory use, slow builds | Disable listeners before commit |
| Editing the JMX in Jenkins workspace | Changes vanish next build | Edit locally → commit → push |
| Hard-coded host in a sampler | `TARGET_ENV` has no effect | Use HTTP Request Defaults + `__P` |
| Laptop uses `ci.properties` | `UnknownHostException: perf-app` | Laptops use `local.properties` |
| Branch `master` vs `main` | `Couldn't find any revision to build` | Fix branch specifier |
| Windows `jmeter.bat` path with spaces | `'C:\Program' is not recognized` | Install to `C:\tools\…`, quote paths |
| Reading only the console summary | "Err: 0" in smoke but failures in load | Read `perf-gate-*.txt` and the dashboard Errors table |
| Lowering thresholds until green | – | Thresholds change only with a reason in the commit message |

## Final challenge (15 minutes, individually)

Each student, in their own repository and job:

1. **Modify the pipeline**: add a threshold `p95.ms.label.03_Get_Product=200` to `config/thresholds-load.properties`
   **and** add the parameter `SLA_MS` (default `3000`) to the Jenkinsfile, passed to JMeter as `-Jsla_ms`.
2. **Introduce a controlled failure** – the trainer gives each student a sealed card with one of:
   - set chaos `{"delayMs":400,"endpoint":"/api/products"}` (performance breach)
   - commit a JMX with a wrong JSON path in *Extract TOKEN* (`$.tokn`) (correlation/assertion failure)
   - set `SLA_MS=20` (Duration Assertion failures → error rate)
   - delete `test-data/users.csv` in a commit (missing data)
   - change `config/env/ci.properties` to `port=9090` (environment unreachable)
3. **Identify the root cause** from Jenkins only (console, artifacts, dashboard) – no looking at the card's solution.
4. **Fix it** with a commit (or chaos reset) and run the pipeline again.
5. **Demonstrate** to the trainer: the failing build, the evidence line that proves the cause, the fixing commit,
   and the green (or justified UNSTABLE) build.

## Final assessment (pass = 8/10)

| # | Criterion | Evidence |
|---|---|---|
| 1 | Pipeline from SCM in own repository | Job config + own commit hash in build description |
| 2 | Tools proven on the agent | Stage 2 console lines |
| 3 | Smoke and load stages ran in non-GUI mode | `-n` in console, `summary` lines |
| 4 | JTL and logs archived | Build Artifacts |
| 5 | Dashboard published and readable | Student explains p95 and throughput of their run |
| 6 | Parameters change the load | Two builds with different `THREADS`, matching sample counts |
| 7 | SUCCESS, UNSTABLE, FAILURE produced on purpose | Three builds, explained |
| 8 | Final challenge root cause found from evidence | The quoted log/report line |
| 9 | Fix committed, pipeline green again | Commit + build |
| 10 | Interview answer: "Why can a build be green when requests fail?" | Verbal, ≤ 1 minute |

## After the session

- Students: complete the exercises in docs/07 §7.1 (new gate rule) and docs/11 §11.1 (dedicated agent).
- Trainer: `docker compose down` (keeps the `jenkins_home` volume) or `docker compose down -v` to reset everything for the next batch.
