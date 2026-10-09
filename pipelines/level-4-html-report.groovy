// Level 4 - Generate the JMeter HTML dashboard and show it on the build page.
pipeline {
    agent { label 'jmeter' }

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
                      -Jthreads=5 -Jrampup=5 -Jduration=60 \
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
                // -g = build a report from an existing results file, -o = output folder (must not exist yet)
                sh 'jmeter -g results/results.jtl -o results/html'

                // HTML Publisher plugin: adds a "JMeter Report" link to the build page
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
