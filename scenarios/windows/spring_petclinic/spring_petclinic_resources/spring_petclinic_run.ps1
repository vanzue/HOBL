# Copyright (c) Microsoft. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.

param(
    [string]$logFile = "",
    [string]$startTime = (Get-Date).ToString("o")
)

$scriptDrive = Split-Path -Qualifier $PSScriptRoot

if (-not (Test-Path "$scriptDrive\hobl_data")) {
    Write-Host " ERROR - Required directory not found: $scriptDrive\hobl_data" -ForegroundColor Red
    Exit 1
}
if (-not (Test-Path "$scriptDrive\hobl_bin")) {
    Write-Host " ERROR - Required directory not found: $scriptDrive\hobl_bin" -ForegroundColor Red
    Exit 1
}

$timeTraceFile = "$scriptDrive\hobl_data\result_time.trace"

if (-not $logFile) { $logFile = "$scriptDrive\hobl_data\spring_petclinic_run.log" }

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 9F0F6E2E-8D06-4D2F-B8F5-6F1F2D5A1C01 is a custom provider we use to emit phase markers from the scenario script (optional, may not be present)
if (-not ('HoblRunPhaseProvider' -as [type])) {
    Add-Type -TypeDefinition @"
using System.Diagnostics.Tracing;

[EventSource(Name = "HOBL-Scenario-Phases", Guid = "9f0f6e2e-8d06-4d2f-b8f5-6f1f2d5a1c01")]
public sealed class HoblRunPhaseProvider : EventSource
{
    public static readonly HoblRunPhaseProvider Log = new HoblRunPhaseProvider();

    [Event(1, Level = EventLevel.Informational)]
    public void Phase(string marker, string scenario)
    {
        WriteEvent(1, marker, scenario);
    }
}
"@
}

function Write-RunPhaseMarker {
    param([string]$Marker)

    try {
        [HoblRunPhaseProvider]::Log.Phase($Marker, "spring_petclinic")
    } catch {
    }
}

# Determine processor architecture for log file naming
$osInfo = Get-CimInstance Win32_OperatingSystem
$arch = $osInfo.OSArchitecture
$processorArch = $env:PROCESSOR_ARCHITECTURE

if ($arch -eq "64-bit" -and $processorArch -eq "AMD64") {
    $logSuffix = "x64"
} elseif ($arch -match "ARM" -or $processorArch -match "ARM") {
    $logSuffix = "ARM64"
} else {
    Write-Host " ERROR - Unsupported architecture: $arch (Processor: $processorArch)" -ForegroundColor Red
    Exit 1
}

# Update log file name to include architecture
$logFile = $logFile -replace "\.log$", "_$($logSuffix.ToLower()).log"

function log {
    [CmdletBinding()] Param([Parameter(ValueFromPipeline)] $msg)
    process {
        if ($msg -Match " ERROR - ") {
            Write-Host $msg -ForegroundColor Red
        } else {
            Write-Host $msg
        }
        Add-Content -Path $logFile -encoding utf8 "$msg"
    }
}

function check {
    param($code)
    if ($code -ne 0) {
        " ERROR - Last command failed." | log
        Exit 1
    }
}

Set-Content -Path $logFile -encoding utf8 "-- spring_petclinic_run started ($logSuffix version)"
Write-RunPhaseMarker "phase.run_prep.start"

$Env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")

# Find the Microsoft JDK 25 installation dynamically
$microsoftJdkBase = "${env:ProgramFiles}\Microsoft"
$javaHome = $null

if (Test-Path $microsoftJdkBase) {
    # Look for any jdk-25.* directory
    $jdkDirs = Get-ChildItem -Path $microsoftJdkBase -Directory -Filter "jdk-25.*" | Sort-Object Name -Descending
    if ($jdkDirs.Count -gt 0) {
        $javaHome = $jdkDirs[0].FullName
        "Found Java 25 installation at: $javaHome" | log
    }
}

if ($javaHome -and (Test-Path $javaHome)) {
    $Env:JAVA_HOME = $javaHome
    # Refresh PATH to include new Java
    $Env:Path = "$Env:JAVA_HOME\bin;" + $Env:Path
    "Set JAVA_HOME to: $javaHome" | log
} else {
    " ERROR - Could not find Java 25 installation in $microsoftJdkBase" | log
    Exit 1
}

Set-Location "$scriptDrive\spring-petclinic"

# ============================================================================
# Clean phase (not timed)
# ============================================================================
# Redirect Maven output to a per-phase log under hobl_data so it is preserved
# in the results share. Without redirection, stdout goes only to the RPC
# buffer and is lost on RPC timeout. Build/test phases below are wrapped in
# Measure-Command which DISCARDS stdout entirely, so file redirection is the
# only way to capture their output.
"-- spring_petclinic clean started" | log
$mavenCleanLog = "$scriptDrive\hobl_data\spring_petclinic_maven_clean_$($logSuffix.ToLower()).log"
"-- Maven clean output: $mavenCleanLog" | log
.\mvnw.cmd -o "-Dmaven.repo.local=$scriptDrive\temp\m2-spring-petclinic" clean *> $mavenCleanLog
check($lastexitcode)
"-- spring_petclinic clean completed" | log

$tools = "$scriptDrive\hobl_bin\petclinic_trace"
$env:HOBL_TRACE_OUT = (New-Item -ItemType Directory -Path "$scriptDrive\hobl_data\spring_startup_trace-$([guid]::NewGuid())" -ErrorAction Stop).FullName
$env:HOBL_RUNNER_PID = "$PID"
"Spring startup tracing enabled (Maven and Surefire): $env:HOBL_TRACE_OUT" | log
$versionLog = Join-Path $env:HOBL_TRACE_OUT "maven-version.log"
.\mvnw.cmd -o --version *> $versionLog
check($LASTEXITCODE)
& "$Env:JAVA_HOME\bin\java.exe" "$tools\PrepareOuter.java" $tools $versionLog
check($LASTEXITCODE)
$outer = Join-Path $env:HOBL_TRACE_OUT "outer"
$wrapper = Join-Path $outer "mvnw.cmd"
$ext = "-Dmaven.ext.class.path=$outer\probe.jar"

function Save-CommandBoundary($phase, $startWall, $startTick, $endWall, $endTick, $duration, $code) {
    @(
        "runner_pid`tstart_utc`tstart_tick`tend_utc`tend_tick`tticks_per_second`tmeasured_ticks_100ns`texit_code"
        "$PID`t$startWall`t$startTick`t$endWall`t$endTick`t$([Diagnostics.Stopwatch]::Frequency)`t$($duration.Ticks)`t$code"
    ) | Set-Content -LiteralPath "$outer\$phase-command.tsv" -Encoding utf8 -ErrorAction Stop
}

# ============================================================================
# Build phase (timed)
# ============================================================================
"-- spring_petclinic build started" | log
Write-RunPhaseMarker "phase.run_prep.end"
Write-RunPhaseMarker "phase.run_build.start"

$mavenBuildLog = "$env:HOBL_TRACE_OUT\maven-package.log"
"-- Maven build output: $mavenBuildLog" | log

$env:HOBL_TRACE_PHASE = "package"
$startWall = [DateTimeOffset]::UtcNow.ToString("o")
$startTick = [Diagnostics.Stopwatch]::GetTimestamp()
$buildDuration = Measure-Command {
    & $wrapper -o $ext "-Dmaven.repo.local=$scriptDrive\temp\m2-spring-petclinic" "-DskipTests" package *> $mavenBuildLog
}
$buildExit = $LASTEXITCODE
$endTick = [Diagnostics.Stopwatch]::GetTimestamp()
Save-CommandBoundary package $startWall $startTick ([DateTimeOffset]::UtcNow.ToString("o")) $endTick $buildDuration $buildExit
Copy-Item -LiteralPath $mavenBuildLog -Destination "$scriptDrive\hobl_data\spring_petclinic_maven_build_$($logSuffix.ToLower()).log" -ErrorAction Stop
Copy-Item -LiteralPath $mavenBuildLog -Destination "$env:HOBL_TRACE_OUT\maven-build.log" -ErrorAction Stop
if ($buildExit -ne 0) {
    " ERROR - Offline build failed. See $mavenBuildLog for Maven output. Ensure prep completed online and populated $scriptDrive\temp\m2-spring-petclinic" | log
    Exit 1
}

$buildTime = [math]::Round($buildDuration.TotalSeconds, 2)
"-- spring_petclinic build completed in ${buildTime}s" | log
Write-RunPhaseMarker "phase.run_build.end"
& "$Env:JAVA_HOME\bin\java.exe" "$tools\PrepareProbe.java" $tools "$PSScriptRoot\trace-classpath.txt"
check($LASTEXITCODE)
Write-RunPhaseMarker "phase.run_test.start"

# ============================================================================
# Test phase (timed)
# ============================================================================
"-- spring_petclinic test started" | log

$mavenTestLog = "$env:HOBL_TRACE_OUT\maven-test.log"
"-- Maven test output: $mavenTestLog" | log

$env:HOBL_TRACE_PHASE = "test"
$startWall = [DateTimeOffset]::UtcNow.ToString("o")
$startTick = [Diagnostics.Stopwatch]::GetTimestamp()
$testDuration = Measure-Command {
    & $wrapper -o $ext "-Dmaven.repo.local=$scriptDrive\temp\m2-spring-petclinic" "-Dmaven.surefire.debug=-javaagent:target/hobl-agent.jar" "-Djunit.jupiter.extensions.autodetection.enabled=true" test *> $mavenTestLog
}
$testExit = $LASTEXITCODE
$endTick = [Diagnostics.Stopwatch]::GetTimestamp()
Save-CommandBoundary test $startWall $startTick ([DateTimeOffset]::UtcNow.ToString("o")) $endTick $testDuration $testExit
Write-RunPhaseMarker "phase.run_test.end"
Copy-Item -LiteralPath $mavenTestLog -Destination "$scriptDrive\hobl_data\spring_petclinic_maven_test_$($logSuffix.ToLower()).log" -ErrorAction Stop
if ($testExit -ne 0) {
    " ERROR - Offline test failed. See $mavenTestLog for Maven output. Ensure prep completed online and populated $scriptDrive\temp\m2-spring-petclinic" | log
    Exit 1
}
Write-RunPhaseMarker "phase.run_results.start"
@(
    "phase`tseconds"
    "package`t$($buildDuration.TotalSeconds.ToString('R', [cultureinfo]::InvariantCulture))"
    "test`t$($testDuration.TotalSeconds.ToString('R', [cultureinfo]::InvariantCulture))"
) | Set-Content -LiteralPath "$env:HOBL_TRACE_OUT\phase-times.tsv" -Encoding utf8 -ErrorAction Stop
"phase-times.tsv: unrounded Measure-Command wall seconds (scenario metrics retain two-decimal rounding)." | log
Copy-Item target\jacoco.exec $env:HOBL_TRACE_OUT -ErrorAction Stop
.\mvnw.cmd -o "-Dmaven.repo.local=$scriptDrive\temp\m2-spring-petclinic" jacoco:report *> "$env:HOBL_TRACE_OUT\coverage.log"
check($LASTEXITCODE)
Copy-Item target\site\jacoco\jacoco.xml $env:HOBL_TRACE_OUT -ErrorAction Stop
& "$Env:JAVA_HOME\bin\java.exe" "$tools\TraceWindow.java" $env:HOBL_TRACE_OUT *> "$env:HOBL_TRACE_OUT\analysis.log"
check($LASTEXITCODE)
Get-Content -LiteralPath "$env:HOBL_TRACE_OUT\analysis.log" | log
$windowMs = "unknown"
if (Test-Path -LiteralPath "$env:HOBL_TRACE_OUT\window-ms.txt") {
    $windowMs = Get-Content -LiteralPath "$env:HOBL_TRACE_OUT\window-ms.txt" -ErrorAction Stop
}
& "$Env:JAVA_HOME\bin\java.exe" "$tools\OuterSummary.java" $env:HOBL_TRACE_OUT *> "$outer\summary.log"
$summaryExit = $LASTEXITCODE
Get-Content -LiteralPath "$outer\summary.log" | log
check($summaryExit)
& "$Env:JAVA_HOME\bin\java.exe" "$tools\RoundEvidence.java" $env:HOBL_TRACE_OUT *> "$env:HOBL_TRACE_OUT\round-evidence.log"
$evidenceExit = $LASTEXITCODE
Get-Content -LiteralPath "$env:HOBL_TRACE_OUT\round-evidence.log" | log
check($evidenceExit)

$testTime = [math]::Round($testDuration.TotalSeconds, 2)
"-- spring_petclinic test completed in ${testTime}s" | log

# ============================================================================
# Calculate scenario_runtime and save metrics
# ============================================================================
$scenarioRuntime = [math]::Round($buildTime + $testTime, 2)

"" | log
"========================================" | log
"Spring Pet Clinic Metrics Summary" | log
"========================================" | log
"Build Time:       ${buildTime}s" | log
"Test Time:        ${testTime}s" | log
"Spring Starting to Tomcat initialized (traced): ${windowMs} ms" | log
"Architecture:     $logSuffix" | log
"Scenario Runtime: ${scenarioRuntime}s" | log
"========================================" | log

# Write metrics CSV file (key,value format - HOBL convention)
$outputDir = Split-Path $logFile -Parent
$resultsFile = Join-Path $outputDir "spring_petclinic_results.csv"
$metricsContent = @(
    "scenario_runtime,$scenarioRuntime"
    "build_time,$buildTime"
    "test_time,$testTime"
    "architecture,$logSuffix"
    "spring_starting_to_tomcat_ms,$windowMs"
)
$metricsContent | Set-Content $resultsFile -Encoding UTF8
"Metrics saved to: $resultsFile" | log

"-- spring_petclinic_run completed ($logSuffix version)" | log

$elapsedTime = ((Get-Date) - [DateTime]$startTime).TotalSeconds
# Append to .trace files for timeline correlation (key,value format)
if (-not (Test-Path $timeTraceFile)) {
    "Creating trace file with header: $timeTraceFile" | log
    Set-Content -Path $timeTraceFile -Value "Timestamp,build_time, test_time, total_time" -Encoding UTF8
}

$traceContent = @"
$elapsedTime,$buildTime,$testTime,$scenarioRuntime
"@
"Appending time info to trace file: $timeTraceFile" | log  
Add-Content -Path $timeTraceFile -Value $traceContent -Encoding UTF8

Write-RunPhaseMarker "phase.run_results.end"

Exit 0