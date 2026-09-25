package effortanalyzer.wl15;

import effortanalyzer.library.LibraryUpgradeAnalyzer;
import effortanalyzer.version.LibraryVersionAnalyzer;
import effortanalyzer.version.LibraryVersionRules;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Orchestrates the WebLogic 15 Library Migration analysis.
 *
 * <p>Runs two checks over the same input tree:
 * <ol>
 *   <li>API compatibility scan — {@link LibraryUpgradeAnalyzer} with the WL15
 *       rule set loaded by {@link Wl15LibraryRules}</li>
 *   <li>Library version scan — {@link LibraryVersionAnalyzer} with the target
 *       version table from {@link LibraryVersionRules}</li>
 * </ol>
 *
 * <p>Report generation is delegated to {@link Wl15ReportWriter}.
 */
public class Wl15Analyzer {

    private static final Logger logger = LogManager.getLogger(Wl15Analyzer.class);

    private final LibraryUpgradeAnalyzer analyzer;
    private final LibraryVersionAnalyzer versionAnalyzer;

    public Wl15Analyzer() {
        this("");
    }

    /**
     * @param libraryVersionsFile optional path to a library-versions.properties
     *                            override file; blank means default resolution
     *                            (next to the JAR, then working directory)
     */
    public Wl15Analyzer(String libraryVersionsFile) {
        this.analyzer = new LibraryUpgradeAnalyzer(Wl15LibraryRules.load());
        this.versionAnalyzer = new LibraryVersionAnalyzer(
                LibraryVersionRules.load(LibraryVersionRules.resolveOverride(libraryVersionsFile)));
        logger.info("WL15 analyzer initialized with {} API rules and {} version rules",
                analyzer.getActiveRuleCount(), versionAnalyzer.getRuleCount());
    }

    /**
     * Scans all JARs/WARs/EARs under the given path for WL15 library migration
     * issues and outdated bundled library versions.
     *
     * @param inputPath path to a single archive or a directory tree containing archives
     */
    public void analyze(String inputPath) throws IOException {
        logger.info("Starting WL15 library migration scan: {}", inputPath);
        analyzer.analyze(inputPath);
        versionAnalyzer.analyze(inputPath);
        logger.info("WL15 scan complete. Findings: {}, outdated libraries: {}",
                analyzer.getFindingsByJar().values().stream().mapToInt(java.util.List::size).sum(),
                versionAnalyzer.countOutdated());
    }

    /**
     * Writes the WL15 Migration Report Excel workbook.
     *
     * @param outputFile path for the output .xlsx file
     */
    public void generateReport(String outputFile) throws IOException {
        new Wl15ReportWriter(analyzer.getFindingsByJar(), analyzer.getActiveRuleCount(),
                versionAnalyzer.getFindings(), Wl15ReportWriter.ReportProduct.WL15)
                .write(outputFile);
        logger.info("WL15 migration report written: {}", outputFile);
    }

    /** Exposed for tests/diagnostics. */
    public LibraryVersionAnalyzer getVersionAnalyzer() { return versionAnalyzer; }
}