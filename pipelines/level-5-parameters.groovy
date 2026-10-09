// Level 5 - Parameters: choose the load (users, ramp-up, duration) when you start the build.
pipeline {
    agent { label 'jmeter' }

    parameters {
        string(name: 'THREADS',  defaultValue: '5',  description: 'Number of virtual users')
        string(name: 'RAMPUP',   defaultValue: '5',  description: 'Seconds to start all users')
        string(name: 'DURATION', defaultValue: '60', description: 'Test length in seconds')
    }

    stages {
        stage('Checkout') {
            steps {
                git url: 'https://github.com/vasanthshanmugam/jenkins-with-jmeter.git', branch: 'main'
            }
        }

        stage('Run JMeter test') {
            steps {
                // Build parameters are available to the shell as environment variables: $THREADS etc.
                // -Jthreads=... sets the JMeter property that the test plan reads with ${__P(threads,5)}
                sh '''
                    echo "Load: $THREADS users, ramp-up $RAMPUP s, duration $DURATION s"
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
    }
}
