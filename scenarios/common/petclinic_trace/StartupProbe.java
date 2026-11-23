// Copyright (c) Microsoft. Licensed under the MIT license.
package hobl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jdk.jfr.*;
import org.junit.jupiter.api.extension.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.metrics.jfr.FlightRecorderApplicationStartup;

public class StartupProbe implements SpringApplicationRunListener {
    @Name("hobl.PetclinicMarker")
    static class Marker extends Event {
        String kind;
        String detail;
        long pid;
    }

    static void mark(String kind, String detail) {
        var marker = new Marker();
        marker.kind = kind;
        marker.detail = detail;
        marker.pid = ProcessHandle.current().pid();
        marker.commit();
    }

    public StartupProbe(SpringApplication app, String[] args) {
        app.setApplicationStartup(new FlightRecorderApplicationStartup());
    }

    @Override
    public void contextPrepared(ConfigurableApplicationContext context) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender("hobl-window") != null) return;
        var appender = new AppenderBase<ILoggingEvent>() {
            protected void append(ILoggingEvent event) {
                if (!event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.INFO)) return;
                String msg = event.getFormattedMessage();
                if (msg.startsWith("Starting ") && msg.contains(" using Java ")) mark("starting", msg);
                else if (msg.startsWith("Started ") && msg.contains(" seconds (process running for ")) mark("ready", msg);
                else if (msg.startsWith("Initializing Spring TestDispatcherServlet")) mark("mvc-servlet", msg);
                else if (msg.startsWith("Tomcat initialized")) mark("tomcat", msg);
                else if (msg.startsWith("Root WebApplicationContext:") || msg.startsWith("Exposing ") ||
                    msg.startsWith("HHH008540:") || msg.startsWith("HHH10001005:") ||
                    msg.startsWith("HHH000489:") || msg.startsWith("Initialized JPA EntityManagerFactory") ||
                    msg.startsWith("HikariPool-") || msg.startsWith("Finished Spring Data repository scanning"))
                    mark("milestone", msg.lines().findFirst().orElse(""));
            }
        };
        appender.setName("hobl-window");
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    public static class Suites implements BeforeAllCallback, AfterAllCallback, BeforeTestExecutionCallback, AfterTestExecutionCallback {
        public void beforeAll(ExtensionContext ctx) { mark("suite-start", ctx.getRequiredTestClass().getName()); }
        public void afterAll(ExtensionContext ctx) { mark("suite-end", ctx.getRequiredTestClass().getName()); }
        public void beforeTestExecution(ExtensionContext ctx) { mark("test-start", ctx.getUniqueId()); }
        public void afterTestExecution(ExtensionContext ctx) { mark("test-end", ctx.getUniqueId()); }
    }
}
