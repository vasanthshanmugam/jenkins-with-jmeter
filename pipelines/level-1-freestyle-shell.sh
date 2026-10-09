# Level 1 - Freestyle job: paste this into  Build Steps > Add build step > Execute shell
# Jenkins has already checked out the Git repository into the workspace (Source Code Management = Git).

# Start with an empty results folder on every build
rm -rf results
mkdir results

# Run the JMeter test in non-GUI mode
jmeter -n -t test-plans/shoplite-api.jmx \
  -Jhost=perf-app -Jport=8080 \
  -Jthreads=5 -Jrampup=5 -Jduration=60 \
  -l results/results.jtl
