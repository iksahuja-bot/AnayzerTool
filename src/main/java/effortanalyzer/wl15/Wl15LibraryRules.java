package effortanalyzer.wl15;

import effortanalyzer.library.DeprecatedApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates all WL15 migration rules.
 * Mirrors the role of {@code LibraryUpgradeRules} in the {@code upgrade} module.
 *
 * <p>Rules cover the library upgrade set required for WebLogic 15:
 * <ul>
 *   <li>Spring Framework 6.2.11 / Spring Security 6.5.9 ({@link Spring6Rules})</li>
 *   <li>Jackson 2.18.9 ({@link Jackson218Rules})</li>
 *   <li>Netty 4.1.135.Final ({@link Netty4Rules})</li>
 *   <li>Log4j 2.25.4 ({@link Log4j225Rules})</li>
 *   <li>Jetty 12.0.33 ({@link Jetty12Rules})</li>
 *   <li>JasperReports 7.0.4 ({@link JasperReports7Rules})</li>
 *   <li>EhCache 3.11.1, commons-*, hibernate-validator, c3p0, MINA, Nimbus,
 *       OWASP, lz4-java, neethi, commons-vfs2, assertj-core ({@link Wl15MiscRules})</li>
 * </ul>
 */
public class Wl15LibraryRules {

    private Wl15LibraryRules() {}

    /** Returns all WL15 migration rules. */
    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> all = new ArrayList<>();
        all.addAll(Spring6Rules.load());
        all.addAll(Jackson218Rules.load());
        all.addAll(Netty4Rules.load());
        all.addAll(Log4j225Rules.load());
        all.addAll(Jetty12Rules.load());
        all.addAll(JasperReports7Rules.load());
        all.addAll(Wl15MiscRules.load());
        return all;
    }
}