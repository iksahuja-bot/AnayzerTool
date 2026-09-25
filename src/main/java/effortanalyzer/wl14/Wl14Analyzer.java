package effortanalyzer.wl14;

import effortanalyzer.library.LibraryUpgradeAnalyzer;
import effortanalyzer.version.LibraryVersionAnalyzer;
import effortanalyzer.version.LibraryVersionRules;
import effortanalyzer.wl15.Wl15LibraryRules;
import effortanalyzer.wl15.Wl15ReportWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Orchestrates the WebLogic 14 Library Migration analysis.
 *
 * <p>Runs the same check set as {@link effortanalyzer.wl15.Wl15Analyzer} —
 * API compatibility scan via {@link Wl15LibraryRules} plus bundled-library
 * version checks via {@link LibraryVersionRules} — and writes the report with
 * WL14 identity through {@link Wl15ReportWriter}.
 */
public class Wl14Analyzer {

    private static final Logger logger = LogManager.getLogger(Wl14Analyzer.class);

    private final LibraryUpgradeAnalyzer analyzer;
    private final LibraryVersionAnalyzer versionAnalyzer;

    public Wl14Analyzer() {
        this("");
    }

    /**
     * @param libraryVersionsFile optional path to a library-versions.properties
     *                            override file; blank means default resolution
     *                            (next to the JAR, then working directory)
     */
    public Wl14Analyzer(String libraryVersionsFile) {
        this.analyzer = new LibraryUpgradeAnalyzer(Wl15LibraryRules.load());
        this.versionAnalyzer = new LibraryVersionAnalyzer(
                LibraryVersionRules.load(LibraryVersionRules.resolveOverride(libraryVersionsFile)));
        logger.info("WL14 analyzer initialized with {} API rules and {} version rules",
                analyzer.getActiveRuleCount(), versionAnalyzer.getRuleCount());
    }

    /**
     * Scans all JARs/WARs/EARs under the given path for WL14 library migration
     * issues and outdated bundled library versions.
     *
     * @param inputPath path to a single archive or a directory tree containing archives
     */
    public void analyze(String inputPath) throws IOException {
        logger.info("Starting WL14 library migration scan: {}", inputPath);
        analyzer.analyze(inputPath);
        versionAnalyzer.analyze(inputPath);
        logger.info("WL14 scan complete. Findings: {}, outdated libraries: {}",
                analyzer.getFindingsByJar().values().stream().mapToInt(java.util.List::size).sum(),
                versionAnalyzer.countOutdated());
    }

    /**
     * Writes the WL14 Migration Report Excel workbook.
     *
     * @param outputFile path for the output .xlsx file
     */
    public void generateReport(String outputFile) throws IOException {
        new Wl15ReportWriter(analyzer.getFindingsByJar(), analyzer.getActiveRuleCount(),
                versionAnalyzer.getFindings(), Wl15ReportWriter.ReportProduct.WL14)
                .write(outputFile);
        logger.info("WL14 migration report written: {}", outputFile);
    }

    /** Exposed for tests/diagnostics. */
    public LibraryVersionAnalyzer getVersionAnalyzer() { return versionAnalyzer; }
}