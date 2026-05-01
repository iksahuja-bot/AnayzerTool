package effortanalyzer.wljboss;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.Pattern;
import java.util.stream.*;

/**
 * Module 4 – WebLogic to JBoss/WildFly Migration Analyzer
 *
 * Standalone entry point.  Run directly with:
 *   java -cp target/EffortAnalyzer-2.0.0.jar
 *        effortanalyzer.wljboss.WlJBossAnalyzer
 *        <jar-or-directory> [output.xlsx]
 *
 * Or via the batch / PowerShell wrappers:
 *   run-wl-jboss-analyzer.bat  myapp.jar
 *   .\Analyze-WlToJBoss.ps1  -JarPath myapp.jar
 *
 * ── How scanning works ──────────────────────────────────────────────────────
 *   .java  files  – line-by-line text scan (imports, class refs, String literals)
 *   .class files  – byte scan of constant-pool strings using ISO-8859-1 trick
 *                   (class names in bytecode use '/' separator)
 *   .xml   files  – line-by-line text scan (descriptor names, namespaces)
 *   MANIFEST.MF   – scanned as text
 *   JAR name itself – checked for weblogic*.jar indicator
 *
 * ── Finding deduplication ───────────────────────────────────────────────────
 *   Grouped by (jarName + rule.apiPattern).
 *   Each group records the set of files where the hit occurred and occurrence count
 *   so the report stays readable even for large codebases.
 */
public class WlJBossAnalyzer {

    // ──────────────────────────────────────────────────────────────────────────
    // Data model
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * One aggregated finding per (JAR × rule).
     */
    public static class Finding {
        public final String   jarName;
        public final WlJBossRules.Rule rule;
        public final Set<String> affectedFiles = new LinkedHashSet<>();
        public int occurrenceCount = 0;
        public String exampleContext = "";   // first matching line / note

        /**
         * True when this finding represents WebLogic appc-generated stub/skeleton classes,
         * NOT developer-written code.  Reported as WL_GENERATED_STUBS category.
         * All other findings are guaranteed to be from developer-written code because
         * generated stubs are excluded from normal rule scanning.
         */
        public boolean isGeneratedStub = false;

        Finding(String jarName, WlJBossRules.Rule rule) {
            this.jarName = jarName;
            this.rule    = rule;
        }

        public void record(String filePath, int line, String context) {
            affectedFiles.add(filePath);
            occurrenceCount++;
            if (exampleContext.isEmpty() && !context.isBlank()) {
                exampleContext = (line > 0 ? "Line " + line + ": " : "") + context.strip();
                if (exampleContext.length() > 200) {
                    exampleContext = exampleContext.substring(0, 197) + "...";
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // WebLogic generated stub detection
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * WebLogic's EJB compiler (appc) generates several classes per EJB bean:
     *   BeanName_[random]_EOImpl      – entity-object implementation
     *   BeanName_[random]_HomeImpl    – home-interface implementation
     *   BeanName_[random]_WLSkel      – server-side skeleton
     *   BeanName_[random]_WLStub / BeanName_[random]_[version]_WLStub – remote stub
     *   BeanName_[random]_Impl        – concrete EJB implementation wrapper
     *   BeanName_[random]_Intf        – interface wrapper
     *
     * These are packaging artifacts; the developer never wrote them.
     * They reference weblogic.ejb.* and weblogic.security.* heavily, which
     * inflates rule-match counts massively (238 false positives observed in one JAR).
     *
     * Pattern: class simple-name ends with  _<5-8 alphanum>_(EOImpl|HomeImpl|WLStub|WLSkel|Impl|Intf)
     */
    private static final Pattern WL_STUB_PATTERN =
            Pattern.compile(".*_[a-z0-9]{4,8}_(EOImpl|HomeImpl|WLStub|WLSkel|Impl|Intf)(\\$.*)?$",
                            Pattern.CASE_INSENSITIVE);

    private boolean isWlGeneratedStub(String classEntryName) {
        // Strip .class suffix and work on the class name portion only
        String noExt = classEntryName.endsWith(".class")
                ? classEntryName.substring(0, classEntryName.length() - 6)
                : classEntryName;
        // Use just the simple class name (after last '/')
        int slash = noExt.lastIndexOf('/');
        String simpleName = slash >= 0 ? noExt.substring(slash + 1) : noExt;
        return WL_STUB_PATTERN.matcher(simpleName).matches();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────────────

    private final WlJBossRules rules;
    private final WlJBossRules.TargetProfile targetProfile;

    // key = jarName + "||" + rule.apiPattern
    private final Map<String, Finding> findings = new LinkedHashMap<>();

    // Per-JAR counter for WebLogic-generated stub classes (excluded from normal rules)
    private final Map<String, Integer> wlStubCountPerJar = new LinkedHashMap<>();

    // Per-JAR counters for the inventory sheet
    public static class JarStats {
        public String jarName = "";
        public int classes, sourceFiles, xmlFiles, manifestEntries;
        public int wlGeneratedStubs;   // excluded from rule scans
        public int critical, high, medium, info;
        public boolean isWebLogicJar;
    }

    private final Map<String, JarStats> jarStats = new LinkedHashMap<>();

    // ──────────────────────────────────────────────────────────────────────────
    // Entry point
    // ──────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String targetPath  = args[0];
        String outputFile  = args.length > 1 ? args[1] : "WlToJBossMigrationReport.xlsx";

        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  WebLogic → JBoss/WildFly Migration Analyzer");
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  Input  : " + targetPath);
        System.out.println("  Output : " + outputFile);
        System.out.println("═══════════════════════════════════════════════════");

        long start = System.currentTimeMillis();

        try {
            WlJBossAnalyzer analyzer = new WlJBossAnalyzer();
            analyzer.run(targetPath, outputFile);

            long elapsed = System.currentTimeMillis() - start;
            System.out.printf("%nCompleted in %.1f s%n", elapsed / 1000.0);

        } catch (Exception e) {
            System.err.println("Analysis failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Default constructor — uses WildFly 27+ / Java 21 profile. */
    public WlJBossAnalyzer() {
        this(WlJBossRules.TargetProfile.WILDFLY27_JAVA21);
    }

    /** Create an analyzer for the given migration target profile string. */
    public WlJBossAnalyzer(String targetProfileValue) {
        this(WlJBossRules.TargetProfile.from(targetProfileValue));
    }

    public WlJBossAnalyzer(WlJBossRules.TargetProfile profile) {
        this.targetProfile = profile;
        this.rules         = WlJBossRules.load(profile);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Orchestration
    // ──────────────────────────────────────────────────────────────────────────

    public void run(String targetPath, String outputFile) throws IOException {
        List<Path> jars = collectJars(Path.of(targetPath));

        if (jars.isEmpty()) {
            System.out.println("No JAR files found at: " + targetPath);
            System.exit(1);
        }

        System.out.println("  Migration target: " + rules.getTarget().displayLabel());
        System.out.println("  Rules loaded    : " + rules.getRules().size());
        System.out.println();
        System.out.println("Found " + jars.size() + " JAR(s) to scan...\n");

        for (Path jar : jars) {
            scanJar(jar);
        }

        WlJBossReportWriter.write(
                outputFile,
                new ArrayList<>(findings.values()),
                new ArrayList<>(jarStats.values()),
                targetProfile
        );

        printConsoleSummary();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // JAR collection
    // ──────────────────────────────────────────────────────────────────────────

    private List<Path> collectJars(Path root) throws IOException {
        if (Files.isRegularFile(root) && isJar(root)) {
            return List.of(root);
        }
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                return stream.filter(Files::isRegularFile)
                             .filter(WlJBossAnalyzer::isJar)
                             .sorted()
                             .toList();
            }
        }
        return Collections.emptyList();
    }

    private static boolean isJar(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // JAR scanning
    // ──────────────────────────────────────────────────────────────────────────

    private void scanJar(Path jarPath) {
        String jarName = jarPath.getFileName().toString();
        System.out.println("  Scanning: " + jarName);

        JarStats stats = new JarStats();
        stats.jarName = jarName;
        stats.isWebLogicJar = jarName.toLowerCase().startsWith("weblogic");
        jarStats.put(jarName, stats);

        // Flag the JAR itself if it IS a WebLogic JAR
        if (stats.isWebLogicJar) {
            WlJBossRules.Rule jarRule = new WlJBossRules.Rule(
                    "WEBLOGIC_API", jarName, "CRITICAL",
                    "This JAR is a WebLogic proprietary library and must not be deployed to JBoss",
                    "Remove from classpath. Replace with the Jakarta EE API JARs or JBoss-provided equivalents.",
                    WlJBossRules.ScanMode.SOURCE
            );
            finding(jarName, jarRule).record(jarName, -1, "JAR file itself is WebLogic-specific");
            bumpStats(stats, "CRITICAL");
        }

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName();

                if (name.endsWith(".class")) {
                    stats.classes++;
                    if (isWlGeneratedStub(name)) {
                        // Count but do NOT run rules — these are appc-generated artifacts
                        stats.wlGeneratedStubs++;
                        wlStubCountPerJar.merge(jarName, 1, Integer::sum);
                    } else {
                        scanClassBytes(jar, entry, jarName, stats);
                    }
                } else if (name.endsWith(".java")) {
                    stats.sourceFiles++;
                    scanTextEntry(jar, entry, jarName, stats, false);
                } else if (name.endsWith(".xml") || name.endsWith(".properties")) {
                    stats.xmlFiles++;
                    scanTextEntry(jar, entry, jarName, stats, true);
                } else if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    stats.manifestEntries++;
                    scanTextEntry(jar, entry, jarName, stats, false);
                }
            }
        } catch (IOException e) {
            System.err.println("    Warning: could not open " + jarName + ": " + e.getMessage());
        }

        // Emit a single synthetic finding that summarises all WL-generated stubs in this JAR
        int stubCount = wlStubCountPerJar.getOrDefault(jarName, 0);
        if (stubCount > 0) {
            WlJBossRules.Rule stubRule = new WlJBossRules.Rule(
                    "WL_GENERATED_STUBS",
                    "WL_GENERATED_STUBS",
                    "HIGH",
                    "WebLogic-generated EJB stubs/skeletons found in JAR ("
                            + stubCount + " classes: *_WLStub, *_WLSkel, *_EOImpl, *_HomeImpl, *_Impl, *_Intf)",
                    "These classes are auto-generated by WebLogic's appc EJB compiler and are NOT developer code. "
                    + "They must be REMOVED from the JAR before deploying to WildFly — WildFly generates its own "
                    + "equivalent proxies from standard EJB annotations at deployment time. "
                    + "Action: strip the JAR (e.g. jar -d or Maven Shade exclusion) of all classes matching "
                    + "the pattern *_[alphanum]_(WLStub|WLSkel|EOImpl|HomeImpl). "
                    + "Note: if the beans still use EJB 2.x patterns (EntityBean, EJBHome), those must also be "
                    + "migrated to EJB 3.x / JPA @Entity before WildFly can generate proxies.",
                    WlJBossRules.ScanMode.BYTECODE
            );
            Finding f = finding(jarName, stubRule);
            f.isGeneratedStub = true;
            f.record(jarName, -1,
                    stubCount + " WebLogic appc-generated stub/skeleton class files detected "
                    + "(excluded from individual rule counts to avoid false positives)");
            bumpStats(stats, "HIGH");
            System.out.println("    [INFO] Excluded " + stubCount
                    + " WebLogic-generated stub class(es) from rule scan.");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // .class file scanning (bytecode constant-pool trick)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Class files store string constants as raw UTF-8 in the constant pool.
     * Reading as ISO-8859-1 preserves every byte, so simple string.contains()
     * works for ASCII patterns.  Class names use '/' as separator in bytecode.
     */
    private void scanClassBytes(JarFile jar, JarEntry entry, String jarName, JarStats stats) {
        try (InputStream is = jar.getInputStream(entry)) {
            byte[] bytes = is.readAllBytes();
            String content = new String(bytes, StandardCharsets.ISO_8859_1);

            for (WlJBossRules.Rule rule : rules.getRules()) {
                if (rule.scanMode() == WlJBossRules.ScanMode.SOURCE) continue;

                // Bytecode uses '/' separator
                String slashPattern = rule.bytecodePattern();
                // Also check plain text form (String literals, etc.)
                String dotPattern   = rule.apiPattern();

                if (content.contains(slashPattern) || content.contains(dotPattern)) {
                    Finding f = finding(jarName, rule);
                    // Convert entry path to readable class name for the example context
                    String className = entry.getName()
                            .replace('/', '.')
                            .replaceAll("\\.class$", "");
                    f.record(entry.getName(), -1, "Developer class: " + className);
                    bumpStats(stats, rule.severity());
                }
            }
        } catch (IOException e) {
            // silently skip unreadable entries
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Text file scanning (.java, .xml, MANIFEST)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * @param xmlMode  true → also check for descriptor file-name patterns
     */
    private void scanTextEntry(JarFile jar, JarEntry entry, String jarName,
                               JarStats stats, boolean xmlMode) {
        String entryName = entry.getName();

        // Check entry name itself for deployment descriptor patterns (xml mode)
        if (xmlMode) {
            checkTextLine(entryName, entryName, -1, jarName, stats,
                          WlJBossRules.ScanMode.SOURCE, true);
        }

        try (InputStream is  = jar.getInputStream(entry);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                checkTextLine(line, entryName, lineNo, jarName, stats,
                              WlJBossRules.ScanMode.SOURCE, false);
            }
        } catch (IOException e) {
            // silently skip
        }
    }

    /** Tests one line of text against every applicable rule. */
    private void checkTextLine(String line, String filePath, int lineNo,
                               String jarName, JarStats stats,
                               WlJBossRules.ScanMode modeFilter,
                               boolean isFileName) {
        for (WlJBossRules.Rule rule : rules.getRules()) {
            if (rule.scanMode() == WlJBossRules.ScanMode.BYTECODE) continue;

            if (line.contains(rule.apiPattern())) {
                Finding f = finding(jarName, rule);
                String ctx = isFileName ? "Descriptor file: " + line : line.strip();
                f.record(filePath, lineNo, ctx);
                bumpStats(stats, rule.severity());
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private Finding finding(String jarName, WlJBossRules.Rule rule) {
        String key = jarName + "||" + rule.apiPattern();
        return findings.computeIfAbsent(key, k -> new Finding(jarName, rule));
    }

    private void bumpStats(JarStats stats, String severity) {
        switch (severity) {
            case "CRITICAL" -> stats.critical++;
            case "HIGH"     -> stats.high++;
            case "MEDIUM"   -> stats.medium++;
            default         -> stats.info++;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Console summary
    // ──────────────────────────────────────────────────────────────────────────

    private void printConsoleSummary() {
        Map<String, Long> bySev = findings.values().stream()
                .collect(Collectors.groupingBy(f -> f.rule.severity(), Collectors.counting()));
        long critical = bySev.getOrDefault("CRITICAL", 0L);
        long high     = bySev.getOrDefault("HIGH",     0L);
        long medium   = bySev.getOrDefault("MEDIUM",   0L);
        long info     = bySev.getOrDefault("INFO",     0L);
        int  totalStubs = wlStubCountPerJar.values().stream().mapToInt(Integer::intValue).sum();

        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  SUMMARY");
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  JARs scanned      : " + jarStats.size());
        System.out.println("  Unique issues     : " + findings.size());
        System.out.println("  ─────────────────────────────────────────────");
        System.out.println("  CRITICAL          : " + critical);
        System.out.println("  HIGH              : " + high);
        System.out.println("  MEDIUM            : " + medium);
        System.out.println("  INFO              : " + info);
        if (totalStubs > 0) {
            System.out.println("  ─────────────────────────────────────────────");
            System.out.println("  WL generated stubs: " + totalStubs
                    + " class(es) excluded from rule scan (reported as WL_GENERATED_STUBS)");
        }
        System.out.println("═══════════════════════════════════════════════════");
    }

    private static void printUsage() {
        System.out.println("Usage: WlJBossAnalyzer <jar-path> [output-file]");
        System.out.println();
        System.out.println("  jar-path    Single JAR/WAR/EAR or directory containing them");
        System.out.println("  output-file Excel report (default: WlToJBossMigrationReport.xlsx)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  WlJBossAnalyzer myapp.war");
        System.out.println("  WlJBossAnalyzer C:\\apps\\lib\\ migration-report.xlsx");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public accessors (used by WlJBossReportWriter)
    // ──────────────────────────────────────────────────────────────────────────

    public Map<String, Finding>      getFindings()      { return findings;      }
    public Map<String, JarStats>     getJarStats()      { return jarStats;      }
    public WlJBossRules.TargetProfile getTargetProfile() { return targetProfile; }
}
