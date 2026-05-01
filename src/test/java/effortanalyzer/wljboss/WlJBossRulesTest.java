package effortanalyzer.wljboss;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class WlJBossRulesTest {

    // TargetProfile.from()

    @Test
    void fromNullReturnsWildFly27() {
        assertEquals(WlJBossRules.TargetProfile.WILDFLY27_JAVA21,
                WlJBossRules.TargetProfile.from(null));
    }

    @Test
    void fromUnknownStringReturnsWildFly27() {
        assertEquals(WlJBossRules.TargetProfile.WILDFLY27_JAVA21,
                WlJBossRules.TargetProfile.from("something-random"));
    }

    @Test
    void fromWildFly26DashJava8Alias() {
        assertEquals(WlJBossRules.TargetProfile.WILDFLY26_JAVA8,
                WlJBossRules.TargetProfile.from("wildfly26-java8"));
    }

    @Test
    void fromWildFly26UnderscoreJava8Alias() {
        assertEquals(WlJBossRules.TargetProfile.WILDFLY26_JAVA8,
                WlJBossRules.TargetProfile.from("wildfly26_java8"));
    }

    @Test
    void fromEap74Alias() {
        assertEquals(WlJBossRules.TargetProfile.WILDFLY26_JAVA8,
                WlJBossRules.TargetProfile.from("eap74"));
    }

    @Test
    void fromJava8Alias() {
        assertEquals(WlJBossRules.TargetProfile.WILDFLY26_JAVA8,
                WlJBossRules.TargetProfile.from("java8"));
    }

    @Test
    void fromIsCaseInsensitive() {
        assertEquals(WlJBossRules.TargetProfile.WILDFLY26_JAVA8,
                WlJBossRules.TargetProfile.from("WILDFLY26-JAVA8"));
        assertEquals(WlJBossRules.TargetProfile.WILDFLY26_JAVA8,
                WlJBossRules.TargetProfile.from("Java8"));
    }

    @Test
    void fromTrimsWhitespace() {
        assertEquals(WlJBossRules.TargetProfile.WILDFLY26_JAVA8,
                WlJBossRules.TargetProfile.from("  java8  "));
    }

    // TargetProfile labels

    @Test
    void wildFly27DisplayLabelContainsAllParts() {
        String label = WlJBossRules.TargetProfile.WILDFLY27_JAVA21.displayLabel();
        assertTrue(label.contains("WildFly 27+"));
        assertTrue(label.contains("Java 21"));
        assertTrue(label.contains("Jakarta EE 10"));
    }

    @Test
    void wildFly26DisplayLabelContainsAllParts() {
        String label = WlJBossRules.TargetProfile.WILDFLY26_JAVA8.displayLabel();
        assertTrue(label.contains("WildFly 26"));
        assertTrue(label.contains("Java 8"));
        assertTrue(label.contains("Jakarta EE 8"));
    }

    // Rule loading

    @Test
    void loadDefaultReturnsNonEmptyRuleList() {
        WlJBossRules rules = WlJBossRules.load();
        assertFalse(rules.getRules().isEmpty());
    }

    @Test
    void loadWildFly27ReturnsNonEmptyRuleList() {
        WlJBossRules rules = WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY27_JAVA21);
        assertFalse(rules.getRules().isEmpty());
    }

    @Test
    void loadWildFly26ReturnsNonEmptyRuleList() {
        WlJBossRules rules = WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY26_JAVA8);
        assertFalse(rules.getRules().isEmpty());
    }

    @Test
    void loadByStringDelegatesToFrom() {
        WlJBossRules rules = WlJBossRules.load("java8");
        assertEquals(WlJBossRules.TargetProfile.WILDFLY26_JAVA8, rules.getTarget());
    }

    @Test
    void getRulesIsUnmodifiable() {
        WlJBossRules rules = WlJBossRules.load();
        assertThrows(UnsupportedOperationException.class,
                () -> rules.getRules().clear());
    }

    @Test
    void getTargetReflectsChosenProfile() {
        WlJBossRules wf27 = WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY27_JAVA21);
        WlJBossRules wf26 = WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY26_JAVA8);
        assertEquals(WlJBossRules.TargetProfile.WILDFLY27_JAVA21, wf27.getTarget());
        assertEquals(WlJBossRules.TargetProfile.WILDFLY26_JAVA8,  wf26.getTarget());
    }

    // Category presence / absence

    @Test
    void wildFly27RulesContainJavaxToJakartaCategory() {
        WlJBossRules rules = WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY27_JAVA21);
        assertTrue(categories(rules).contains("JAVAX_TO_JAKARTA"),
                "WildFly 27 profile must include JAVAX_TO_JAKARTA rules");
    }

    @Test
    void wildFly26RulesDoNotContainJavaxToJakartaCategory() {
        WlJBossRules rules = WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY26_JAVA8);
        assertFalse(categories(rules).contains("JAVAX_TO_JAKARTA"),
                "WildFly 26 profile must NOT include JAVAX_TO_JAKARTA rules");
    }

    @Test
    void wildFly27RulesContainJava21IncompatibleCategory() {
        WlJBossRules rules = WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY27_JAVA21);
        assertTrue(categories(rules).contains("JAVA21_INCOMPATIBLE"));
    }

    @Test
    void wildFly26RulesContainJava8CompatCategory() {
        WlJBossRules rules = WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY26_JAVA8);
        assertTrue(categories(rules).contains("JAVA8_COMPAT"));
    }

    @Test
    void bothProfilesContainWebLogicApiCategory() {
        assertTrue(categories(WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY27_JAVA21)).contains("WEBLOGIC_API"));
        assertTrue(categories(WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY26_JAVA8)).contains("WEBLOGIC_API"));
    }

    @Test
    void bothProfilesContainEjbLegacyCategory() {
        assertTrue(categories(WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY27_JAVA21)).contains("EJB_LEGACY"));
        assertTrue(categories(WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY26_JAVA8)).contains("EJB_LEGACY"));
    }

    // Rule field validation

    @Test
    void allRulesHaveNonBlankCategory() {
        WlJBossRules rules = WlJBossRules.load();
        rules.getRules().forEach(r ->
                assertFalse(r.category().isBlank(),
                        "Rule '" + r.apiPattern() + "' has blank category"));
    }

    @Test
    void allRulesHaveNonBlankApiPattern() {
        WlJBossRules rules = WlJBossRules.load();
        rules.getRules().forEach(r ->
                assertFalse(r.apiPattern().isBlank(),
                        "Rule in category '" + r.category() + "' has blank apiPattern"));
    }

    @Test
    void allRulesHaveValidSeverity() {
        WlJBossRules rules = WlJBossRules.load();
        Set<String> valid = Set.of("CRITICAL", "HIGH", "MEDIUM", "INFO");
        rules.getRules().forEach(r ->
                assertTrue(valid.contains(r.severity()),
                        "Rule '" + r.apiPattern() + "' has invalid severity: " + r.severity()));
    }

    @Test
    void allRulesHaveNonBlankDescription() {
        WlJBossRules rules = WlJBossRules.load();
        rules.getRules().forEach(r ->
                assertFalse(r.description().isBlank(),
                        "Rule '" + r.apiPattern() + "' has blank description"));
    }

    @Test
    void allRulesHaveNonBlankRemediation() {
        WlJBossRules rules = WlJBossRules.load();
        rules.getRules().forEach(r ->
                assertFalse(r.remediation().isBlank(),
                        "Rule '" + r.apiPattern() + "' has blank remediation"));
    }

    @Test
    void allRulesHaveNonNullScanMode() {
        WlJBossRules rules = WlJBossRules.load();
        rules.getRules().forEach(r ->
                assertNotNull(r.scanMode(),
                        "Rule '" + r.apiPattern() + "' has null scanMode"));
    }

    // Rule.bytecodePattern()

    @Test
    void bytecodePatternReplacesDotWithSlash() {
        WlJBossRules.Rule rule = new WlJBossRules.Rule(
                "TEST", "weblogic.servlet.http", "HIGH", "desc", "remed",
                WlJBossRules.ScanMode.BOTH);
        assertEquals("weblogic/servlet/http", rule.bytecodePattern());
    }

    @Test
    void bytecodePatternNoDotsLeftUnchanged() {
        WlJBossRules.Rule rule = new WlJBossRules.Rule(
                "TEST", "t3://", "CRITICAL", "desc", "remed",
                WlJBossRules.ScanMode.SOURCE);
        assertEquals("t3://", rule.bytecodePattern());
    }

    @Test
    void bytecodePatternForJavaxServlet() {
        WlJBossRules.Rule rule = new WlJBossRules.Rule(
                "JAVAX_TO_JAKARTA", "javax.servlet", "CRITICAL", "desc", "remed",
                WlJBossRules.ScanMode.BOTH);
        assertEquals("javax/servlet", rule.bytecodePattern());
    }

    @Test
    void wildFly27HasMoreRulesThanWildFly26() {
        int count27 = WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY27_JAVA21).getRules().size();
        int count26 = WlJBossRules.load(WlJBossRules.TargetProfile.WILDFLY26_JAVA8).getRules().size();
        assertTrue(count27 > count26,
                "WildFly 27 (" + count27 + ") should have more rules than WildFly 26 (" + count26 + ")");
    }

    // Helper

    private static Set<String> categories(WlJBossRules rules) {
        return rules.getRules().stream()
                .map(WlJBossRules.Rule::category)
                .collect(Collectors.toSet());
    }
}