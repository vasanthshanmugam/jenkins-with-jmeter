# 2. End-to-End Architecture

## 2.1 The flow

```
 ┌────────────┐  git push   ┌──────────────────┐   poll every 5 min / webhook
 │ Developer / │───────────▶│  Git repository  │────────────────────────────────┐
 │ Perf tester │            │ (GitHub / GitLab)│                                │
 └────────────┘             └──────────────────┘                                ▼
      ▲  JMeter GUI on laptop                       ┌──────────────── Mac 192.168.0.19 · Docker Desktop ─────────────────┐
      │  (script development only,                  │                                                                    │
      │   config/env/local.properties)              │  ┌───────────────────────── container: jenkins ────────────────┐  │
      │                                             │  │ Jenkins controller 2.580.1 LTS (JDK 21)                      │  │
      │                                             │  │  └ built-in node, label "jmeter"  (lab default)              │  │
      │                                             │  │     workspace: /var/jenkins_home/workspace/shoplite-perf     │  │
      │                                             │  │     JMETER_HOME=/opt/apache-jmeter-5.6.3                      │  │
      │                                             │  │                                                              │  │
      │                                             │  │  1 Checkout ─▶ 2 Validate ─▶ 3 Smoke ─▶ 4 Load (jmeter -n)   │  │
      │                                             │  │        ─▶ 5 Archive JTL ─▶ 6 HTML (jmeter -g) ─▶ 7 Publish   │  │
      │                                             │  │        ─▶ 8 PerfGate.java ─▶ SUCCESS / UNSTABLE / FAILURE    │  │
      │                                             │  └───────────────────────────────┬──────────────────────────────┘  │
      │                                             │                 HTTP load        │ http://perf-app:8080             │
      │                                             │                                  ▼                                  │
      │   http://192.168.0.19:8081  ◀───────────────┼──────────────  container: perf-app (ShopLite API)                   │
      │                                             │                                                                    │
      │   http://192.168.0.19:8080  ◀───────────────┤  volume jenkins_home: jobs, build history, artifacts, reports      │
      └─────────────── browse reports ──────────────┴────────────────────────────────────────────────────────────────────┘
```

## 2.2 Components and their roles

| Component | Role in the pipeline | In this lab |
|---|---|---|
| **Git** | Single source of truth for the JMX, test data, properties, thresholds, scripts and the `Jenkinsfile`. Every build is traceable to a commit. | Repository `jenkins-with-jmeter` (GitHub or any Git server reachable from the Mac). |
| **Jenkins controller** | Schedules builds, stores configuration, build history, logs and artifacts, serves the UI. | Docker container `jenkins`, data in volume `jenkins_home`. |
| **Jenkins agent (node)** | Machine/container that actually runs the stages. It needs Java, JMeter and the label `jmeter`. | The controller's *built-in node* (labelled `jmeter`); later a dedicated agent container (docs/11). |
| **Java** | Runs Jenkins, JMeter and `PerfGate.java`. | JDK 21 inside the image (`java -version` in stage 2). |
| **Apache JMeter** | Generates load in non-GUI mode, writes the JTL, builds the HTML dashboard. | `/opt/apache-jmeter-5.6.3`, exposed as `JMETER_HOME`. |
| **Operating system** | Decides the shell (`sh` vs `bat`), path separators, file locking, process limits and network stack. | Debian Linux in the containers; Windows/macOS on student laptops. |
| **System under test** | The application whose performance is measured. | ShopLite API container `perf-app` – fully under our control, so results are reproducible. |
| **PerfGate** | Converts raw results into a build decision using thresholds stored in Git. | `scripts/PerfGate.java` (pure Java, no plugin). |

## 2.3 How Jenkins invokes JMeter

Jenkins does not "integrate" with JMeter through magic – it runs a shell command on the agent:

```bash
"$JMETER_HOME/bin/jmeter" -n -t test-plans/shoplite-api.jmx \
  -q config/jmeter-ci.properties -q config/env/ci.properties \
  -Jthreads=10 -Jrampup=20 -Jduration=120 \
  -l results/load.jtl -j results/jmeter-load.log
```

- `sh` (Linux/macOS agents) or `bat` (Windows agents) starts a process in the **workspace** directory.
- JMeter's exit code comes back to the pipeline; the pipeline decides what it means.
- Everything JMeter writes (JTL, log, dashboard) lands in the workspace, from where Jenkins archives it.

## 2.4 Where things live

| Item | In Git | On the agent during a build | After the build |
|---|---|---|---|
| Test plan | `test-plans/shoplite-api.jmx` | `$WORKSPACE/test-plans/…` | in Git (versioned) |
| Test data | `test-data/users.csv` | `$WORKSPACE/test-data/…` | in Git |
| Runtime config | `config/env/*.properties`, `config/jmeter-ci.properties` | `$WORKSPACE/config/…` | in Git |
| Thresholds | `config/thresholds-*.properties` | `$WORKSPACE/config/…` | in Git |
| Raw results | – (git-ignored) | `$WORKSPACE/results/*.jtl` | **archived artifacts** of the build |
| JMeter logs | – | `$WORKSPACE/results/jmeter-*.log` | archived artifacts (always, even on failure) |
| HTML dashboard | – | `$WORKSPACE/reports/html/` | archived artifacts + "JMeter Dashboard" link |
| Gate summary | – | `$WORKSPACE/results/perf-gate-*.txt` | archived artifacts |

**Workspace vs artifacts.** The workspace (`/var/jenkins_home/workspace/<job>`) is scratch space reused
and overwritten by the next build – the pipeline deletes `results/` and `reports/` at the start of every
build. Artifacts are copied to `/var/jenkins_home/jobs/<job>/builds/<n>/archive/` and stay with that build
number until the build discarder removes them (`buildDiscarder` in the Jenkinsfile keeps artifacts of the last 15 builds).

## 2.5 When JMeter runs on a separate load generator

```
 Jenkins controller ──(agent protocol: WebSocket/TCP)──▶ Agent "jmeter-agent-01" (label jmeter)
   - schedules, stores                                     - checks out the repo into its own workspace
   - receives archived                                     - runs jmeter -n, generates the load
     artifacts from the agent  ◀── artifacts upload ──      - runs PerfGate
```

What changes:

1. The **label** decides where the stages run. The Jenkinsfile says `agent { label 'jmeter' }`; you move the
   label from the built-in node to the new agent – the pipeline code does not change.
2. **Tools must exist on the agent**: Java, JMeter, `JMETER_HOME`, network access to the target. Stage 2 proves it on every build.
3. **Workspace paths change** (`/home/jenkins/agent/workspace/...`), which is why the Jenkinsfile uses only relative paths and `$WORKSPACE`.
4. **Artifacts travel** from the agent to the controller after `archiveArtifacts`; big JTLs cost network and disk.
5. For distributed JMeter (one controller, several `jmeter-server` engines) see docs/11 §11.2.

The controller should only coordinate. Generating load on it competes with the Jenkins UI and other
builds for CPU – fine for a classroom, not for real load.

## 2.6 Smoke test vs genuine load test in a pipeline

| | Smoke test (stage 3) | CI load test (stage 4) | Production-scale load test (outside the commit pipeline) |
|---|---|---|---|
| Purpose | "Does the script and the environment work?" | "Did this change make performance worse?" | "Can the system handle peak/expected traffic?" |
| Load | 1 user, 1 iteration (5 requests) | 10 users, 2 min (≤ 200 users, ≤ 30 min) | Hundreds–thousands of users, 30 min – hours |
| Where | any agent | labelled agent, isolated environment | dedicated load generators, prod-like environment |
| Gate | any error ⇒ FAILURE | thresholds vs baseline ⇒ SUCCESS/UNSTABLE/FAILURE | analysis by an engineer + APM + capacity model |
| Runs | every build | every build or nightly | scheduled / pre-release |

A green smoke test tells you nothing about performance. A green CI load test tells you nothing about
peak capacity. Both are still valuable because they run automatically on every change.
