// Copyright (c) Microsoft. Licensed under the MIT license.
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import jdk.jfr.consumer.*;

class OuterSummary {
    record Goal(long thread, Instant start, Instant end, String name) {}
    record Key(String goal, String operation) {}
    record Meter(long count, long ns) {
        Meter add(Meter b) { return new Meter(count+b.count, ns+b.ns); }
    }
    static double ms(long ns) { return ns / 1e6; }

    record Boundary(String name, String pid, Instant time) {}
    static Instant epoch(String n, int scale) {
        var ns = new BigDecimal(n).movePointRight(scale).longValue();
        return Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000), Math.floorMod(ns, 1_000_000_000));
    }
    static void boundaries(Path out, String phase, RecordedEvent session, List<String> wrapper) throws Exception {
        var lines = Files.readAllLines(out.resolve(phase+"-command.tsv"));
        var command = lines.get(1).split("\t");
        boolean win = lines.getFirst().contains("start_utc");
        var start = win ? Instant.parse(command[1]) : epoch(command[1], 9);
        var end = win ? Instant.parse(command[3]) : epoch(command[2], 9);
        var bounds = new ArrayList<Boundary>();
        bounds.add(new Boundary("command-before-timer",command[0],start));
        for (String row : wrapper) {
            var v = row.split("\t");
            bounds.add(new Boundary("wrapper-"+v[0],v[1],epoch(v[2],6)));
        }
        for (String row : Files.readAllLines(out.resolve(phase+"-vm.tsv")).stream().skip(1).toList()) {
            var v = row.split("\t");
            bounds.add(new Boundary(v[0],v[1],Instant.parse(v[2])));
        }
        var pid = String.valueOf(session.getLong("pid"));
        bounds.add(new Boundary("maven-session-start",pid,session.getStartTime()));
        bounds.add(new Boundary("maven-session-end",pid,session.getEndTime()));
        bounds.add(new Boundary("command-after-timer",command[0],end));
        bounds.sort(Comparator.comparing(Boundary::time));
        var rows = new ArrayList<>(List.of("phase\tboundary\tpid\tutc\tns_from_command_before_timer"));
        for (var b : bounds) rows.add(phase+"\t"+b.name+"\t"+b.pid+"\t"+b.time+"\t"+Duration.between(start,b.time).toNanos());
        Files.write(out.resolve(phase+"-boundaries.tsv"),rows);
        System.out.printf(Locale.ROOT,"  outer wall anchors: before timer to Maven session %.3f ms; session end to after timer %.3f ms (includes observer overhead)%n",
            ms(Duration.between(start,session.getStartTime()).toNanos()),ms(Duration.between(session.getEndTime(),end).toNanos()));
    }

    static void report(Path out, String phase) throws Exception {
        var trace = out.resolve(phase + ".jfr");
        var goals = new ArrayList<Goal>();
        var spans = new ArrayList<>(List.of("phase\tpid\tthread\tkind\tname\toutcome\tstart_utc\tend_utc\tduration_ns"));
        RecordedEvent session = null;
        try (var r = new RecordingFile(trace)) {
            while (r.hasMoreEvents()) {
                var e = r.readEvent();
                if (!e.getEventType().getName().equals("hobl.MavenSpan")) continue;
                if (!phase.equals(e.getString("phase"))) throw new IllegalStateException("Mixed phases: " + trace);
                spans.add(String.join("\t", phase, String.valueOf(e.getLong("pid")), String.valueOf(e.getThread().getJavaThreadId()),
                    e.getString("kind"), e.getString("name"), e.getString("outcome"), e.getStartTime().toString(),
                    e.getEndTime().toString(), String.valueOf(e.getDuration().toNanos())));
                if ("session".equals(e.getString("kind"))) session = e;
                else goals.add(new Goal(e.getThread().getJavaThreadId(), e.getStartTime(), e.getEndTime(), e.getString("name")));
            }
        }
        if (session == null || !"success".equals(session.getString("outcome")) || goals.isEmpty())
            throw new IllegalStateException("Missing successful Maven session/goals in " + trace);
        long pid = session.getLong("pid");
        Files.write(out.resolve(phase+"-spans.tsv"), spans);
        var groups = new LinkedHashMap<Key,Meter>();
        try (var r = new RecordingFile(trace)) {
            while (r.hasMoreEvents()) {
                var e = r.readEvent();
                String kind = e.getEventType().getName();
                if (kind.equals("jdk.MethodTrace")) {
                    var m = (RecordedMethod)e.getValue("method");
                    kind = m.getType().getName()+"::"+m.getName();
                } else if (kind.equals("jdk.FileRead") || kind.equals("jdk.FileWrite")) {
                    kind += e.getString("path") == null ? " (unnamed descriptor, possibly pipe)" : " (named path)";
                } else continue;
                var thread = e.getThread().getJavaThreadId();
                // Only attribute when the entire call ran on the goal thread
                // within its interval. Worker time and lifecycle setup stay separate.
                var owner = goals.stream().filter(g -> g.thread == thread && !e.getStartTime().isBefore(g.start)
                    && !e.getEndTime().isAfter(g.end)).max(Comparator.comparing(Goal::start));
                groups.merge(new Key(owner.map(Goal::name).orElse("(outside same-thread goal)"), kind),
                    new Meter(1, e.getDuration().toNanos()), Meter::add);
            }
        }
        var rows = new ArrayList<>(List.of("phase\tpid\tgoal\toperation\tcount\tinclusive_operation_ns"));
        groups.forEach((k,v) -> rows.add(phase+"\t"+pid+"\t"+k.goal+"\t"+k.operation+"\t"+v.count+"\t"+v.ns));
        Files.write(out.resolve(phase+"-operations.tsv"), rows);
        var marks = Files.readAllLines(out.resolve(phase+"-wrapper.tsv"));
        var first = marks.stream().filter(s -> s.startsWith("script-enter\t")).findFirst().orElseThrow().split("\t");
        var last = marks.getLast().split("\t");
        if (!(last[0].equals("script-leave") || last[0].equals("exec-handoff")) || !first[1].equals(last[1]))
            throw new IllegalStateException("Incomplete wrapper markers: "+marks);
        double wrapperMs = (Long.parseLong(last[3])-Long.parseLong(first[3]))*1000.0/Long.parseLong(last[4]);
        System.out.printf(Locale.ROOT, "%s: wrapper %s PID %s script-enter to %s %.3f ms; Maven PID %d session %.3f ms (%d goals)%n",
            phase, last[0].equals("script-leave") ? "child PowerShell" : "sh", first[1], last[0], wrapperMs,
            pid, ms(session.getDuration().toNanos()), goals.size());
        if (Files.exists(out.resolve(phase+"-command.tsv"))) boundaries(out, phase, session, marks);
        for (String line : marks) {
            if (line.startsWith("process-start\t")) System.out.printf(Locale.ROOT,
                "  child PowerShell process-start to script-enter (wall) %.3f ms; script-leave is not process exit%n",
                Double.parseDouble(first[2])-Double.parseDouble(line.split("\t")[2]));
        }
        goals.stream().sorted(Comparator.comparing((Goal g) -> Duration.between(g.start,g.end)).reversed()).limit(3)
            .forEach(g -> System.out.printf(Locale.ROOT, "  goal %.3f ms  %s%n", ms(Duration.between(g.start,g.end).toNanos()),g.name));
        var totals = new LinkedHashMap<String,Meter>();
        groups.forEach((k,v) -> totals.merge(k.operation,v,Meter::add));
        totals.forEach((op,v) -> System.out.printf(Locale.ROOT,"  observed %.3f ms (%d calls)  %s%n",ms(v.ns),v.count,op));
    }
    public static void main(String[] args) throws Exception {
        var out = Path.of(args[0], "outer");
        System.out.println("Outer/Maven diagnostic (goals and nested calls overlap; Java file I/O >= 1 ms, not disk time; absent events are unmeasured).");
        for (String phase : List.of("package","test")) report(out, phase);
    }
}
