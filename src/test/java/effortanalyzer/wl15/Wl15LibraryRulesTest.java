package effortanalyzer.wl15;

import effortanalyzer.library.DeprecatedApi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Wl15LibraryRules: verifies that the aggregator loads all rule
 * files and that every loaded rule has required fields populated correctly.
 */
class Wl15LibraryRulesTest {

    private static final List<DeprecatedApi> RULES = Wl15LibraryRules.load();

    // ── Structural integrity ──────────────────────────────────────────────────

    @Test
    void rulesLoadWithoutError() {
        assertNotNull(RULES, "Rule list must not be null");
    }

    @Test
    void ruleCountIsReasonable() {
        assertTrue(RULES.size() >= 50,
                "Expected at least 50 WL15 rules, got: " + RULES.size());
    }

    @Test
    void noRuleHasNullOrBlankLibrary() {
        for (DeprecatedApi r : RULES) {
            assertNotNull(r.library(), "library must not be null: " + r);
            assertFalse(r.library().isBlank(), "library must not be blank: " + r);
        }
    }

    @Test
    void noRuleHasNullOrBlankClassName() {
        for (DeprecatedApi r : RULES) {
            assertNotNull(r.className(), "className must not be null: " + r);
            assertFalse(r.className().isBlank(), "className must not be blank: " + r);
        }
    }

    @Test
    void noRuleHasNullOrBlankSeverity() {
        for (DeprecatedApi r : RULES) {
            assertNotNull(r.severity(), "severity must not be null: " + r);
            assertTrue(
                    r.severity().equals("CRITICAL") || r.severity().equals("HIGH")
                    || r.severity().equals("WARNING") || r.severity().equals("INFO"),
                    "severity must be one of CRITICAL/HIGH/WARNING/INFO: " + r);
        }
    }

    @Test
    void noRuleHasNullOrBlankReplacement() {
        for (DeprecatedApi r : RULES) {
            assertNotNull(r.replacement(), "replacement must not be null: " + r);
            assertFalse(r.replacement().isBlank(), "replacement must not be blank: " + r);
        }
    }

    // ── Library coverage ──────────────────────────────────────────────────────

    @Test
    void allExpectedLibrariesRepresented() {
        Set<String> libraries = RULES.stream()
                .map(DeprecatedApi::library)
                .collect(Collectors.toSet());

        assertAll("Expected library names present",
                () -> assertTrue(libraries.stream().anyMatch(l -> l.contains("Spring 6")),
                        "Spring 6 rules expected"),
                () -> assertTrue(libraries.stream().anyMatch(l -> l.contains("Spring Security")),
                        "Spring Security rules expected"),
                () -> assertTrue(libraries.stream().anyMatch(l -> l.contains("Jackson")),
                        "Jackson rules expected"),
                () -> assertTrue(libraries.stream().anyMatch(l -> l.contains("Netty")),
                        "Netty rules expected"),
                () -> assertTrue(libraries.stream().anyMatch(l -> l.contains("Log4j")),
                        "Log4j rules expected"),
                () -> assertTrue(libraries.stream().anyMatch(l -> l.contains("Jetty")),
                        "Jetty rules expected"),
                () -> assertTrue(libraries.stream().anyMatch(l -> l.contains("JasperReports")),
                        "JasperReports rules expected"),
                () -> assertTrue(libraries.stream().anyMatch(l -> l.contains("EhCache")),
                        "EhCache rules expected"),
                () -> assertTrue(libraries.stream().anyMatch(l -> l.contains("mina")),
                        "MINA rules expected"),
                () -> assertTrue(libraries.stream().anyMatch(l -> l.contains("nimbus")),
                        "Nimbus JOSE+JWT rules expected")
        );
    }

    // ── Key rule presence ────────────────────────────────────────────────────

    @Test
    void springSecurityContainsWebSecurityConfigurerAdapterRule() {
        boolean found = RULES.stream().anyMatch(r ->
                r.className().contains("WebSecurityConfigurerAdapter"));
        assertTrue(found, "WebSecurityConfigurerAdapter rule must be present");
    }

    @Test
    void springSecurityContainsAntMatchersRule() {
        boolean found = RULES.stream().anyMatch(r ->
                "antMatchers".equals(r.methodName()));
        assertTrue(found, "antMatchers rule must be present");
    }

    @Test
    void springContainsRemotingPackageRule() {
        boolean found = RULES.stream().anyMatch(r ->
                r.className().contains("org.springframework.remoting.httpinvoker"));
        assertTrue(found, "Spring remoting HTTP invoker package rule must be present");
    }

    @Test
    void ehcacheContainsNetSfEhcachePackageRule() {
        boolean found = RULES.stream().anyMatch(r ->
                r.className().equals("net.sf.ehcache"));
        assertTrue(found, "net.sf.ehcache package rule must be present");
    }

    @Test
    void jettyContainsJavaxServletPackageRule() {
        boolean found = RULES.stream().anyMatch(r ->
                r.className().equals("javax.servlet"));
        assertTrue(found, "javax.servlet package rule must be present");
    }

    @Test
    void jacksonContainsEnableDefaultTypingRule() {
        boolean found = RULES.stream().anyMatch(r ->
                "enableDefaultTyping".equals(r.methodName()));
        assertTrue(found, "enableDefaultTyping rule must be present");
    }

    @Test
    void log4jContainsLog4j1xPackageRule() {
        boolean found = RULES.stream().anyMatch(r ->
                r.className().equals("org.apache.log4j"));
        assertTrue(found, "org.apache.log4j (log4j 1.x) package rule must be present");
    }

    @Test
    void criticalRulesExist() {
        long criticalCount = RULES.stream()
                .filter(r -> "CRITICAL".equals(r.severity()))
                .count();
        assertTrue(criticalCount >= 10,
                "Expected at least 10 CRITICAL rules, got: " + criticalCount);
    }
}