// =====================================================================================
//  JMeter + Jenkins performance pipeline - ShopLite API lab
//  Works on Linux/macOS agents (sh) and Windows agents (bat). The agent must have:
//    - Java 11+ on PATH            (runs JMeter and scripts/PerfGate.java)
//    - JMETER_HOME environment var (e.g. /opt/apache-jmeter-5.6.3 or C:\tools\apache-jmeter-5.6.3)
//    - the label "jmeter"
//  Build result rules
//    FAILURE  : agent/tool problem, JMeter could not run, smoke test failed, JTL missing,
//               or a "fail" performance threshold was breached
//    UNSTABLE : a "warn" threshold was breached, or the HTML report could not be generated/published
//    SUCCESS  : everything ran and every threshold passed
// =====================================================================================

// Runs a POSIX shell command on Linux/macOS agents or a batch command on Windows agents.
// Returns the exit code instead of failing, so each stage can decide what a non-zero code means.
def run(String unixCmd, String windowsCmd) {
    return isUnix() ? sh(script: unixCmd, returnStatus: true) : bat(script: windowsCmd, returnStatus: true)
}

// Evaluates a JTL with scripts/PerfGate.java and converts its exit code into a build result.
def perfGate(String name, String jtl, String thresholds, String summary, String overrides) {
    int rc
    withEnv(["GATE_JTL=${jtl}", "GATE_THRESHOLDS=${thresholds}", "GATE_SUMMARY=${summary}", "GATE_ARGS=${overrides}"]) {
        rc = run('java scripts/PerfGate.java --jtl "$GATE_JTL" --thresholds "$GATE_THRESHOLDS" --summary "$GATE_SUMMARY" $GATE_ARGS',
                 'java scripts\\PerfGate.java --jtl "%GATE_JTL%" --thresholds "%GATE_THRESHOLDS%" --summary "%GATE_SUMMARY%" %GATE_ARGS%')
    }
    switch (rc) {
        case 0:
            echo "${name}: PASS - all thresholds met"
            break
        case 1:
            unstable("${name}: WARN threshold breached - see ${summary}")
            break
        case 2:
            error("${name}: FAIL threshold breached - see ${summary}")
            break
        default:
            error("${name}: gate could not evaluate the results (exit ${rc}) - is the JTL missing or empty?")
    }
}

// Reads simple key=value pairs (enough for config/env/*.properties; no plugin required).
def readKeyValues(String path) {
    def map = [:]
    readFile(path).readLines().each { line ->
        def l = line.trim()
        if (l && !l.startsWith('#') && l.contains('=')) {
            def i = l.indexOf('=')
            map[l.substring(0, i).trim()] = l.substring(i + 1).trim()
        }
    }
    return map
}

pipeline {
    agent { label 'jmeter' }

    parameters {
        choice(name: 'TEST_TYPE', choices: ['load', 'smoke-only'],
               description: 'smoke-only = 1 user, 1 iteration (fast feedback). load = smoke + CI load test.')
        choice(name: 'TARGET_ENV', choices: ['ci', 'qa'],
               description: 'Selects config/env/<TARGET_ENV>.properties (protocol, host, port).')
        string(name: 'THREADS', defaultValue: '10', trim: true, description: 'Virtual users for the load test (1-200).')
        string(name: 'RAMPUP', defaultValue: '20', trim: true, description: 'Ramp-up in seconds (0-600).')
        string(name: 'DURATION', defaultValue: '120', trim: true, description: 'Load test duration in seconds (10-1800).')
        string(name: 'THINK_TIME_MS', defaultValue: '500', trim: true, description: 'Base think time per request in ms (0-10000).')
        string(name: 'GATE_OVERRIDES', defaultValue: '', trim: true,
               description: 'Optional threshold overrides, space separated, e.g.  p95.ms.warn=300 error.pct.fail=2')
    }

    options {
        disableConcurrentBuilds()          // two load tests at once would corrupt each other's results
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '15'))
        timeout(time: 45, unit: 'MINUTES') // a hung test must not block the agent forever
        timestamps()
        skipDefaultCheckout(true)          // checkout happens in its own visible stage
    }

    triggers {
        pollSCM('H/5 * * * *')             // build when the repository changes (see docs/10 for webhooks)
    }

    environment {
        JMX            = 'test-plans/shoplite-api.jmx'
        CI_PROPS       = 'config/jmeter-ci.properties'
        // Parameters are copied into env vars so shell/batch commands reference them as
        // $VAR / %VAR% - never by Groovy string interpolation (prevents command injection).
        TEST_TYPE      = "${params.TEST_TYPE}"
        TARGET_ENV     = "${params.TARGET_ENV}"
        THREADS        = "${params.THREADS}"
        RAMPUP         = "${params.RAMPUP}"
        DURATION       = "${params.DURATION}"
        THINK_TIME_MS  = "${params.THINK_TIME_MS}"
        // GATE_OVERRIDES is read from params directly: an empty string in this block becomes "null".
    }

    stages {

        stage('1. Checkout') {
            steps {
                checkout scm
                script {
                    def commit = isUnix() ? sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                                          : bat(script: '@git rev-parse --short HEAD', returnStdout: true).trim()
                    currentBuild.description = "${env.TEST_TYPE} | ${env.THREADS}u x ${env.DURATION}s | ${env.TARGET_ENV} | ${commit}"
                }
            }
        }

        stage('2. Validate environment') {
            steps {
                script {
                    // ---- parameters (invalid values fail fast, before any load is generated) ----
                    def checkInt = { String name, String value, int min, int max ->
                        if (!(value ==~ /\d+/) || value.toInteger() < min || value.toInteger() > max) {
                            error("Invalid parameter ${name}='${value}': must be an integer between ${min} and ${max}")
                        }
                    }
                    checkInt('THREADS', env.THREADS, 1, 200)
                    checkInt('RAMPUP', env.RAMPUP, 0, 600)
                    checkInt('DURATION', env.DURATION, 10, 1800)
                    checkInt('THINK_TIME_MS', env.THINK_TIME_MS, 0, 10000)
                    def overrides = params.GATE_OVERRIDES ?: ''
                    if (overrides && !(overrides ==~ /([A-Za-z0-9._-]+=[A-Za-z0-9._,-]+)(\s+[A-Za-z0-9._-]+=[A-Za-z0-9._,-]+)*/)) {
                        error("Invalid GATE_OVERRIDES='${overrides}': use key=value pairs separated by spaces")
                    }

                    // ---- required files in the workspace ----
                    def required = [env.JMX, env.CI_PROPS, "config/env/${env.TARGET_ENV}.properties",
                                    'config/thresholds-smoke.properties', 'config/thresholds-load.properties',
                                    'test-data/users.csv', 'scripts/PerfGate.java']
                    def missing = required.findAll { !fileExists(it) }
                    if (missing) {
                        error("Missing files in workspace ${env.WORKSPACE}: ${missing}")
                    }

                    // ---- tools on this agent ----
                    int rc = run('''
                        echo "Agent: $NODE_NAME   Workspace: $WORKSPACE"
                        uname -a
                        java -version || { echo "ERROR: java is not on PATH for the Jenkins agent"; exit 10; }
                        [ -n "$JMETER_HOME" ] || { echo "ERROR: JMETER_HOME is not set on this agent"; exit 11; }
                        [ -x "$JMETER_HOME/bin/jmeter" ] || { echo "ERROR: $JMETER_HOME/bin/jmeter not found or not executable"; exit 12; }
                        "$JMETER_HOME/bin/jmeter" --version
                    ''', '''
                        @echo Agent: %NODE_NAME%   Workspace: %WORKSPACE%
                        ver
                        java -version || (echo ERROR: java is not on PATH for the Jenkins agent & exit /b 10)
                        if "%JMETER_HOME%"=="" (echo ERROR: JMETER_HOME is not set on this agent & exit /b 11)
                        if not exist "%JMETER_HOME%\\bin\\jmeter.bat" (echo ERROR: %JMETER_HOME%\\bin\\jmeter.bat not found & exit /b 12)
                        call "%JMETER_HOME%\\bin\\jmeter.bat" --version < NUL
                    ''')
                    if (rc != 0) {
                        error("Agent tool check failed (exit ${rc}) - see the console output above")
                    }

                    // ---- target reachable? (separates 'environment down' from 'test failed') ----
                    def target = readKeyValues("config/env/${env.TARGET_ENV}.properties")
                    def url = "${target.protocol ?: 'http'}://${target.host}:${target.port}/health"
                    withEnv(["TARGET_HEALTH_URL=${url}"]) {
                        rc = run('curl -fsS --max-time 10 "$TARGET_HEALTH_URL"',
                                 'curl.exe -fsS --max-time 10 "%TARGET_HEALTH_URL%"')
                    }
                    if (rc != 0) {
                        error("Target ${url} is not reachable from agent ${env.NODE_NAME} (curl exit ${rc}) - environment problem, not a performance result")
                    }

                    // ---- fresh output folders for every build ----
                    rc = run('rm -rf results reports && mkdir -p results reports', '''
                        if exist results rmdir /s /q results
                        if exist reports rmdir /s /q reports
                        mkdir results reports
                    ''')
                    if (rc != 0) {
                        error('Could not create clean results/ and reports/ folders - is a file locked on the agent?')
                    }
                }
            }
        }

        stage('3. Smoke test') {
            steps {
                script {
                    int rc = run('''
                        "$JMETER_HOME/bin/jmeter" -n -t "$JMX" \
                          -q "$CI_PROPS" -q "config/env/$TARGET_ENV.properties" \
                          -Jthreads=1 -Jrampup=1 -Jloops=1 -Jduration=60 -Jthinktime=0 -Jthinktime_range=0 \
                          -l results/smoke.jtl -j results/jmeter-smoke.log
                    ''', '''
                        call "%JMETER_HOME%\\bin\\jmeter.bat" -n -t "%JMX%" ^
                          -q "%CI_PROPS%" -q "config\\env\\%TARGET_ENV%.properties" ^
                          -Jthreads=1 -Jrampup=1 -Jloops=1 -Jduration=60 -Jthinktime=0 -Jthinktime_range=0 ^
                          -l results\\smoke.jtl -j results\\jmeter-smoke.log < NUL
                    ''')
                    if (rc != 0) {
                        error("JMeter smoke run exited with code ${rc} - see results/jmeter-smoke.log")
                    }
                    perfGate('Smoke gate', 'results/smoke.jtl', 'config/thresholds-smoke.properties',
                             'results/perf-gate-smoke.txt', '')
                }
            }
        }

        stage('4. Load test (non-GUI)') {
            when { expression { env.TEST_TYPE == 'load' } }
            steps {
                script {
                    int rc = run('''
                        "$JMETER_HOME/bin/jmeter" -n -t "$JMX" \
                          -q "$CI_PROPS" -q "config/env/$TARGET_ENV.properties" \
                          -Jthreads="$THREADS" -Jrampup="$RAMPUP" -Jduration="$DURATION" -Jloops=-1 \
                          -Jthinktime="$THINK_TIME_MS" -Jthinktime_range="$THINK_TIME_MS" \
                          -l results/load.jtl -j results/jmeter-load.log
                    ''', '''
                        call "%JMETER_HOME%\\bin\\jmeter.bat" -n -t "%JMX%" ^
                          -q "%CI_PROPS%" -q "config\\env\\%TARGET_ENV%.properties" ^
                          -Jthreads=%THREADS% -Jrampup=%RAMPUP% -Jduration=%DURATION% -Jloops=-1 ^
                          -Jthinktime=%THINK_TIME_MS% -Jthinktime_range=%THINK_TIME_MS% ^
                          -l results\\load.jtl -j results\\jmeter-load.log < NUL
                    ''')
                    if (rc != 0) {
                        error("JMeter load run exited with code ${rc} - see results/jmeter-load.log")
                    }
                    if (!fileExists('results/load.jtl')) {
                        error('JMeter finished but results/load.jtl was not created - see results/jmeter-load.log')
                    }
                }
            }
        }

        stage('5. Archive raw results') {
            when { expression { env.TEST_TYPE == 'load' } }
            steps {
                archiveArtifacts artifacts: 'results/*.jtl, results/*.log', fingerprint: true
            }
        }

        stage('6. Generate HTML dashboard') {
            when { expression { env.TEST_TYPE == 'load' } }
            steps {
                // A broken report must not hide the performance verdict: mark UNSTABLE and continue,
                // because the gate (stage 8) reads the JTL, not the report.
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                    script {
                        int rc = run('''
                            rm -rf reports/html
                            "$JMETER_HOME/bin/jmeter" -g results/load.jtl -o reports/html \
                              -q "$CI_PROPS" -j results/jmeter-report.log
                        ''', '''
                            if exist reports\\html rmdir /s /q reports\\html
                            call "%JMETER_HOME%\\bin\\jmeter.bat" -g results\\load.jtl -o reports\\html ^
                              -q "%CI_PROPS%" -j results\\jmeter-report.log < NUL
                        ''')
                        if (rc != 0 || !fileExists('reports/html/index.html')) {
                            error("HTML dashboard generation failed (exit ${rc}) - see results/jmeter-report.log")
                        }
                    }
                }
            }
        }

        stage('7. Publish report') {
            when {
                allOf {
                    expression { env.TEST_TYPE == 'load' }
                    expression { fileExists('reports/html/index.html') }
                }
            }
            steps {
                // Plugin-independent: the dashboard is always downloadable from Build Artifacts.
                archiveArtifacts artifacts: 'reports/html/**', allowEmptyArchive: false
                // HTML Publisher plugin: adds a "JMeter Dashboard" link to the build page.
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                    publishHTML(target: [
                        reportName           : 'JMeter Dashboard',
                        reportDir            : 'reports/html',
                        reportFiles          : 'index.html',
                        keepAll              : true,
                        alwaysLinkToLastBuild: true,
                        allowMissing         : false
                    ])
                }
            }
        }

        stage('8. Performance gate') {
            when { expression { env.TEST_TYPE == 'load' } }
            steps {
                script {
                    perfGate('Load gate', 'results/load.jtl', 'config/thresholds-load.properties',
                             'results/perf-gate-load.txt', params.GATE_OVERRIDES ?: '')
                }
            }
        }
    }

    post {
        always {
            // Logs and gate summaries are kept for every build, including failed ones.
            archiveArtifacts artifacts: 'results/*.log, results/perf-gate-*.txt', allowEmptyArchive: true
        }
        failure {
            // Partial results help diagnose a failed run (stage 5 may not have been reached).
            archiveArtifacts artifacts: 'results/*.jtl', allowEmptyArchive: true
            echo 'BUILD FAILED - check: tool/agent errors (stage 2), JMeter logs (results/jmeter-*.log), gate summary (results/perf-gate-*.txt)'
        }
        unstable {
            echo 'BUILD UNSTABLE - a warning threshold was breached or the report could not be published. See results/perf-gate-*.txt'
        }
        success {
            echo 'BUILD SUCCESS - all performance thresholds met.'
        }
    }
}
