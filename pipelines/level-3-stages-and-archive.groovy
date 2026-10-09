// Level 3 - Split the work into stages and keep the results after the build.
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
                // Copies the files to the build record: Build > "Build Artifacts"
                archiveArtifacts artifacts: 'results/*.jtl, results/*.log'
            }
        }
    }
}
