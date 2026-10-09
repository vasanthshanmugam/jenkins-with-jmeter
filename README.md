# JMeter + Jenkins: End-to-End Performance Testing in CI/CD

**Vasanth Cloud Talk · Performance Testing & Engineering Interview Bootcamp**

A hands-on lab that takes a JMeter test from "I run it by hand in the GUI" to a Jenkins pipeline that
runs it in non-GUI mode, keeps the raw results, publishes the HTML dashboard and decides
**SUCCESS / UNSTABLE / FAILURE** from explicit performance thresholds.

```
Developer ──git push──▶ Git repo ──poll/webhook──▶ Jenkins pipeline (Docker on Mac 192.168.0.19)
                                                     │
                     checkout ▶ validate ▶ smoke ▶ load test (jmeter -n) ▶ archive JTL
                                                     ▶ HTML dashboard (jmeter -g) ▶ publish
                                                     ▶ PerfGate (JTL vs thresholds) ▶ build status
```

## Lab topology

| Component | Where | URL / path |
|---|---|---|
| Jenkins 2.580.1 LTS (JDK 21) + JMeter 5.6.3 | Docker on the Mac | http://192.168.0.19:8080 |
| ShopLite API (system under test) | Docker on the Mac | http://192.168.0.19:8081 (from LAN), `http://perf-app:8080` (from Jenkins) |
| JMeter GUI for script development | Student laptop (Windows/macOS/Linux) | `config/env/local.properties` |
| Optional dedicated JMeter agent | Docker on the Mac (`--profile agent`) | label `jmeter` |

## Quick start (trainer, on the Mac)

```bash
git clone <your-repo-url> jenkins-with-jmeter && cd jenkins-with-jmeter
./setup/mac-setup.sh          # builds images, starts Jenkins + API, prints the unlock password
```

Then follow [docs/03](docs/03-prerequisites-and-installation.md#34-first-time-jenkins-configuration) to unlock Jenkins,
label the built-in node `jmeter`, and [docs/08](docs/08-execution-and-validation.md) to create the pipeline job.

## Quick start (student laptop, no Jenkins)

```powershell
# Windows
$env:JMETER_HOME = "C:\tools\apache-jmeter-5.6.3"
.\scripts\run-local.ps1 -Mode load -TargetEnv local -Threads 5 -RampUp 10 -Duration 60
```
```bash
# macOS / Linux
export JMETER_HOME=/opt/apache-jmeter-5.6.3
./scripts/run-local.sh load local 5 10 60
```

## Where to start

| You want to… | Read |
|---|---|
| Learn step by step, from one JMeter command to the full pipeline | **[BASICS-LEARNING-PATH.md](BASICS-LEARNING-PATH.md)** – Levels 0–8, scripts in [`pipelines/`](pipelines/) |
| Create a job for the full pipeline | [CREATE-NEW-PIPELINE.md](CREATE-NEW-PIPELINE.md) |
| Set up the lab, unlock Jenkins, day-to-day commands | [docs/03-prerequisites-and-installation.md](docs/03-prerequisites-and-installation.md) |

## Training guide

| # | Document | Covers |
|---|---|---|
| 1 | [Session overview](docs/01-session-overview.md) | Title, audience, learning objectives |
| 2 | [Architecture](docs/02-architecture.md) | Diagram, components, workspaces, load generators, smoke vs load |
| 3 | [Prerequisites & installation](docs/03-prerequisites-and-installation.md) | Docker Jenkins on the Mac, Java, JMeter, Git, plugins, Windows/Linux notes |
| 4 | [Repository structure & Git practices](docs/04-repository-structure.md) | Layout, what to commit, triggers, secrets, concurrency |
| 5 | [JMeter test plan & parameterization](docs/05-jmeter-test-plan.md) | Every element, properties vs variables, `-J`, GUI validation |
| 6 | [Jenkinsfile](docs/06-jenkinsfile.md) | Every stage, JMeter CLI options, exit codes, Windows vs Linux |
| 7 | [Supporting scripts](docs/07-supporting-scripts.md) | PerfGate.java, run-local scripts, mac-setup.sh |
| 8 | [Execution & validation](docs/08-execution-and-validation.md) | Create the job, first run, expected output |
| 9 | [Reports & performance gates](docs/09-reporting-and-gates.md) | Dashboard metrics, publishing, retention, gate rules |
| 10 | [Failure scenarios](docs/10-failure-scenarios.md) | 13 hands-on troubleshooting exercises |
| 11 | [Advanced extensions](docs/11-advanced-extensions.md) | Dedicated agents, distributed JMeter, Grafana, APM, trends |
| 12 | [Interview questions](docs/12-interview-questions.md) | 25 questions, beginner → senior, scenario-based |
| 13 | [Trainer agenda & assessment](docs/13-trainer-agenda.md) | 3-hour plan, checkpoints, final challenge |

## Versions used and verified

| Tool | Version | Notes |
|---|---|---|
| Jenkins | 2.580.1 LTS, image `jenkins/jenkins:2.580.1-lts-jdk21` | multi-arch (Apple Silicon + Intel) |
| Java | 21 in the Jenkins image; 11+ anywhere else | JMeter 5.6.3 needs Java 8+; `PerfGate.java` needs 11+ |
| Apache JMeter | 5.6.3 | SHA-512 verified at image build |
| Docker Desktop for Mac | current, Compose v2 | 4 CPUs / 6 GB RAM recommended |
| Python | 3.12 (inside the API image only) | students do not need Python |
