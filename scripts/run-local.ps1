# Runs the same JMeter commands as the Jenkinsfile, from Windows PowerShell.
# Usage (from the repository root):
#   $env:JMETER_HOME = "C:\tools\apache-jmeter-5.6.3"
#   .\scripts\run-local.ps1 -Mode smoke -TargetEnv local
#   .\scripts\run-local.ps1 -Mode load -TargetEnv local -Threads 5 -RampUp 10 -Duration 60
param(
    [ValidateSet('smoke', 'load')] [string] $Mode = 'smoke',
    [string] $TargetEnv = 'local',
    [int] $Threads = 5,
    [int] $RampUp = 10,
    [int] $Duration = 60,
    [int] $ThinkTimeMs = 500
)
$ErrorActionPreference = 'Stop'

if (-not $env:JMETER_HOME) { throw 'Set JMETER_HOME first, e.g. $env:JMETER_HOME = "C:\tools\apache-jmeter-5.6.3"' }
$jmeter = Join-Path $env:JMETER_HOME 'bin\jmeter.bat'
if (-not (Test-Path $jmeter)) { throw "JMeter not found at $jmeter" }
Set-Location (Join-Path $PSScriptRoot '..')

foreach ($d in 'results', 'reports') { if (Test-Path $d) { Remove-Item -Recurse -Force $d } }
New-Item -ItemType Directory -Force results, reports | Out-Null

# jmeter.bat pauses on errors; "< NUL" makes the pause return immediately in scripts and CI.
function Invoke-JMeter([string] $argLine) {
    cmd /c "call `"$jmeter`" $argLine < NUL"
    if ($LASTEXITCODE -ne 0) { throw "JMeter exited with code $LASTEXITCODE" }
}

Write-Host ">>> Smoke test (1 user, 1 iteration) against config\env\$TargetEnv.properties"
Invoke-JMeter "-n -t test-plans\shoplite-api.jmx -q config\jmeter-ci.properties -q config\env\$TargetEnv.properties -Jthreads=1 -Jrampup=1 -Jloops=1 -Jduration=60 -Jthinktime=0 -Jthinktime_range=0 -l results\smoke.jtl -j results\jmeter-smoke.log"
java scripts\PerfGate.java --jtl results\smoke.jtl --thresholds config\thresholds-smoke.properties --summary results\perf-gate-smoke.txt
if ($LASTEXITCODE -ne 0 -or $Mode -eq 'smoke') { exit $LASTEXITCODE }

Write-Host ">>> Load test: $Threads users, ramp-up ${RampUp}s, duration ${Duration}s"
Invoke-JMeter "-n -t test-plans\shoplite-api.jmx -q config\jmeter-ci.properties -q config\env\$TargetEnv.properties -Jthreads=$Threads -Jrampup=$RampUp -Jduration=$Duration -Jloops=-1 -Jthinktime=$ThinkTimeMs -Jthinktime_range=$ThinkTimeMs -l results\load.jtl -j results\jmeter-load.log"

Write-Host '>>> HTML dashboard -> reports\html\index.html'
Invoke-JMeter '-g results\load.jtl -o reports\html -q config\jmeter-ci.properties -j results\jmeter-report.log'

java scripts\PerfGate.java --jtl results\load.jtl --thresholds config\thresholds-load.properties --summary results\perf-gate-load.txt
$rc = $LASTEXITCODE
Write-Host "Gate exit code: $rc  (0=PASS, 1=WARN/UNSTABLE, 2=FAIL, 3=ERROR)"
exit $rc
