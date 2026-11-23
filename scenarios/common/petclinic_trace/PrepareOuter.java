// Copyright (c) Microsoft. Licensed under the MIT license.
import java.io.File;
import java.nio.file.*;
import java.util.jar.*;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;

class PrepareOuter {
    static String once(String input, String anchor, String replacement) {
        if (input.indexOf(anchor) < 0 || input.indexOf(anchor) != input.lastIndexOf(anchor))
            throw new IllegalStateException("Unexpected Maven wrapper; cannot locate unique marker: " + anchor);
        return input.replace(anchor, replacement);
    }

    // Instrument a round-local copy, never the checkout. The launcher expects the
    // original basename and resolves its wrapper properties relative to itself.
    static void wrapper(Path out, boolean windows) throws Exception {
        var props = Path.of(".mvn", "wrapper", "maven-wrapper.properties");
        Files.createDirectories(out.resolve(props).getParent());
        Files.copy(props, out.resolve(props));
        var name = windows ? "mvnw.cmd" : "mvnw";
        var input = Files.readString(Path.of(name)).replace("\r\n", "\n");
        if (windows) {
            var key = "$ErrorActionPreference = \"Stop\"";
            var start = """
                $hoblEnterTick = [Diagnostics.Stopwatch]::GetTimestamp()
                $hoblEnterWall = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                try {
                """;
            input = once(input, key, key + "\n" + start) + """

                } finally {
                    $hoblLeaveTick = [Diagnostics.Stopwatch]::GetTimestamp()
                    $hoblLeaveWall = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                    $hoblFreq = [Diagnostics.Stopwatch]::Frequency
                    $hoblStart = [DateTimeOffset]::new([Diagnostics.Process]::GetCurrentProcess().StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
                    $hoblMarks = "process-start`t$PID`t$hoblStart`t-`t$hoblFreq`nscript-enter`t$PID`t$hoblEnterWall`t$hoblEnterTick`t$hoblFreq`nscript-leave`t$PID`t$hoblLeaveWall`t$hoblLeaveTick`t$hoblFreq`n"
                    [IO.File]::WriteAllText("$env:HOBL_TRACE_OUT\\outer\\$env:HOBL_TRACE_PHASE-wrapper.tsv", $hoblMarks)
                }
                """;
            input = input.replace("\n", "\r\n");
        } else {
            // POSIX sh on macOS has no subsecond clock. The helper samples its
            // parent's (wrapper) PID; these points include helper launch latency.
            var marker = """
                hobl_mark() {
                  /usr/bin/perl -MTime::HiRes=time,clock_gettime,CLOCK_MONOTONIC -e 'printf "%s\\t%d\\t%.3f\\t%.0f\\t1000000000\\n", $ARGV[0], getppid(), time()*1000, clock_gettime(CLOCK_MONOTONIC)*1e9' "$1" >> "$HOBL_TRACE_OUT/outer/$HOBL_TRACE_PHASE-wrapper.tsv"
                }
                hobl_mark script-enter
                """;
            input = once(input, "scriptDir=\"$(dirname", marker + "scriptDir=\"$(dirname");
            input = once(input, "  exec \"$MAVEN_HOME/bin", "  hobl_mark exec-handoff\n  exec \"$MAVEN_HOME/bin");
        }
        var dest = Files.writeString(out.resolve(name), input);
        if (!windows && !dest.toFile().setExecutable(true)) throw new IllegalStateException("Cannot chmod " + dest);
    }

    public static void main(String[] args) throws Exception {
        var tools = Path.of(args[0]);
        var out = Files.createDirectory(Path.of(System.getenv("HOBL_TRACE_OUT"), "outer"));
        var version = Files.readString(Path.of(args[1])).replaceAll("\u001b\\[[;\\d]*m", "");
        var home = version.lines().filter(s -> s.startsWith("Maven home: ")).findFirst().orElseThrow();
        var lib = Path.of(home.substring("Maven home: ".length()).trim(), "lib");
        String cp;
        try (var libs = Files.list(lib)) {
            cp = libs.filter(p -> p.toString().endsWith(".jar")).map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        }
        var classes = Files.createDirectory(out.resolve("classes"));
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-cp", cp,
            "-d", classes.toString(), tools.resolve("MavenProbe.java").toString());
        if (rc != 0) throw new IllegalStateException("Cannot compile against " + lib);
        var descriptor = classes.resolve("META-INF/plexus/components.xml");
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, """
            <component-set><components><component>
              <role>org.apache.maven.eventspy.EventSpy</role><role-hint>hobl-round</role-hint>
              <implementation>hobl.MavenProbe</implementation><instantiation-strategy>singleton</instantiation-strategy>
            </component></components></component-set>
            """);
        try (var jar = new JarOutputStream(Files.newOutputStream(out.resolve("probe.jar")));
             var walk = Files.walk(classes)) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
        wrapper(out, File.separatorChar == '\\');
    }
}
