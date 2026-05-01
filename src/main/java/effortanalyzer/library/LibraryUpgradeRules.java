package effortanalyzer.library;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Aggregates all third-party library upgrade compatibility rules.
 *
 * Each library has its own dedicated rules class:
 *   ┌──────────────────────────────┬──────────────────────┬──────────────────────┐
 *   │ Library                      │ Rules class          │ Target version       │
 *   ├──────────────────────────────┼──────────────────────┼──────────────────────┤
 *   │ Spring Framework             │ SpringRules          │ 3.x → 5.3.39         │
 *   │ Guava                        │ GuavaRules           │ any → 31.1-jre       │
 *   │ Guice                        │ GuiceRules           │ 3.x → 5.1.0          │
 *   │ CGLib                        │ CglibRules           │ → ByteBuddy          │
 *   │ Jersey                       │ JerseyRules          │ 1.x → 2.22.2         │
 *   └──────────────────────────────┴──────────────────────┴──────────────────────┘
 *
 * Custom rules can be added via:
 *   - Classpath resource {@code upgrade-compatibility-rules.txt}
 *   - External file {@code upgrade-compatibility-rules.txt} next to the JAR
 *
 * Rule file format (pipe-delimited):
 *   library|className|methodName|severity|replacement|description
 *   (legacy 5-field: className|methodName|severity|replacement|description → library = "Custom")
 */
public class LibraryUpgradeRules {

    private static final Logger logger = LogManager.getLogger(LibraryUpgradeRules.class);

    public static final String LIB_SPRING  = SpringRules.LIBRARY;
    public static final String LIB_GUAVA   = GuavaRules.LIBRARY;
    public static final String LIB_GUICE   = GuiceRules.LIBRARY;
    public static final String LIB_CGLIB   = CglibRules.LIBRARY;
    public static final String LIB_JERSEY  = JerseyRules.LIBRARY;

    private final List<DeprecatedApi> rules;

    private LibraryUpgradeRules(List<DeprecatedApi> rules) {
        this.rules = rules;
    }

    /** Load all rules with no exclusions. */
    public static LibraryUpgradeRules load() {
        return load(Set.of());
    }

    /**
     * Load all rules, removing entries whose library name or class name
     * matches any token in {@code excluded}.
     *
     * <p>Exclusion tokens can be:
     * <ul>
     *   <li>A full library name such as {@code "Jersey 2.22.2"} — excludes all Jersey rules</li>
     *   <li>A fully-qualified class name or prefix — excludes matching rules</li>
     * </ul>
     *
     * @param excluded set of exclusion tokens (library names, class prefixes)
     */
    public static LibraryUpgradeRules load(Set<String> excluded) {
        List<DeprecatedApi> all = new ArrayList<>();
        all.addAll(SpringRules.load());
        all.addAll(GuavaRules.load());
        all.addAll(GuiceRules.load());
        all.addAll(CglibRules.load());
        all.addAll(JerseyRules.load());

        if (!excluded.isEmpty()) {
            all.removeIf(api -> isExcluded(api, excluded));
        }

        LibraryUpgradeRules result = new LibraryUpgradeRules(all);
        result.loadCustomRules();

        logger.info("Loaded {} library upgrade compatibility rules ({} excluded)",
                all.size(), excluded.size());
        return result;
    }

    private void loadCustomRules() {
        for (String name : new String[]{"upgrade-compatibility-rules.txt", "spring-deprecation-rules.txt"}) {
            try (InputStream is = LibraryUpgradeRules.class.getClassLoader().getResourceAsStream(name)) {
                if (is != null) loadFromStream(is);
            } catch (IOException e) {
                logger.debug("Could not load classpath resource {}: {}", name, e.getMessage());
            }
        }
        Path external = Path.of("upgrade-compatibility-rules.txt");
        if (Files.exists(external)) {
            try (InputStream is = Files.newInputStream(external)) {
                loadFromStream(is);
            } catch (IOException e) {
                logger.warn("Could not load external rules file: {}", e.getMessage());
            }
        }
    }

    /**
     * Parses a pipe-delimited rule file.
     * Format: library|className|methodName|severity|replacement|description
     * Legacy (5 fields): className|methodName|severity|replacement|description → library = "Custom"
     */
    private void loadFromStream(InputStream is) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\|", -1);
                if (p.length >= 6) {
                    rules.add(new DeprecatedApi(
                            p[0].trim(), p[1].trim(),
                            p[2].trim().isEmpty() ? null : p[2].trim(),
                            p[3].trim(), p[4].trim(), p[5].trim()));
                } else if (p.length >= 5) {
                    rules.add(new DeprecatedApi(
                            "Custom", p[0].trim(),
                            p[1].trim().isEmpty() ? null : p[1].trim(),
                            p[2].trim(), p[3].trim(), p.length > 4 ? p[4].trim() : ""));
                } else {
                    logger.warn("Invalid rule at line {}: {}", lineNum, line);
                }
            }
        }
        logger.info("Loaded custom rules from stream");
    }

    /**
     * A rule is excluded if any token from {@code excluded} matches:
     * <ul>
     *   <li>The rule's library name (exact match)</li>
     *   <li>The rule's class name (exact match or prefix)</li>
     * </ul>
     */
    private static boolean isExcluded(DeprecatedApi api, Set<String> excluded) {
        for (String token : excluded) {
            if (api.library().equals(token)) return true;
            if (api.className().equals(token)) return true;
            if (api.className().startsWith(token)) return true;
        }
        return false;
    }

    public List<DeprecatedApi> getRules() {
        return Collections.unmodifiableList(rules);
    }
}
