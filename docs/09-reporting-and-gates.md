# 9. HTML Reporting and Performance Gates

## 9.1 Opening the dashboard

| Way | How | Needs |
|---|---|---|
| Build page → **JMeter Dashboard** | HTML Publisher link, kept per build (`keepAll: true`) | `htmlpublisher` plugin + relaxed CSP (§9.4) |
| Build page → **Build Artifacts → reports/html/index.html** | Plugin-independent fallback | Relaxed CSP for charts, or download the folder |
| Download | *Build Artifacts → (all files in zip)* → unzip → open `reports/html/index.html` locally | Nothing – always works |
| Locally | `run-local` scripts → `reports/html/index.html` | – |

## 9.2 Reading the dashboard

**Dashboard (first page)**

| Panel | Read it as | In our 10-user run |
|---|---|---|
| Test and Report information | Start/end time, JTL file, filters | 2 min run |
| APDEX | Share of satisfied (≤ 500 ms) / tolerated (≤ 1500 ms) requests, per label (thresholds in `jmeter-ci.properties`) | 1.000 |
| Requests Summary | Pass/fail pie | 100 % OK |
| **Statistics** | One row per sampler + Total – the most important table | see below |
| Errors | Error types with counts and % of errors / % of all samples | empty |
| Top 5 Errors by sampler | Which transaction fails and with what | empty |

**Statistics columns**

| Column | Meaning | Why it matters |
|---|---|---|
| #Samples | Total requests (**total samples**) | Too few ⇒ test did not really run; compare with expected load model |
| FAIL / Error % | Failed samples and **error percentage** | Includes assertion failures, not only HTTP errors |
| Average | Arithmetic mean | Hides outliers – never use alone |
| Min / Max | Extremes | Max is one sample; look for spikes, do not gate on it |
| **Median** | 50 % of requests were faster | The "typical" experience |
| **90th / 95th / 99th pct** | X % of requests were faster than this | SLAs and gates use p95/p99: they show the tail users feel |
| **Transactions/s** | **Throughput** | Must match the intended load; falling throughput under constant users = server slowing down |
| Received / Sent KB/sec | Network volume | Bandwidth bottlenecks on the load generator |

**Charts (Charts menu)**

- *Over Time → Response Times Over Time / Percentiles Over Time*: trends during the run – a rising line means saturation or a leak.
- *Over Time → Active Threads Over Time*: confirms the ramp-up and steady state happened as configured.
- *Throughput → Transactions Per Second / Hits Per Second*: should be flat at steady state.
- *Response Times → Response Time Distribution / Percentiles*: **response time distribution** – bimodal shapes point to caching or two code paths.
- *Response Time vs Request* (throughput vs latency): where latency starts climbing as load rises.

Exercise: compare build #1 (no chaos) with a build after `{"delayMs":300}` – the median, p95 and throughput
change together; the users count does not. Explain why throughput falls when latency rises with fixed users.

## 9.3 Preserving reports and raw results

- Stage 5 archives `results/*.jtl` and logs; stage 7 archives `reports/html/**` and publishes the dashboard.
- They live under `/var/jenkins_home/jobs/shoplite-perf/builds/<n>/` (in the `jenkins_home` Docker volume) – they
  survive container restarts and image rebuilds, **not** `docker compose down -v` (which deletes volumes).
- Retention: `buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '15'))`. A 10-minute 100-user test
  can create a JTL of tens of MB; size retention to the disk. Back up `jenkins_home` if results must be kept longer,
  or export metrics to a time-series database (docs/11).

## 9.4 HTML Publisher plugin and Content-Security-Policy

Installation: preinstalled from `docker/jenkins/plugins.txt` (on another Jenkins: *Manage Jenkins → Plugins →
Available → "HTML Publisher" → Install*). Configuration is entirely in the Jenkinsfile (`publishHTML(target: [...])`):

| Option | Value | Meaning |
|---|---|---|
| `reportName` | `JMeter Dashboard` | Link text on the build page |
| `reportDir` / `reportFiles` | `reports/html` / `index.html` | What to copy and open |
| `keepAll` | `true` | Keep a dashboard for every build, not only the last |
| `alwaysLinkToLastBuild` | `true` | Job page links to the latest dashboard |
| `allowMissing` | `false` | Fail the step if the folder is missing (we guard the stage with `when` anyway) |

**CSP.** Jenkins serves user-generated files (artifacts, HTML reports) with a strict Content-Security-Policy that
blocks JavaScript. The JMeter dashboard is built with JavaScript, so without a change you see the page frame but empty
tables and charts. The lab sets this in `docker-compose.yml`:

```
-Dhudson.model.DirectoryBrowserSupport.CSP="sandbox allow-scripts allow-same-origin; default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:;"
```

Verified response header in the lab: `Content-Security-Policy: sandbox allow-scripts allow-same-origin; default-src 'self'; …`.

**Risk:** any job that can write HTML into an artifact can now run JavaScript in the Jenkins origin of anyone who
opens it. Acceptable on a single-user lab. In a shared Jenkins, prefer one of:
1. Configure a **Resource Root URL** (*Manage Jenkins → System → Resource Root URL*, a second host name for Jenkins) –
   user content is then served from a different origin and can run scripts without endangering Jenkins sessions.
2. Publish dashboards to a separate static web server / object storage and link to them.
3. Download the zip and open locally.

Change CSP at runtime (lost on restart) from *Manage Jenkins → Script Console*:
`System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "…")`.

## 9.5 Sensitive data in reports

- Our `jmeter-ci.properties` disables saving response bodies, request/response headers and sampler data in the JTL.
  Turning `response_data.on_error=true` on helps debugging but writes bodies – which may include tokens, personal data
  or session IDs – into archived files.
- The dashboard shows URLs and error messages: avoid secrets in query strings.
- Anyone with *Job/Read* can open artifacts. Restrict jobs that test production-like data with folder-level permissions.
- `-J` properties appear in `jmeter.log` (verified: `Setting JMeter property: threads=1`). Never pass secrets with `-J` (docs/04 §4.6).

## 9.6 Performance gates

### Rules used in this lab (`config/thresholds-load.properties`)

| Rule | Threshold | Result if breached |
|---|---|---|
| Minimum expected request count | `min.samples=200` | FAILURE |
| Error rate | warn `> 1 %`, fail `> 5 %` | UNSTABLE / FAILURE |
| 95th-percentile response time (overall) | warn `> 500 ms`, fail `> 1500 ms` | UNSTABLE / FAILURE |
| 95th percentile per critical transaction | `01_Login ≤ 400 ms`, `04_Create_Order ≤ 600 ms` | UNSTABLE |
| No failed critical transactions | `critical.labels=01_Login,04_Create_Order`, `critical.max.errors=0` | FAILURE |

The smoke thresholds are stricter on errors (`0 %`) and lenient on time – the smoke test checks function, not speed.

### How thresholds should be chosen

1. Run the same test 5–10 times on a known-good build; note the spread of p95 and error rate.
2. Set *warn* a little above the normal spread (e.g. +20–30 %), *fail* at the agreed SLO or a clear regression.
3. Keep them in Git: a threshold change is a reviewed commit with a reason, not a silent edit in Jenkins.
4. Re-baseline when hardware, data volume or the test changes – and record that in the commit message.

### Four different kinds of failure – never mix them up

| Kind | Example | Detected by | Build | Who acts |
|---|---|---|---|---|
| **Infrastructure / agent failure** | agent offline, Java/JMeter missing, disk full, target down | stage 2 checks, Jenkins itself | FAILURE (stage 2) or build never starts | DevOps / platform |
| **JMeter execution failure** | JMX not found, unparsable, OOM, report folder not writable | non-zero JMeter exit code, missing JTL | FAILURE (stage 3/4) or UNSTABLE (stage 6) | Test engineer |
| **Test assertion failure** | login returns 401, wrong JSON, Duration Assertion exceeded | JTL `success=false` → error %, critical labels | via the gate | Developer / test engineer |
| **Performance threshold breach** | p95 600 ms vs 500 ms limit, error rate 2 % | PerfGate | UNSTABLE or FAILURE (stage 8) | Developer + performance engineer |

**JMeter exiting with 0 does not mean the application passed.** In verification build #4, JMeter exited 0 while
2.41 % of requests failed; only the gate turned that into FAILURE.

### Running the gate yourself

```bash
java scripts/PerfGate.java --jtl results/load.jtl --thresholds config/thresholds-load.properties; echo "exit=$?"
java scripts/PerfGate.java --jtl results/load.jtl --thresholds config/thresholds-load.properties p95.ms.warn=10; echo "exit=$?"   # 1
```
