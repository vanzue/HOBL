// Copyright (c) Microsoft. Licensed under the MIT license.
package hobl;

import java.nio.file.*;
import java.util.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

class OperationProbeTest {
    public static class Fixture {
        public long doCreateBean(String name, int depth) {
            if (depth == -1) return name.length();
            if (depth == -2) {
                try { return doCreateBean(null, -1); }
                catch (NullPointerException expected) { return 20L; }
            }
            return depth == 0 ? 1L : 1L + doCreateBean(name, depth-1);
        }
    }

    public static void main(String[] args) throws Exception {
        var loader = new ClassLoader(OperationProbeTest.class.getClassLoader()) {
            Class<?> install(byte[] code) { return defineClass(null, code, 0, code.length); }
        };
        var fixture = Fixture.class.getName().replace('.', '/') + ".class";
        byte[] transformed;
        try (var in = loader.getResourceAsStream(fixture)) {
            transformed = new OperationProbe().transform(loader,
                "org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory", null, null, in.readAllBytes());
        }
        if (transformed == null) throw new AssertionError(OperationProbe.errors);
        var type = loader.install(transformed);
        var f = type.getConstructor().newInstance();
        var call = type.getMethod("doCreateBean", String.class, int.class);
        Path file = Files.createTempFile("hobl-hook-", ".jfr");
        try (var r = new Recording()) {
            r.enable("hobl.PetclinicOperation");
            r.start();
            if (!call.invoke(f, "test", 2).equals(3L) || !call.invoke(f, "test", -2).equals(20L))
                throw new AssertionError("Method result changed");
            r.stop();
            r.dump(file);
            var events = RecordingFile.readAllEvents(file).stream()
                .filter(e -> e.getEventType().getName().equals("hobl.PetclinicOperation")).toList();
            if (events.size() != 5 || events.stream().filter(e -> e.getBoolean("failed")).count() != 1)
                throw new AssertionError("Expected four successful calls and one implicit exception: " + events);
        } finally { Files.delete(file); }
    }
}
