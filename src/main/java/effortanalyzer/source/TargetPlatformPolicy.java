package effortanalyzer.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Target-server knowledge applied on top of the generic Java 21 rules.
 *
 * <p>The WL14 target is WebLogic 14.1.2 on Java 21. It is still Java EE 8: the server supplies the
 * {@code javax.*} EE APIs that Java SE 11 removed from the JDK, and {@code jakarta.*} is not available.
 * For WL14 those rules are therefore re-labelled
 * {@link #PLATFORM_PROVIDED} (INFO) with "keep javax" guidance instead of "removed / migrate".</p>
 */
public final class TargetPlatformPolicy {

    public static final String PLATFORM_PROVIDED = "PLATFORM_PROVIDED";

    /** Package prefix → compile-time API artifact to declare with {@code provided} scope. */
    private static final Map<String, String> WL14_PROVIDED_APIS = new LinkedHashMap<>();

    static {
        WL14_PROVIDED_APIS.put("javax.annotation", "javax.annotation:javax.annotation-api:1.3.2");
        WL14_PROVIDED_APIS.put("javax.transaction", "javax.transaction:javax.transaction-api:1.3");
        WL14_PROVIDED_APIS.put("javax.xml.bind", "javax.xml.bind:jaxb-api:2.3.1");
        WL14_PROVIDED_APIS.put("javax.xml.ws", "javax.xml.ws:jaxws-api:2.3.1");
        WL14_PROVIDED_APIS.put("javax.xml.soap", "javax.xml.soap:javax.xml.soap-api:1.4.0");
        WL14_PROVIDED_APIS.put("javax.activation", "javax.activation:javax.activation-api:1.2.0");
        WL14_PROVIDED_APIS.put("javax.jws", "javax.jws:javax.jws-api:1.1");
    }

    private TargetPlatformPolicy() {
    }

    public static List<Java21SourceRule> applyWl14(List<Java21SourceRule> rules) {
        if (rules == null) return List.of();
        return rules.stream().map(TargetPlatformPolicy::applyWl14).toList();
    }

    static Java21SourceRule applyWl14(Java21SourceRule rule) {
        String artifact = wl14ApiArtifact(rule.apiPattern());
        if (artifact.isEmpty()) return rule;
        return new Java21SourceRule(rule.id(), PLATFORM_PROVIDED, rule.apiPattern(), "INFO", rule.javaVersion(),
                rule.description() + " WebLogic 14.1.2 (Java EE 8 on Java 21) provides " + rule.apiPattern() + " at runtime.",
                "No code change for WL14: keep the javax.* namespace (jakarta.* is WL15/Jakarta EE 9+ only). "
                        + "Because JDK 21 does not ship this API, declare " + artifact + " with scope 'provided' (or the WebLogic 14.1.2 API jar) to compile. "
                        + "Add it at runtime only for code that runs outside WebLogic, such as standalone tools or tests.",
                rule.scanTarget());
    }

    /** The provided-scope API artifact when {@code apiPattern} is supplied by WebLogic 14.1.2, otherwise empty. */
    public static String wl14ApiArtifact(String apiPattern) {
        if (apiPattern == null) return "";
        for (Map.Entry<String, String> e : WL14_PROVIDED_APIS.entrySet()) {
            String pkg = e.getKey();
            if (apiPattern.equals(pkg) || apiPattern.startsWith(pkg + ".")) return e.getValue();
        }
        return "";
    }

    public static boolean isWl14Provided(String apiPattern) {
        return !wl14ApiArtifact(apiPattern).isEmpty();
    }
}
