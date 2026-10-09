# 10. Real-World Failure Scenarios

Each scenario: **how to cause it** in the lab → **symptoms** → **root cause** → **investigate** → **fix** → **prevent**.
Students should break the pipeline on purpose, read the evidence, and fix it – without being told the cause first.

Trainer tip: create a branch per scenario (`break/03-wrong-jmx`) and point a copy of the job at it.

---

### 1. Jenkins cannot find Java

- **Cause it:** Windows agent whose service account has no Java on PATH; or on Linux, an agent image without Java on PATH.
- **Symptoms:** stage 2 fails with `java: not found` / `'java' is not recognized…` and `ERROR: java is not on PATH for the Jenkins agent` (exit 10). On agents without Java at all, the agent cannot even connect (remoting needs Java).
- **Root cause:** PATH of the **agent process** differs from your interactive shell. Jenkins services on Windows often run as *Local System*.
- **Investigate:** add `sh 'env | sort'` / `bat 'set'` to see the agent's real environment; *Manage Jenkins → Nodes → <node> → System Information*.
- **Fix:** install a JDK on the agent; set `PATH`/`JAVA_HOME` in *Node → Configure → Environment variables*, or use the *JDK tool* (`tools { jdk 'jdk21' }`).
- **Prevent:** stage 2 checks it on every build; agent images (Dockerfile) instead of hand-configured machines.

### 2. Jenkins cannot find the JMeter executable

- **Cause it:** on the dedicated agent, set the node environment variable `JMETER_HOME=/opt/jmeter` (wrong path).
- **Symptoms:** `ERROR: /opt/jmeter/bin/jmeter not found or not executable` (exit 12), or `JMETER_HOME is not set on this agent` (exit 11).
- **Root cause:** JMeter not installed on *this* agent, wrong path, or missing execute permission (`chmod +x` lost when a zip was extracted).
- **Investigate:** `sh 'ls -l "$JMETER_HOME/bin/jmeter"; echo $JMETER_HOME'` in a test pipeline; check which node ran the build (`Running on …`).
- **Fix:** correct `JMETER_HOME`; install JMeter into the agent image.
- **Prevent:** never hard-code `/opt/apache-jmeter-5.6.3` in the Jenkinsfile; always `JMETER_HOME` provided by the agent.

### 3. Incorrect workspace or JMX path

- **Cause it:** rename `test-plans/` to `tests/` and push, or change `JMX` in `environment {}` to `test-plans/ShopLite-API.jmx` (case!).
- **Symptoms:** `Missing files in workspace /var/jenkins_home/workspace/shoplite-perf: [test-plans/ShopLite-API.jmx]`. Without the check, JMeter exits **1** with "file not found" and no JTL.
- **Root cause:** relative path does not exist from the workspace root; Linux file systems are case-sensitive, Windows usually is not – "works on my Windows laptop".
- **Investigate:** `sh 'pwd; ls -R test-plans'`; *Workspace* link on the build page.
- **Fix:** correct the path in one place (`environment { JMX = ... }`).
- **Prevent:** stage 2 `fileExists` checks; paths only relative to the workspace.

### 4. Invalid JMeter properties

- **Cause it:** *Build with Parameters* → `THREADS=ten`. Or bypass validation and run `jmeter … -Jthreads=ten` locally.
- **Symptoms:** pipeline: `Invalid parameter THREADS='ten': must be an integer between 1 and 200` in ~7 s. Without validation (verified): JMeter **exits 0**, `jmeter.log` shows `Starting 0 threads for group Shopper Journey`, the JTL contains only a header, the gate reports `JTL has a header but no samples` (exit 3).
- **Root cause:** `__P` returns the text `ten`; the Thread Group cannot parse it and starts no threads.
- **Investigate:** `results/jmeter-load.log` → `Setting JMeter property: threads=ten` and `Starting 0 threads`.
- **Fix:** pass a number.
- **Prevent:** parameter validation in stage 2 + `min.samples` in the gate. Also: a property name typo (`-Jthread=10`) is silently ignored and the default (`5`) is used – check `Setting JMeter property` lines.

### 5. Missing CSV data

- **Cause it:** `git rm test-data/users.csv` and push; or run locally with `-Jdatafile=../test-data/nope.csv`.
- **Symptoms:** pipeline: `Missing files in workspace …: [test-data/users.csv]`. Without the check (verified): JMeter **exits 0**, log shows `java.lang.IllegalArgumentException: Could not read file header line for file ../test-data/nope.csv … File nope.csv must exist and be readable`, every thread dies, 0 samples, smoke gate exit 3.
- **Root cause:** file not committed (often because of a `.gitignore` rule like `*.csv`), wrong path, or path relative to the wrong folder (CSV paths are relative to the **JMX's folder**).
- **Investigate:** `git ls-files test-data`; the JMeter log.
- **Fix:** commit the file / correct the relative path.
- **Prevent:** file checks in stage 2; review `.gitignore`; test data generated in a pipeline step when it must be fresh.

### 6. Failed HTTP requests

- **Cause it:** `TARGET_ENV=qa` after editing `qa.properties` to `port=9999`; or `docker compose stop perf-app` during a build.
- **Symptoms:** if the target is down at the start: stage 2 `Target http://perf-app:9999/health is not reachable` (curl exit 7). If it dies mid-test: `summary` lines show rising `Err:`; dashboard *Errors* shows `Non HTTP response code: org.apache.http.conn.HttpHostConnectException`; gate FAILURE.
- **Root cause:** environment/network, not the application's performance.
- **Investigate:** dashboard *Errors* table (response code column tells HTTP vs non-HTTP), `docker ps`, `curl` from the agent.
- **Fix:** restore the environment, re-run.
- **Prevent:** health check before load; separate "environment unavailable" in the build description; monitor the environment.

### 7. Assertion failures

- **Cause it:** in the JMX change the expected status in *Assert status = CONFIRMED* to `SHIPPED`, commit, build.
- **Symptoms:** HTTP 200 everywhere, yet `05_Get_Order` has 100 % errors; smoke gate `Error rate 20.00% > error.pct.fail 0.00%` → FAILURE in stage 3.
- **Root cause:** functional expectation mismatch – application change or wrong assertion.
- **Investigate:** JTL `failureMessage` column / dashboard *Errors*: `Value in json path '$.status' expected to be 'SHIPPED', but found 'CONFIRMED'` (verified).
- **Fix:** decide whether the app or the test is wrong; fix that one.
- **Prevent:** smoke test runs before load; validate script changes in the GUI (docs/05 §5.5).

### 8. JMeter process exits unsuccessfully

- **Cause it:** commit a JMX with broken XML (delete one `</hashTree>`), or set `HEAP=-Xmx16m` on the agent.
- **Symptoms:** `JMeter smoke run exited with code 1 - see results/jmeter-smoke.log`; log shows `Error in NonGUIDriver … CannotResolveClassException` / `OutOfMemoryError`.
- **Root cause:** unreadable plan, missing plugin JAR (plan uses a plugin the agent lacks), JVM memory.
- **Investigate:** `results/jmeter-smoke.log` (archived by `post { always }`).
- **Fix:** restore the JMX from Git; install required plugins in the agent image; size `HEAP`.
- **Prevent:** open/save the plan in the same JMeter version as the agents; list plugins in the agent Dockerfile.

### 9. HTML report generation fails

- **Cause it:** in stage 6 remove the `rm -rf reports/html` line and run a build where `reports/html` already contains files (or `-o` into a folder whose parent does not exist).
- **Symptoms:** stage 6 red, build **UNSTABLE**, gate still runs. `results/jmeter-report.log`: `Cannot write to '…/reports/html' as folder does not exist and parent folder is not writable` or `… is not empty`.
- **Root cause:** `-o` requires an empty/non-existent folder with an existing parent. Also: JTL saved as XML or without header (`print_field_names=false`), or a JTL truncated by a killed run.
- **Investigate:** report log; `head -2 results/load.jtl`.
- **Fix:** clean folder per build; CSV JTL with header (`-q config/jmeter-ci.properties`).
- **Prevent:** stage 2 recreates `results/` and `reports/`; report generated from the JTL in its own stage.

### 10. Jenkins cannot publish the report

- **Cause it:** remove `htmlpublisher` from `plugins.txt` and rebuild the image; or start Jenkins without the CSP option.
- **Symptoms:** without the plugin: `No such DSL method 'publishHTML'` → stage 7 UNSTABLE (dashboard still in *Build Artifacts*). Without CSP: the *JMeter Dashboard* link opens but tables and charts are empty; browser console shows `Refused to execute inline script because it violates … Content Security Policy`.
- **Root cause:** missing plugin; Jenkins' default CSP blocks JavaScript in user content.
- **Investigate:** browser developer tools → Console; `curl -sI <dashboard-url> | grep -i content-security`.
- **Fix:** install HTML Publisher; set CSP or a Resource Root URL (docs/09 §9.4).
- **Prevent:** plugins pinned in the image; archive the report as an artifact as a fallback (already done).

### 11. Performance thresholds are breached

- **Cause it:** `curl -X POST …/admin/chaos -d '{"delayMs":600}'` then build.
- **Symptoms:** all stages green until stage 8 → `[FAIL] Overall p95 7xx ms > p95.ms.fail …` or `[WARN] …` → UNSTABLE/FAILURE. Throughput falls (users wait longer per request).
- **Root cause:** the application (here: injected latency) got slower. In real life: code change, DB query plan, configuration, data growth, noisy neighbour.
- **Investigate:** which labels breached (`perf-gate-load.txt`), when it started (*Response Times Over Time*), what changed (commit diff since the last green build), server-side metrics/APM for the same time window.
- **Fix:** fix the regression – or, if the change is accepted, update the threshold in a reviewed commit with justification.
- **Prevent:** run on every change so the culprit commit is small; baselines per environment.

### 12. Test passes locally but fails on the Jenkins agent

- **Typical causes:** different target (`local` vs `ci` properties), DNS (`perf-app` only resolves inside Docker), proxy/firewall from the agent, case-sensitive paths, CRLF line endings in a shell script, different JMeter version or missing plugin, agent locale (decimal comma) or timezone, less CPU/RAM on the agent, data already consumed by an earlier run.
- **Investigate:** compare `Setting JMeter property` lines in both logs; `jmeter --version` on both; `curl` the target **from the agent**; `git diff` between the committed file and the local one (uncommitted local changes are the #1 cause).
- **Fix/prevent:** `run-local` scripts use the same commands as Jenkins; agents built from a Dockerfile; `.gitattributes` for line endings; versions pinned.

### 13. The load generator becomes the bottleneck

- **Cause it:** `THREADS=200`, `THINK_TIME_MS=0` on the built-in node while watching `docker stats`.
- **Symptoms:** throughput plateaus while response times rise; but the **server** shows low CPU; `docker stats jenkins` shows the JMeter container near 100 % CPU; the JMeter log shows GC pauses; `Connect` times grow; summary lines become irregular. Results vary wildly between runs.
- **Root cause:** the load generator, not the application, is saturated (CPU, heap/GC, network, ephemeral ports, file descriptors) – or it shares CPU with Jenkins and the application on the same Mac.
- **Investigate:** CPU/memory of the generator *and* the server for the same window; repeat the test with half the users on two generators – if total throughput doubles, the generator was the limit.
- **Fix:** non-GUI only, listeners disabled, CSV JTL without response data, more `HEAP`, think time, more/bigger generators (distributed testing), separate the generator from Jenkins and from the SUT.
- **Prevent:** monitor generators during every test (docs/11); keep generator CPU < ~70–80 %; size generators with a calibration run.
