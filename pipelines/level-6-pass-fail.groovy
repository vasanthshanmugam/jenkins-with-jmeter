// Level 6 - Pass/fail: JMeter exits 0 even when requests fail, so we check the results ourselves.
pipeline {
    agent { label 'jmeter' }

    parameters {
        string(name: 'THREADS',  defaultValue: '5',  description: 'Number of virtual users')
        string(name: 'RAMPUP',   defaultValue: '5',  description: 'Seconds to start all users')
        string(name: 'DURATION', defaultValue: '60', description: 'Test length in seconds')
    }

    // Copy the parameters into environment variables for the shell ($THREADS ...).
    // params.X always has a value (the default on the very first build); the automatic
    // environment variables for parameters are empty on the first build.
    environment {
        THREADS  = "${params.THREADS}"
        RAMPUP   = "${params.RAMPUP}"
        DURATION = "${params.DURATION}"
    }

    stages {
        stage('Checkout') {
            steps {
                git url: 'https://github.com/vasanthshanmugam/jenkins-with-jmeter.git', branch: 'main'
            }
        }

        stage('Run JMeter test') {
            steps {
                sh '''
                    rm -rf results
                    mkdir results
                    jmeter -n -t test-plans/shoplite-api.jmx \
                      -Jhost=perf-app -Jport=8080 \
                      -Jthreads="$THREADS" -Jrampup="$RAMPUP" -Jduration="$DURATION" \
                      -l results/results.jtl \
                      -j results/jmeter.log
                '''
            }
        }

        stage('Archive results') {
            steps {
                archiveArtifacts artifacts: 'results/*.jtl, results/*.log'
            }
        }

        stage('HTML report') {
            steps {
                sh 'jmeter -g results/results.jtl -o results/html'
                publishHTML(target: [
                    reportName : 'JMeter Report',
                    reportDir  : 'results/html',
                    reportFiles: 'index.html',
                    keepAll    : true,
                    allowMissing: false,
                    alwaysLinkToLastBuild: true
                ])
            }
        }

        stage('Check results') {
            steps {
                // Each line of results.jtl (after the header) is one request.
                // The "success" column is true or false, so failed requests contain ",false,".
                sh '''
                    total=$(tail -n +2 results/results.jtl | wc -l)
                    failed=$(tail -n +2 results/results.jtl | grep -c ',false,' || true)
                    echo "Requests: $total   Failed: $failed"

                    if [ "$total" -eq 0 ]; then
                        echo "FAIL: no requests were recorded - did the test run?"
                        exit 1
                    fi
                    if [ "$failed" -gt 0 ]; then
                        echo "FAIL: $failed request(s) failed - open the JMeter Report, Errors table"
                        exit 1
                    fi
                    echo "PASS: all requests succeeded"
                '''
            }
        }
    }
}
