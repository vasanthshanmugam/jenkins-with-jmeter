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

| Time | Min | Section | Trainer demo | Students do | Checkpoint |
|---|---|---|---|---|---|
| 0:00 | 10 | Why perf tests in CI; objectives | Show a finished build: stage view, dashboard, gate summary | – | Students can name the 8 stages |
| 0:10 | 15 | Architecture (docs/02) | Walk the diagram; `docker ps`, workspace vs artifacts on disk | Draw the flow on paper from memory | Each student explains where the JTL lives during and after a build |
| 0:25 | 15 | Lab check (docs/03) | `java -version`, `jmeter --version`, `curl …/health` | Same on laptop; reach `:8080` and `:8081` | ✅ all three commands work |
| 0:40 | 25 | Test plan (docs/05) | Open JMX in GUI, explain each element, run 1 user with View Results Tree | Run in GUI, find TOKEN in a request header, break the password and observe skipped samplers | ✅ green GUI run; disable listener; `run-local … smoke` PASS |
| 1:05 | 15 | Parameterization | Properties vs variables; `-J` precedence demo (`-Jhost`) | Run CLI with `-Jthreads=3 -Jloops=1`, count logins in the JTL | ✅ JTL has 3 × `01_Login` |
| 1:20 | 10 | **Break** + troubleshooting desk | – | Fix install issues | – |
| 1:30 | 30 | Jenkinsfile + first build (docs/06, 08) | Create job from SCM, first build, walk the console stage by stage | Push own repo, create own job, run first build | ✅ own build SUCCESS; can open own dashboard |
| 2:00 | 15 | Dashboard + gates (docs/09) | Statistics table, percentiles, throughput sanity check; thresholds file | Produce SUCCESS, UNSTABLE (`GATE_OVERRIDES=p95.ms.warn=10`), FAILURE (`THREADS=abc`) | ✅ three builds with three colours, each explained |
| 2:15 | 20 | Failure scenarios (docs/10) | Trainer injects chaos delay → threshold breach; explain the 4 failure kinds | Pairs pick 2 scenarios each, break, diagnose, fix | ✅ each pair presents one root cause in 1 minute |
| 2:35 | 15 | Final challenge | – | See below | ✅ demonstrated to trainer |
| 2:50 | 10 | Enterprise extensions (docs/11) + interview drill (docs/12) | Dedicated agent: move the label, show `Running on jmeter-agent-01` | Answer 3 random interview questions | – |
| 3:00 | | End | | | |

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
