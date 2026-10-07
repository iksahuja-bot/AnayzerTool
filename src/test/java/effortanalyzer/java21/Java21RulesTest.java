package effortanalyzer.java21;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Java21RulesTest {

    @Test
    void removedRulesDoNotFlagApisStillPresentInJava21() {
        List<Java21Rules.Rule> removedRules = Java21Rules.load().getRules().stream()
                .filter(rule -> "JAVA_REMOVED".equals(rule.category()))
                .toList();

        assertFalse(hasRule(removedRules, "javax.transaction"),
                "javax.transaction.xa is still present in Java SE 21, so the bytecode rules must not use a broad javax.transaction rule");
        assertFalse(hasRule(removedRules, "javax.script.ScriptEngineManager"),
                "ScriptEngineManager is still present in Java 21; only Nashorn-specific APIs should be reported");
        assertFalse(hasRule(removedRules, "java.rmi.server.UnicastRemoteObject"),
                "UnicastRemoteObject is still present in Java 21; only java.rmi.activation was removed");
    }

    @Test
    void removedRulesStillFlagNonXaJavaTransactionApis() {
        List<Java21Rules.Rule> removedRules = Java21Rules.load().getRules().stream()
                .filter(rule -> "JAVA_REMOVED".equals(rule.category()))
                .toList();

        assertTrue(hasRule(removedRules, "javax.transaction.UserTransaction"));
        assertTrue(hasRule(removedRules, "javax.transaction.Transaction"));
        assertTrue(hasRule(removedRules, "javax.transaction.Transactional"));
    }

    private static boolean hasRule(List<Java21Rules.Rule> rules, String apiPattern) {
        return rules.stream().anyMatch(rule -> apiPattern.equals(rule.apiPattern()));
    }
}