# 1. Session Title and Learning Objectives

## Title

**Apache JMeter + Jenkins: End-to-End Performance Testing Automation in CI/CD**
*Vasanth Cloud Talk – Performance Testing & Engineering Interview Bootcamp*

## Who this is for

- Performance testers using JMeter, LoadRunner, NeoLoad, Gatling or k6.
- Manual/automation testers moving into performance engineering.
- Engineers who need to explain how performance tests fit into DevOps pipelines.
- Candidates preparing for senior performance testing / engineering interviews.

## What students build

A Git repository containing a parameterized JMeter test plan, a declarative `Jenkinsfile` and a
threshold-evaluation script. Jenkins (in Docker on the lab Mac, `192.168.0.19`) checks the
repository out, validates the agent, runs a smoke test, runs a CI load test in non-GUI mode,
archives the JTL, generates and publishes the HTML dashboard, and sets the build to
SUCCESS, UNSTABLE or FAILURE from explicit, version-controlled rules.

## Learning objectives

By the end of the session a student can:

1. **Draw and explain** the flow *Developer → Git → Jenkins → JMeter → JTL → HTML dashboard → threshold evaluation → build status*, and say where each file lives at every step.
2. **Install and verify** Java, JMeter, Git and Jenkins, and prove from the Jenkins agent itself (not their laptop) that each tool is found.
3. **Build a realistic JMeter plan** with defaults, headers, CSV data, correlation, assertions and timers, and validate it in the GUI before committing it.
4. **Parameterize** the plan with JMeter properties (`__P`) and override them with `-J` and `-q`, and explain the difference between properties and variables.
5. **Write a declarative Jenkinsfile** with checkout, validation, smoke, load, archive, report, publish and gate stages that works on Linux and Windows agents.
6. **Run JMeter in non-GUI mode** and explain every option of `jmeter -n -t … -l … -e -o …`, its exit codes and its preconditions.
7. **Read the HTML dashboard**: samples, throughput, average, median, percentiles, error %, distribution, per-transaction statistics.
8. **Implement a performance gate** that turns raw results into a build decision and explain why "JMeter exited 0" does not mean "the application passed".
9. **Diagnose failures** by category – execution failure, assertion failure, threshold breach, infrastructure/agent failure – from the console log and archived artifacts.
10. **Apply CI/CD hygiene**: what to commit, secrets in Jenkins Credentials, no overlapping builds, smoke vs load separation, result retention.
11. **Describe the enterprise extension path**: dedicated/distributed load generators, scheduled regressions, Grafana/APM correlation, baselines and trend alerts.
12. **Answer senior interview questions** about performance testing as a CI/CD quality gate.

## Ground rules for the session

- Students type and run the steps themselves. Copying the trainer's repository is allowed only
  for the starting point; every checkpoint asks them to *show* a result from *their* Jenkins build.
- The CI "load" test in this lab is deliberately small (≤ 200 users, ≤ 30 minutes). It catches
  regressions; it is **not** a production-scale capacity test. That distinction is repeated in
  every module.
