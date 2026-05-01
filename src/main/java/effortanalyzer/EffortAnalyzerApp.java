package effortanalyzer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import effortanalyzer.analyzer.ReportAnalyzer;
import effortanalyzer.config.AnalyzerConfig;
import effortanalyzer.config.AppConfig;
import effortanalyzer.merger.TicketComponentMerger;
import effortanalyzer.upgrade.UpgradeAnalyzer;
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
                case AppConfig.MODULE_ANALYZE    -> runAnalyze(cfg);
                case AppConfig.MODULE_MERGE      -> runMerge(cfg);
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

    // ── Module: upgrade ───────────────────────────────────────────────────────

    private static void runUpgrade(AppConfig cfg) throws Exception {
        String ibmScanner = resolveIbmScanner(cfg.getIbmScannerJar());

        UpgradeAnalyzer analyzer = new UpgradeAnalyzer(ibmScanner);
        String inputPath = cfg.getJarListFile().isBlank()
                ? cfg.getInputPath()
                : expandJarList(cfg.getJarListFile());
        analyzer.analyze(inputPath);
        analyzer.generateReport(cfg.getOutputFile());
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
        WlJBossAnalyzer analyzer = new WlJBossAnalyzer(target);

        if (!cfg.getJarListFile().isBlank()) {
            String tempDir = expandJarList(cfg.getJarListFile());
            analyzer.run(tempDir, cfg.getOutputFile());
            deleteDir(Path.of(tempDir));
        } else {
            analyzer.run(cfg.getInputPath(), cfg.getOutputFile());
        }
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
