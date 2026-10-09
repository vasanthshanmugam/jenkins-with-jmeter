# 12. Interview Questions and Answers

Levels: 🟢 beginner · 🟡 intermediate · 🔴 senior / scenario.

---

**1. 🟢 Why integrate JMeter with Jenkins at all?**
To make performance testing repeatable and continuous: every change runs the same test with the same settings, results
are stored per build and traceable to a commit, and a regression is caught when the change is small and its author
still remembers it – instead of in a big test two weeks before release.

**2. 🟢 How does Jenkins execute JMeter?**
It runs a shell (`sh`) or batch (`bat`) command on an agent: `jmeter -n -t plan.jmx -l results.jtl …`. There is no
special integration: Jenkins provides the workspace, parameters and environment, captures the console and exit code,
and archives the files JMeter writes.

**3. 🟢 Why non-GUI mode?**
The GUI spends CPU and memory rendering listeners, View Results Tree keeps responses in memory, and a GUI cannot run
headless on a CI agent. Non-GUI (`-n`) gives the maximum load per machine and a scriptable, reproducible run.

**4. 🟢 What do `-n -t -l -e -o` mean?**
Non-GUI; test plan file; results (JTL) file; generate dashboard after the run; dashboard output folder. `-o` must be
empty or non-existent with an existing parent; `-l` appends to an existing file, so use a fresh folder per run.

**5. 🟢 What is a JTL?**
JMeter's sample results file – one row per request with timestamp, elapsed time, label, response code, success flag,
failure message, bytes, latency, connect time. CSV with a header is the CI-friendly format; the dashboard and any
gate script are computed from it.

**6. 🟡 How are runtime properties passed from Jenkins?**
Jenkins parameters → environment variables → `-Jname=value` on the command line (or a `-q` properties file). In the
plan, `${__P(name,default)}` reads them. Example: `THREADS=25` → `-Jthreads="$THREADS"` → `${__P(threads,5)}` in the Thread Group.

**7. 🟡 Difference between JMeter properties and variables?**
Properties are global (all threads), set from outside (`-J`, `-q`, `user.properties`) and read with `__P`. Variables are
per thread, set inside the test (UDV, extractors, CSV) and read with `${name}`. Configuration → properties; runtime
data like tokens → variables.

**8. 🟡 How do you generate the HTML report, and why separately?**
`jmeter -g results.jtl -o report/` (or `-e -o` at the end of a run). Separately so that a report failure does not look
like a test failure, and so the report can be regenerated from an archived JTL with different settings.

**9. 🟡 Why can a Jenkins build be green even though requests failed?**
Because JMeter exits 0 whenever the run completes – even with 100 % failed samples. Failed requests are just data in the
JTL. Unless something evaluates that data (a gate script or plugin), the build stays green.

**10. 🟡 How do you fail a pipeline when performance degrades?**
Evaluate the JTL against explicit thresholds after the run (error %, p95/p99 per transaction, minimum sample count,
critical transactions) and map the result to a build status. In this lab `PerfGate.java` exits 0/1/2/3 and the
Jenkinsfile maps that to SUCCESS / `unstable()` / `error()`.

**11. 🟡 SUCCESS vs UNSTABLE vs FAILURE – how do you use them?**
FAILURE: something is definitely wrong (tool/environment broken, hard threshold breached, critical transaction failed).
UNSTABLE: a soft threshold breached or a non-essential step (report publishing) failed – needs a human look.
SUCCESS: everything ran and all thresholds passed.

**12. 🟡 Why percentiles instead of average?**
The average hides the tail: 95 fast requests and 5 requests of 10 s average out to ~0.5 s, which looks fine. p95/p99 show
what the slowest users experience; SLAs are written on percentiles.

**13. 🟡 How do you handle secrets?**
Never in Git. Store them in Jenkins Credentials, bind with `withCredentials` only around the step that needs them, pass
to JMeter through a temporary `-q` file (not `-J`, which appears in the process list and in `jmeter.log`), keep request
headers and response data out of the JTL, and restrict who can read artifacts.

**14. 🟡 How do you prevent two builds from corrupting each other's results?**
`disableConcurrentBuilds()` so builds of the job queue; clean `results/` and `reports/` at the start of each build;
lock the shared test environment (`lock('perf-env')`) so different jobs cannot load it at the same time.

**15. 🟡 Why have a smoke test stage before the load test?**
It proves the script, the data and the environment work in seconds, with 1 user. It prevents wasting a 30-minute
load slot on a broken login and prevents hammering a broken environment with errors.

**16. 🔴 How do you manage distributed load generation?**
JMeter client with `-R engine1,engine2` and `jmeter-server` on each engine (same JMeter version, plugins, data; RMI
keystore or SSL disabled on isolated networks; firewall ports open; `-G` for global properties); or independent non-GUI
instances orchestrated by Jenkins/Kubernetes with JTLs merged afterwards. Every engine runs the full thread count.
Monitor every generator.

**17. 🔴 How do you tell an application bottleneck from a load-generator bottleneck?**
Look at both sides for the same time window. Generator bottleneck: generator CPU/GC/network saturated, server resources
mostly idle, connect times rising, throughput plateaus; adding a second generator increases total throughput.
Application bottleneck: server CPU/DB/threads/connection pools saturated, generator healthy, response times rise with
load while throughput flattens; adding generators does not help.

**18. 🔴 How would you implement performance testing as a CI/CD quality gate?**
Tests and thresholds in Git; a smoke perf test on every commit (minutes), a CI load test after deploy to a stable
perf environment, a nightly/longer regression on prod-like infrastructure; dedicated agents; thresholds from a baseline
of repeated runs per environment; build status from an explicit gate; results stored and trended; alerts to the team;
FAILURE blocks promotion, UNSTABLE needs sign-off.

**19. 🔴 Scenario: the build is green but users report slowness after the release. What went wrong?**
Possible gaps: the CI load model does not match production (users, mix, data volume, think time); the gate checks
average instead of percentiles or only overall instead of per transaction; the environment is not prod-like (cache warm,
small DB); the regression is in an untested journey; or the gate never ran (JTL empty but no `min.samples` rule). Fix the
model and the gate, add the journey, and compare CI results with production monitoring.

**20. 🔴 Scenario: the same build gives p95 = 300 ms on one run and 900 ms on the next. What do you do?**
Find the noise before tuning thresholds: shared environment or other tests running, the generator sharing CPU with
Jenkins, cold caches, GC on the generator, autoscaling, noisy neighbours. Isolate the environment, warm up (exclude the
ramp-up from evaluation), run several times, and gate on stable metrics with tolerance – or compare with a rolling baseline.

**21. 🔴 Scenario: the pipeline passes locally but fails on the Jenkins agent.**
Compare the actual inputs: which properties and target (`local` vs `ci`), JMeter version and plugins, Java version,
file paths/case sensitivity, line endings, DNS/proxy/firewall from the agent, uncommitted local changes, test data
state. Make local and CI use the same commands (wrapper scripts) and the same image.

**22. 🔴 Scenario: JMeter exits 0 but the JTL is empty.**
The run started no threads or every thread died: invalid numeric property (`threads=ten` → "Starting 0 threads"),
missing CSV ("must exist and be readable"), thread group disabled, duration 0. Prevention: validate parameters, check files,
gate on `min.samples` and fail when the JTL has no samples.

**23. 🔴 How do you choose thresholds?**
From SLOs where they exist; otherwise from a baseline: run the test 5–10 times on a known-good build, measure the spread,
set warn slightly above normal variation and fail at a clear regression or the SLO. Per environment, per transaction,
in Git, re-baselined deliberately.

**24. 🔴 How do you stop CI performance tests from overwhelming Jenkins?**
No load on the controller; dedicated agents with labels and one executor; parameter limits (max users/duration);
`timeout`; artifact retention; long tests in separate scheduled jobs on dedicated infrastructure.

**25. 🔴 What would you add beyond JMeter metrics to make a gate trustworthy?**
Server-side metrics and APM for the same window (CPU, memory, GC, DB time, error logs), generator health metrics, and
correlation via a test header/tag so test traffic can be separated in the APM. A gate that only sees client-side numbers
cannot tell *why* something failed, and cannot detect a generator bottleneck.
