// Copyright (c) Microsoft. Licensed under the MIT license.
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import jdk.jfr.*;

class OuterSummaryTest {
    @Name("hobl.MavenSpan") static class Span extends Event {
        String phase = "package", kind, name, outcome = "success";
        long pid = 19;
        Span(String kind, String name) { this.kind = kind; this.name = name; begin(); }
        void finish() { end(); commit(); }
    }
    @Name("jdk.FileRead") static class Read extends Event {
        String path = "example";
        static void emit() { new Read().commit(); }
    }

    // Run with OuterSummary on the class path; argv[0] is an empty output directory.
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        long start = Instant.now().getEpochSecond();
        try (var r = new Recording()) {
            r.enable(Span.class); r.enable(Read.class);
            r.start();
            var session = new Span("session", "package");
            var enclosing = new Span("goal", "outer");
            Read.emit();
            var nested = new Span("goal", "nested");
            Read.emit();
            var worker = new Thread(Read::emit); worker.start(); worker.join();
            nested.finish(); enclosing.finish(); session.finish();
            r.stop(); r.dump(dir.resolve("package.jfr"));
        }
        Files.write(dir.resolve("package-command.tsv"), List.of("runner_pid\tstart_epoch_s\tend_epoch_s\texit_code",
            "7\t"+start+".000001\t"+(Instant.now().getEpochSecond()+1)+"\t0"));
        Files.write(dir.resolve("package-vm.tsv"), List.of("label\tpid\tutc\tjvm_nano_time",
            "vm-start\t19\t"+Instant.ofEpochSecond(start,20_000_000)+"\t-"));
        Files.write(dir.resolve("package-wrapper.tsv"), List.of("script-enter\t9\t"+(start*1000+1)+".125\t100\t1000",
            "exec-handoff\t9\t"+(start*1000+11)+".125\t110\t1000"));
        OuterSummary.report(dir, "package");
        var ops = Files.readString(dir.resolve("package-operations.tsv"));
        for (String owner : List.of("outer", "nested", "(outside same-thread goal)"))
            if (!ops.contains("\t"+owner+"\tjdk.FileRead (named path)\t1\t")) throw new AssertionError(ops);
        var spans = Files.readAllLines(dir.resolve("package-spans.tsv"));
        if (spans.size() != 4) throw new AssertionError(spans);
        var enter = Files.readAllLines(dir.resolve("package-boundaries.tsv")).stream()
            .filter(s -> s.contains("\twrapper-script-enter\t9\t")).findFirst().orElseThrow();
        if (!enter.endsWith("\t1124000")) throw new AssertionError(enter);
        Files.copy(dir.resolve("package.jfr"), dir.resolve("test.jfr"));
        try {
            OuterSummary.report(dir, "test");
            throw new AssertionError("Mixed-phase recording accepted");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().startsWith("Mixed phases")) throw expected;
        }
    }
}
