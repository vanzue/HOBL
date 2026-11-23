// Copyright (c) Microsoft. Licensed under the MIT license.
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import javax.xml.stream.*;

/** Post-timer evidence. Do not export Surefire XML properties (they include the DUT environment). */
class RoundEvidence {
    static String logText(Path p) throws IOException {
        var bytes = Files.readAllBytes(p);
        return new String(bytes, bytes.length >= 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe
            ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8).replaceAll("\u001b\\[[;\\d]*m", "");
    }

    static BigDecimal mavenTime(String log) throws IOException {
        var m = Pattern.compile("(?m)\\[INFO\\]\\s+Total time:\\s+([0-9.]+) s\\s*$").matcher(log);
        if (m.find()) return new BigDecimal(m.group(1));
        m = Pattern.compile("(?m)\\[INFO\\]\\s+Total time:\\s+(\\d+):([0-9.]+) min\\s*$").matcher(log);
        if (m.find()) return new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(60)).add(new BigDecimal(m.group(2)));
        throw new IOException("Missing or unsupported Maven Total time");
    }

    static void ledger(Path dir) throws IOException {
        var times = new HashMap<String,BigDecimal>();
        for (var line : Files.readAllLines(dir.resolve("phase-times.tsv"))) {
            String[] fields = line.split("\t");
            if (fields.length == 2 && Set.of("test","package").contains(fields[0])) times.put(fields[0], new BigDecimal(fields[1]));
        }
        if (times.size() != 2) throw new IOException("Incomplete command times");
        var testLog = logText(dir.resolve("maven-test.log"));
        var build = mavenTime(logText(dir.resolve("maven-build.log")));
        var test = mavenTime(testLog);
        var ready = Pattern.compile(" INFO\\s+(\\d+)\\s+---[^\\r\\n]*?: Started \\S+ in ([0-9.]+) seconds \\(process running for ([0-9.]+)\\)").matcher(testLog);
        String pid = null;
        BigDecimal starts = BigDecimal.ZERO, last = BigDecimal.ZERO;
        int contexts = 0;
        while (ready.find()) {
            if (pid != null && !pid.equals(ready.group(1))) throw new IOException("Multiple startup PIDs; no single fork window");
            pid = ready.group(1);
            starts = starts.add(new BigDecimal(ready.group(2)));
            last = new BigDecimal(ready.group(3));
            contexts++;
        }
        if (pid == null || starts.compareTo(last) > 0 || last.compareTo(test) > 0)
            throw new IOException("Missing or incomparable Spring startup windows");
        var total = times.get("package").add(times.get("test"));
        var parts = new LinkedHashMap<String,BigDecimal>();
        parts.put("maven_package", build);
        parts.put("spring_startups", starts);
        parts.put("fork_to_last_ready_less_startups", last.subtract(starts));
        parts.put("maven_test_outside_fork_window", test.subtract(last));
        parts.put("hobl_outside_maven_clocks", total.subtract(build).subtract(test));
        if (parts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(total) != 0)
            throw new IOException("Whole-round ledger does not close");
        parts.put("total", total);
        var rows = new ArrayList<>(List.of("phase\tseconds"));
        parts.forEach((k,v) -> rows.add(k + "\t" + v.toPlainString()));
        Files.write(dir.resolve("whole-round.tsv"), rows);
        System.out.println("Exclusive wall seconds (" + contexts + " contexts in fork " + pid + "): " + parts);
    }

    static void checkHooks(Path dir) throws IOException {
        var expected = List.of("PremainAttachAccess.getInstrumentation(", "ByteBuddyAgent.install(",
            "MockitoBeanOverrideHandler.createMock(", "AbstractAutowireCapableBeanFactory.doCreateBean(",
            "PoolBase.newConnection(", "EntityManagerFactoryBuilderImpl.build(");
        var installed = new HashSet<String>();
        var warnings = new ArrayList<String>();
        try (var files = Files.newDirectoryStream(dir, "fork-*-hooks.txt")) {
            for (var p : files) for (var line : Files.readAllLines(p)) {
                if (line.startsWith("ERROR ")) warnings.add(p.getFileName() + ": " + line);
                for (var key : expected) if (line.contains(key)) installed.add(key);
            }
        }
        for (var key : expected) if (!installed.contains(key)) warnings.add("Missing transformed method: " + key);
        if (!logText(dir.resolve("maven-test.log")).contains("Mockito is currently self-attaching"))
            warnings.add("Original Mockito self-attach warning not observed. Do not use this run to attribute attach cost.");
        if (warnings.isEmpty()) System.out.println("MVC/JPA/Hikari probe inventory present; original Mockito self-attach warning observed.");
        else for (var warning : warnings) System.out.println("Probe coverage warning: " + warning);
    }
    static String digest(InputStream stream) throws Exception {
        try (stream) {
            var md = MessageDigest.getInstance("SHA-256");
            try (var in = new DigestInputStream(stream, md)) { in.transferTo(OutputStream.nullOutputStream()); }
            return HexFormat.of().formatHex(md.digest());
        }
    }

    static XMLInputFactory xml() {
        var f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }

    static void cases(Path report, List<String> rows) throws Exception {
        try (var in = Files.newInputStream(report)) {
            var r = xml().createXMLStreamReader(in);
            String test = null, status = null;
            while (r.hasNext()) {
                var tag = r.next();
                if (tag == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "testcase" -> {
                            test = r.getAttributeValue(null, "classname") + "\t" + r.getAttributeValue(null, "name");
                            status = "passed";
                        }
                        case "skipped", "failure", "error" -> status = r.getLocalName();
                    }
                } else if (tag == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("testcase"))
                    rows.add(test.replace('\n', ' ').replace('\r',' ') + "\t" + status);
            }
            r.close();
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]), target = Path.of("target");
        var tests = new ArrayList<String>();
        try (var reports = Files.newDirectoryStream(target.resolve("surefire-reports"), "TEST-*.xml")) {
            for (var file : reports) cases(file, tests);
        }
        if (tests.isEmpty()) throw new IOException("No Surefire test cases");
        long skipped = tests.stream().filter(s -> s.endsWith("\tskipped")).count();
        Collections.sort(tests);
        tests.add(0, "class\ttest\tstatus");
        Files.write(dir.resolve("test-cases.tsv"), tests);

        var artifacts = new TreeMap<String,String>();
        try (var jars = Files.newDirectoryStream(target, "*.jar")) {
            for (var jar : jars) {
                try (var zip = new ZipFile(jar.toFile())) {
                    for (var entry : Collections.list(zip.entries())) {
                        if (!entry.isDirectory() && (entry.getName().startsWith("BOOT-INF/classes/") ||
                            entry.getName().startsWith("BOOT-INF/lib/")))
                            artifacts.put(jar.getFileName() + "!" + entry.getName(), digest(zip.getInputStream(entry)));
                    }
                }
            }
        }
        if (artifacts.isEmpty()) throw new IOException("Missing repackaged PetClinic application entries");
        try (var w = Files.newBufferedWriter(dir.resolve("package-entries.tsv"))) {
            w.write("entry\tsha256\n");
            for (var e : artifacts.entrySet()) w.write(e.getKey() + "\t" + e.getValue() + "\n");
        }
        var commit = new ProcessBuilder("git", "rev-parse", "HEAD").start();
        String revision;
        try (var reader = commit.inputReader()) { revision = reader.readLine(); }
        if (commit.waitFor() != 0 || revision == null) throw new IOException("Cannot verify PetClinic HEAD");
        String msg = "revision=" + revision + " cases=" + (tests.size()-1) + " skipped=" + skipped +
            " package_entries=" + artifacts.size() + " jacoco_exec_bytes=" + Files.size(dir.resolve("jacoco.exec"));
        Files.writeString(dir.resolve("workload.txt"), msg + "\n");
        System.out.println(msg);
        checkHooks(dir);
        try { ledger(dir); }
        catch (IOException | NumberFormatException e) {
            String reason = "Five-bucket ledger unavailable: " + e.getMessage();
            Files.writeString(dir.resolve("whole-round-unavailable.txt"), reason + "\n");
            System.out.println(reason);
        }
    }
}
