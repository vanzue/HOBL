// Copyright (c) Microsoft. Licensed under the MIT license.
import java.nio.file.*;
import java.util.*;
import jdk.jfr.*;

class TraceWindowTest {
    // Synthetic events exercise RecordingFile decoding without Maven or an agent.
    @Name("jdk.JVMInformation") static class ForkInfo extends Event {
        String javaArguments = "fixture ForkedBooter", jvmArguments = "-javaagent:fixture/org.jacoco.agent";
        long pid = 17;
    }
    @Name("hobl.PetclinicMarker") static class Marker extends Event {
        String kind, detail;
        long pid = 17;
    }
    @Name("hobl.PetclinicOperation") static class Operation extends Event {
        String operation = "fixture.operation";
        long cpuNanos = -1;
    }
    @Name("hobl.PetclinicOperation") static class Extended extends Operation {
        boolean failed = true;
        long items = 13;
        String outcome = "miss";
    }
    @Name("org.springframework.core.metrics.jfr.FlightRecorderStartupEvent") static class Step extends Event {
        String name = "fixture.step", tags = "tag=value";
        long eventId = 9, parentId = 4;
    }
    @Name("jdk.FileRead") static class Read extends Event { String path = "fixture-path"; long bytesRead = 42; }
    static void mark(String kind, String detail) {
        var m = new Marker(); m.kind = kind; m.detail = detail; m.commit();
    }
    static List<List<String>> rows(Path dir, String name) throws Exception {
        return Files.readAllLines(dir.resolve(name + ".tsv")).stream().skip(1)
            .map(line -> List.of(line.split("\t", -1))).toList();
    }
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static TraceWindow.Span span(int id, long a, long b, String name) {
        return new TraceWindow.Span(id, a, b, "main", TraceWindow.OP, name, "", -1, "", -1, "");
    }
    static void accounting(Path dir) throws Exception {
        // A clipped parent, two crossing children, and two identical children.
        var list = new ArrayList<>(List.of(span(1, -10, 90, "parent"), span(2, 10, 50, "child"),
            span(3, 40, 80, "child"), span(4, 15, 22, "tie"), span(5, 15, 22, "tie")));
        try (var out = new TraceWindow.Reports(dir)) {
            var f = new TraceWindow.Fork("test", out);
            TraceWindow.account(f, new TraceWindow.Scope(1, "scope", "", "", "", "", "main", 0, 100), "main", list);
            TraceWindow.account(f, new TraceWindow.Scope(2, "scope", "", "", "", "", "main", 12, 14), "main", list.subList(0, 2));
        }
        var cover = rows(dir, "coverage").get(0);
        check(cover.subList(4, 9).equals(List.of("100","90","73","17","10")), "coverage conservation: " + cover);
        var stats = rows(dir, "span-summary");
        var child = stats.stream().filter(r -> r.get(5).equals("child") && r.get(2).equals("1")).findFirst().orElseThrow();
        check(child.subList(6,14).equals(List.of("2","40","40","40","40","80","70","53")), "child totals: " + child);
        var clippedChild = stats.stream().filter(r -> r.get(5).equals("child") && r.get(2).equals("2")).findFirst().orElseThrow();
        check(clippedChild.get(13).equals("2"), "Clipping must not turn proper nesting into a tie");
        var holes = rows(dir, "holes");
        check(holes.stream().anyMatch(r -> r.get(4).equals("unclassified") && r.get(7).equals("10")), "missing hole");
    }
    static void contextRecording(Path dir, boolean target) throws Exception {
        try (var recording = new Recording()) {
            for (var cl : List.of(ForkInfo.class, Marker.class, Operation.class, Extended.class, Step.class, Read.class))
                recording.enable(cl).withoutThreshold();
            recording.start();
            new ForkInfo().commit();
            mark("suite-start", "example.OwnerControllerTests");
            mark("starting", "Starting OwnerControllerTests using Java");
            var op = new Operation(); op.begin();
            mark("milestone", "fixture-milestone");
            var worker = new Thread(() -> { var w = new Extended(); w.cpuNanos = 43; w.commit(); });
            worker.start(); worker.join();
            new Read().commit();
            mark("ready", "Started OwnerControllerTests in 1 second");
            op.commit();
            mark("mvc-servlet", "Initializing Spring TestDispatcherServlet");
            mark("tomcat", "Tomcat initialized in unrelated context");
            mark("suite-start", "nested-suite");
            mark("test-start", "test-one"); mark("test-end", "test-one");
            mark("suite-end", "nested-suite");
            mark("suite-end", "example.OwnerControllerTests");
            mark("suite-start", TraceWindow.SUITE);
            mark("starting", "Starting UnrelatedBootApp using Java");
            mark("tomcat", "Tomcat initialized for another app inside the target suite");
            mark("starting", "Starting AnotherBootApp using Java");
            mark("ready", "Started UnrelatedBootApp in 1 second");
            mark("ready", "Started AnotherBootApp in 1 second");
            if (target) {
                mark("starting", "Starting PetClinicConcurrencyTests using Java");
                new Step().commit(); new Read().commit();
                mark("tomcat", "Tomcat initialized in target context");
            }
            mark("suite-end", TraceWindow.SUITE);
            recording.stop();
            recording.dump(dir.resolve("fork-fixture.jfr"));
        }
        TraceWindow.main(new String[]{dir.toString()});
        var scopes = rows(dir, "scopes");
        var mvc = scopes.stream().filter(r -> r.get(3).equals("starting-to-mvc-servlet")).findFirst().orElseThrow();
        check(mvc.get(6).equals("example.OwnerControllerTests"), "MVC context attached to the wrong suite");
        var starts = scopes.stream().filter(r -> r.get(3).equals("starting-to-ready")).map(r -> r.get(4)).toList();
        check(starts.containsAll(List.of("Starting UnrelatedBootApp using Java", "Starting AnotherBootApp using Java")),
            "overlapping contexts on one thread lost named ready boundaries");
        var evidence = rows(dir, "evidence");
        check(evidence.stream().anyMatch(r -> List.of(r.get(2).split(",")).contains(mvc.get(2))
            && r.get(7).equals("fixture-path bytes=42")), "non-target MVC window lost evidence");
        var spans = rows(dir, "spans").stream().filter(r -> r.get(2).equals(mvc.get(2))).toList();
        check(spans.size() == 2 && !spans.get(0).get(3).equals(spans.get(1).get(3)), "worker spans not kept separately");
        check(spans.stream().map(r -> r.get(15)).sorted().toList().equals(List.of("-1", "43")), "unknown and measured CPU");
        check(spans.stream().anyMatch(r -> r.subList(16,19).equals(List.of("true","13","miss"))), "extended operation fields");
        check(spans.stream().anyMatch(r -> r.subList(16,19).equals(List.of("","-1",""))), "legacy operation field absence");
        check(scopes.stream().anyMatch(r -> r.get(3).equals("test") && r.get(7).equals("test-one")), "test pair missing");
        check(scopes.stream().filter(r -> r.get(3).equals("suite")).count() == 3, "nested or enclosing suite missing");
        var stats = rows(dir, "span-summary");
        check(stats.stream().anyMatch(r -> r.get(2).equals(mvc.get(2)) && r.get(15).equals("1")
            && r.get(16).equals("43")), "known CPU should count once on the worker thread");
        var window = rows(dir, "window");
        check(Files.exists(dir.resolve("window-ms.txt")) == target, "target metric wrong");
        if (target) {
            check(window.size() == 2, "legacy window must only include target events: " + window);
            check(window.stream().anyMatch(r -> r.get(6).equals("fixture.step id=9 parent=4 tag=value")), "missing step IDs/tags");
        } else check(window.isEmpty(), "different Starting marker substituted for target");
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createDirectory(Path.of("trace-window-check-" + UUID.randomUUID()));
        try {
            accounting(dir);
            contextRecording(dir, true);
            contextRecording(dir, false);
            System.out.println("TraceWindowTest passed");
        } finally {
            try (var files = Files.list(dir)) {
                for (var file : files.toList()) Files.delete(file);
            }
            Files.delete(dir);
        }
    }
}
