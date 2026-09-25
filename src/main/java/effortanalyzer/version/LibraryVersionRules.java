package effortanalyzer.version;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Table of library-version upgrade checks for the WebLogic 14/15 target stack.
 *
 * <p>Built-in rows are the authoritative LibraryUpgradeList table for the
 * WL14/WL15 migration (28 rows; entries without a planned version get no check). The
 * whole table can be adjusted without recompiling via an external
 * {@code library-versions.properties} placed next to the EffortAnalyzer JAR
 * (same resolution strategy as {@code effort-overrides.properties}):
 *
 * <pre>
 *   # override target version of a built-in artifact
 *   spring-core=6.2.12
 *
 *   # override target version, severity and display name of any row
 *   spring-core=6.2.12:CRITICAL:Spring Framework
 *
 *   # add a new check (default severity WARNING)
 *   my-shared-lib=3.4.1
 *
 *   # drop a built-in check
 *   disable.moment=true
 * </pre>
 */
public final class LibraryVersionRules {

    public static final String OVERRIDE_FILE_NAME = "library-versions.properties";

    /** Fallback severity for rows added only via the override file. */
    private static final String DEFAULT_SEVERITY = "WARNING";

    private LibraryVersionRules() {}

    /** All built-in version checks (no external overrides applied). */
    public static List<LibraryVersionRule> builtIn() {
        List<LibraryVersionRule> r = new ArrayList<>();

        // ---- Bouncy Castle: 1.80 -> 1.85 (LibraryUpgradeList) --------------
        add(r, "bcprov-jdk18on",          "Bouncy Castle",        "1.85",        "HIGH");
        add(r, "bcpg-jdk18on",            "Bouncy Castle",        "1.85",        "HIGH");
        add(r, "bcutil-jdk18on",          "Bouncy Castle",        "1.85",        "HIGH");
        add(r, "bcpkix-jdk18on",          "Bouncy Castle",        "1.85",        "HIGH");

        // ---- Apache Commons ------------------------------------------------
        add(r, "commons-beanutils",       "Commons BeanUtils",    "1.11.0",      "WARNING");
        add(r, "commons-fileupload",      "Commons FileUpload",   "1.6.0",       "WARNING");
        add(r, "commons-text",            "Commons Text",         "1.13.0",      "HIGH");
        add(r, "commons-vfs",             "Commons VFS",          "2.10.0",      "WARNING");
        add(r, "commons-vfs2",            "Commons VFS2",         "2.10.0",      "WARNING");

        // ---- Jackson Family (prefix rule covers the whole jackson-* line) --
        add(r, "jackson-",                "Jackson",              "2.18.9",      "CRITICAL");

        // ---- Logging, servers, security ------------------------------------
        add(r, "ehcache",                 "EhCache",              "3.11.1",      "CRITICAL");
        add(r, "esapi",                   "ESAPI",                "2.7.0.0",     "HIGH");
        add(r, "jetty-http",              "Jetty",                "12.0.33",     "CRITICAL");
        add(r, "log4j-core",              "Log4j",                "2.25.4",      "CRITICAL");
        add(r, "logback-core",            "Logback",              "1.5.36",      "HIGH");
        add(r, "nimbus-jose-jwt",         "Nimbus JOSE+JWT",      "9.37.2",      "WARNING");
        add(r, "owasp-java-html-sanitizer","OWASP HTML Sanitizer","20260101.1",  "WARNING");
        add(r, "sshd-core",               "Apache SSHD",          "2.19.0",      "HIGH");

        // ---- Other planned upgrades ----------------------------------------
        add(r, "hibernate-validator",     "Hibernate Validator",  "6.2.0.CR1",   "HIGH");
        add(r, "jasperreports",           "JasperReports",        "7.0.4",       "HIGH");
        add(r, "mina-core",               "MINA",                 "2.0.29",      "HIGH");
        add(r, "neethi",                  "Neethi",               "3.2.2",       "INFO");
        add(r, "netty-codec",             "Netty",                "4.1.135.Final","CRITICAL");

        // ---- Spring: Framework 6.2.11, Security 6.5.9, webflux pinned 6.1.14
        add(r, "spring-core",             "Spring Framework",     "6.2.11",      "CRITICAL");
        add(r, "spring-webflux",          "Spring Framework",     "6.1.14",      "CRITICAL");
        add(r, "spring-security-web",     "Spring Security",      "6.5.9",       "CRITICAL");

        // ---- Front-end libraries bundled inside archives --------------------
        add(r, "jspdf",                   "jsPDF",                "4.2.1",       "WARNING");
        add(r, "moment",                  "Moment.js",            "2.29.4",      "WARNING");

        return List.copyOf(r);
    }

    /**
     * Resolves the override file for an explicit user-supplied path.
     * Blank → default resolution; non-blank → that path if it exists,
     * otherwise default resolution with a warning.
     */
    public static Path resolveOverride(String explicitPath) {
        if (explicitPath == null || explicitPath.isBlank()) return resolveOverrideFile();
        Path p = Path.of(explicitPath.trim());
        if (Files.exists(p)) return p;
        System.err.println("Warning: --library-versions file not found: " + explicitPath
                + " — using built-in defaults.");
        return null;
    }

    /** Built-in table with overrides from the default external file applied. */
    public static List<LibraryVersionRule> load() {
        return load(resolveOverrideFile());
    }

    /**
     * Built-in table with overrides from the given file applied.
     * A {@code null} or non-existent file yields the built-in table unchanged.
     */
    public static List<LibraryVersionRule> load(Path overrideFile) {
        LinkedHashMap<String, LibraryVersionRule> byArtifact = new LinkedHashMap<>();
        for (LibraryVersionRule rule : builtIn()) {
            byArtifact.put(rule.artifact(), rule);
        }
        if (overrideFile == null || !Files.exists(overrideFile)) {
            return List.copyOf(byArtifact.values());
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(overrideFile)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("Warning: could not read " + overrideFile + " — " + e.getMessage());
            return List.copyOf(byArtifact.values());
        }

        for (String key : props.stringPropertyNames()) {
            String k = key.trim();
            String rawVal = props.getProperty(key).trim();
            if (k.startsWith("disable.")) {
                byArtifact.remove(k.substring("disable.".length()).trim().toLowerCase(Locale.ROOT));
                continue;
            }
            String artifact = k.toLowerCase(Locale.ROOT);
            String[] parts = rawVal.split(":", 3);
            String target = parts[0].trim();
            if (target.isEmpty()) continue;

            LibraryVersionRule existing = byArtifact.get(artifact);
            String severity = parts.length > 1 && !parts[1].isBlank()
                    ? parts[1].trim().toUpperCase(Locale.ROOT) : null;
            String library = parts.length > 2 && !parts[2].isBlank()
                    ? parts[2].trim() : null;

            byArtifact.put(artifact, new LibraryVersionRule(
                    artifact,
                    library    != null ? library
                            : existing != null ? existing.library() : displayFromArtifact(artifact),
                    target,
                    severity   != null ? severity
                            : existing != null ? existing.severity() : DEFAULT_SEVERITY));
        }
        return List.copyOf(byArtifact.values());
    }

    /**
     * Resolves the external override file: next to the EffortAnalyzer JAR when
     * it exists there, otherwise the current working directory
     * (same strategy as {@code EffortConfig}).
     */
    static Path resolveOverrideFile() {
        try {
            Path jarDir = Path.of(
                    LibraryVersionRules.class.getProtectionDomain()
                                             .getCodeSource().getLocation().toURI()
            ).getParent();
            if (jarDir != null) {
                Path candidate = jarDir.resolve(OVERRIDE_FILE_NAME);
                if (Files.exists(candidate)) return candidate;
            }
        } catch (Exception ignored) { }
        return Path.of(OVERRIDE_FILE_NAME);
    }

    private static void add(List<LibraryVersionRule> list, String artifact,
                            String library, String target, String severity) {
        list.add(new LibraryVersionRule(artifact, library, target, severity));
    }

    private static String displayFromArtifact(String artifact) {
        String base = artifact.endsWith("-") ? artifact.substring(0, artifact.length() - 1) : artifact;
        String[] words = base.split("[-_.]");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}

