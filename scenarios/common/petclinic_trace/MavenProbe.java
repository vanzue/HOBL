// Copyright (c) Microsoft. Licensed under the MIT license.
package hobl;

import java.nio.file.*;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.*;
import java.util.*;
import jdk.jfr.*;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.plugin.MojoExecution;

public final class MavenProbe extends AbstractEventSpy {
    @Name("hobl.MavenSpan") @StackTrace(false)
    public static final class Span extends Event {
        public String phase, kind, name, outcome;
        public long pid;
    }

    private final String phase = System.getenv("HOBL_TRACE_PHASE");
    private final ProcessHandle self = ProcessHandle.current();
    private final Map<MojoExecution, Span> goals = new IdentityHashMap<>();
    private Span session;
    private Recording recording;

    private void mark(Path file, String label) throws IOException {
        Files.writeString(file, label+"\t"+self.pid()+"\t"+Instant.now()+"\t"+System.nanoTime()+"\n",
            StandardOpenOption.APPEND);
    }

    @Override public void init(Context context) throws Exception {
        var out = Path.of(System.getenv("HOBL_TRACE_OUT"), "outer");
        var clock = out.resolve(phase+"-vm.tsv");
        Files.writeString(clock, "label\tpid\tutc\tjvm_nano_time\nvm-start\t"+self.pid()+"\t"
            + Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime())+"\t-\n");
        mark(clock, "spy-init");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { mark(clock, "shutdown-hook"); }
            catch (IOException ex) { System.err.println("HOBL Maven shutdown marker: "+ex); }
        }, "hobl-maven-shutdown"));
        var rows = new ArrayList<String>();
        rows.add("pid\tppid\tstart_utc\tcommand");
        for (var p = self; p != null; p = p.parent().orElse(null)) {
            rows.add(p.pid() + "\t" + p.parent().map(ProcessHandle::pid).orElse(-1L) + "\t"
                + p.info().startInstant().map(Instant::toString).orElse("-") + "\t" + p.info().command().orElse("-"));
            if (String.valueOf(p.pid()).equals(System.getenv("HOBL_RUNNER_PID"))) break;
        }
        Files.write(out.resolve(phase + "-processes.tsv"), rows);
        recording = new Recording();
        recording.setDestination(out.resolve(phase + ".jfr"));
        recording.enable(Span.class);
        for (var e : List.of("jdk.FileRead", "jdk.FileWrite"))
            recording.enable(e).withoutStackTrace().withThreshold(Duration.ofMillis(1));
        recording.enable("jdk.ProcessStart").withoutStackTrace();
        String javac = "com.sun.tools.javac.main.JavaCompiler::";
        String resolver = "org.eclipse.aether.internal.impl.";
        recording.enable("jdk.MethodTrace").withoutStackTrace().withThreshold(Duration.ZERO).with("filter", String.join(";",
            javac+"parseFiles", javac+"enterTrees", javac+"attribute", javac+"flow", javac+"desugar", javac+"generate",
            resolver+"DefaultArtifactResolver::resolveArtifacts", resolver+"DefaultArtifactDescriptorReader::readArtifactDescriptor",
            resolver+"EnhancedLocalRepositoryManager::find", "java.io.File::isFile",
            "java.nio.file.Files::readAttributes", "java.io.FileInputStream::open", "java.util.zip.ZipFile::getEntry"));
        recording.start();
    }

    private Span start(String kind, String name) {
        var s = new Span();
        s.phase = phase;
        s.pid = self.pid();
        s.kind = kind;
        s.name = name;
        s.begin();
        return s;
    }

    private void finish(Span s, String outcome) {
        if (s != null) {
            s.end();
            s.outcome = outcome;
            s.commit();
        }
    }

    @Override public synchronized void onEvent(Object value) {
        if (!(value instanceof ExecutionEvent e)) return;
        switch (e.getType()) {
            case SessionStarted -> session = start("session", phase);
            case SessionEnded -> {
                finish(session, e.getSession().getResult().hasExceptions() ? "failed" : "success");
                session = null;
            }
            case MojoStarted -> {
                var m = e.getMojoExecution();
                goals.put(m, start("goal", e.getProject().getId()+"/"+m.getGroupId()+":"+m.getArtifactId()+":"+m.getGoal()+"/"+m.getExecutionId()));
            }
            case MojoSucceeded, MojoFailed, MojoSkipped -> finish(goals.remove(e.getMojoExecution()), e.getType().name());
            default -> { }
        }
    }

    @Override public synchronized void close() {
        goals.values().forEach(s -> finish(s, "incomplete"));
        finish(session, "incomplete");
        if (recording != null) {
            recording.stop();
            recording.close();
        }
    }
}
