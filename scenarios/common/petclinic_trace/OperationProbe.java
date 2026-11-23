// Copyright (c) Microsoft. Licensed under the MIT license.
package hobl;

import java.lang.classfile.*;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.management.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/** Installed at class definition, without resolving or initializing any measured library. */
public final class OperationProbe implements ClassFileTransformer {
    static final Map<String, List<String>> targets = Map.ofEntries(
        target("org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory", "doCreateBean"),
        target("org/springframework/context/annotation/ConfigurationClassPostProcessor", "processConfigBeanDefinitions"),
        target("org/springframework/context/annotation/ClassPathScanningCandidateComponentProvider", "findCandidateComponents"),
        target("org/springframework/test/context/bean/override/mockito/MockitoBeanOverrideHandler", "createMock"),
        target("org/springframework/test/context/cache/DefaultContextCache", "get", "put"),
        target("org/mockito/internal/PremainAttachAccess", "getInstrumentation"),
        target("net/bytebuddy/agent/ByteBuddyAgent", "install", "installExternal"),
        target("org/springframework/boot/tomcat/TomcatWebServer", "initialize", "start"),
        target("org/springframework/data/repository/config/RepositoryConfigurationDelegate", "registerRepositoriesIn"),
        target("org/springframework/data/repository/core/support/RepositoryFactorySupport", "getRepository"),
        target("org/springframework/web/servlet/handler/AbstractHandlerMethodMapping", "initHandlerMethods"),
        target("org/springframework/boot/actuate/endpoint/annotation/EndpointDiscoverer", "discoverEndpoints"),
        target("com/zaxxer/hikari/pool/HikariPool", "checkFailFast"),
        target("com/zaxxer/hikari/pool/PoolBase", "newConnection", "setupConnection"),
        target("org/h2/Driver", "connect"),
        target("org/springframework/jdbc/datasource/init/ScriptUtils", "executeSqlScript"),
        target("org/hibernate/jpa/boot/internal/EntityManagerFactoryBuilderImpl", "metadata", "build"),
        target("org/hibernate/boot/internal/SessionFactoryBuilderImpl", "build"),
        target("org/hibernate/engine/jdbc/env/internal/JdbcEnvironmentInitiator", "getJdbcEnvironmentUsingJdbcMetadata"),
        target("org/testcontainers/dockerclient/DockerClientProviderStrategy", "getFirstValidStrategy"),
        target("org/junit/platform/launcher/core/EngineDiscoveryOrchestrator", "discover")
    );
    static final Set<String> transformed = new ConcurrentSkipListSet<>(), errors = new ConcurrentSkipListSet<>();
    static final ThreadMXBean cpu = ManagementFactory.getThreadMXBean();
    static final ClassDesc helper = ClassDesc.of("hobl.OperationProbe"), event = ClassDesc.of("hobl.OperationProbe$Span");
    static final MethodTypeDesc begin = MethodTypeDesc.of(event, ConstantDescs.CD_String, ConstantDescs.CD_String);
    static final MethodTypeDesc end = MethodTypeDesc.of(ConstantDescs.CD_void, event);

    private static Map.Entry<String,List<String>> target(String name, String... methods) {
        return Map.entry(name, List.of(methods));
    }

    @Name("hobl.PetclinicOperation") @StackTrace(false)
    public static class Span extends Event {
        public String operation;
        @Timespan(Timespan.NANOSECONDS) public long cpuNanos;
        public boolean failed;
        public String outcome;
        public long items;
    }

    public static Span begin(String method, String bean) {
        Span s = new Span();
        s.items = -1;
        s.operation = bean == null ? method : method + ":" + bean;
        s.cpuNanos = cpu.isCurrentThreadCpuTimeSupported() ? cpu.getCurrentThreadCpuTime() : -1;
        s.begin();
        return s;
    }

    public static void end(Span s) {
        s.end();
        if (s.cpuNanos >= 0) {
            long now = cpu.getCurrentThreadCpuTime();
            s.cpuNanos = now < s.cpuNanos ? -1 : now - s.cpuNanos;
        }
        s.commit();
    }

    public static void failed(Span s) { s.failed = true; end(s); }

    public static void cache(Object result, Span s) { s.outcome = result == null ? "miss" : "hit"; }
    public static void size(Object result, Span s) {
        if (result instanceof Collection<?> items) s.items = items.size();
    }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redef,
                                       ProtectionDomain domain, byte[] bytes) {
        if (name == null || !targets.containsKey(name)) return null;
        try {
            var cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.ofResourceParsing(loader).cached()));
            var model = cf.parse(bytes);
            var found = new ArrayList<String>();
            var result = cf.transformClass(model, (cb, element) -> {
                if (!(element instanceof MethodModel m) || m.code().isEmpty() ||
                    !targets.get(name).contains(m.methodName().stringValue())) { cb.with(element); return; }
                String method = m.methodName().stringValue();
                String base = name.replace('/', '.') + "." + method;
                String label = model.methods().stream().filter(x -> x.methodName().equalsString(method)).count() > 1
                    ? base + m.methodType().stringValue() : base;
                // Only the public no-arg install is the whole Byte Buddy install; its overloads delegate.
                if (method.equals("install") && !m.methodType().stringValue().startsWith("()")) {
                    cb.with(m); return;
                }
                boolean named = method.equals("doCreateBean") || method.equals("createMock");
                found.add(base + m.methodType().stringValue());
                cb.transformMethod(m, MethodTransform.transformingCode(new CodeTransform() {
                    Label first;
                    int local;
                    public void atStart(CodeBuilder b) {
                        first = b.newLabel();
                        local = b.allocateLocal(TypeKind.REFERENCE);
                        b.ldc(label);
                        if (named) b.aload(1); else b.aconst_null();
                        b.invokestatic(helper, "begin", begin).astore(local).labelBinding(first);
                    }
                    public void accept(CodeBuilder b, CodeElement e) {
                        if (e instanceof ReturnInstruction) {
                            String observer = switch (method) {
                                case "get" -> "cache";
                                case "findCandidateComponents", "registerRepositoriesIn" -> "size";
                                default -> null;
                            };
                            if (observer != null) b.dup().aload(local).invokestatic(helper, observer,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, event));
                            b.aload(local).invokestatic(helper, "end", end);
                        }
                        b.with(e);
                    }
                    public void atEnd(CodeBuilder b) {
                        var handler = b.newLabel();
                        b.labelBinding(handler).aload(local).invokestatic(helper, "failed", end).athrow();
                        b.exceptionCatchAll(first, handler, handler);
                    }
                }));
            });
            transformed.addAll(found);
            return result;
        } catch (RuntimeException | LinkageError e) {
            errors.add(name + ": " + e);
            return null;
        }
    }
}
