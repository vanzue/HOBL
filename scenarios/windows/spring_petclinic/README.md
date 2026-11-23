# Spring PetClinic Build & Test Workload

Builds and tests the [Spring PetClinic](https://github.com/spring-projects/spring-petclinic)
Spring Boot sample app with Maven (`mvnw`), pinned to the historical `500158f` revision
that includes `PetClinicConcurrencyTests`. This diagnostic worktree always records Maven and Surefire JFRs.
It runs an offline `package` then `test` and reports build time and test time — a Java /
Maven developer inner-loop benchmark.

## What HOBL sets up (from `spring_petclinic_resources/spring_petclinic_prep.ps1`)

- winget: Microsoft OpenJDK 25, Git
- Clones `spring-projects/spring-petclinic` @ commit
  `500158f732419217507c7656904b8e6aa1bcc0d6` to `<drive>\spring-petclinic`
- Pre-populates a local Maven cache so builds run fully offline

## Run it standalone (Windows)

On a prepared DUT, invoke `spring_petclinic_run.ps1` from the deployed scenario
resources. Use the sibling `petclinic_trace` tools and the classpath file from
prep; no trace argument is needed.

## Notes

- The `package`/`test` phases run offline (`-o`) against the pre-populated cache.
- Default: 5 loops (build + test each loop). Works on x64 and ARM64.

## Spring startup diagnostics (Windows and macOS)

Run `spring_petclinic` or `mac_spring_petclinic` normally. The existing HOBL
metrics banner and `<scenario>_results.csv` (key,value; consumed by `run_report`)
include `scenario_runtime`, `build_time`, `test_time` (wall seconds) and
`spring_starting_to_tomcat_ms` (the window **inside** test, not an additive
phase). macOS retains its original real/user/sys/CPU fields. `scenario_runtime`
is still offline package plus full-suite test; clean, probe preparation and
post-test analysis are outside those timers. The test now includes JFR cost;
do not treat it as an uninstrumented historical baseline. The JDK selection
policy is unchanged; check the actual fork JDK when comparing platforms.

In each collected `spring_startup_trace-<unique-id>` directory (one per loop):

1. Check `maven-test.log` for actual suite order, `Starting PetClinicConcurrencyTests`,
   `Tomcat initialized`, failures and skips. `jacoco.exec` and `jacoco.xml` are
   from that round.
2. Check `fork-<pid>-<uuid>.txt` for the **current** Surefire command, JDK,
   architecture and JaCoCo agent. The matching `.jfr` is started by an agent
   appended to Surefire's fork arguments, not `MAVEN_OPTS`. `analysis.log`
   lists timestamped JUnit suite markers, log boundaries, PID and thread IDs;
   the analyzer checks the command in the recording independently.
3. `window.tsv` contains Spring steps (ID, parent, tags), thread, timestamps,
   clipped overlap and stack frames for the matched interval. Start on the
   startup thread; inspect overlapping workers separately. Nested steps and
   parallel threads are **not additive**. File-read, monitor and park events
   have a 1 ms threshold; execution samples are periodic observations, **not**
   calls. Missing samples do not establish waiting or scheduler delay.
   Inspect full stacks in JDK Mission Control or with `jfr print`. No system
   ETW/System Trace is required. No matching window (e.g. context reuse) is
   reported explicitly, not silently substituted with a different suite.

## Whole-round attribution

No scenario switch is required. These are diagnostic measurements, not
uninstrumented benchmark results. Compare against a separate stock run on
each original DUT, with the same JDK, source, offline cache and test order.
Keep loop 1 separate; do not infer an operation's stock cost by applying its
diagnostic percentage to an older run.

| Question | Evidence to use |
|---|---|
| Does the round close? | `phase-times.tsv` holds the command wall times; `whole-round.tsv` splits their sum into Maven package, Spring start times, the rest of the fork-to-last-ready window, Maven test outside that window, and time outside both Maven clocks. The last three are differences, not causal labels. If the historical boundaries cannot be matched, see `whole-round-unavailable.txt`. |
| Is the workload comparable? | `workload.txt`, `test-cases.tsv`, `jacoco.xml`, `package-entries.tsv`, `maven-build.log`, `maven-test.log` and the fork metadata. Compare individual entries; build metadata may contain platform paths or timestamps. Use the actual fork JDK, not merely `JAVA_HOME`. |
| What ran before or after Maven? | `outer/` has command boundaries, a marked copy of the pinned wrapper, wrapper PID/timestamps, Maven VM and ancestor PID records, per-phase recordings, goal spans and operation summaries. Windows child PowerShell startup, script body, Maven VM, and runner return are different boundaries. Script-leave is not OS process exit. The macOS shell timestamp helper has a cost. |
| Where did package and test spend time inside Maven? | Read goal spans alongside resolver/javac method events, reads and writes. Nested operations, worker time, and descriptor/pipe reads are not additive contributions to the goal. A thresholded read count is not a count of all files opened. |
| Why is MVC slower? | In `scopes.tsv`, choose `starting-to-mvc-servlet` for a matched test class (especially `PetControllerTests`), or compare the first MVC on each platform, explicitly naming the different suites. Join `span-summary.tsv` on fork, scope and owner thread. `doCreateBean:<name>`, Mockito mock creation, agent install and external attach have separate events. Verify the original self-attach in `maven-test.log`, rather than assuming it still ran. |
| What about other contexts and waits? | `starting-to-ready` covers the other startups. Compare Tomcat, repository scan/proxy, Hikari/H2 first connection, SQL scripts, Hibernate metadata/factory, MVC mappings and actuator discovery. `markers.tsv` preserves test order; test execution callbacks bound the method body, not all lifecycle callbacks. Raw `spans.tsv` records operation wall, thread CPU, exceptional exits, cache lookup results and returned scan sizes where available. `*-hooks.txt` lists methods actually transformed; not seeing an operation is not a zero-cost measurement. |

Use **one window and one thread** for a contribution budget. Nested scopes
(suite, test, context, MVC) are different views of the same time. A broad
Spring step covering a hole locates time but does not explain its mechanism.
`coverage.tsv` and `holes.tsv` keep ambiguous overlap and uncovered time
explicit. Thread CPU and JFR park/monitor/I/O events can support an explanation;
wall minus CPU is **not** proof of scheduler delay. No machine-wide kernel
trace is started. Preserve the recordings when an unexplained wait needs
OS scheduling evidence.
