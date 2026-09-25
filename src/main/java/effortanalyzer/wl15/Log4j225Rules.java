package effortanalyzer.wl15;

import effortanalyzer.library.DeprecatedApi;
import java.util.ArrayList;
import java.util.List;

/** Log4j 2.x to 2.25.4 migration rules. */
public class Log4j225Rules {

    private static final String LIBRARY = "Log4j 2.25.4";

    private Log4j225Rules() {}

    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();

        // MDC.getContext() deprecated -- returns live map
        add(rules, "org.apache.logging.log4j.ThreadContext", "getContext", "HIGH",
                "Replace ThreadContext.getContext() with ThreadContext.getImmutableContext() for safe read-only copy.",
                "ThreadContext.getContext() deprecated -- returns mutable live map; use getImmutableContext()");

        // Old Log4j 1.x bridge
        add(rules, "org.apache.log4j", null, "CRITICAL",
                "Replace all log4j 1.x classes with log4j2 API (org.apache.logging.log4j.*).",
                "log4j 1.x API (org.apache.log4j) is replaced by Log4j 2; use log4j-1.2-api bridge or migrate");

        // PatternLayout builder change
        add(rules, "org.apache.logging.log4j.core.layout.PatternLayout", "createLayout", "HIGH",
                "Use PatternLayout.newBuilder().withPattern(...).build() instead of createLayout().",
                "PatternLayout.createLayout() static factory removed in 2.x; use builder API");

        // ConsoleAppender factory change
        add(rules, "org.apache.logging.log4j.core.appender.ConsoleAppender", "createAppender", "WARNING",
                "Use ConsoleAppender.newBuilder().setName(...).build() instead of createAppender().",
                "ConsoleAppender.createAppender() factory method removed; use builder API");

        // RollingFileAppender factory change
        add(rules, "org.apache.logging.log4j.core.appender.RollingFileAppender", "createAppender", "WARNING",
                "Use RollingFileAppender.newBuilder().build() instead of createAppender().",
                "RollingFileAppender.createAppender() removed; use builder API");

        // LogManager root logger name
        add(rules, "org.apache.logging.log4j.LogManager", "getRootLogger", "WARNING",
                "Use LogManager.getRootLogger() -- retained but verify root logger config in log4j2.xml.",
                "LogManager.getRootLogger() retained; verify root logger XML config updated to log4j2 format");

        // Level constants changes
        add(rules, "org.apache.logging.log4j.Level", "toLevel", "WARNING",
                "Use Level.toLevel(String, Level) with default; standalone toLevel(String) removed.",
                "Level.toLevel(String) removed in Log4j 2.x; use Level.toLevel(String, Level)");

        // Configurator programmatic config
        add(rules, "org.apache.logging.log4j.core.config.Configurator", "initialize", "WARNING",
                "Review Configurator.initialize() usage; ConfigurationSource approach preferred.",
                "Configurator.initialize() signature changed; prefer ConfigurationSource-based init");

        // AppenderRef changes
        add(rules, "org.apache.logging.log4j.core.config.AppenderRef", "createAppenderRef", "WARNING",
                "Use AppenderRef.createAppenderRef(name, level, filter) with 3-arg version.",
                "AppenderRef.createAppenderRef() parameter set changed in 2.x");

        // JMX appender removed from core
        add(rules, "org.apache.logging.log4j.core.jmx", null, "WARNING",
                "Review JMX configuration; JMX management moved to optional module in 2.25.",
                "Log4j JMX support moved to separate module in 2.25; add log4j-jmx-gui if needed");

        return rules;
    }

    private static void add(List<DeprecatedApi> rules, String className, String methodName,
                             String severity, String replacement, String description) {
        rules.add(new DeprecatedApi(LIBRARY, className, methodName, severity, replacement, description));
    }
}