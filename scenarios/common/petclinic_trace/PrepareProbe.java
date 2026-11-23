// Copyright (c) Microsoft. Licensed under the MIT license.
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

class PrepareProbe {
    static void compile(String release, Path dest, Path source, String... options) throws IOException {
        Files.createDirectories(dest);
        var args = new ArrayList<>(List.of("--release", release, "-d", dest.toString()));
        Collections.addAll(args, options);
        args.add(source.toString());
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)) != 0)
            throw new IOException("Cannot compile " + source);
    }

    public static void main(String[] args) throws Exception {
        Path tools = Path.of(args[0]), classes = Path.of("target/test-classes");
        compile("17", classes, tools.resolve("StartupProbe.java"), "-cp", Files.readString(Path.of(args[1])).trim());
        Path meta = Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(meta.resolve("org.junit.jupiter.api.extension.Extension"), "hobl.StartupProbe$Suites\n",
            StandardOpenOption.CREATE_NEW);
        Files.writeString(meta.resolveSibling("spring.factories"),
            "org.springframework.boot.SpringApplicationRunListener=hobl.StartupProbe\n", StandardOpenOption.CREATE_NEW);
        Path agent = Path.of("target/hobl-agent");
        compile("25", agent, tools.resolve("ForkRecorder.java"),
            tools.resolve("OperationProbe.java").toString());
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", "hobl.ForkRecorder");
        try (var jar = new JarOutputStream(Files.newOutputStream(Path.of("target/hobl-agent.jar")), manifest)) {
            try (var classesInAgent = Files.list(agent.resolve("hobl"))) {
                for (var file : classesInAgent.filter(p -> p.toString().endsWith(".class")).toList()) {
                    jar.putNextEntry(new JarEntry("hobl/" + file.getFileName()));
                    Files.copy(file, jar);
                    jar.closeEntry();
                }
            }
        }
        Files.writeString(Path.of(System.getenv("HOBL_TRACE_OUT"), "run.txt"),
            "diagnostic=true\nprobePrepared=" + Instant.now() + "\n");
    }
}
