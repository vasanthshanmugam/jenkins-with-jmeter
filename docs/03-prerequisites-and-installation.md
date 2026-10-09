# 3. Prerequisites and Installation

The lab has two kinds of machines:

| Machine | What runs there | What you install |
|---|---|---|
| **Lab Mac** `192.168.0.19` | Jenkins + JMeter (container `jenkins`), ShopLite API (container `perf-app`) | Docker Desktop, Git |
| **Student laptop** (Windows / macOS / Linux) | JMeter GUI for building and debugging the script; Git client | Java 17 or 21, JMeter 5.6.3, Git |

Nothing is assumed to be on `PATH` already. Every step ends with a verification command and the expected output.

---

## 3.1 Lab Mac: Docker Desktop and Git

1. Install **Docker Desktop for Mac** (Apple Silicon or Intel build to match the Mac) from docker.com and start it.
2. Docker Desktop → *Settings → Resources*: **CPUs ≥ 4, Memory ≥ 6 GB**. Jenkins + JMeter + the API share this budget; with less memory JMeter's 1 GB heap and Jenkins compete and results become noisy.
3. Verify:

```bash
docker version --format 'Client {{.Client.Version}} / Server {{.Server.Version}}'
docker compose version
git --version          # macOS offers to install the Command Line Tools on first use
```

Expected: a client and server version (no "Cannot connect to the Docker daemon"), `Docker Compose version v2.x`, `git version 2.x`.

4. Allow LAN access: *System Settings → Network → Firewall*. If the firewall is on, allow **Docker** to accept incoming connections, otherwise laptops cannot open `http://192.168.0.19:8080`.
5. Give the Mac a fixed IP (DHCP reservation on the router for `192.168.0.19`) – every student's `config/env/local.properties` points at it.

## 3.2 Lab Mac: start Jenkins and the API

```bash
git clone <your-repo-url> jenkins-with-jmeter
cd jenkins-with-jmeter
./setup/mac-setup.sh
```

What the script does (you can run the same commands by hand):

| Step | Command | Expected |
|---|---|---|
| Build + start | `docker compose up -d --build jenkins perf-app` | `Container perf-app Started`, `Container jenkins Started` |
| API from the Mac | `curl http://localhost:8081/health` | `{"status": "UP"}` |
| API from Jenkins | `docker exec jenkins curl -s http://perf-app:8080/health` | `{"status": "UP"}` |
| Java in Jenkins | `docker exec jenkins java -version` | `openjdk version "21.0.x"` |
| JMeter in Jenkins | `docker exec jenkins bash -c 'echo $JMETER_HOME; jmeter --version'` | `/opt/apache-jmeter-5.6.3` and the JMeter 5.6.3 banner |
| Unlock password | `docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword` | 32 hex characters |

What is inside the Jenkins image (`docker/jenkins/Dockerfile`):

- `jenkins/jenkins:2.580.1-lts-jdk21` – pinned LTS, multi-arch.
- JMeter 5.6.3 downloaded from `archive.apache.org`, **SHA-512 verified**, unpacked to `/opt/apache-jmeter-5.6.3`.
- `JMETER_HOME`, `PATH` and `HEAP=-Xms512m -Xmx1g` set as image environment variables – so every build on this node sees them.
- Plugins from `docker/jenkins/plugins.txt` pre-installed with `jenkins-plugin-cli`.

### Why a custom image instead of installing JMeter by hand in the container?

A container's filesystem is rebuilt from the image whenever the container is recreated. Anything installed with
`docker exec … apt-get install` disappears on the next `docker compose up --build`. Putting JMeter in the
Dockerfile makes the agent **reproducible** – the same reason we keep the JMX in Git.

## 3.3 Required Jenkins plugins and why

| Plugin (ID) | Needed for | If missing |
|---|---|---|
| Pipeline (`workflow-aggregator`) | `pipeline { }`, stages, `when`, `post` | Job type "Pipeline" does not exist |
| Git (`git`) | *Pipeline script from SCM*, `checkout scm` | Cannot read the Jenkinsfile from Git |
| Pipeline: Stage View (`pipeline-stage-view`) | Stage table on the job page | Cosmetic only |
| HTML Publisher (`htmlpublisher`) | `publishHTML` → "JMeter Dashboard" link | Stage 7 becomes UNSTABLE; dashboard still in artifacts |
| Timestamper (`timestamper`) | `timestamps()` option | Pipeline fails to start ("Invalid option type") |
| Credentials Binding (`credentials-binding`) | `withCredentials` for secrets | Only needed for the secrets exercise |
| Workspace Cleanup (`ws-cleanup`) | `cleanWs()` | Optional |

No JMeter-specific Jenkins plugin is used. The *Performance* plugin exists and can parse JTLs, but this lab
deliberately uses a script in Git for the gate so the rules are visible, reviewable and portable to any CI tool.

## 3.4 First-time Jenkins configuration

1. Browse to **http://192.168.0.19:8080**. *Unlock Jenkins* → paste the initial admin password.
2. *Customize Jenkins* → **Install suggested plugins** (our plugins are already present; this adds the usual set).
3. *Create First Admin User* → your own user. **Do not** reuse this password anywhere and never commit it.
4. *Instance Configuration* → Jenkins URL `http://192.168.0.19:8080/` → *Save and Finish*.
5. **Label the node that will run JMeter**: *Manage Jenkins → Nodes → Built-In Node → Configure*
   - Number of executors: `2`
   - Labels: `jmeter`
   - Usage: *Only build jobs with label expressions matching this node*
   - Save.
6. (Optional) *Manage Jenkins → Security → Agents*: leave the TCP port disabled; the optional agent uses WebSocket.

**Why a label?** The Jenkinsfile says `agent { label 'jmeter' }`. A label is a contract: "this machine has Java, JMeter
and network access to the target". Later you move the label to a dedicated agent and the pipeline code does not change.

> **Security note.** Jenkins warns that building on the built-in node is a risk – a pipeline running there
> can read Jenkins' own files. That is acceptable for a single-trainer lab; docs/11 §11.1 moves the work to a
> dedicated agent, which is what you do in any shared Jenkins.

## 3.5 Student laptop: Java

JMeter 5.6.3 runs on Java 8+, `PerfGate.java` needs Java 11+. Use **Java 17 or 21 (LTS)**.

| OS | Install | Verify |
|---|---|---|
| Windows | Eclipse Temurin 21 MSI from adoptium.net – tick *Set JAVA_HOME* and *Add to PATH* | `java -version` in a **new** PowerShell window |
| macOS | `brew install --cask temurin@21` | `java -version` |
| Ubuntu/Debian | `sudo apt-get install -y openjdk-21-jdk` | `java -version` |

Expected: `openjdk version "21.0.x"` (or 17.0.x). `'java' is not recognized` means PATH was not updated – open a new terminal or fix PATH.

## 3.6 Student laptop: JMeter 5.6.3

| OS | Install | JMETER_HOME |
|---|---|---|
| Windows | Download `apache-jmeter-5.6.3.zip` from jmeter.apache.org, extract to `C:\tools\` (avoid paths with spaces such as `Program Files`) | `C:\tools\apache-jmeter-5.6.3` |
| macOS/Linux | `curl -LO https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-5.6.3.tgz && sudo tar -xzf apache-jmeter-5.6.3.tgz -C /opt` | `/opt/apache-jmeter-5.6.3` |

Set `JMETER_HOME` and verify:

```powershell
# Windows (persist for your user, then open a new window)
setx JMETER_HOME "C:\tools\apache-jmeter-5.6.3"
& "$env:JMETER_HOME\bin\jmeter.bat" --version
```
```bash
# macOS/Linux (add to ~/.zshrc or ~/.bashrc)
export JMETER_HOME=/opt/apache-jmeter-5.6.3
export PATH="$JMETER_HOME/bin:$PATH"
jmeter --version
```

Expected: the ASCII "APACHE JMETER" banner followed by `5.6.3`.

Optional checksum check (recommended in training – it is what the Dockerfile does):

```bash
curl -s https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-5.6.3.tgz.sha512
shasum -a 512 apache-jmeter-5.6.3.tgz     # macOS;  sha512sum on Linux;  certutil -hashfile <file> SHA512 on Windows
```

## 3.7 Student laptop: Git

| OS | Install | Verify |
|---|---|---|
| Windows | Git for Windows from git-scm.com (default options) | `git --version` |
| macOS | `xcode-select --install` or `brew install git` | `git --version` |
| Linux | `sudo apt-get install -y git` | `git --version` |

Then once: `git config --global user.name "Your Name"` and `git config --global user.email "you@example.com"`.

Check that the laptop can reach the lab:

```bash
curl http://192.168.0.19:8081/health      # {"status": "UP"}
```

## 3.8 Windows vs Linux considerations

| Topic | Linux / macOS agent | Windows agent |
|---|---|---|
| Pipeline step | `sh` | `bat` (or `powershell`) |
| JMeter launcher | `$JMETER_HOME/bin/jmeter` | `%JMETER_HOME%\bin\jmeter.bat` – call it with `call … < NUL` because it runs `pause` on error |
| Env var syntax | `$VAR` | `%VAR%` |
| Path separator | `/` | `\` (JMeter also accepts `/`) |
| Line continuation | `\` | `^` |
| Delete a folder | `rm -rf reports` | `rmdir /s /q reports` |
| File locks | rare | an open JTL/report in Explorer or an antivirus scan can block `rmdir` |
| Service account | `jenkins` user | Jenkins service often runs as *Local System* – it does **not** see your user's PATH/JMETER_HOME; set them in *Node → Environment variables* |
| Long paths | fine | keep workspaces short (`C:\jw\...`) to avoid the 260-character limit |

The Jenkinsfile already contains both forms (`run(unixCmd, windowsCmd)`), chosen at runtime with `isUnix()`.

## 3.9 Moving to a Linux agent later

The same design moves to any Linux agent (VM, bare metal or container) when it has:

1. Java 17/21 (`java -version` works for the agent user).
2. JMeter unpacked and `JMETER_HOME` set **for the agent process** (Node → *Environment variables* or the agent's service file).
3. The label `jmeter`.
4. Network access to the system under test and to the Git server.

`docker/jmeter-agent/Dockerfile` is exactly that, packaged as a container; docs/11 §11.1 shows how to connect it.

## 3.10 Installation troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Cannot connect to the Docker daemon` | Docker Desktop not running | Start Docker Desktop, wait for "Engine running" |
| `sha512sum: WARNING: 1 computed checksum did NOT match` during build | Corrupt/intercepted download | Rebuild; check proxy; never remove the check |
| Laptop cannot open `:8080` but the Mac can | macOS firewall / wrong IP / guest Wi-Fi isolation | Allow Docker in firewall; `ipconfig getifaddr en0` on the Mac; same network |
| Jenkins page "Please wait while Jenkins is getting ready" for minutes | First start unpacking plugins / low memory | Wait; raise Docker memory; `docker logs -f jenkins` |
| `docker compose up` says port is already allocated | Another Jenkins/app on 8080/8081 | Stop it, or change the left side of `ports:` (e.g. `"9090:8080"`) |
| Jobs stay "Waiting for next available executor on jmeter" | No node has the label | Add label `jmeter` to the Built-In Node (§3.4 step 5) |
| `jmeter: command not found` on laptop | PATH not updated | Use the full path `$JMETER_HOME/bin/jmeter` or open a new terminal |
