package effortanalyzer.wl14;

import effortanalyzer.library.DeprecatedApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WebLogic 12c → 14c library/API migration rules.
 *
 * <p>These rules augment the general {@code upgrade} library scan with issues that are
 * specific to moving from Oracle WebLogic Server 12c to WebLogic Server 14.1.2.
 * They are intentionally separate from the {@code wl15} rule set because WL15 targets
 * Java 21 / Jakarta EE 9+ migration concerns while WL14 remains on WebLogic 14.1.2
 * (Java 8/11, {@code javax.*} still valid) but removes or deprecates several WebLogic
 * proprietary APIs.</p>
 */
public class Wl14LibraryRules {

    private static final String WEBLOGIC = "WebLogic 14.1.2";

    private Wl14LibraryRules() {
        // utility class
    }

    /**
     * Loads the full set of WebLogic 12 → 14 migration rules.
     */
    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();
        addWebLogicLifecycleRules(rules);
        addWebLogicLoggingRules(rules);
        addWebLogicSecurityRules(rules);
        return Collections.unmodifiableList(rules);
    }

    // WebLogic common internal lifecycle interfaces removed in 14.1.2
    private static void addWebLogicLifecycleRules(List<DeprecatedApi> rules) {
        add(rules,
                "weblogic.common.T3StartupDef",
                "HIGH",
                "Replace with weblogic.application.ApplicationLifecycleListener or a ServletContextListener.",
                "T3StartupDef was removed in WebLogic 14.1.2; startup logic must be migrated to a supported lifecycle listener.");
        add(rules,
                "weblogic.common.T3ShutdownDef",
                "HIGH",
                "Replace with weblogic.application.ApplicationLifecycleListener shutdown logic.",
                "T3ShutdownDef was removed in WebLogic 14.1.2; shutdown logic must be migrated to a supported lifecycle listener.");
    }

    // Deprecated logging SPI
    private static void addWebLogicLoggingRules(List<DeprecatedApi> rules) {
        add(rules,
                "weblogic.i18n.logging.MessageLogger",
                "WARNING",
                "Replace with java.util.logging, SLF4J, or the WebLogic Server logging API.",
                "weblogic.i18n.logging.MessageLogger is deprecated; avoid proprietary logging interfaces for WebLogic 14.1.2.");
    }

    // Removed/deprecated security APIs
    private static void addWebLogicSecurityRules(List<DeprecatedApi> rules) {
        add(rules,
                "weblogic.security.SSL.TrustManager",
                "WARNING",
                "Replace with standard javax.net.ssl.TrustManager or the JDK/Oracle-supported WebLogic Security API.",
                "The weblogic.security.SSL.TrustManager class is no longer supported on WebLogic 14.1.2.");
        add(rules,
                "weblogic.security.SSL.HostnameVerifier",
                "WARNING",
                "Replace with javax.net.ssl.HostnameVerifier.",
                "The weblogic.security.SSL.HostnameVerifier class is no longer supported on WebLogic 14.1.2.");
    }

    private static void add(List<DeprecatedApi> rules, String className, String severity,
                            String replacement, String description) {
        rules.add(new DeprecatedApi(WEBLOGIC, className, null, severity, replacement, description));
    }
}
