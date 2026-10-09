// Level 2 - First pipeline: one stage that gets the code and runs JMeter.
// Paste into: Job > Configure > Pipeline > Definition = "Pipeline script".
pipeline {
    agent { label 'jmeter' }                 // run on a machine that has JMeter

    stages {
        stage('Run JMeter test') {
            steps {
                // 1. Get the test plan from GitHub
                git url: 'https://github.com/vasanthshanmugam/jenkins-with-jmeter.git', branch: 'main'

                // 2. Run JMeter in non-GUI mode (exactly the command you would type in a terminal)
                sh '''
                    rm -rf results
                    mkdir results
                    jmeter -n -t test-plans/shoplite-api.jmx \
                      -Jhost=perf-app -Jport=8080 \
                      -Jthreads=5 -Jrampup=5 -Jduration=60 \
                      -l results/results.jtl
                '''
            }
        }
    }
}
