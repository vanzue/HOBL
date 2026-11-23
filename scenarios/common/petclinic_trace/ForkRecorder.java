// Copyright (c) Microsoft. Licensed under the MIT license.
package hobl;

import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.lang.instrument.Instrumentation;
import jdk.jfr.*;

public class ForkRecorder {
    public static void premain(String unused, Instrumentation instrumentation) throws Exception {
        var process = ProcessHandle.current();
        var id = "fork-" + process.pid() + "-" + UUID.randomUUID();
        var out = Path.of(System.getenv("HOBL_TRACE_OUT"));
        var recording = new Recording(Configuration.getConfiguration("profile"));
        recording.disable("jdk.InitialEnvironmentVariable");
        recording.disable("jdk.InitialSystemProperty");
        recording.disable("jdk.SystemProcess");
        recording.enable("jdk.ClassLoad").withStackTrace().withoutThreshold();
        for (var event : new String[]{"jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite",
                "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark"}) {
            recording.enable(event).withStackTrace().withThreshold(Duration.ofMillis(1));
        }
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
        recording.enable("jdk.ProcessStart").withStackTrace();
        recording.enable("hobl.PetclinicOperation").withoutThreshold().withoutStackTrace();
        recording.enable("org.springframework.core.metrics.jfr.FlightRecorderStartupEvent").withoutThreshold();
        recording.setDestination(out.resolve(id + ".jfr"));
        recording.setDumpOnExit(true);
        recording.start();
        var probe = new OperationProbe();
        instrumentation.addTransformer(probe);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                var lines = new java.util.ArrayList<>(OperationProbe.transformed);
                OperationProbe.errors.forEach(s -> lines.add("ERROR " + s));
                Files.write(out.resolve(id + "-hooks.txt"), lines);
            } catch (java.io.IOException e) { System.err.println("Cannot write PetClinic hook inventory: " + e); }
        }));
        Files.writeString(out.resolve(id + ".txt"), "pid=" + process.pid()
            + "\ncommand=" + System.getProperty("sun.java.command")
            + "\njava.home=" + System.getProperty("java.home")
            + "\njava.vendor=" + System.getProperty("java.vendor")
            + "\njava.runtime.version=" + Runtime.version() + "\nos.arch=" + System.getProperty("os.arch")
            + "\njacoco=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
                .stream().anyMatch(s -> s.startsWith("-javaagent:") && s.contains("org.jacoco.agent")) + "\n");
    }
}
