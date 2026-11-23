// Copyright (c) Microsoft. Licensed under the MIT license.
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import jdk.jfr.consumer.*;

class TraceWindow {
    static final String SUITE = "org.springframework.samples.petclinic.PetClinicConcurrencyTests";
    static final String STEP = "org.springframework.core.metrics.jfr.FlightRecorderStartupEvent";
    static final String OP = "hobl.PetclinicOperation";
    static final Set<String> EVENTS = Set.of("jdk.ExecutionSample", "jdk.NativeMethodSample",
        "jdk.ClassLoad", "jdk.FileRead", "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark");
    static final Set<String> OTHER_EVIDENCE = Set.of("jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite",
        "jdk.ProcessStart", "jdk.ThreadSleep", "jdk.GarbageCollection", "jdk.GCPhasePause", "jdk.Compilation");

    static String thread(RecordedThread t) {
        return t == null ? "-" : t.getJavaName() + "#" + t.getJavaThreadId() + "/os:" + t.getOSThreadId();
    }
    static long ns(Instant i) { return i.getEpochSecond() * 1_000_000_000L + i.getNano(); }
    static Instant instant(long ns) { return Instant.ofEpochSecond(0, ns); }
    static String cell(Object o) {
        return String.valueOf(o).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    static class Reports implements Closeable {
        final Path dir;
        final Map<String, PrintWriter> writers = new LinkedHashMap<>();
        Reports(Path dir) throws IOException {
            this.dir = dir;
            try {
                table("window", "fork\tstart\tend\toverlap_ns\tthread\tevent\tdetail\tstack");
                table("markers", "fork\tpid\tsequence\ttime\tthread\tkind\tdetail\tsuite\ttest\tcontext");
                table("scopes", "fork\tpid\tscope\tkind\tname\tcontext\tsuite\ttest\towner_thread\tstart\tend\twall_ns");
                table("spans", "fork\tpid\tscope\tthread\tspan_id\tevent\toperation\tdetail\tstart\tend\twall_ns\tclip_start\tclip_end\tclipped_ns\tself_ns\twhole_span_cpu_ns\tfailed\titems\toutcome");
                table("span-summary", "fork\tpid\tscope\tthread\tevent\toperation\tcount\tmin_clipped_ns\tp50_clipped_ns\tp95_clipped_ns\tmax_clipped_ns\tsum_clipped_ns\tunion_ns\tself_ns\tcomplete_count\tcomplete_known_cpu_count\tcomplete_known_inclusive_cpu_sum_ns\tcomplete_known_inclusive_cpu_p50_ns\tcomplete_known_inclusive_cpu_p95_ns");
                table("coverage", "fork\tpid\tscope\tthread\tscope_wall_ns\tunion_ns\tunique_self_ns\tambiguous_overlap_ns\tunclassified_ns");
                table("holes", "fork\tpid\tscope\tthread\tkind\tstart\tend\twall_ns");
                table("evidence", "fork\tpid\tmatching_scopes\tthread\tevent\tstart\tend\tdetail\tstack");
            } catch (IOException ex) { close(); throw ex; }
        }
        void table(String name, String header) throws IOException {
            var out = new PrintWriter(Files.newBufferedWriter(dir.resolve(name + ".tsv")));
            writers.put(name, out);
            out.println(header);
        }
        void row(String name, Object... values) {
            var line = new StringJoiner("\t");
            for (var value : values) line.add(cell(value));
            writers.get(name).println(line);
        }
        public void close() throws IOException {
            var errors = new ArrayList<String>();
            writers.forEach((name, out) -> {
                out.close();
                if (out.checkError()) errors.add(name);
            });
            if (!errors.isEmpty()) throw new IOException("Cannot write tables: " + errors);
        }
    }

    record Marker(long at, String thread, String kind, String text) {}
    record Scope(int id, String kind, String name, String context, String suite, String test,
                 String owner, long a, long b) {}
    record Span(int id, long a, long b, String thread, String type, String name, String detail, long cpu,
                String failed, long items, String outcome) {}
    record Open(Marker marker, String context, String suite, String test) {}
    record StartupKey(String thread, Open suite, String application) {}
    static String application(String message) {
        int start = message.indexOf(' ') + 1, end = message.indexOf(' ', start);
        return end < 0 ? message.substring(start) : message.substring(start, end);
    }
    static String name(Open o) { return o == null ? "-" : o.marker.text; }
    static class Fork {
        final String file;
        final Reports out;
        long pid = -1;
        final List<Scope> scopes = new ArrayList<>(), targets = new ArrayList<>();
        record Tid(long java, long os) {}
        final Map<Tid,String> threadLabels = new HashMap<>();
        Fork(String file, Reports out) { this.file = file; this.out = out; }
        String label(RecordedThread t) {
            return t == null ? "-" : threadLabels.computeIfAbsent(new Tid(t.getJavaThreadId(), t.getOSThreadId()),
                ignored -> thread(t));
        }
        Scope scope(String kind, Open open, long end) {
            var m = open.marker;
            var s = new Scope(scopes.size() + 1, kind, m.text, open.context, open.suite, open.test,
                m.thread, m.at, end);
            scopes.add(s);
            out.row("scopes", file, pid, s.id, kind, s.name, s.context, s.suite, s.test,
                s.owner, instant(s.a), instant(s.b), s.b - s.a);
            return s;
        }
        void markers(List<Marker> markers) {
            markers.sort(Comparator.comparingLong(Marker::at));
            Map<String,Deque<Open>> suites = new HashMap<>();
            Map<String,Open> tests = new HashMap<>(), contexts = new HashMap<>();
            Map<Open,Open> savedContexts = new HashMap<>();
            Map<StartupKey,Deque<Open>> awaitingReady = new HashMap<>();
            Set<String> ready = new HashSet<>(), mvc = new HashSet<>(), tomcat = new HashSet<>();
            var pendingTargets = new HashMap<Open,List<Scope>>();
            int sequence = 0;
            for (var m : markers) {
                sequence++;
                var stack = suites.computeIfAbsent(m.thread, ignored -> new ArrayDeque<>());
                Open suite = stack.peek(), test = tests.get(m.thread), ctx = contexts.get(m.thread);
                String context = ctx == null ? "-" : ctx.context;
                switch (m.kind) {
                    case "suite-start" -> {
                        suite = new Open(m, context, m.text, "-");
                        if (stack.peek() != null && ctx != null) savedContexts.put(suite, ctx);
                        stack.push(suite);
                    }
                    case "test-start" -> {
                        test = new Open(m, context, name(suite), m.text);
                        tests.put(m.thread, test);
                    }
                    case "starting" -> {
                        context = "context-" + sequence;
                        var opened = new Open(m, context, name(suite), name(test));
                        contexts.put(m.thread, opened);
                        awaitingReady.computeIfAbsent(new StartupKey(m.thread, suite, application(m.text)),
                            ignored -> new ArrayDeque<>()).push(opened);
                    }
                    case "ready", "mvc-servlet", "tomcat" -> {
                        if (m.kind.equals("ready")) {
                            var pending = awaitingReady.get(new StartupKey(m.thread, suite, application(m.text)));
                            if (pending != null && !pending.isEmpty()) ctx = pending.pop();
                            else if (ctx != null && !application(ctx.marker.text).equals(application(m.text))) ctx = null;
                            context = ctx == null ? "-" : ctx.context;
                        }
                        if (ctx != null) {
                            var seen = switch (m.kind) {
                                case "ready" -> ready;
                                case "mvc-servlet" -> mvc;
                                default -> tomcat;
                            };
                            if (seen.add(context)) {
                                var s = scope("starting-to-" + m.kind, ctx, m.at);
                                if (m.kind.equals("tomcat") && ctx.marker.text.startsWith("Starting PetClinicConcurrencyTests ")
                                    && suite != null && SUITE.equals(ctx.suite) && SUITE.equals(suite.suite))
                                    pendingTargets.computeIfAbsent(suite, ignored -> new ArrayList<>()).add(s);
                            }
                        }
                    }
                    case "suite-end" -> {
                        if (suite != null && m.text.equals(suite.suite)) {
                            scope("suite", suite, m.at);
                            targets.addAll(pendingTargets.getOrDefault(suite, List.of()));
                            stack.pop();
                            contexts.remove(m.thread);
                            Open previous = savedContexts.remove(suite);
                            if (previous != null) contexts.put(m.thread, previous);
                        }
                    }
                    case "test-end" -> {
                        if (test != null && m.text.equals(test.test)) {
                            scope("test", test, m.at);
                            tests.remove(m.thread);
                        }
                    }
                }
                out.row("markers", file, pid, sequence, instant(m.at), m.thread, m.kind, m.text, name(suite), name(test), context);
                System.out.printf("%s %s pid=%d %s %s %s%n", file, instant(m.at), pid, m.thread, m.kind, m.text);
            }
        }
    }

    // Balanced interval index: JFR chunks need not deliver events in start order.
    static class Scopes {
        final Scope value;
        final Scopes left, right;
        final long maxEnd;
        Scopes(List<Scope> sorted, int lo, int hi) {
            int mid = (lo + hi) >>> 1;
            value = sorted.get(mid);
            left = lo < mid ? new Scopes(sorted, lo, mid) : null;
            right = mid + 1 < hi ? new Scopes(sorted, mid + 1, hi) : null;
            maxEnd = Math.max(value.b, Math.max(left == null ? Long.MIN_VALUE : left.maxEnd,
                right == null ? Long.MIN_VALUE : right.maxEnd));
        }
        void overlaps(long a, long b, Consumer<Scope> hit) {
            if (maxEnd < a) return;
            if (left != null) left.overlaps(a, b, hit);
            if (value.a > b) return;
            if (value.b >= a) hit.accept(value);
            if (right != null) right.overlaps(a, b, hit);
        }
    }

    record Key(String type, String name) {}
    record Edge(long time, boolean enter, Span span) {}
    static class Stats {
        final ArrayList<Long> durations = new ArrayList<>(), cpus = new ArrayList<>();
        long sum, union, self, lastEnd = Long.MIN_VALUE;
        long complete, cpuSum;
        // Called in clipped-start order.
        void add(Span v, long a, long b, long exclusive) {
            durations.add(b - a);
            sum += b - a;
            union += Math.max(0, b - Math.max(a, lastEnd));
            lastEnd = Math.max(lastEnd, b);
            self += exclusive;
            if (a == v.a && b == v.b) {
                complete++;
                if (v.cpu >= 0) { cpus.add(v.cpu); cpuSum += v.cpu; }
            }
        }
        static long percentile(List<Long> values, int p) {
            return values.isEmpty() ? -1 : values.get((int)((values.size() * (long)p + 99) / 100 - 1));
        }
    }

    // One timeline per scope and event thread. A slice belongs to a span only when
    // that span is uniquely inside every other active span (using UNCLIPPED bounds).
    // Crossing siblings and indistinguishable equal bounds get a separate budget.
    static void account(Fork f, Scope s, String tid, List<Span> spans) {
        var edges = new ArrayList<Edge>();
        spans.sort(Comparator.comparingLong(Span::a).thenComparingInt(Span::id));
        for (var v : spans) {
            long a = Math.max(v.a, s.a), b = Math.min(v.b, s.b);
            if (b > a) { edges.add(new Edge(a, true, v)); edges.add(new Edge(b, false, v)); }
        }
        edges.sort(Comparator.comparingLong(Edge::time));
        var active = new TreeSet<>(Comparator.comparingLong(Span::a).reversed()
            .thenComparingLong(Span::b).thenComparingInt(Span::id));
        var ends = new TreeSet<>(Comparator.comparingLong(Span::b).thenComparingInt(Span::id));
        var self = new HashMap<Integer,Long>();
        long cursor = s.a, holes = 0, ambiguous = 0;
        for (int i = 0; i <= edges.size(); i++) {
            long next = i == edges.size() ? s.b : edges.get(i).time;
            long dt = next - cursor;
            if (dt > 0) {
                Span first = active.isEmpty() ? null : active.first();
                Span other = first == null ? null : active.higher(first);
                if (first == null || first.b > ends.first().b
                    || (other != null && first.a == other.a && first.b == other.b)) {
                    if (first == null) holes += dt; else ambiguous += dt;
                    f.out.row("holes", f.file, f.pid, s.id, tid, first == null ? "unclassified" : "ambiguous-overlap",
                        instant(cursor), instant(next), dt);
                } else self.merge(first.id, dt, Long::sum);
            }
            if (i < edges.size()) {
                var e = edges.get(i);
                if (e.enter) { active.add(e.span); ends.add(e.span); }
                else { active.remove(e.span); ends.remove(e.span); }
            }
            cursor = next;
        }
        var stats = new LinkedHashMap<Key,Stats>();
        for (var v : spans) {
            long a = Math.max(v.a, s.a), b = Math.min(v.b, s.b), own = self.getOrDefault(v.id, 0L);
            f.out.row("spans", f.file, f.pid, s.id, tid, v.id, v.type, v.name, v.detail, instant(v.a),
                instant(v.b), v.b - v.a, instant(a), instant(b), b - a, own, v.cpu, v.failed, v.items, v.outcome);
            stats.computeIfAbsent(new Key(v.type, v.name), ignored -> new Stats()).add(v, a, b, own);
        }
        stats.forEach((k, v) -> {
            Collections.sort(v.durations);
            Collections.sort(v.cpus);
            f.out.row("span-summary", f.file, f.pid, s.id, tid, k.type, k.name, v.durations.size(),
                v.durations.get(0), Stats.percentile(v.durations, 50), Stats.percentile(v.durations, 95),
                Stats.percentile(v.durations, 100), v.sum, v.union, v.self, v.complete, v.cpus.size(),
                v.cpus.isEmpty() ? -1 : v.cpuSum, Stats.percentile(v.cpus, 50), Stats.percentile(v.cpus, 95));
        });
        long wall = s.b - s.a;
        f.out.row("coverage", f.file, f.pid, s.id, tid, wall, wall - holes, wall - holes - ambiguous, ambiguous, holes);
        if (s.kind.startsWith("starting-to-") && tid.equals(s.owner)) {
            String mvc = "";
            if (s.kind.equals("starting-to-mvc-servlet")) {
                var top = new StringJoiner("; ");
                stats.entrySet().stream().filter(e -> e.getKey().type.equals(OP) && e.getValue().self > 0)
                    .sorted(Comparator.<Map.Entry<Key,Stats>>comparingLong(e -> e.getValue().self).reversed()
                        .thenComparing(e -> e.getKey().name))
                    .limit(3).forEach(e -> top.add(String.format(Locale.ROOT, "%s=%.3f",
                        cell(e.getKey().name), e.getValue().self / 1_000_000.0)));
                mvc = " suite=" + (s.suite.equals("-") ? application(s.name) : s.suite)
                    + " top_probe_self_ms=[" + (top.length() == 0 ? "none" : top) + "]";
            }
            System.out.printf("Scope %d %s %s owner=%s wall_ns=%d unique_self_ns=%d ambiguous_overlap_ns=%d unclassified_ns=%d%s%n",
                s.id, s.context, s.kind, tid, wall, wall - holes - ambiguous, ambiguous, holes, mvc);
        }
    }

    static String detail(RecordedEvent e, String type) {
        return switch (type) {
            case STEP -> e.getString("name") + " id=" + e.getLong("eventId") + " parent="
                + e.getLong("parentId") + " " + e.getString("tags");
            case "jdk.FileRead" -> e.getString("path");
            case "jdk.ClassLoad" -> e.getClass("loadedClass").getName();
            default -> "";
        };
    }
    static String stack(RecordedEvent e) {
        var trace = e.getStackTrace();
        return trace == null ? "" : String.join(" <- ", trace.getFrames().stream().limit(8)
            .map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName()).toList());
    }
    static String evidenceDetail(RecordedEvent e, String type, String legacy) {
        return switch (type) {
            case "jdk.FileRead" -> legacy + " bytes=" + e.getValue("bytesRead");
            case "jdk.FileWrite" -> e.getString("path") + " bytes=" + e.getLong("bytesWritten");
            case "jdk.SocketRead" -> "port=" + e.getInt("port") + " bytes=" + e.getLong("bytesRead");
            case "jdk.SocketWrite" -> "port=" + e.getInt("port") + " bytes=" + e.getLong("bytesWritten");
            case "jdk.ProcessStart" -> "pid=" + e.getLong("pid");
            case "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait" -> className(e, "monitorClass");
            case "jdk.ThreadPark" -> className(e, "parkedClass");
            case "jdk.GarbageCollection" -> e.getString("name") + " cause=" + e.getString("cause");
            case "jdk.GCPhasePause" -> e.getString("name");
            case "jdk.Compilation" -> {
                var method = e.getValue("method");
                yield method instanceof RecordedMethod m ? m.getType().getName() + "." + m.getName() : "";
            }
            default -> legacy;
        };
    }
    static String className(RecordedEvent e, String field) {
        var c = e.getClass(field);
        return c == null ? "" : c.getName();
    }

    record Result(List<Scope> targets, boolean missingEvidence) {}
    static Result inspect(Path file, Reports out) throws IOException {
        var f = new Fork(file.getFileName().toString(), out);
        var markers = new ArrayList<Marker>();
        var spans = new ArrayList<Span>();
        var markerPids = new HashSet<Long>();
        boolean surefire = false, jacoco = false;
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        try (var r = new RecordingFile(file)) {
            while (r.hasMoreEvents()) {
                var e = r.readEvent();
                var type = e.getEventType().getName();
                long a = ns(e.getStartTime()), b = ns(e.getEndTime());
                first = Math.min(first, a); last = Math.max(last, b);
                switch (type) {
                    case "hobl.PetclinicMarker" -> {
                        markerPids.add(e.getLong("pid"));
                        markers.add(new Marker(a, f.label(e.getThread()), e.getString("kind"), e.getString("detail")));
                    }
                    case STEP, OP -> spans.add(new Span(spans.size()+1, a, b, f.label(e.getThread()), type,
                        e.getString(type.equals(OP) ? "operation" : "name"), detail(e, type),
                        type.equals(OP) ? e.getLong("cpuNanos") : -1,
                        e.hasField("failed") ? Boolean.toString(e.getBoolean("failed")) : "",
                        e.hasField("items") ? e.getLong("items") : -1,
                        e.hasField("outcome") ? Objects.toString(e.getString("outcome"), "") : ""));
                    case "jdk.JVMInformation" -> {
                        String args = e.getString("javaArguments");
                        surefire |= args != null && (args.contains("surefirebooter") || args.contains("ForkedBooter"));
                        String vm = e.getString("jvmArguments");
                        jacoco |= vm != null && vm.contains("-javaagent:") && vm.contains("org.jacoco.agent");
                        f.pid = e.getLong("pid");
                    }
                }
            }
        }
        if (!surefire) throw new IOException("Not a verified Surefire recording: " + file);
        if (!jacoco) throw new IOException("Missing JaCoCo agent in " + file);
        if (markerPids.stream().anyMatch(id -> id != f.pid)) throw new IOException("Marker/fork PID mismatch: " + file);
        f.markers(markers);
        f.scope("recorded-event-range", new Open(new Marker(first, "-", "", "all observed events"), "-", "-", "-"), last);
        var sorted = f.scopes.stream().sorted(Comparator.comparingLong(Scope::a)).toList();
        var index = new Scopes(sorted, 0, sorted.size());
        Map<Integer,Map<String,List<Span>>> matches = new HashMap<>();
        for (var v : spans) index.overlaps(v.a, v.b, s -> {
            if (v.a != v.b && (v.b <= s.a || v.a >= s.b)) return;
            matches.computeIfAbsent(s.id, ignored -> new TreeMap<>())
                .computeIfAbsent(v.thread, ignored -> new ArrayList<>()).add(v);
        });
        for (var s : f.scopes) {
            var tids = matches.remove(s.id);
            if (tids == null) tids = new TreeMap<>();
            if (!s.owner.equals("-")) tids.computeIfAbsent(s.owner, ignored -> new ArrayList<>());
            tids.forEach((tid, list) -> account(f, s, tid, list));
        }

        // Stream high-volume JDK evidence; never keep RecordedEvent, stack traces
        // or class-load/file-read observations for the lifetime of the recording.
        Map<Integer,int[]> targetCounts = new HashMap<>();
        f.targets.forEach(s -> targetCounts.put(s.id, new int[2]));
        try (var r = new RecordingFile(file)) {
            while (r.hasMoreEvents()) {
                var e = r.readEvent();
                String type = e.getEventType().getName();
                boolean evidence = EVENTS.contains(type) || OTHER_EVIDENCE.contains(type);
                if (!evidence && !type.equals(STEP)) continue;
                long a = ns(e.getStartTime()), b = ns(e.getEndTime());
                var hit = new ArrayList<Scope>();
                index.overlaps(a, b, hit::add);
                if (!evidence && hit.stream().noneMatch(s -> targetCounts.containsKey(s.id))) continue;
                var t = e.hasField("sampledThread") ? e.getThread("sampledThread") : e.getThread();
                String tid = f.label(t), detail = detail(e, type), stack = stack(e);
                if (evidence) {
                    var ids = new StringJoiner(",");
                    hit.forEach(s -> ids.add(Integer.toString(s.id)));
                    out.row("evidence", f.file, f.pid, ids, tid, type, e.getStartTime(), e.getEndTime(),
                        evidenceDetail(e, type, detail), stack);
                }
                if (OTHER_EVIDENCE.contains(type)) continue;
                for (var s : hit) {
                    var counts = targetCounts.get(s.id);
                    if (counts == null) continue;
                    counts[type.equals(STEP) ? 0 : 1]++;
                    out.row("window", f.file, e.getStartTime(), e.getEndTime(), Math.min(b, s.b)-Math.max(a, s.a),
                        tid, type, detail, stack);
                }
            }
        }
        boolean missingEvidence = false;
        for (var s : f.targets) {
            var counts = targetCounts.get(s.id);
            System.out.printf("Window %s .. %s (%s); %d overlapping Spring steps, %d thread observations%n",
                instant(s.a), instant(s.b), Duration.ofNanos(s.b - s.a), counts[0], counts[1]);
            if (counts[0] == 0 || counts[1] == 0) missingEvidence = true;
        }
        return new Result(f.targets, missingEvidence);
    }

    public static void main(String[] args) throws Exception {
        var dir = Path.of(args[0]);
        var metric = dir.resolve("window-ms.txt");
        Files.deleteIfExists(metric);
        var targets = new ArrayList<Scope>();
        boolean missingEvidence = false;
        try (var reports = new Reports(dir); var files = Files.newDirectoryStream(dir, "fork-*.jfr")) {
            boolean any = false;
            for (var file : files) {
                var result = inspect(file, reports);
                targets.addAll(result.targets);
                missingEvidence |= result.missingEvidence;
                any = true;
            }
            if (!any) throw new IOException("No Surefire fork recordings in " + dir);
        }
        if (missingEvidence) throw new IOException("No Spring steps or thread evidence in a matched target window; other tables retained");
        if (targets.size() == 1) {
            var s = targets.get(0);
            Files.writeString(metric, Double.toString((s.b - s.a) / 1_000_000.0));
        } else System.err.printf("Expected one target-suite startup window, found %d; legacy metric unavailable. See markers.tsv and scopes.tsv.%n", targets.size());
        System.out.println("Scope IDs are local to each recording. Context IDs identify observed Starting markers on the owner thread;"
            + " other-thread overlaps are temporal evidence only. Scope and thread totals are not additive."
            + " Per scope/thread: wall = unique self + ambiguous overlap + unclassified; named unions overlap."
            + " Percentiles use nearest rank. CPU is whole-event inclusive (-1 unknown); summary CPU counts only complete"
            + " known spans, never prorates clipped spans, and is not additive across nested calls."
            + " Samples and waits are evidence only; unclassified wall does not imply CPU or waiting.");
    }
}
