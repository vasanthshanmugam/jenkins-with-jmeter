# 4. Git Repository Structure and CI/CD Practices

## 4.1 Layout

```
jenkins-with-jmeter/
├── Jenkinsfile                         # the pipeline (versioned with the test it runs)
├── README.md
├── docker-compose.yml                  # lab infrastructure on the Mac
├── .env.example                        # template for local, non-committed settings
├── .gitignore / .gitattributes
├── test-plans/
│   └── shoplite-api.jmx                # JMeter test plan (all settings via __P properties)
├── test-data/
│   └── users.csv                       # synthetic test users (no real data, ever)
├── config/
│   ├── jmeter-ci.properties            # JTL format, summariser, dashboard settings
│   ├── env/
│   │   ├── ci.properties               # target as seen from the Jenkins agent
│   │   ├── local.properties            # target as seen from a laptop
│   │   └── qa.properties               # example second environment
│   ├── thresholds-smoke.properties     # gate rules for the smoke test
│   └── thresholds-load.properties      # gate rules for the CI load test
├── scripts/
│   ├── PerfGate.java                   # JTL -> PASS/WARN/FAIL (exit 0/1/2/3)
│   ├── run-local.sh                    # same commands as Jenkins, macOS/Linux
│   └── run-local.ps1                   # same commands as Jenkins, Windows
├── app/                                # ShopLite API (system under test)
│   ├── server.py
│   └── Dockerfile
├── docker/
│   ├── jenkins/Dockerfile, plugins.txt # Jenkins + JMeter image
│   └── jmeter-agent/Dockerfile         # dedicated agent image (docs/11)
├── setup/
│   └── mac-setup.sh
└── docs/                               # this training guide
```

## 4.2 What is version-controlled and what is not

| Commit ✅ | Never commit ❌ |
|---|---|
| `*.jmx`, `Jenkinsfile`, scripts | `results/`, `reports/`, `*.jtl`, `jmeter.log` (generated every run; huge; may contain response data) |
| Synthetic test data (`users.csv`) | Real customer data, production exports, PII |
| Environment *settings* (host, port, protocol) | Passwords, API keys, tokens, certificates (`.env`, `*.secret.properties`) |
| Thresholds and baselines | JMeter GUI backups (`*.jmx.bak`, `backups/`) |
| Dockerfiles, compose file, plugin list | `jenkins_home` contents |

`.gitignore` already enforces the right-hand column. `.gitattributes` pins line endings so a Jenkinsfile
edited on Windows still runs on a Linux agent (`Jenkinsfile`, `*.sh` → LF; `*.bat`, `*.ps1` → CRLF).

> The `users.csv` passwords (`Passw0rd!`) are deliberately fake lab data for a lab-only API. In a real
> project, generate test accounts per environment and keep their passwords in the Jenkins credential store.

## 4.3 Create your own repository (student task)

```bash
# 1. Start from the trainer's copy (zip or clone), then make it yours
cd jenkins-with-jmeter
rm -rf .git
git init -b main
git add .
git status                 # check: no results/, reports/, .env in the list
git commit -m "JMeter + Jenkins lab: initial import"

# 2. Create an empty repository named jenkins-with-jmeter on GitHub (no README), then:
git remote add origin https://github.com/<you>/jenkins-with-jmeter.git
git push -u origin main
```

For a **private** repository, Jenkins needs a credential: on GitHub create a fine-grained token with
*Contents: Read-only* on this repository, then in Jenkins *Manage Jenkins → Credentials → (global) → Add* →
*Username with password* (username = GitHub user, password = token), ID `github-readonly`.

## 4.4 Triggering builds

| Trigger | How | When to use |
|---|---|---|
| Manual | *Build with Parameters* | Demos, ad-hoc runs with different load |
| Poll SCM | `triggers { pollSCM('H/5 * * * *') }` (in our Jenkinsfile) | Jenkins on a private LAN (like the lab Mac) that GitHub cannot reach |
| Webhook | GitHub repo → *Settings → Webhooks* → `http://<public-jenkins>/github-webhook/` + "GitHub" plugin | Jenkins reachable from the internet / corporate Git server |
| Schedule | `triggers { cron('H 2 * * 1-5') }` | Nightly regression with a longer load profile |
| Upstream | `build job: 'shoplite-perf'` from the application's pipeline after deploy | Real CD: test the build that was just deployed |

`H` spreads start times by job name so many jobs do not fire at the same second.
Triggers declared in a Jenkinsfile are registered **after the first build** – run it once manually.

## 4.5 Build parameters

Defined in the `parameters {}` block (see docs/06). Rules we follow:

- Every parameter has a **safe default**, so a triggered (non-manual) build works.
- Numeric parameters are **validated** in stage 2 with explicit limits (THREADS 1–200, DURATION 10–1800 s) –
  a typo cannot turn a CI job into an accidental stress test.
- Parameters reach shell commands as **environment variables** (`$THREADS`, `%THREADS%`), not via Groovy
  string interpolation, so input like `10; rm -rf ~` is never executed.

## 4.6 Keeping credentials out of source control

1. Store secrets in *Manage Jenkins → Credentials*.
2. Bind them only where needed:

```groovy
withCredentials([string(credentialsId: 'shoplite-api-key', variable: 'API_KEY')]) {
    sh '''
      printf 'api_key=%s\n' "$API_KEY" > results/secret.properties
      "$JMETER_HOME/bin/jmeter" -n -t "$JMX" -q "$CI_PROPS" -q results/secret.properties ...
      rm -f results/secret.properties
    '''
}
```

Why a temporary `-q` file instead of `-Japi_key=$API_KEY`? A `-J` value is visible in the process list and
**JMeter writes every `-J` property to `jmeter.log`** ("Setting JMeter property: api_key=…"), which we archive.
Jenkins masks the bound value in the console, but not inside archived files. Inside the JMX read it with
`${__P(api_key)}`. Also keep `jmeter.save.saveservice.requestHeaders=false` (already in `jmeter-ci.properties`)
so `Authorization` headers never reach the JTL.

## 4.7 Separating smoke tests from load tests

- Stage 3 (smoke) always runs – it is cheap and catches broken scripts/environments in < 30 s.
- Stages 4–8 run only when `TEST_TYPE=load`.
- Long or high-volume tests belong in a separate job (e.g. `shoplite-perf-nightly`) with its own thresholds file,
  its own schedule and a dedicated agent – never in the developer feedback path.

## 4.8 Environment-specific settings and test data

- One properties file per environment in `config/env/`, selected by `TARGET_ENV` and loaded with `-q`.
- Thresholds are per test type today; for per-environment baselines add `config/thresholds-load-<env>.properties`.
- Test data must be valid in the target environment (users exist, products exist). If data is consumed
  (e.g. one-time orders), generate it in a setup step rather than reusing a static CSV.

## 4.9 Preventing overlapping builds

`options { disableConcurrentBuilds() }` queues a second build until the first one finishes. Without it,
two builds would share the same workspace, delete each other's `results/` folder, and – worse – load the same
environment at the same time, so both results are wrong. For tests against a *shared* environment, also use the
Lockable Resources plugin (`lock('shoplite-qa') { ... }`) so jobs from different pipelines cannot overlap.

## 4.10 Storing and comparing results across builds

- Each build archives `results/load.jtl`, `results/perf-gate-load.txt` and the dashboard – compare build N
  with build N-1 directly in the Jenkins UI.
- `buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '15'))` caps disk usage: 30 builds of
  history, artifacts for the last 15.
- For long-term trends export metrics to a time-series database (docs/11 §11.4–11.6) instead of keeping years
  of JTLs in Jenkins.
