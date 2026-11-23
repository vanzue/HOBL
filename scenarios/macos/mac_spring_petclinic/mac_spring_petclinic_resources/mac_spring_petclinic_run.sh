#!/bin/zsh
# Copyright (c) Microsoft. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.

LOG_DIR="/Users/Shared/hobl_data"
METRICS_FILE="$LOG_DIR/mac_spring_petclinic_results.csv"
LOG_FILE="$LOG_DIR/mac_spring_petclinic_run.log"

# Create log directory if it doesn't exist
if [ ! -d "$LOG_DIR" ]; then
    mkdir -p "$LOG_DIR"
fi

log() {
    echo "$1"
    echo "$1" >> "$LOG_FILE"
}

# Helper function for error checking
check_status() {
    if [ $? -ne 0 ]; then
        log " ERROR - $1 failed"
        exit 1
    fi
    log "✓ $1 successful"
}

# Helper function to verify command exists
check_command() {
    if command -v "$1" >/dev/null 2>&1; then
        log "✓ $1 is available"
        return 0
    else
        log " ERROR - $1 is not available"
        return 1
    fi
}

# Helper function to parse time output and calculate cputime
# Args: $1 = time log file, $2 = phase name prefix (e.g., "build" or "test")
# Sets: <prefix>_real, <prefix>_user, <prefix>_sys, <prefix>_cputime
#
# Time metrics explained:
#   Real - Wall clock time from start to finish of the call. This is all elapsed time
#          including time slices used by other processes and time the process spends
#          blocked (for example if it is waiting for I/O to complete).
#
#   User - The amount of CPU time spent in user-mode code (outside the kernel) within
#          the process. This is only actual CPU time used in executing the process.
#          Other processes and time the process spends blocked do not count towards
#          this figure.
#
#   Sys  - The amount of CPU time spent in the kernel within the process. This means
#          executing CPU time spent in system calls within the kernel, as opposed to
#          library code, which is still running in user-space. Like 'user', this is
#          only CPU time used by the process.
#
#   User+Sys (cputime) - How much actual CPU time your process used. Note that this is
#          across all CPUs, so if the process has multiple threads on a multi-processor
#          system, it could potentially exceed the wall clock time reported by 'Real'.
#          These figures include the 'User' and 'Sys' time of all child processes when
#          they could have been collected (e.g., by wait(2) or waitpid(2)).
#
parse_time_output() {
    local time_file="$1"
    local prefix="$2"
    
    if [ ! -f "$time_file" ]; then
        log " ERROR - Time log file not found: $time_file"
        return 1
    fi
    
    # Parse the -p format output (real, user, sys on separate lines)
    local real_val=$(grep "^real" "$time_file" | awk '{print $2}')
    local user_val=$(grep "^user" "$time_file" | awk '{print $2}')
    local sys_val=$(grep "^sys" "$time_file" | awk '{print $2}')
    
    # Calculate cputime = user + sys
    local cputime=$(echo "$user_val + $sys_val" | bc)
    
    # Export values using eval for dynamic variable names
    eval "${prefix}_real=$real_val"
    eval "${prefix}_user=$user_val"
    eval "${prefix}_sys=$sys_val"
    eval "${prefix}_cputime=$cputime"
    
    log "✓ Parsed $prefix phase: real=${real_val}s, user=${user_val}s, sys=${sys_val}s, cputime=${cputime}s"
}

echo "-- mac_spring_petclinic_run.sh started $(date)" > "$LOG_FILE"
log "-- spring_petclinic run started"

# Source profile
if [ -f ~/.zprofile ]; then
    source ~/.zprofile
    check_status "Loading profile"
else
    echo " ERROR - ~/.zprofile not found"
    exit 1
fi

# Verify required commands are available
log "-- Verifying required commands"
check_command "java" || exit 1

# Set BIN_DIR to /Users/Shared/hobl_bin
BIN_DIR="/Users/Shared/hobl_bin"

log "-- Changing directory to: $BIN_DIR/spring-petclinic"

# Change to spring-petclinic directory
if [ ! -d "$BIN_DIR/spring-petclinic" ]; then
    log " ERROR - Spring Pet Clinic directory not found: $BIN_DIR/spring-petclinic"
    log "Please run mac_spring_petclinic_prep.sh first"
    exit 1
fi

cd $BIN_DIR/spring-petclinic || {
    log " ERROR - Failed to change to $BIN_DIR/spring-petclinic"
    exit 1
}
log "✓ Current directory: $(pwd)"

# Verify Maven wrapper exists
if [ ! -f "./mvnw" ]; then
    log " ERROR - Maven wrapper (mvnw) not found"
    exit 1
fi
log "✓ Maven wrapper found"

log "-- Cleaning previous build"
# Redirect Maven output to a per-phase log under hobl_data so it is preserved
# in the results share. Without redirection, stdout goes only to the RPC
# buffer and is lost on RPC timeout.
MAVEN_CLEAN_LOG="$LOG_DIR/mac_spring_petclinic_maven_clean.log"
log "-- Maven clean output: $MAVEN_CLEAN_LOG"
./mvnw -o -Dmaven.repo.local=$BIN_DIR/m2-spring-petclinic clean > "$MAVEN_CLEAN_LOG" 2>&1
check_status "Maven clean"

tools="$BIN_DIR/petclinic_trace"
round_id=$(uuidgen) || exit 1
HOBL_TRACE_OUT="$LOG_DIR/spring_startup_trace-$round_id"
mkdir "$HOBL_TRACE_OUT" || exit 1
export HOBL_TRACE_OUT
export HOBL_RUNNER_PID=$$
log "Spring startup tracing enabled (Maven and Surefire): $HOBL_TRACE_OUT"
./mvnw -o --version > "$HOBL_TRACE_OUT/maven-version.log" 2>&1
check_status "Locating wrapper Maven distribution"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" "$tools/PrepareOuter.java" "$tools" "$HOBL_TRACE_OUT/maven-version.log"
check_status "Preparing outer command trace"
outer="$HOBL_TRACE_OUT/outer"
wrapper="$outer/mvnw"
ext="-Dmaven.ext.class.path=$outer/probe.jar"
# The scenario invokes this script with zsh. Take wall anchors in that
# process, outside /usr/bin/time; real/user/sys are still BSD time's values.
zmodload zsh/datetime || exit 1
save_command_boundary() {
    printf 'runner_pid\tstart_epoch_s\tend_epoch_s\texit_code\n%s\t%s\t%s\t%s\n' \
        "$$" "$2" "$3" "$4" > "$outer/$1-command.tsv" || exit 1
}

log "-- spring_petclinic build started"

MAVEN_BUILD_LOG="$HOBL_TRACE_OUT/maven-package.log"
log "-- Maven build output: $MAVEN_BUILD_LOG"
export HOBL_TRACE_PHASE=package
start_wall=$EPOCHREALTIME
/usr/bin/time -p -o "$LOG_DIR/mac_spring_petclinic_build_time.log" "$wrapper" -o "$ext" -Dmaven.repo.local=$BIN_DIR/m2-spring-petclinic -DskipTests package > "$MAVEN_BUILD_LOG" 2>&1
build_exit=$?
save_command_boundary package "$start_wall" "$EPOCHREALTIME" "$build_exit"
cp "$LOG_DIR/mac_spring_petclinic_build_time.log" "$outer/package-time.log" || exit 1
cp "$MAVEN_BUILD_LOG" "$LOG_DIR/mac_spring_petclinic_maven_build.log" || exit 1
cp "$MAVEN_BUILD_LOG" "$HOBL_TRACE_OUT/maven-build.log" || exit 1
[ "$build_exit" -eq 0 ]
check_status "Maven package (build)"

# Parse build phase timing
parse_time_output "$LOG_DIR/mac_spring_petclinic_build_time.log" "build"

log "-- spring_petclinic build ended"

"${JAVA_HOME:+$JAVA_HOME/bin/}java" "$tools/PrepareProbe.java" "$tools" "$BIN_DIR/mac_spring_petclinic_resources/trace-classpath.txt"
check_status "Preparing Surefire startup probe"

log "-- spring_petclinic tests started"

MAVEN_TEST_LOG="$HOBL_TRACE_OUT/maven-test.log"
log "-- Maven test output: $MAVEN_TEST_LOG"
export HOBL_TRACE_PHASE=test
start_wall=$EPOCHREALTIME
/usr/bin/time -p -o "$LOG_DIR/mac_spring_petclinic_test_time.log" "$wrapper" -o "$ext" -Dmaven.repo.local=$BIN_DIR/m2-spring-petclinic \
    -Dmaven.surefire.debug=-javaagent:target/hobl-agent.jar -Djunit.jupiter.extensions.autodetection.enabled=true test > "$MAVEN_TEST_LOG" 2>&1
test_exit=$?
save_command_boundary test "$start_wall" "$EPOCHREALTIME" "$test_exit"
cp "$LOG_DIR/mac_spring_petclinic_test_time.log" "$outer/test-time.log" || exit 1
cp "$MAVEN_TEST_LOG" "$LOG_DIR/mac_spring_petclinic_maven_test.log" || exit 1
[ "$test_exit" -eq 0 ]
check_status "Maven test"

# Parse test phase timing
parse_time_output "$LOG_DIR/mac_spring_petclinic_test_time.log" "test"

log "-- spring_petclinic tests completed"
printf 'phase\tseconds\npackage\t%s\ntest\t%s\n' "$build_real" "$test_real" > "$HOBL_TRACE_OUT/phase-times.tsv" || exit 1
log "phase-times.tsv: BSD time -p real seconds at its reported precision."
cp target/jacoco.exec "$HOBL_TRACE_OUT/" || exit 1
./mvnw -o -Dmaven.repo.local=$BIN_DIR/m2-spring-petclinic jacoco:report > "$HOBL_TRACE_OUT/coverage.log" 2>&1
check_status "Creating JaCoCo report"
cp target/site/jacoco/jacoco.xml "$HOBL_TRACE_OUT/" || exit 1
"${JAVA_HOME:+$JAVA_HOME/bin/}java" "$tools/TraceWindow.java" "$HOBL_TRACE_OUT" > "$HOBL_TRACE_OUT/analysis.log" 2>&1
check_status "Analyzing Surefire JFR"
while IFS= read -r line; do log "$line"; done < "$HOBL_TRACE_OUT/analysis.log"
window_ms=unknown
if [ -f "$HOBL_TRACE_OUT/window-ms.txt" ]; then
    window_ms=$(cat "$HOBL_TRACE_OUT/window-ms.txt") || exit 1
fi
"${JAVA_HOME:+$JAVA_HOME/bin/}java" "$tools/OuterSummary.java" "$HOBL_TRACE_OUT" > "$outer/summary.log" 2>&1
summary_exit=$?
while IFS= read -r line; do log "$line"; done < "$outer/summary.log"
[ "$summary_exit" -eq 0 ]
check_status "Analyzing Maven trace"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" "$tools/RoundEvidence.java" "$HOBL_TRACE_OUT" > "$HOBL_TRACE_OUT/round-evidence.log" 2>&1
evidence_exit=$?
while IFS= read -r line; do log "$line"; done < "$HOBL_TRACE_OUT/round-evidence.log"
[ "$evidence_exit" -eq 0 ]
check_status "Collecting round evidence"

# ============================================================================
# Calculate scenario_runtime and save metrics
# ============================================================================
# Use real (wall clock) time for scenario_runtime as it represents actual elapsed time
scenario_runtime=$(echo "$build_real + $test_real" | bc)

log ""
log "========================================"
log "Spring Pet Clinic Metrics Summary"
log "========================================"
log "Build Phase: real=${build_real}s, user=${build_user}s, sys=${build_sys}s, cputime=${build_cputime}s"
log "Test Phase:  real=${test_real}s, user=${test_user}s, sys=${test_sys}s, cputime=${test_cputime}s"
log "Spring Starting to Tomcat initialized (traced): ${window_ms} ms"
log "scenario_runtime (total real time): ${scenario_runtime}s"
log "========================================"

# Write metrics CSV file (key,value format)
cat > "$METRICS_FILE" << EOF
scenario_runtime,$scenario_runtime
build_time,$build_real
test_time,$test_real
spring_starting_to_tomcat_ms,$window_ms
build_real,$build_real
build_user,$build_user
build_sys,$build_sys
build_cputime,$build_cputime
test_real,$test_real
test_user,$test_user
test_sys,$test_sys
test_cputime,$test_cputime
EOF

log "✓ Metrics saved to: $METRICS_FILE"

exit 0