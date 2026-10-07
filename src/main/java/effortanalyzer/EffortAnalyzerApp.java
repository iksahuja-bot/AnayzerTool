package effortanalyzer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import effortanalyzer.analyzer.ReportAnalyzer;
import effortanalyzer.config.AnalyzerConfig;
import effortanalyzer.config.AppConfig;
import effortanalyzer.merger.TicketComponentMerger;
import effortanalyzer.source.CheckoutCredentials;
import effortanalyzer.source.GeneratedArtifactScanner;
import effortanalyzer.source.SourceInventoryAnalyzer;
import effortanalyzer.source.SourceScanProfile;
import effortanalyzer.source.SourceScanResult;
import effortanalyzer.upgrade.UpgradeAnalyzer;
import effortanalyzer.wl14.Wl14Analyzer;
import effortanalyzer.wl15.Wl15Analyzer;
import effortanalyzer.wljboss.WlJBossAnalyzer;
import effortanalyzer.wljboss.WlJBossRules;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * EffortAnalyzer – unified entry point.
 *
 * Configuration is resolved from (highest priority first):
 *   1. Command-line arguments  --key=value
 *   2. ./analyzer.properties   (working directory)
 *   3. classpath analyzer.properties  (bundled in JAR)
 *
 * Run with --help for full option reference.
 */
public class EffortAnalyzerApp {

    private static final Logger logger = LogManager.getLogger(EffortAnalyzerApp.class);

    public static void main(String[] args) {
        printBanner();

        AppConfig cfg = AppConfig.parse(args);

        if (cfg.isHelpRequested()) {
            AppConfig.printHelp();
            return;
        }
        if (cfg.isVersionRequested()) {
            AppConfig.printVersion();
            return;
        }

        String error = cfg.validate();
        if (!error.isEmpty()) {
            System.err.println("Configuration error: " + error);
            System.err.println();
            System.err.println("Run with --help to see all available options.");
            System.err.println("Or set the required values in analyzer.properties.");
            System.exit(1);
        }

        logger.info("Running module: {}", cfg.getModule());
        logger.info("Output file:    {}", cfg.getOutputFile());

        try {
            switch (cfg.getModule()) {
                case AppConfig.MODULE_UPGRADE    -> runUpgrade(cfg);
                case AppConfig.MODULE_WL15       -> runWl15(cfg);
                case AppConfig.MODULE_WL14       -> runWl14(cfg);
                case AppConfig.MODULE_ANALYZE    -> runAnalyze(cfg);
                case AppConfig.MODULE_MERGE      -> runMerge(cfg);
                case AppConfig.MODULE_SOURCE_INVENTORY -> runSourceInventory(cfg);
                case AppConfig.MODULE_WL_JBOSS26 -> runWlJBoss(cfg, WlJBossRules.TargetProfile.WILDFLY26_JAVA8);
                case AppConfig.MODULE_WL_JBOSS27 -> runWlJBoss(cfg, WlJBossRules.TargetProfile.WILDFLY27_JAVA21);
                case AppConfig.MODULE_WL_JBOSS   -> runWlJBoss(cfg, WlJBossRules.TargetProfile.from(cfg.getWlJBossTarget()));
                default -> {
                    System.err.println("Unknown module: '" + cfg.getModule() + "'");
                    System.err.println("Run with --help to see available modules.");
                    System.exit(1);
                }
            }
            printOutputLocation(cfg.getOutputFile());
        } catch (Exception e) {
            logger.error("Module '{}' failed: {}", cfg.getModule(), e.getMessage(), e);
            System.exit(1);
        }
    }

    // ── Module: wl15 ──────────────────────────────────────────────────────────

    private static void runWl15(AppConfig cfg) throws Exception {
        if (hasSourceInventory(cfg) && !hasCompiledInput(cfg)) {
            runSourceInventory(cfg);
            return;
        }

        if (isBothMode(cfg)) {
            try (InventoryScopedScan scoped = prepareInventoryScopedScan(cfg)) {
                Wl15Analyzer analyzer = new Wl15Analyzer(cfg.getLibraryVersionsFile());
                analyzer.analyze(scoped.binaryInputPath());
                analyzer.generateReport(cfg.getOutputFile());
                appendSourceInventory(cfg, scoped.sourceResults());
            }
            logger.info("WL15 inventory-scoped combined analysis complete → {}", cfg.getOutputFile());
            return;
        }

        String inputPath = cfg.getJarListFile().isBlank()
                ? cfg.getInputPath()
                : expandJarList(cfg.getJarListFile());

        Wl15Analyzer analyzer = new Wl15Analyzer(cfg.getLibraryVersionsFile());
        analyzer.analyze(inputPath);
        analyzer.generateReport(cfg.getOutputFile());
        if (hasSourceInventory(cfg)) appendSourceInventory(cfg);
        logger.info("WL15 library migration analysis complete → {}", cfg.getOutputFile());
    }

    // ── Module: wl14 ──────────────────────────────────────────────────────────

    private static void runWl14(AppConfig cfg) throws Exception {
        if (hasSourceInventory(cfg) && !hasCompiledInput(cfg)) {
            runSourceInventory(cfg);
            return;
        }

        if (isBothMode(cfg)) {
            try (InventoryScopedScan scoped = prepareInventoryScopedScan(cfg)) {
                String ibmScanner = resolveIbmScanner(cfg.getIbmScannerJar());
                Wl14Analyzer analyzer = new Wl14Analyzer(cfg.getLibraryVersionsFile(), ibmScanner);
                analyzer.analyze(scoped.binaryInputPath());
                analyzer.generateReport(cfg.getOutputFile());
                appendSourceInventory(cfg, scoped.sourceResults());
            }
            logger.info("WL14 inventory-scoped combined analysis complete → {}", cfg.getOutputFile());
            return;
        }

        String inputPath = cfg.getJarListFile().isBlank()
                ? cfg.getInputPath()
                : expandJarList(cfg.getJarListFile());

        String ibmScanner = resolveIbmScanner(cfg.getIbmScannerJar());

        Wl14Analyzer analyzer = new Wl14Analyzer(cfg.getLibraryVersionsFile(), ibmScanner);
        analyzer.analyze(inputPath);
        analyzer.generateReport(cfg.getOutputFile());
        if (hasSourceInventory(cfg)) appendSourceInventory(cfg);
        logger.info("WL14 library migration analysis complete → {}", cfg.getOutputFile());
    }

    // ── Module: analyze ───────────────────────────────────────────────────────

    private static void runAnalyze(AppConfig cfg) throws Exception {
        Properties overrides = new Properties();
        // --input points to an external directory of JSON reports (preferred, consistent with other modules)
        if (!cfg.getInputPath().isBlank()) {
            overrides.setProperty("input.path", cfg.getInputPath());
        }
        overrides.setProperty("analyzer.resource.folder",    cfg.getReportFolder());
        overrides.setProperty("analyzer.output.file",        cfg.getOutputFile());
        overrides.setProperty("analyzer.parallel.enabled",   String.valueOf(cfg.isParallelEnabled()));
        overrides.setProperty("analyzer.excel.maxCellLength", String.valueOf(cfg.getMaxCellLength()));
        if (!cfg.getExcludedRules().isBlank()) {
            overrides.setProperty("analyzer.excluded.rules", cfg.getExcludedRules());
        }

        AnalyzerConfig analyzerConfig = AnalyzerConfig.fromProperties(overrides);
        ReportAnalyzer analyzer = new ReportAnalyzer(analyzerConfig);
        analyzer.run();
        logger.info("IBM TA report analysis complete → {}", cfg.getOutputFile());
    }

    // ── Module: merge ────────────────────────────────────────────────────────

    private static void runMerge(AppConfig cfg) throws Exception {
        TicketComponentMerger merger = new TicketComponentMerger(
                Path.of(cfg.getTicketFile()),
                Path.of(cfg.getComponentFile()),
                Path.of(cfg.getOutputFile())
        );
        merger.merge();
    }

    // ── Module: source-inventory ──────────────────────────────────────────────

    private static void runSourceInventory(AppConfig cfg) throws Exception {
        SourceInventoryAnalyzer analyzer = createSourceInventoryAnalyzer(cfg);
        analyzer.run(cfg.getOutputFile());
    }

    private static SourceScanProfile buildSourceScanProfile(AppConfig cfg) {
        return SourceScanProfile.forModule(
                cfg.getModule(),
                WlJBossRules.TargetProfile.from(cfg.getWlJBossTarget())
        );
    }

    private static SourceInventoryAnalyzer createSourceInventoryAnalyzer(AppConfig cfg) throws IOException {
        return createSourceInventoryAnalyzer(cfg, List.of());
    }

    private static SourceInventoryAnalyzer createSourceInventoryAnalyzer(AppConfig cfg, List<Path> artifactRoots) throws IOException {
        return new SourceInventoryAnalyzer(
                Path.of(cfg.getSourceInventoryFile()),
                Path.of(cfg.getWorkspaceDir()),
                cfg.isReuseWorkspace(),
                cfg.isCleanWorkspace(),
                cfg.isFailOnCheckoutError(),
                buildSourceScanProfile(cfg),
                promptCredentials(cfg),
                artifactRoots
        );
    }

    private static boolean hasSourceInventory(AppConfig cfg) {
        return cfg.getSourceInventoryFile() != null && !cfg.getSourceInventoryFile().isBlank();
    }

    private static boolean hasCompiledInput(AppConfig cfg) {
        return (cfg.getInputPath() != null && !cfg.getInputPath().isBlank())
                || (cfg.getJarListFile() != null && !cfg.getJarListFile().isBlank());
    }

    private static boolean isBothMode(AppConfig cfg) {
        return hasSourceInventory(cfg)
                && ("both".equalsIgnoreCase(cfg.getMode()) || (cfg.getMode().isBlank() && hasCompiledInput(cfg)));
    }

    private static void appendSourceInventory(AppConfig cfg) throws Exception {
        SourceInventoryAnalyzer analyzer = createSourceInventoryAnalyzer(cfg);
        analyzer.appendToReport(cfg.getOutputFile());
    }

    private static void appendSourceInventory(AppConfig cfg, List<SourceScanResult> results) throws Exception {
        new effortanalyzer.source.SourceInventoryReportWriter().append(
                cfg.getOutputFile(), buildSourceScanProfile(cfg).module(), results);
    }

    private static InventoryScopedScan prepareInventoryScopedScan(AppConfig cfg) throws Exception {
        List<Path> artifactRoots = bothModeArtifactRoots(cfg);
        SourceInventoryAnalyzer sourceAnalyzer = createSourceInventoryAnalyzer(cfg, artifactRoots);
        List<SourceScanResult> sourceResults = sourceAnalyzer.scanInventory();
        Path binaryInput = copyInventoryGeneratedArtifacts(sourceResults, artifactRoots);
        return new InventoryScopedScan(sourceResults, binaryInput);
    }

    private static List<Path> bothModeArtifactRoots(AppConfig cfg) throws IOException {
        List<Path> roots = new ArrayList<>();
        if (cfg.getInputPath() != null && !cfg.getInputPath().isBlank()) {
            roots.add(Path.of(cfg.getInputPath()).toAbsolutePath().normalize());
        }
        if (cfg.getJarListFile() != null && !cfg.getJarListFile().isBlank()) {
            try (BufferedReader reader = Files.newBufferedReader(Path.of(cfg.getJarListFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isBlank() || line.startsWith("#")) continue;
                    roots.add(Path.of(line).toAbsolutePath().normalize());
                }
            }
        }
        return roots;
    }

    private static Path copyInventoryGeneratedArtifacts(List<SourceScanResult> results, List<Path> artifactRoots) throws IOException {
        Path tempDir = Files.createTempDirectory("ea-inventory-jars-");
        int copied = 0;
        Map<String, Integer> names = new HashMap<>();

        for (SourceScanResult result : results) {
            if (result == null || result.component() == null || result.checkout() == null || !result.checkout().success()) {
                continue;
            }
            List<Path> artifacts = GeneratedArtifactScanner.resolveGeneratedArtifacts(
                    result.component(), result.checkout().checkoutPath(), artifactRoots);
            for (Path artifact : artifacts) {
                String fileName = safeFilePrefix(result.component().displayName()) + "--" + artifact.getFileName();
                int duplicate = names.merge(fileName, 1, Integer::sum);
                String targetName = duplicate == 1 ? fileName : duplicate + "-" + fileName;
                Files.copy(artifact, tempDir.resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }

        if (copied == 0) {
            deleteDir(tempDir);
            throw new IOException("No generated JAR/WAR/EAR files were found from enabled source inventory rows. "
                    + "Populate the Generated JARs column and either build the components or pass a compiled artifact directory "
                    + "as --input / run.bat <module> both <compiled-input> <component-workbook> <output-file>.");
        }

        logger.info("Inventory-scoped binary scan input prepared with {} artifact(s): {}", copied, tempDir);
        System.out.println("  [both] Inventory-scoped binary scan: " + copied + " generated artifact(s) from ComponentList.xlsx"
                + (artifactRoots == null || artifactRoots.isEmpty() ? "" : " and supplied compiled input"));
        return tempDir;
    }

    private static String safeFilePrefix(String value) {
        String safe = value == null ? "component" : value.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        safe = safe.replaceAll("^-+|-+$", "");
        return safe.isBlank() ? "component" : safe;
    }

    private record InventoryScopedScan(List<SourceScanResult> sourceResults, Path binaryInput) implements AutoCloseable {
        String binaryInputPath() {
            return binaryInput.toString();
        }

        @Override
        public void close() {
            deleteDir(binaryInput);
        }
    }

    private static CheckoutCredentials promptCredentials(AppConfig cfg) throws IOException {
        if (!cfg.isPromptCredentials()) return CheckoutCredentials.none();

        Console console = System.console();
        String username;
        String password;
        if (console != null) {
            username = console.readLine("SCM username (blank to use existing Git/SVN credentials): ");
            if (username == null || username.isBlank()) return CheckoutCredentials.none();
            char[] chars = console.readPassword("SCM password/token: ");
            password = chars == null ? "" : new String(chars);
            if (chars != null) Arrays.fill(chars, '\0');
        } else {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            System.out.print("SCM username (blank to use existing Git/SVN credentials): ");
            username = reader.readLine();
            if (username == null || username.isBlank()) return CheckoutCredentials.none();
            System.out.print("SCM password/token (input may be visible in this terminal): ");
            password = reader.readLine();
        }
        return new CheckoutCredentials(username.trim(), password == null ? "" : password);
    }

    // ── Module: upgrade ───────────────────────────────────────────────────────

    private static void runUpgrade(AppConfig cfg) throws Exception {
        if (hasSourceInventory(cfg) && !hasCompiledInput(cfg)) {
            runSourceInventory(cfg);
            return;
        }

        String ibmScanner = resolveIbmScanner(cfg.getIbmScannerJar());

        if (isBothMode(cfg)) {
            try (InventoryScopedScan scoped = prepareInventoryScopedScan(cfg)) {
                UpgradeAnalyzer analyzer = new UpgradeAnalyzer(ibmScanner);
                analyzer.analyze(scoped.binaryInputPath());
                analyzer.generateReport(cfg.getOutputFile());
                appendSourceInventory(cfg, scoped.sourceResults());
            }
            logger.info("Upgrade inventory-scoped combined analysis complete → {}", cfg.getOutputFile());
            return;
        }

        UpgradeAnalyzer analyzer = new UpgradeAnalyzer(ibmScanner);
        String inputPath = cfg.getJarListFile().isBlank()
                ? cfg.getInputPath()
                : expandJarList(cfg.getJarListFile());
        analyzer.analyze(inputPath);
        analyzer.generateReport(cfg.getOutputFile());
        if (hasSourceInventory(cfg)) appendSourceInventory(cfg);
        logger.info("Upgrade compatibility analysis complete → {}", cfg.getOutputFile());
    }

    /**
     * Resolves the IBM binaryAppScanner.jar path.
     * Priority: (1) explicit --ibm-scanner arg, (2) auto-detect next to running JAR, (3) blank.
     */
    private static String resolveIbmScanner(String explicit) {
        if (!explicit.isBlank()) {
            System.out.println("  [upgrade] IBM scanner: " + explicit + " (explicit)");
            return explicit;
        }

        // Auto-detect: look next to the running JAR / in the working directory
        String[] candidates = {
            "binaryAppScanner.jar",
            System.getProperty("user.dir") + java.io.File.separator + "binaryAppScanner.jar"
        };
        try {
            java.security.CodeSource cs = EffortAnalyzerApp.class
                    .getProtectionDomain().getCodeSource();
            if (cs != null) {
                Path jarDir = Path.of(cs.getLocation().toURI()).getParent();
                if (jarDir != null) {
                    Path candidate = jarDir.resolve("binaryAppScanner.jar");
                    candidates = new String[]{candidate.toString(), candidates[0], candidates[1]};
                }
            }
        } catch (Exception ignored) {}

        for (String c : candidates) {
            if (Files.exists(Path.of(c))) {
                System.out.println("  [upgrade] IBM scanner auto-detected: " + c);
                return c;
            }
        }

        System.out.println("  [upgrade] IBM scanner not found (binaryAppScanner.jar).");
        System.out.println("            Download from: https://www.ibm.com/support/pages/migration-toolkit-application-binaries");
        System.out.println("            Place it next to EffortAnalyzer-2.0.0-shaded.jar to enable Java 21 scan.");
        return "";
    }

    // ── Module: wl-jboss26 / wl-jboss27 / wl-jboss (legacy) ─────────────────

    private static void runWlJBoss(AppConfig cfg, WlJBossRules.TargetProfile target) throws Exception {
        if (hasSourceInventory(cfg) && !hasCompiledInput(cfg)) {
            runSourceInventory(cfg);
            return;
        }

        WlJBossAnalyzer analyzer = new WlJBossAnalyzer(target);

        if (isBothMode(cfg)) {
            try (InventoryScopedScan scoped = prepareInventoryScopedScan(cfg)) {
                analyzer.run(scoped.binaryInputPath(), cfg.getOutputFile());
                appendSourceInventory(cfg, scoped.sourceResults());
            }
            logger.info("WL-JBoss inventory-scoped combined analysis complete → {}", cfg.getOutputFile());
            return;
        }

        if (!cfg.getJarListFile().isBlank()) {
            String tempDir = expandJarList(cfg.getJarListFile());
            analyzer.run(tempDir, cfg.getOutputFile());
            deleteDir(Path.of(tempDir));
        } else {
            analyzer.run(cfg.getInputPath(), cfg.getOutputFile());
        }
        if (hasSourceInventory(cfg)) appendSourceInventory(cfg);
    }

    /**
     * Reads a jar-list file and copies each JAR into a temp directory,
     * returning the temp directory path for the analyzer.
     */
    private static String expandJarList(String jarListFile) throws IOException {
        Path tempDir = Files.createTempDirectory("ea-jars-");

        try (BufferedReader reader = Files.newBufferedReader(Path.of(jarListFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isBlank() || line.startsWith("#")) continue;
                Path src = Path.of(line);
                if (Files.exists(src)) {
                    Files.copy(src, tempDir.resolve(src.getFileName()),
                               StandardCopyOption.REPLACE_EXISTING);
                    logger.debug("Queued: {}", src.getFileName());
                } else {
                    logger.warn("JAR not found (skipped): {}", line);
                }
            }
        }

        return tempDir.toString();
    }

    private static void deleteDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder())
                          .map(Path::toFile)
                          .forEach(File::delete);
                }
            }
        } catch (IOException ignored) {}
    }

    // ── Output location reporter ──────────────────────────────────────────────

    private static void printOutputLocation(String outputFile) {
        Path abs = Path.of(outputFile).toAbsolutePath();
        System.out.println();
        if (Files.exists(abs)) {
            System.out.println("✔  Report saved to:");
            System.out.println("   " + abs);
        } else {
            System.out.println("⚠  Expected report not found at:");
            System.out.println("   " + abs);
            System.out.println("   Check the log output above for errors.");
        }
        System.out.println();
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    private static void printBanner() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  EffortAnalyzer v2.0.0                            ║");
        System.out.println("║  Migration Analysis & Effort Estimation Tool      ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();
    }
}
