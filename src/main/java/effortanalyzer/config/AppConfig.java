package effortanalyzer.config;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Unified configuration for the EffortAnalyzer application.
 *
 * Configuration is resolved in this priority order (highest wins):
 *   1. Command-line arguments  (--key=value  or  --key value)
 *   2. Custom properties file  (--config=<file>)
 *   3. Working-directory       ./analyzer.properties  (optional, user-managed)
 *   4. Hard-coded defaults     (in resolve() below)
 *
 * ── Supported CLI arguments ──────────────────────────────────────────────────
 *
 *   --module=<name>          Module to run: upgrade | analyze | merge | wl-jboss26 | wl-jboss27
 *   --input=<path>           Input JAR/WAR/EAR or directory
 *   --output=<file>          Output Excel file path
 *   --config=<file>          Path to a custom properties file
 *
 *   # merge module
 *   --ticket-file=<file>     Ticket report Excel file
 *   --component-file=<file>  Component list Excel file
 *
 *   # analyze module
 *   --report-folder=<name>   Resource folder containing JSON reports
 *   --parallel=true|false    Enable parallel processing
 *   --excluded-rules=A,B     Comma-separated rule IDs to skip
 *
 *   # upgrade / wl-jboss modules
 *   --jar-list=<file>        Text file with one JAR path per line
 *   --target=<profile>       WildFly target (wildfly27-java21 | wildfly26-java8 | eap74 | java8)
 *   --ibm-scanner=<path>     Path to binaryAppScanner.jar (auto-detected when absent)
 *
 *   --help / -h              Print help and exit
 *   --version / -v           Print version and exit
 */
public class AppConfig {

    // ── Modules ───────────────────────────────────────────────────────────────
    public static final String MODULE_ANALYZE    = "analyze";
    public static final String MODULE_MERGE      = "merge";
    /** WildFly 26 / JBoss EAP 7.4 – Java 8 – Jakarta EE 8 (javax.*) */
    public static final String MODULE_WL_JBOSS26 = "wl-jboss26";
    /** WildFly 27+ / JBoss EAP 8 – Java 21 – Jakarta EE 10 (jakarta.*) */
    public static final String MODULE_WL_JBOSS27 = "wl-jboss27";
    /** Legacy alias — defaults to WildFly 27 profile; kept for backward compatibility */
    public static final String MODULE_WL_JBOSS   = "wl-jboss";
    /**
     * Combined upgrade compatibility module.
     * Runs both the Java 8 → 21 JVM scan and the Spring/Guava/Guice/CGLib library scan
     * in one pass, producing a unified 5-sheet Excel report.
     * Exclusions are controlled via {@code upgrade-excluded-rules.txt}.
     */
    public static final String MODULE_UPGRADE    = "upgrade";

    private static final String VERSION = "2.0.0";

    // ── Resolved values ───────────────────────────────────────────────────────
    private String  module;

    // common
    private String  inputPath;
    private String  outputFile;

    // merge
    private String  ticketFile;
    private String  componentFile;

    // analyze
    private String  reportFolder;
    private boolean parallelEnabled;
    private int     maxCellLength;
    private String  excludedRules;

    // wl-jboss
    private String  jarListFile;
    private String  wljbossTarget;

    // upgrade — IBM scanner
    private String  ibmScannerJar;

    // meta
    private boolean helpRequested;
    private boolean versionRequested;

    // ── Internal: raw properties merged from all sources ─────────────────────
    private final Properties resolved = new Properties();

    // ──────────────────────────────────────────────────────────────────────────
    // Factory
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parses the given command-line args and merges them with the properties
     * file, then resolves every setting.
     */
    public static AppConfig parse(String[] args) {
        AppConfig cfg = new AppConfig();
        cfg.load(args);
        return cfg;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Loading & merging
    // ──────────────────────────────────────────────────────────────────────────

    private void load(String[] args) {

        // Step 1 – scan args for --config override and for help/version flags
        String customConfigPath = scanForConfigPath(args);

        // Step 2 – load properties (working-dir < custom < CLI)
        loadWorkingDirProperties();
        if (customConfigPath != null) {
            loadPropertiesFile(Path.of(customConfigPath));
        }

        // Step 3 – parse all CLI args (highest priority)
        parseArgs(args);

        // Step 4 – resolve final values from the merged property bag
        resolve();
    }

    /** Quick first pass over args to find --config and flag help/version. */
    private String scanForConfigPath(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help") || a.equals("-h"))    { helpRequested    = true; }
            if (a.equals("--version") || a.equals("-v")) { versionRequested = true; }
            if (a.startsWith("--config=")) return a.substring("--config=".length());
            if (a.equals("--config") && i + 1 < args.length) return args[i + 1];
        }
        return null;
    }

    private void loadWorkingDirProperties() {
        loadPropertiesFile(Path.of("analyzer.properties"));
    }

    private void loadPropertiesFile(Path path) {
        if (!Files.exists(path)) return;
        try (InputStream is = Files.newInputStream(path)) {
            Properties p = new Properties();
            p.load(is);
            // overwrite classpath values
            p.forEach((k, v) -> resolved.put(k, v));
        } catch (IOException e) {
            System.err.println("Warning: could not read " + path + " — " + e.getMessage());
        }
    }

    /**
     * Parses CLI args in the form:
     *   --key=value
     *   --key value
     * and writes them into {@code resolved}, overriding any properties file value.
     *
     * Also handles positional arguments for backwards compatibility:
     *   java -jar ea.jar wl-jboss myapp.jar report.xlsx
     */
    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];

            if (a.startsWith("--")) {
                // --key=value
                if (a.contains("=")) {
                    String[] kv = a.substring(2).split("=", 2);
                    putArg(kv[0], kv[1]);
                }
                // --key value
                else if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    putArg(a.substring(2), args[++i]);
                }
            }
            // Positional: first non-flag arg is the module name
            else if (!a.startsWith("-") && !resolved.containsKey("module")) {
                resolved.put("module", a);
            }
        }
    }

    /** Maps CLI key names to property file key names and stores them. */
    private void putArg(String key, String value) {
        switch (key) {
            case "module"          -> resolved.put("module",                    value);
            case "input"           -> resolved.put("input.path",                value);
            case "output"          -> resolved.put("output.file",               value);
            case "ticket-file"     -> resolved.put("merge.ticket.file",         value);
            case "component-file"  -> resolved.put("merge.component.file",      value);
            case "report-folder"   -> resolved.put("analyzer.resource.folder",  value);
            case "parallel"        -> resolved.put("analyzer.parallel.enabled", value);
            case "jar-list"        -> resolved.put("wl.jar.list.file",          value);
            case "target"          -> resolved.put("wljboss.target",            value);
            case "excluded-rules"  -> resolved.put("analyzer.excluded.rules",   value);
            case "ibm-scanner"     -> resolved.put("analyzer.ibm.scanner.jar",  value);
            case "config"          -> { /* already handled in first pass */ }
            default                -> System.err.println("Warning: unknown argument --" + key);
        }
    }

    /** Reads final values out of the merged property bag. */
    private void resolve() {
        module         = get("module",                    "");
        inputPath      = get("input.path",                "");
        outputFile     = get("output.file",               "");
        ticketFile     = get("merge.ticket.file",         "TicketReport.xlsx");
        componentFile  = get("merge.component.file",      "ComponentList.xlsx");
        reportFolder   = get("analyzer.resource.folder",  "reports");
        parallelEnabled= Boolean.parseBoolean(get("analyzer.parallel.enabled", "true"));
        maxCellLength  = Integer.parseInt(    get("analyzer.excel.maxCellLength", "32000"));
        excludedRules  = get("analyzer.excluded.rules",   "");
        jarListFile    = get("wl.jar.list.file",          "");
        wljbossTarget  = get("wljboss.target",            "wildfly27-java21");
        ibmScannerJar  = get("analyzer.ibm.scanner.jar",  "");

        // Apply module-specific output defaults when no --output was provided
        if (outputFile.isBlank()) {
            outputFile = switch (module) {
                case MODULE_ANALYZE    -> get("analyzer.output.file", "AnalyzerOutput.xlsx");
                case MODULE_MERGE      -> "MergedOutput.xlsx";
                case MODULE_WL_JBOSS26 -> "WlToJBoss-WildFly26-Report.xlsx";
                case MODULE_WL_JBOSS27 -> "WlToJBoss-WildFly27-Report.xlsx";
                case MODULE_WL_JBOSS   -> "WlToJBossMigrationReport.xlsx";
                case MODULE_UPGRADE    -> "Upgrade-Compatibility-Report.xlsx";
                default                -> "output.xlsx";
            };
        }
    }

    private String get(String key, String defaultValue) {
        return resolved.getProperty(key, defaultValue).trim();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Validation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Validates the configuration for the resolved module.
     * Returns a non-empty error message if invalid, or an empty string if OK.
     */
    public String validate() {
        if (module.isBlank()) {
            return "No module specified. Use --module=<name> or set 'module' in analyzer.properties.\n"
                 + "Available modules: upgrade | analyze | merge | wl-jboss26 | wl-jboss27";
        }

        return switch (module) {
            case MODULE_ANALYZE -> {
                // --input is optional: when given it must be an existing directory
                if (!inputPath.isBlank() && !Files.isDirectory(Path.of(inputPath))) {
                    yield "analyze: --input path is not a directory: " + inputPath;
                }
                yield "";
            }

            case MODULE_MERGE -> {
                List<String> missing = new ArrayList<>();
                if (!Files.exists(Path.of(ticketFile)))    missing.add("--ticket-file=" + ticketFile + " (not found)");
                if (!Files.exists(Path.of(componentFile))) missing.add("--component-file=" + componentFile + " (not found)");
                yield missing.isEmpty() ? "" : "merge: " + String.join(", ", missing);
            }

            case MODULE_WL_JBOSS, MODULE_WL_JBOSS26, MODULE_WL_JBOSS27, MODULE_UPGRADE -> {
                if (inputPath.isBlank() && jarListFile.isBlank()) {
                    yield module + ": --input=<jar-or-directory> is required "
                        + "(or set input.path / wl.jar.list.file in analyzer.properties)";
                }
                if (!inputPath.isBlank() && !Files.exists(Path.of(inputPath))) {
                    yield module + ": input path not found: " + inputPath;
                }
                if (!jarListFile.isBlank() && !Files.exists(Path.of(jarListFile))) {
                    yield module + ": jar list file not found: " + jarListFile;
                }
                yield "";
            }

            default -> "Unknown module: '" + module
                     + "'. Available: upgrade | analyze | merge | wl-jboss26 | wl-jboss27";
        };
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Help / version output
    // ──────────────────────────────────────────────────────────────────────────

    public static void printHelp() {
        System.out.println("Usage: java -jar effortanalyzer.jar [--module=<name>] [options]");
        System.out.println("       java -jar effortanalyzer.jar --help");
        System.out.println();
        System.out.println("Modules:");
        System.out.println("  upgrade     Java 21 JVM + Spring/Guava/Guice/CGLib library upgrade scan");
        System.out.println("                Exclusions: edit upgrade-excluded-rules.txt");
        System.out.println("  analyze     Analyze JSON migration reports (IBM TA format)");
        System.out.println("  merge       Merge ticket report with component list");
        System.out.println("  wl-jboss26  WebLogic → WildFly 26 / JBoss EAP 7.4  (Java 8,  javax.*)");
        System.out.println("  wl-jboss27  WebLogic → WildFly 27+ / JBoss EAP 8   (Java 21, jakarta.*)");
        System.out.println();
        System.out.println("Common options:");
        System.out.println("  --module=<name>          Module to run (required)");
        System.out.println("  --output=<file>          Output Excel file path");
        System.out.println("  --config=<file>          Custom properties file (default: analyzer.properties)");
        System.out.println("  --help / -h              Show this help");
        System.out.println("  --version / -v           Show version");
        System.out.println();
        System.out.println("Module: analyze");
        System.out.println("  Reads IBM Transformation Advisor JSON reports and consolidates them into Excel.");
        System.out.println("  --input=<dir>            Directory containing *.json IBM TA report files");
        System.out.println("                           (optional – omit to read from the embedded 'reports/' folder)");
        System.out.println("  --report-folder=<name>   Classpath sub-folder fallback (default: reports)");
        System.out.println("  --parallel=true|false    Enable parallel file processing (default: true)");
        System.out.println("  --excluded-rules=A,B,C   Comma-separated rule IDs to skip");
        System.out.println("  --output=<file>          Output (default: AnalyzerOutput.xlsx)");
        System.out.println();
        System.out.println("Module: merge");
        System.out.println("  --ticket-file=<file>     Ticket report Excel  (default: TicketReport.xlsx)");
        System.out.println("  --component-file=<file>  Component list Excel (default: ComponentList.xlsx)");
        System.out.println("  --output=<file>          Output (default: MergedOutput.xlsx)");
        System.out.println();
        System.out.println("Module: upgrade");
        System.out.println("  Two-phase scan producing a single Excel report:");
        System.out.println("  Phase 1 — Java 21 compatibility via IBM Migration Toolkit for Application Binaries:");
        System.out.println("    Runs: java -jar binaryAppScanner.jar <input> --analyzeJavaSE");
        System.out.println("          --sourceJava=oracle8 --targetJava=java21 --format=json");
        System.out.println("    Download binaryAppScanner.jar from:");
        System.out.println("      https://www.ibm.com/support/pages/migration-toolkit-application-binaries");
        System.out.println("    Place it next to EffortAnalyzer-2.0.0-shaded.jar (auto-detected).");
        System.out.println("  Phase 2 — Library upgrade: Spring 5.3.39 / Guava 31.1 / Guice 5.1 / CGLib→ByteBuddy");
        System.out.println("  Exclusions: edit upgrade-excluded-rules.txt (IBM TA rule IDs, one per line)");
        System.out.println("  --input=<path>           JAR/WAR/EAR or directory of archives (required)");
        System.out.println("  --jar-list=<file>        Text file: one JAR path per line (alternative to --input)");
        System.out.println("  --ibm-scanner=<path>     Path to binaryAppScanner.jar (default: auto-detect)");
        System.out.println("  --output=<file>          Output (default: Upgrade-Compatibility-Report.xlsx)");
        System.out.println();
        System.out.println("Module: wl-jboss26");
        System.out.println("  Target: WildFly 26 / JBoss EAP 7.4 – Java 8 – Jakarta EE 8 (javax.*)");
        System.out.println("  --input=<path>           JAR/WAR/EAR or directory  (required unless --jar-list given)");
        System.out.println("  --jar-list=<file>        Text file: one JAR path per line");
        System.out.println("  --output=<file>          Output (default: WlToJBoss-WildFly26-Report.xlsx)");
        System.out.println();
        System.out.println("Module: wl-jboss27");
        System.out.println("  Target: WildFly 27+ / JBoss EAP 8 – Java 21 – Jakarta EE 10 (jakarta.*)");
        System.out.println("  --input=<path>           JAR/WAR/EAR or directory  (required unless --jar-list given)");
        System.out.println("  --jar-list=<file>        Text file: one JAR path per line");
        System.out.println("  --output=<file>          Output (default: WlToJBoss-WildFly27-Report.xlsx)");
        System.out.println();
        System.out.println("Configuration file (optional):");
        System.out.println("  Place an analyzer.properties in the working directory to persist settings.");
        System.out.println("  Any CLI flag has an equivalent property key name (e.g. --module → module=).");
        System.out.println("  CLI arguments always override the file. Use --config=<file> for a custom path.");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar effortanalyzer.jar --module=upgrade --input=C:\\apps\\lib\\");
        System.out.println("  java -jar effortanalyzer.jar --module=upgrade --input=myapp.jar --output=report.xlsx");
        System.out.println("  java -jar effortanalyzer.jar --module=upgrade --jar-list=jars.txt");
        System.out.println("  java -jar effortanalyzer.jar --module=analyze --input=C:\\reports\\json\\");
        System.out.println("  java -jar effortanalyzer.jar --module=analyze --input=C:\\reports\\json\\ --output=out.xlsx");
        System.out.println("  java -jar effortanalyzer.jar --module=analyze --parallel=false --output=out.xlsx");
        System.out.println("  java -jar effortanalyzer.jar --module=wl-jboss26 --input=C:\\apps\\lib\\");
        System.out.println("  java -jar effortanalyzer.jar --module=wl-jboss27 --jar-list=jars.txt --output=report.xlsx");
        System.out.println("  java -jar effortanalyzer.jar --module=merge --ticket-file=t.xlsx --component-file=c.xlsx");
        System.out.println("  java -jar effortanalyzer.jar --config=prod.properties");
        System.out.println();
    }

    public static void printVersion() {
        System.out.println("EffortAnalyzer " + VERSION + "  (Java " + System.getProperty("java.version") + ")");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Accessors
    // ──────────────────────────────────────────────────────────────────────────

    public String  getModule()          { return module; }
    public String  getInputPath()       { return inputPath; }
    public String  getOutputFile()      { return outputFile; }
    public String  getTicketFile()      { return ticketFile; }
    public String  getComponentFile()   { return componentFile; }
    public String  getReportFolder()    { return reportFolder; }
    public boolean isParallelEnabled()  { return parallelEnabled; }
    public int     getMaxCellLength()   { return maxCellLength; }
    public String  getExcludedRules()   { return excludedRules; }
    public String  getJarListFile()     { return jarListFile; }
    public String  getWlJBossTarget()   { return wljbossTarget; }
    public String  getIbmScannerJar()   { return ibmScannerJar; }
    public boolean isHelpRequested()    { return helpRequested; }
    public boolean isVersionRequested() { return versionRequested; }

    @Override
    public String toString() {
        return String.format(
            "AppConfig{module='%s', input='%s', output='%s'}",
            module, inputPath, outputFile
        );
    }
}
