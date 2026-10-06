package effortanalyzer.wl14;

import effortanalyzer.library.DeprecatedApi;
import effortanalyzer.library.LibraryUpgradeAnalyzer;
import effortanalyzer.library.LibraryUpgradeRules;
import effortanalyzer.upgrade.UpgradeAnalyzer;
import effortanalyzer.version.LibraryVersionAnalyzer;
import effortanalyzer.version.LibraryVersionRules;
import effortanalyzer.wl15.Wl15ReportWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates the WebLogic 12c -> 14c migration analysis.
 *
 * <p>WL14 migration first checks Java 21 compatibility using the IBM Migration Toolkit
 * for Application Binaries (binaryAppScanner.jar), then checks for library/API issues:</p>
 * <ul>
 *   <li>General third-party library upgrade issues (Spring, Guava, Guice, Jersey, CGLib)</li>
 *   <li>WebLogic 14.1.2-specific proprietary API removals (T3StartupDef, T3ShutdownDef,
 *       MessageLogger, TrustManager, HostnameVerifier)</li>
 *   <li>Outdated bundled library versions compared to the target version table</li>
 * </ul>
 *
 * <p>To avoid scanning every archive twice, the analyzer loads the general
 * {@link LibraryUpgradeRules} and the WL14-specific {@link Wl14LibraryRules} into a
 * single {@link LibraryUpgradeAnalyzer}. The IBM Java 21 scan is run separately via
 * {@link UpgradeAnalyzer#analyzeIbm(String)}. The final report is written with WL14
 * identity through {@link Wl15ReportWriter}.</p>
 */
public class Wl14Analyzer {

    private static final Logger logger = LogManager.getLogger(Wl14Analyzer.class);

    private final LibraryUpgradeAnalyzer combinedAnalyzer;
    private final LibraryVersionAnalyzer versionAnalyzer;
    private final UpgradeAnalyzer ibmAnalyzer;

    public Wl14Analyzer() {
        this("", null);
    }

    /**
     * @param libraryVersionsFile optional path to a library-versions.properties
     *                            override file; blank means default resolution
     *                            (next to the JAR, then working directory)
     */
    public Wl14Analyzer(String libraryVersionsFile) {
        this(libraryVersionsFile, null);
    }

    /**
     * @param libraryVersionsFile optional path to a library-versions.properties
     *                            override file; blank means default resolution
     * @param ibmScannerJar       path to IBM binaryAppScanner.jar; if present the
     *                            Java 21 compatibility scan is run as a WL14 prerequisite
     */
    public Wl14Analyzer(String libraryVersionsFile, String ibmScannerJar) {
        Set<String> excluded = UpgradeAnalyzer.loadAllExclusions();
        List<DeprecatedApi> combinedRules = new ArrayList<>();
        combinedRules.addAll(LibraryUpgradeRules.load(excluded).getRules());
        combinedRules.addAll(Wl14LibraryRules.load());

        this.combinedAnalyzer = new LibraryUpgradeAnalyzer(combinedRules);
        this.versionAnalyzer = new LibraryVersionAnalyzer(
                LibraryVersionRules.load(LibraryVersionRules.resolveOverride(libraryVersionsFile)));
        this.ibmAnalyzer = new UpgradeAnalyzer(ibmScannerJar, false);

        int generalRules = LibraryUpgradeRules.load(excluded).getRules().size();
        int wl14Rules = Wl14LibraryRules.load().size();
        logger.info("WL14 analyzer initialized: {} general rules, {} WL14-specific rules, {} version rules, IBM available={}",
                generalRules, wl14Rules, versionAnalyzer.getRuleCount(), ibmAnalyzer.isIbmScannerAvailable());
    }

    /**
     * Scans all JARs/WARs/EARs under the given path for WL14 migration issues.
     * Runs the IBM Java 21 scan first, then the combined library scan and version checks.
     *
     * @param inputPath path to a single archive or a directory tree containing archives
     */
    public void analyze(String inputPath) throws IOException {
        logger.info("Starting WL14 migration scan: {}", inputPath);

        ibmAnalyzer.analyzeIbm(inputPath);
        combinedAnalyzer.analyze(inputPath);
        versionAnalyzer.analyze(inputPath);

        int libraryFindings = combinedAnalyzer.getFindingsByJar().values().stream()
                .mapToInt(List::size).sum();
        int ibmFindings = ibmAnalyzer.getIbmFindingsByComponent().values().stream()
                .mapToInt(List::size).sum();

        logger.info("WL14 scan complete. Library findings: {}, IBM Java 21 findings: {}, outdated libraries: {}",
                libraryFindings, ibmFindings, versionAnalyzer.countOutdated());
    }

    /**
     * Writes the WL14 Migration Report Excel workbook.
     *
     * @param outputFile path for the output .xlsx file
     */
    public void generateReport(String outputFile) throws IOException {
        new Wl15ReportWriter(combinedAnalyzer.getFindingsByJar(), combinedAnalyzer.getActiveRuleCount(),
                versionAnalyzer.getFindings(), Wl15ReportWriter.ReportProduct.WL14,
                ibmAnalyzer.getIbmFindingsByComponent()).write(outputFile);
        logger.info("WL14 migration report written: {}", outputFile);
    }

    /** Exposed for tests/diagnostics -- returns the combined analyzer (general + WL14 rules). */
    public LibraryUpgradeAnalyzer getUpgradeAnalyzer() { return combinedAnalyzer; }

    /** Exposed for tests/diagnostics -- returns the combined analyzer (general + WL14 rules). */
    public LibraryUpgradeAnalyzer getWl14ApiAnalyzer() { return combinedAnalyzer; }

    /** Exposed for tests/diagnostics. */
    public LibraryVersionAnalyzer getVersionAnalyzer() { return versionAnalyzer; }

    /** Exposed for tests/diagnostics. */
    public UpgradeAnalyzer getIbmAnalyzer() { return ibmAnalyzer; }
}
