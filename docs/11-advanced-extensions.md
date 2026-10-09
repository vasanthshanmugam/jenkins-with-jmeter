# 11. Extending the Lab to Enterprise Performance Engineering

## 11.1 Dedicated JMeter load generator (agent) – hands-on, verified in this lab

Move the load off the Jenkins controller without touching the Jenkinsfile.

1. *Manage Jenkins → Nodes → New Node* → name `jmeter-agent-01` → *Permanent Agent* → Create.
   - Number of executors: `1` (one load test per generator at a time)
   - Remote root directory: `/home/jenkins/agent`
   - Labels: `jmeter`
   - Usage: *Only build jobs with label expressions matching this node*
   - Launch method: *Launch agent by connecting it to the controller*
   - Save.
2. Open the node page: Jenkins shows a command containing `-secret <64 hex chars>`. Copy the secret into `.env` on the Mac:
   ```
   JMETER_AGENT_NAME=jmeter-agent-01
   JMETER_AGENT_SECRET=<secret>
   ```
   `.env` is git-ignored – the secret never goes to Git.
3. Start it: `docker compose --profile agent up -d --build jmeter-agent`
4. Node page shows *Agent is connected*. `docker logs jmeter-agent-01` ends with `INFO: Connected`.
5. Remove the label `jmeter` from the Built-In Node (or set its executors to 0).
6. Build. Console: `Running on jmeter-agent-01 in /home/jenkins/agent/workspace/shoplite-perf` and stage 2 prints `Agent: jmeter-agent-01`.

The agent image (`docker/jmeter-agent/Dockerfile`) is `jenkins/inbound-agent` (JDK 21) + the same checksum-verified JMeter
5.6.3 with `HEAP=-Xms1g -Xmx2g`. It connects over **WebSocket** on port 8080, so no extra port (50000) is needed.

On a real network the agent runs on its **own VM/host** close to the system under test – a container on the same
Mac as Jenkins and the API still shares CPU with both. Same steps, different machine: install Java + JMeter, set
`JMETER_HOME`, run the agent (`java -jar agent.jar -url http://192.168.0.19:8080/ -secret … -name jmeter-agent-01 -webSocket -workDir /home/jenkins/agent`) as a service.

## 11.2 Distributed JMeter execution

One JMeter *controller* (client) drives several *engines* (`jmeter-server`). Every engine runs the **whole** plan, so
`threads=50` on 4 engines = 200 users.

```
Jenkins agent (JMeter client, -R)  ──RMI──▶  engine-1 (jmeter-server)  ──HTTP──▶
                                     ├────▶  engine-2 (jmeter-server)  ──HTTP──▶  System under test
                                     └────▶  engine-3 (jmeter-server)  ──HTTP──▶
                    ◀── sample results streamed back ──
```

```bash
# on each engine (same JMeter version, same plugins, CSV copied to the same relative path)
jmeter-server -Djava.rmi.server.hostname=<engine-ip>
# on the Jenkins agent
"$JMETER_HOME/bin/jmeter" -n -t "$JMX" -R 10.0.0.11,10.0.0.12,10.0.0.13 \
  -Gthreads=50 -Grampup=60 -Gduration=1800 -Ghost=app.qa.internal -Gport=443 -Gprotocol=https \
  -l results/load.jtl -j results/jmeter-load.log
```

Notes: `-G` sends properties to all engines (`-J` only sets them on the client). RMI uses SSL by default since JMeter 4 –
create the keystore with `bin/create-rmi-keystore.sh` and copy it to all nodes, or set `server.rmi.ssl.disable=true`
on an isolated lab network only. Open the RMI ports (`server.rmi.localport`, `client.rmi.localport`) in firewalls.
Split CSV data per engine to avoid duplicate users. Many teams instead run N independent non-GUI JMeters (e.g. Kubernetes
jobs) and merge the JTLs – simpler networking, same result.

## 11.3 Scheduled regression tests

A second job `shoplite-perf-nightly` from the same repository with a different trigger and profile:

```groovy
triggers { cron('H 1 * * 1-5') }          // weekdays ~01:00, outside working hours
// THREADS default 100, DURATION 1800, thresholds-nightly.properties, agent label 'jmeter-large'
```
Run it against a stable, prod-like environment with nobody else testing (use `lock('perf-env')`).

## 11.4 Prometheus and Grafana monitoring

**Test-side metrics (what JMeter sees):** add a *Backend Listener* to the plan:
- `InfluxdbBackendListenerClient` (built into JMeter) → InfluxDB → Grafana "JMeter" dashboard; or
- a Prometheus listener plugin (e.g. `jmeter-prometheus-plugin`) exposing `/metrics` for Prometheus to scrape.

Tag every run with `application=shoplite, testTitle=${__P(env)}-build-${__P(build,0)}` and pass `-Jbuild=$BUILD_NUMBER` so Grafana can filter by build.

**System-side metrics (why it is slow):** node_exporter (CPU, memory, disk, network) on servers *and load generators*,
cAdvisor for containers, application/DB exporters. Put JMeter throughput/latency and server CPU on the **same Grafana
dashboard with the same time axis** – that is how you tell an application bottleneck from a generator bottleneck.

## 11.5 Dynatrace (or another APM) integration

- Tag requests so the APM can isolate test traffic: add a header in *HTTP Header Manager – Common*, e.g.
  `x-dynatrace-test: VU=${__threadNum};SI=JMeter;TSN=${__samplerName()};LTN=build-${__P(build,0)}`
  (Dynatrace's documented load-test header; for other APMs use a custom header + request attribute).
- Send a deployment/test event to the APM at test start and end (REST call from the pipeline with an API token from
  Jenkins Credentials) so the timeline shows "load test #42".
- Pull server-side response time, CPU, slow DB calls for the test window into the gate or report (Dynatrace has a
  Jenkins plugin and Site Reliability Guardian; New Relic, AppDynamics, Datadog have equivalents).

## 11.6 Historical trend analysis

- Keep `perf-gate-load.txt` per build (already archived) and the JTL for recent builds.
- For trends across months, write one row per build (build number, commit, p95, error %, throughput per label)
  into a time-series DB from the pipeline, or use the Backend Listener data in InfluxDB/Prometheus.
- In Grafana: p95 per label over build number; annotate releases.

## 11.7 Automated regression alerts

- Pipeline: `post { failure { mail to: 'perf-team@example.com', subject: "Perf FAIL ${env.JOB_NAME} #${env.BUILD_NUMBER}", body: "${env.BUILD_URL}" } }` (Mailer plugin) or Slack/Teams notifier steps.
- Grafana alert rules on the trend series (e.g. p95 of `04_Create_Order` > baseline × 1.2 for 3 consecutive nightly runs).

## 11.8 Environment-specific baselines

- `config/thresholds-load-<env>.properties` selected by `TARGET_ENV`; QA hardware is not production hardware.
- Better than fixed numbers: compare with the **last N green builds** on the same environment (e.g. fail if p95 >
  median of last 5 × 1.25). Store the baseline as a file produced by the nightly job, or query it from InfluxDB.
- Re-baseline after infrastructure changes – in a reviewed commit.

## 11.9 CI/CD quality gates

```
commit ─▶ unit tests ─▶ build ─▶ deploy to perf env ─▶ smoke perf (this job, TEST_TYPE=smoke-only) ─▶
         CI load (this job) ─▶ deploy to staging ─▶ nightly/regression load ─▶ release decision
```
- Fast, small gates early (minutes); big tests later and asynchronously.
- The application pipeline calls this job: `build job: 'shoplite-perf', parameters: [string(name: 'THREADS', value: '20')], propagate: true`.
- FAILURE blocks promotion; UNSTABLE requires a human sign-off.

## 11.10 Keeping heavy load away from Jenkins

- The controller only orchestrates: 0 executors on the built-in node in production Jenkins.
- Generators are dedicated, sized by a calibration run, monitored, and ideally ephemeral (container/VM per test).
- Long tests run in a **controlled environment**: known data volume, no other tests, production-like configuration,
  no shared services under unrelated load – otherwise the numbers cannot be compared from run to run.
- Never point a pipeline load test at production or a third-party API you do not own.
