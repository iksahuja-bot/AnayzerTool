package effortanalyzer.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import effortanalyzer.config.AnalyzerConfig;
import effortanalyzer.java21.Java21Rules;
import effortanalyzer.library.LibraryUpgradeAnalyzer;
import effortanalyzer.util.ExcelUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.*;

/**
 * Upgrade Compatibility Analyzer — combined module.
 *
 * <p><b>Phase 1 — Java 21 compatibility</b> via the IBM Migration Toolkit for Application
 * Binaries (binaryAppScanner.jar). The scanner is invoked as a child process:
 * <pre>
 *   java -jar binaryAppScanner.jar &lt;input&gt; --analyzeJavaSE --sourceJava=oracle8
 *        --targetJava=java21 --format=json --output=&lt;reportsDir&gt;
 * </pre>
 * The JSON files are written to a {@code reports/} folder located next to the EffortAnalyzer JAR.
 * The folder is created automatically if absent, and its contents are cleaned before every run so
 * that stale results from a previous scan do not pollute the current output.
 * The JSON report files produced by IBM WAMT are parsed and filtered by the exclusion rules
 * defined in {@code upgrade-excluded-rules.txt} and the 16 built-in IBM TA defaults from
 * {@link AnalyzerConfig#getDefaultExcludedRules()}.
 *
 * <p><b>Phase 2 — Library upgrade compatibility</b> via the built-in Spring / Guava / Guice /
 * Jersey / CGLib deprecation scanner.
 *
 * <p><b>Output</b> — 6-sheet Excel workbook with clear guidance on each sheet:
 * <ol>
 *   <li>📋 Instructions  — how to read and act on the report</li>
 *   <li>📊 Summary       — component-level issue counts and priority</li>
 *   <li>☕ Java 21 Issues — IBM WAMT findings per component/rule</li>
 *   <li>📦 Library Issues — Spring / Guava / Guice / Jersey / CGLib deprecations</li>
 *   <li>✅ Checklist      — deduplicated action items, severity-sorted</li>
 *   <li>🚫 Excluded Rules — what was filtered out and why</li>
 * </ol>
 */
public class UpgradeAnalyzer {

    private static final Logger logger = LogManager.getLogger(UpgradeAnalyzer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String ibmScannerJar;
    private final boolean ibmScannerAvailable;
    private final Set<String> excludedRules;
    private final LibraryUpgradeAnalyzer spring;

    // Results populated by analyze()
    private final Map<String, List<IbmFinding>> ibmFindingsByComponent = new LinkedHashMap<>();
    private int totalJarsScanned = 0;
    private boolean analyzed = false;

    // ── IBM Finding record ────────────────────────────────────────────────────

    record IbmFinding(
            String component,
            String ruleId,
            String ruleTitle,
            String severity,
            int affectedClassCount,
            List<String> affectedClasses,
            String sampleMatches
    ) {}

    // ── Constructor ───────────────────────────────────────────────────────────

    public UpgradeAnalyzer(String ibmScannerJar) {
        this.ibmScannerJar = ibmScannerJar != null ? ibmScannerJar.trim() : "";
        this.ibmScannerAvailable = !this.ibmScannerJar.isEmpty()
                && Files.exists(Path.of(this.ibmScannerJar));
        this.excludedRules = loadAllExclusions();
        this.spring = new LibraryUpgradeAnalyzer(excludedRules);

        logger.info("Exclusions active: {}", excludedRules.size());
        logger.info("IBM scanner available: {} ({})", ibmScannerAvailable, this.ibmScannerJar);
        System.out.println("  [upgrade] Exclusions loaded: " + excludedRules.size() + " rule(s) suppressed");
    }

    /**
     * Merges exclusions from {@code upgrade-excluded-rules.txt} with the 16
     * hardcoded IBM TA defaults (module 4 exclusions).
     */
    private static Set<String> loadAllExclusions() {
        Set<String> all = new HashSet<>(Java21Rules.loadExcludedRules());
        all.addAll(AnalyzerConfig.getDefaultExcludedRules()); // the 16 IBM TA defaults
        return Collections.unmodifiableSet(all);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void analyze(String inputPath) throws IOException {
        System.out.println("  [upgrade] Input: " + inputPath);

        // Phase 1 — IBM Java 21 compatibility scan
        if (ibmScannerAvailable) {
            System.out.println("  [upgrade] Phase 1/2 — IBM binary scanner (Java 21 compatibility)...");
            Path jsonDir = runIbmScanner(inputPath);
            System.out.println("  [upgrade]   Parsing IBM JSON results from: " + jsonDir);
            parseIbmOutput(jsonDir);
            System.out.println("  [upgrade]   IBM JSON reports kept at: " + jsonDir);
            System.out.println("  [upgrade]   IBM scan complete: "
                    + ibmFindingsByComponent.size() + " component(s) with issues");
        } else {
            System.out.println("  [upgrade] Phase 1/2 — IBM scanner not found. Java 21 scan skipped.");
        }

        // Phase 2 — Library upgrade compatibility scan
        System.out.println("  [upgrade] Phase 2/2 — Library upgrade scan (Spring/Guava/Guice/Jersey/CGLib)...");
        spring.analyze(inputPath);
        System.out.println("  [upgrade]   Library scan complete: "
                + spring.getFindingsByJar().size() + " JAR(s) with issues");

        analyzed = true;
    }

    public void generateReport(String outputFile) throws IOException {
        if (!analyzed) throw new IllegalStateException("Call analyze() before generateReport()");

        Path outPath = Path.of(outputFile).toAbsolutePath();
        ExcelUtils.validateAndPrepareOutput(outPath, logger);

        try (Workbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);

            writeInstructionsSheet(wb, s);
            writeSummarySheet(wb, s);
            writeJava21Sheet(wb, s);
            writeLibrarySheet(wb, s);
            writeChecklistSheet(wb, s);
            writeExcludedRulesSheet(wb, s);

            try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                wb.write(fos);
            } catch (IOException e) {
                String detail = ExcelUtils.diagnoseWriteFailure(outPath, e);
                logger.error("Report write failed: {}", detail);
                throw new IOException(detail, e);
            }
        }

        logger.info("Report written: {}", outPath);
        System.out.println("  [upgrade] Report written → " + outPath);
    }

    // ── IBM scanner invocation ────────────────────────────────────────────────

    /**
     * Resolves the {@code reports/} folder that sits next to the EffortAnalyzer JAR.
     * Falls back to {@code ./reports} in the current working directory if the code
     * source location cannot be determined (e.g. when running from an IDE).
     */
    private Path resolveJsonOutputDir() {
        try {
            Path codeSource = Path.of(
                    UpgradeAnalyzer.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            Path jarDir = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            return jarDir.resolve("reports");
        } catch (Exception e) {
            logger.warn("Could not determine JAR location; using ./reports as fallback");
            return Path.of("reports").toAbsolutePath();
        }
    }

    /**
     * Prepares the {@code reports/} folder: creates it if absent, cleans its contents
     * if it already exists so that stale JSON files from a prior run are not mixed in.
     */
    private Path prepareJsonOutputDir() throws IOException {
        Path dir = resolveJsonOutputDir();
        if (Files.exists(dir)) {
            logger.info("Cleaning existing IBM JSON output directory: {}", dir);
            System.out.println("  [upgrade]   Cleaning reports folder: " + dir);
            try (Stream<Path> entries = Files.list(dir)) {
                for (Path entry : entries.toList()) {
                    if (Files.isDirectory(entry)) deleteDir(entry);
                    else Files.delete(entry);
                }
            }
        } else {
            Files.createDirectories(dir);
            logger.info("Created IBM JSON output directory: {}", dir);
            System.out.println("  [upgrade]   Created reports folder: " + dir);
        }
        return dir;
    }

    private Path runIbmScanner(String inputPath) throws IOException {
        Path jsonOutputDir = prepareJsonOutputDir();
        String javaExe = findJavaExecutable();

        List<String> cmd = List.of(
                javaExe, "-jar", ibmScannerJar,
                inputPath,
                "--analyzeJavaSE",
                "--sourceJava=oracle8",
                "--targetJava=java21",
                "--format=json",
                "--output=" + jsonOutputDir
        );

        logger.info("Running IBM scanner: {}", String.join(" ", cmd));
        System.out.println("  [upgrade]   CMD: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Stream IBM scanner output to logger
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("[IBM scanner] {}", line);
                if (line.contains("ERROR") || line.contains("WARN")) {
                    System.out.println("  [IBM]  " + line);
                }
            }
        }

        boolean finished;
        try {
            finished = process.waitFor(15, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("IBM scanner was interrupted", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("IBM scanner timed out after 15 minutes");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            logger.warn("IBM scanner exited with code {}. Parsing any JSON output produced.", exitCode);
            System.out.println("  [upgrade]   IBM scanner exit code " + exitCode
                    + " — parsing available JSON output...");
        }

        return jsonOutputDir;
    }

    // ── IBM JSON parsing ──────────────────────────────────────────────────────

    private void parseIbmOutput(Path jsonDir) throws IOException {
        List<Path> jsonFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(jsonDir)) {
            walk.filter(p -> p.toString().endsWith(".json"))
                .forEach(jsonFiles::add);
        }

        totalJarsScanned = jsonFiles.size();
        logger.info("Parsing {} IBM JSON report(s) from {}", totalJarsScanned, jsonDir);

        for (Path jsonFile : jsonFiles) {
            parseOneIbmFile(jsonFile);
        }
    }

    private void parseOneIbmFile(Path jsonFile) {
        try {
            JsonNode root = MAPPER.readTree(jsonFile.toFile());
            String fileName  = jsonFile.getFileName().toString();
            String component = fileName
                    .replace("_AnalysisReport.json", "")
                    .replace(".json", "")
                    .replace(".jar", "");

            JsonNode rules = root.get("rules");
            if (rules == null || !rules.isArray()) return;

            List<IbmFinding> findings = new ArrayList<>();

            for (JsonNode rule : rules) {
                String ruleId   = rule.path("ruleId").asText("");
                String title    = rule.path("title").asText(ruleId);
                String severity = mapIbmSeverity(rule.path("severity").asText("warning"));

                // Apply unified exclusion set
                if (excludedRules.contains(ruleId)) {
                    logger.debug("Excluded IBM rule: {}", ruleId);
                    continue;
                }

                JsonNode results = rule.get("results");
                if (results == null || !results.isArray()) continue;

                List<String> affectedClasses = new ArrayList<>();
                List<String> sampleMatches   = new ArrayList<>();

                for (JsonNode result : results) {
                    String cls = result.path("fileName").asText("").trim();
                    if (!cls.isBlank()) affectedClasses.add(cls);

                    JsonNode details = result.get("details");
                    if (details != null && details.isArray()) {
                        for (JsonNode detail : details) {
                            String match = detail.path("match").asText("").trim();
                            if (!match.isBlank() && sampleMatches.size() < 5) {
                                sampleMatches.add(match);
                            }
                        }
                    }
                }

                if (!affectedClasses.isEmpty()) {
                    findings.add(new IbmFinding(
                            component, ruleId, title, severity,
                            affectedClasses.size(),
                            affectedClasses,
                            String.join("; ", sampleMatches)
                    ));
                }
            }

            if (!findings.isEmpty()) {
                ibmFindingsByComponent.put(component, findings);
            }

        } catch (IOException e) {
            logger.error("Failed to parse IBM JSON: {}", jsonFile, e);
        }
    }

    private static String mapIbmSeverity(String ibmSeverity) {
        return switch (ibmSeverity.toLowerCase(Locale.ROOT)) {
            case "severe", "stop", "blocker" -> "HIGH";
            case "warning"                   -> "MEDIUM";
            case "informational", "info"     -> "LOW";
            default                          -> "MEDIUM";
        };
    }

    // ── Sheet 1: Instructions ─────────────────────────────────────────────────

    private void writeInstructionsSheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("📋 Instructions");
        sheet.setColumnWidth(0, 32 * 256);
        sheet.setColumnWidth(1, 85 * 256);

        int r = 0;

        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue("How to Read and Use This Upgrade Compatibility Report");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

        Row sub = sheet.createRow(r++);
        sub.createCell(0).setCellValue("Generated: " + LocalDate.now()
                + "   |   EffortAnalyzer v2.0.0"
                + "   |   IBM scanner: " + (ibmScannerAvailable ? "✔ enabled" : "✘ not found"));
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 1));
        r++;

        // ── What is this? ─────────────────────────────────────────────────────
        addInstSection(sheet, s, r++, "WHAT IS THIS REPORT?");
        addInstRow(sheet, wb, r++, "Purpose",
                "Identifies all code changes required when upgrading from Java 8 to Java 21 "
                + "and when upgrading third-party libraries (Spring, Guava, Guice, Jersey, CGLib).");
        addInstRow(sheet, wb, r++, "Phase 1 — Java 21 (IBM)",
                "Uses the IBM Migration Toolkit for Application Binaries (binaryAppScanner.jar) "
                + "to detect Java SE API incompatibilities. The tool is invoked automatically on "
                + "your input JARs and its JSON output is parsed and filtered.");
        addInstRow(sheet, wb, r++, "Phase 2 — Libraries",
                "Built-in EffortAnalyzer scan for deprecated/removed Spring 5.3.39, Guava 31.1-jre, "
                + "Guice 5.1.0, Jersey 2.22.2, and CGLib→ByteBuddy APIs.");
        addInstRow(sheet, wb, r++, "Exclusions",
                "16 IBM TA informational rules are excluded by default (same as the standalone "
                + "analyze module). Additional exclusions can be added to upgrade-excluded-rules.txt.");
        r++;

        // ── Where to look ─────────────────────────────────────────────────────
        addInstSection(sheet, s, r++, "REPORT SHEETS — WHERE TO LOOK");
        addInstRow(sheet, wb, r++, "📋 Instructions (here)",
                "Start here. Read once to understand the structure, then go to the Summary sheet.");
        addInstRow(sheet, wb, r++, "📊 Summary",
                "High-level overview per component: Java 21 issue counts by severity + library issue counts. "
                + "Use this to PRIORITIZE which components need the most work.");
        addInstRow(sheet, wb, r++, "☕ Java 21 Issues (IBM)",
                "One row per IBM WAMT rule triggered per component. "
                + "Shows: Rule ID | Rule description | Severity | # affected classes | Sample class names. "
                + "HIGH = will fail at runtime. Fix these FIRST.");
        addInstRow(sheet, wb, r++, "📦 Library Issues",
                "One row per deprecated API found in a scanned class. "
                + "Shows: File | Deprecated API | Library | Replacement API | What to do. "
                + "HIGH = API removed, MEDIUM = deprecated, LOW = soft-deprecated.");
        addInstRow(sheet, wb, r++, "✅ Remediation Checklist",
                "Deduplicated list of all issues. One row = one unique fix needed. "
                + "Sorted by severity. Mark the 'Done?' column to track progress. "
                + "This is your TASK LIST for the migration.");
        addInstRow(sheet, wb, r++, "🚫 Excluded Rules",
                "Rules that were filtered from the report. Safe to ignore unless you "
                + "want to re-enable any of them (edit upgrade-excluded-rules.txt).");
        r++;

        // ── Severity ──────────────────────────────────────────────────────────
        addInstSection(sheet, s, r++, "SEVERITY GUIDE");
        addInstRow(sheet, wb, r++, "HIGH  (orange background)",
                "APIs removed in Java 21 or library APIs that no longer exist. "
                + "These WILL cause runtime failures. Address before any Java 21 deployment.");
        addInstRow(sheet, wb, r++, "MEDIUM  (yellow background)",
                "APIs deprecated with removal scheduled. Will work on Java 21 today "
                + "but will break in a future release. Plan to fix before next major version.");
        addInstRow(sheet, wb, r++, "LOW  (green background)",
                "Informational or soft-deprecated. May have subtle behaviour changes. "
                + "Address when convenient, before the next major library upgrade.");
        r++;

        // ── How to fix ────────────────────────────────────────────────────────
        addInstSection(sheet, s, r++, "RECOMMENDED MIGRATION STEPS");
        addInstRow(sheet, wb, r++, "Step 1 — Triage",
                "Open the 📊 Summary sheet. Identify components with HIGH-severity IBM findings.");
        addInstRow(sheet, wb, r++, "Step 2 — Fix Java 21 Issues",
                "Open ☕ Java 21 Issues. For each rule, search the IBM Rule ID in IBM WAMT documentation: "
                + "https://www.ibm.com/docs/en/wamt  "
                + "Each rule has a detailed migration guide including code-before/after examples.");
        addInstRow(sheet, wb, r++, "Step 3 — Fix Library Issues",
                "Open 📦 Library Issues. The 'Replacement API' column shows the exact API to switch to. "
                + "Most are simple method/class renames or API upgrades.");
        addInstRow(sheet, wb, r++, "Step 4 — Track Progress",
                "Use ✅ Remediation Checklist. Mark 'Done?' as you complete each fix. "
                + "Share this sheet with your team to coordinate work.");
        addInstRow(sheet, wb, r++, "Step 5 — Verify",
                "After fixes: rebuild, re-run EffortAnalyzer, and confirm the issues no longer appear.");
        r++;

        // ── IBM scanner setup ─────────────────────────────────────────────────
        if (!ibmScannerAvailable) {
            addInstSection(sheet, s, r++, "⚠  ACTION REQUIRED: IBM SCANNER NOT FOUND");
            addInstRow(sheet, wb, r++, "Download",
                    "Go to: https://www.ibm.com/support/pages/migration-toolkit-application-binaries");
            addInstRow(sheet, wb, r++, "Install",
                    "Place binaryAppScanner.jar in the SAME FOLDER as EffortAnalyzer-2.0.0-shaded.jar. "
                    + "No configuration needed — it is auto-detected on next run.");
            addInstRow(sheet, wb, r++, "Re-run",
                    "Run: run.bat upgrade  (or run.ps1 / run.sh)  with the same --input path. "
                    + "The ☕ Java 21 Issues sheet will populate automatically.");
            r++;
        }

        // ── Exclusions ────────────────────────────────────────────────────────
        addInstSection(sheet, s, r++, "MANAGING EXCLUSIONS");
        addInstRow(sheet, wb, r++, "File",
                "upgrade-excluded-rules.txt  (place in same folder as the JAR, or on classpath).");
        addInstRow(sheet, wb, r++, "Format",
                "One IBM WAMT rule ID per line (e.g. CLDRLocaleDataByDefault). "
                + "Lines starting with # are treated as comments.");
        addInstRow(sheet, wb, r++, "Default exclusions",
                "16 informational rules are always excluded (same as the standalone Analyze module). "
                + "See the 🚫 Excluded Rules sheet for the complete list with explanations.");
    }

    private void addInstSection(Sheet sheet, Styles s, int r, String heading) {
        Row row = sheet.createRow(r);
        Cell c = row.createCell(0);
        c.setCellValue(heading);
        c.setCellStyle(s.sectionHeader);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 1));
        row.setHeightInPoints(20);
    }

    private void addInstRow(Sheet sheet, Workbook wb, int r, String label, String text) {
        Row row = sheet.createRow(r);
        row.setHeightInPoints(36);

        CellStyle lblStyle = wb.createCellStyle();
        Font lf = wb.createFont();
        lf.setBold(true);
        lblStyle.setFont(lf);
        lblStyle.setWrapText(true);
        lblStyle.setVerticalAlignment(VerticalAlignment.TOP);
        Cell lc = row.createCell(0);
        lc.setCellValue(label);
        lc.setCellStyle(lblStyle);

        CellStyle txtStyle = wb.createCellStyle();
        txtStyle.setWrapText(true);
        txtStyle.setVerticalAlignment(VerticalAlignment.TOP);
        Cell tc = row.createCell(1);
        tc.setCellValue(text);
        tc.setCellStyle(txtStyle);
    }

    // ── Sheet 2: Summary ──────────────────────────────────────────────────────

    private void writeSummarySheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("📊 Summary");
        sheet.setColumnWidth(0, 36 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        sheet.setColumnWidth(2, 14 * 256);
        sheet.setColumnWidth(3, 14 * 256);
        sheet.setColumnWidth(4, 16 * 256);
        sheet.setColumnWidth(5, 14 * 256);
        sheet.setColumnWidth(6, 14 * 256);

        int r = 0;

        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue("Upgrade Compatibility Analysis — Component Summary");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));

        Row sub = sheet.createRow(r++);
        sub.createCell(0).setCellValue("Generated: " + LocalDate.now()
                + "   |   Exclusions: " + excludedRules.size()
                + "   |   IBM scanner: " + (ibmScannerAvailable ? "✔ enabled" : "✘ not found — Java 21 scan skipped"));
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 6));

        if (!ibmScannerAvailable) {
            r++;
            Row warn = sheet.createRow(r++);
            Cell wc = warn.createCell(0);
            wc.setCellValue("⚠  IBM scanner (binaryAppScanner.jar) not found — Java 21 compatibility scan was NOT performed. "
                    + "Download from https://www.ibm.com/support/pages/migration-toolkit-application-binaries "
                    + "and place next to EffortAnalyzer-2.0.0-shaded.jar, then re-run.");
            CellStyle ws = wb.createCellStyle();
            ws.setFillForegroundColor(IndexedColors.LIGHT_ORANGE.getIndex());
            ws.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            ws.setWrapText(true);
            wc.setCellStyle(ws);
            sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 6));
            warn.setHeightInPoints(40);
        }
        r++;

        // ── Instructions tip ──────────────────────────────────────────────────
        Row tip = sheet.createRow(r++);
        Cell tipC = tip.createCell(0);
        tipC.setCellValue(
                "📋 HOW TO USE: Components with HIGH Java 21 issues must be fixed before deployment. "
                + "Click '☕ Java 21 Issues' for rule details. Click '📦 Library Issues' for library deprecations. "
                + "Use '✅ Remediation Checklist' to track your progress.");
        CellStyle tipStyle = wb.createCellStyle();
        tipStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        tipStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        tipStyle.setWrapText(true);
        tipC.setCellStyle(tipStyle);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 6));
        tip.setHeightInPoints(40);
        r++;

        // ── Column headers ────────────────────────────────────────────────────
        Row hdr = sheet.createRow(r++);
        cellH(hdr, s.colHeader, 0, "Component");
        cellH(hdr, s.colHeader, 1, "Java21 HIGH");
        cellH(hdr, s.colHeader, 2, "Java21 MED");
        cellH(hdr, s.colHeader, 3, "Java21 LOW");
        cellH(hdr, s.colHeader, 4, "Library Issues");
        cellH(hdr, s.colHeader, 5, "Total");
        cellH(hdr, s.colHeader, 6, "Priority");
        sheet.createFreezePane(0, r);

        // ── IBM component rows ─────────────────────────────────────────────────
        for (Map.Entry<String, List<IbmFinding>> entry : ibmFindingsByComponent.entrySet()) {
            String comp = entry.getKey();
            List<IbmFinding> findings = entry.getValue();
            long high   = findings.stream().filter(f -> "HIGH".equals(f.severity())).count();
            long med    = findings.stream().filter(f -> "MEDIUM".equals(f.severity())).count();
            long low    = findings.stream().filter(f -> "LOW".equals(f.severity())).count();
            long libCnt = spring.getFindingsByJar().entrySet().stream()
                    .filter(e -> e.getKey().contains(comp))
                    .mapToLong(e -> e.getValue().size()).sum();
            long total  = high + med + low + libCnt;
            String pri  = high > 0 ? "HIGH" : (med > 0 ? "MEDIUM" : "LOW");

            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(comp);
            Cell hc = row.createCell(1); hc.setCellValue(high);
            row.createCell(2).setCellValue(med);
            row.createCell(3).setCellValue(low);
            row.createCell(4).setCellValue(libCnt);
            row.createCell(5).setCellValue(total);
            Cell pc = row.createCell(6); pc.setCellValue(pri);

            CellStyle sevS = severityCellStyle(wb, s, pri);
            if (sevS != null) { pc.setCellStyle(sevS); }
            if (high > 0) hc.setCellStyle(severityCellStyle(wb, s, "HIGH"));
        }

        // ── Library-only components (no IBM findings) ─────────────────────────
        for (Map.Entry<String, List<LibraryUpgradeAnalyzer.Finding>> entry
                : spring.getFindingsByJar().entrySet()) {
            String jarKey = entry.getKey();
            String comp = Path.of(jarKey).getFileName().toString()
                    .replaceAll("\\.(jar|war|ear)$", "");
            if (ibmFindingsByComponent.containsKey(comp)) continue; // already written above

            long libCnt = entry.getValue().size();
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(comp);
            row.createCell(1).setCellValue(0);
            row.createCell(2).setCellValue(0);
            row.createCell(3).setCellValue(0);
            row.createCell(4).setCellValue(libCnt);
            row.createCell(5).setCellValue(libCnt);
            row.createCell(6).setCellValue("LOW");
        }

        if (ibmFindingsByComponent.isEmpty() && spring.getFindingsByJar().isEmpty()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue("No components found. Verify the --input path is correct.");
        }

        // ── Totals row ────────────────────────────────────────────────────────
        r++;
        Row tot = sheet.createRow(r);
        long tH = ibmFindingsByComponent.values().stream().flatMap(List::stream)
                .filter(f -> "HIGH".equals(f.severity())).count();
        long tM = ibmFindingsByComponent.values().stream().flatMap(List::stream)
                .filter(f -> "MEDIUM".equals(f.severity())).count();
        long tL = ibmFindingsByComponent.values().stream().flatMap(List::stream)
                .filter(f -> "LOW".equals(f.severity())).count();
        long tLib = spring.getFindingsByJar().values().stream().mapToLong(List::size).sum();
        cellH(tot, s.colHeader, 0, "TOTAL");
        cellH(tot, s.colHeader, 1, String.valueOf(tH));
        cellH(tot, s.colHeader, 2, String.valueOf(tM));
        cellH(tot, s.colHeader, 3, String.valueOf(tL));
        cellH(tot, s.colHeader, 4, String.valueOf(tLib));
        cellH(tot, s.colHeader, 5, String.valueOf(tH + tM + tL + tLib));
        cellH(tot, s.colHeader, 6, "");
    }

    // ── Sheet 3: Java 21 Issues (IBM) ─────────────────────────────────────────

    private void writeJava21Sheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("☕ Java 21 Issues (IBM)");
        int[] widths = {30, 30, 45, 12, 16, 55, 55};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = 0;

        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue("Java 8 → Java 21 Compatibility Findings  (IBM Migration Toolkit for Application Binaries)");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, widths.length - 1));

        // Instruction bar
        Row inst = sheet.createRow(r++);
        Cell ic = inst.createCell(0);
        ic.setCellValue(
                "Each row = one IBM WAMT rule triggered in one component.  "
                + "HIGH severity = removed API, will crash at runtime — fix FIRST.  "
                + "Search the IBM Rule ID in IBM WAMT docs for code-level guidance: https://www.ibm.com/docs/en/wamt  "
                + "|  " + excludedRules.size() + " rule(s) excluded (see 🚫 Excluded Rules sheet)");
        CellStyle infoStyle = infoBarStyle(wb);
        ic.setCellStyle(infoStyle);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, widths.length - 1));
        inst.setHeightInPoints(40);
        r++;

        if (!ibmScannerAvailable) {
            Row warn = sheet.createRow(r++);
            Cell wc = warn.createCell(0);
            wc.setCellValue(
                    "⚠  IBM scanner (binaryAppScanner.jar) was NOT found.\n"
                    + "   Download from: https://www.ibm.com/support/pages/migration-toolkit-application-binaries\n"
                    + "   Place next to EffortAnalyzer-2.0.0-shaded.jar, then re-run the upgrade module.");
            CellStyle ws = wb.createCellStyle();
            ws.setFillForegroundColor(IndexedColors.LIGHT_ORANGE.getIndex());
            ws.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            ws.setWrapText(true);
            wc.setCellStyle(ws);
            sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, widths.length - 1));
            warn.setHeightInPoints(55);
            return;
        }

        if (ibmFindingsByComponent.isEmpty()) {
            sheet.createRow(r).createCell(0)
                    .setCellValue("✅  No Java 21 incompatibilities found across scanned JARs.");
            return;
        }

        Row hdr = sheet.createRow(r++);
        String[] cols = {"Component", "IBM Rule ID", "Rule Description",
                         "Severity", "# Classes Affected", "Affected Classes (sample)", "Next Steps"};
        for (int i = 0; i < cols.length; i++) cellH(hdr, s.colHeader, i, cols[i]);
        sheet.createFreezePane(0, r);

        CellStyle wrap = wrapStyle(wb);

        for (Map.Entry<String, List<IbmFinding>> entry : ibmFindingsByComponent.entrySet()) {
            // Component header row
            Row compRow = sheet.createRow(r++);
            Cell cc = compRow.createCell(0);
            cc.setCellValue("▶  " + entry.getKey());
            cc.setCellStyle(s.jarHeader);
            sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, widths.length - 1));

            List<IbmFinding> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(f -> severityOrder(f.severity())))
                    .collect(Collectors.toList());

            for (IbmFinding f : sorted) {
                Row row = sheet.createRow(r++);
                CellStyle sev = severityCellStyle(wb, s, f.severity());

                row.createCell(0).setCellValue(f.component());

                Cell ruleCell = row.createCell(1);
                ruleCell.setCellValue(f.ruleId());
                if (sev != null) ruleCell.setCellStyle(sev);

                Cell descCell = row.createCell(2);
                descCell.setCellValue(f.ruleTitle());
                descCell.setCellStyle(wrap);

                Cell sevCell = row.createCell(3);
                sevCell.setCellValue(f.severity());
                if (sev != null) sevCell.setCellStyle(sev);

                row.createCell(4).setCellValue(f.affectedClassCount());

                // Show up to 5 affected classes
                String preview = f.affectedClasses().stream().limit(5)
                        .collect(Collectors.joining("\n"));
                if (f.affectedClassCount() > 5) {
                    preview += "\n... and " + (f.affectedClassCount() - 5) + " more";
                }
                Cell clsCell = row.createCell(5);
                clsCell.setCellValue(preview);
                clsCell.setCellStyle(wrap);

                Cell nextCell = row.createCell(6);
                nextCell.setCellValue(
                        "1. Search IBM WAMT docs for rule: " + f.ruleId() + "\n"
                        + "2. Visit: https://www.ibm.com/docs/en/wamt\n"
                        + "3. Apply the recommended code change for each affected class above.");
                nextCell.setCellStyle(wrap);

                row.setHeightInPoints(Math.max(30, Math.min(f.affectedClassCount(), 5) * 14));
            }
        }
    }

    // ── Sheet 4: Library Issues ───────────────────────────────────────────────

    private void writeLibrarySheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("📦 Library Issues");
        int[] widths = {35, 8, 42, 12, 12, 42, 52};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = 0;

        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue("Third-Party Library Upgrade Issues — Spring / Guava / Guice / Jersey / CGLib");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, widths.length - 1));

        Row inst = sheet.createRow(r++);
        Cell ic = inst.createCell(0);
        ic.setCellValue(
                "Each row = one deprecated/removed third-party API used in a scanned class.  "
                + "HIGH = API removed (runtime failure).  MEDIUM = deprecated (plan to replace).  "
                + "LOW = soft-deprecated (low risk, but replace before next major upgrade).  "
                + "The 'Replacement API' column shows exactly what to change to.");
        ic.setCellStyle(infoBarStyle(wb));
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, widths.length - 1));
        inst.setHeightInPoints(40);
        r++;

        if (spring.getFindingsByJar().isEmpty()) {
            sheet.createRow(r).createCell(0)
                    .setCellValue("✅  No library upgrade issues found.");
            return;
        }

        Row hdr = sheet.createRow(r++);
        String[] cols = {"File / Class", "Line", "Deprecated API", "Library",
                         "Severity", "Replacement API", "What to Do"};
        for (int i = 0; i < cols.length; i++) cellH(hdr, s.colHeader, i, cols[i]);
        sheet.createFreezePane(0, r);

        CellStyle wrap = wrapStyle(wb);

        for (Map.Entry<String, List<LibraryUpgradeAnalyzer.Finding>> entry
                : spring.getFindingsByJar().entrySet()) {

            Row jarRow = sheet.createRow(r++);
            Cell jc = jarRow.createCell(0);
            jc.setCellValue("▶  " + entry.getKey());
            jc.setCellStyle(s.jarHeader);
            sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, widths.length - 1));

            for (LibraryUpgradeAnalyzer.Finding f : entry.getValue()) {
                Row row = sheet.createRow(r++);
                CellStyle sev = severityCellStyle(wb, s, f.severity());

                row.createCell(0).setCellValue(f.fileName());
                row.createCell(1).setCellValue(f.lineNumber() > 0 ? String.valueOf(f.lineNumber()) : "");

                String api = (f.methodName() != null && !f.methodName().isBlank())
                        ? f.deprecatedClass() + "#" + f.methodName()
                        : f.deprecatedClass();
                Cell apiCell = row.createCell(2);
                apiCell.setCellValue(api);
                if (sev != null) apiCell.setCellStyle(sev);

                row.createCell(3).setCellValue(f.library());

                Cell sevCell = row.createCell(4);
                sevCell.setCellValue(f.severity());
                if (sev != null) sevCell.setCellStyle(sev);

                Cell repCell = row.createCell(5);
                repCell.setCellValue(f.replacement());
                repCell.setCellStyle(wrap);

                Cell descCell = row.createCell(6);
                descCell.setCellValue(f.description());
                descCell.setCellStyle(wrap);
            }
        }
    }

    // ── Sheet 5: Remediation Checklist ────────────────────────────────────────

    private void writeChecklistSheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("✅ Remediation Checklist");
        sheet.setColumnWidth(0, 14 * 256);
        sheet.setColumnWidth(1, 34 * 256);
        sheet.setColumnWidth(2, 12 * 256);
        sheet.setColumnWidth(3, 55 * 256);
        sheet.setColumnWidth(4, 60 * 256);
        sheet.setColumnWidth(5, 10 * 256);

        int r = 0;

        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue("Remediation Checklist — Track Your Migration Progress");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

        Row inst = sheet.createRow(r++);
        Cell ic = inst.createCell(0);
        ic.setCellValue(
                "One row per unique issue (deduplicated). Sorted by severity — HIGH first. "
                + "Mark the 'Done?' column (☐ → ✔) as you complete each fix. "
                + "IBM rules: look up the Rule ID in IBM WAMT docs for code examples. "
                + "Library rules: the 'Action Required' column shows the exact replacement API.");
        ic.setCellStyle(infoBarStyle(wb));
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 5));
        inst.setHeightInPoints(40);
        r++;

        Row hdr = sheet.createRow(r++);
        cellH(hdr, s.colHeader, 0, "Source");
        cellH(hdr, s.colHeader, 1, "Rule / API");
        cellH(hdr, s.colHeader, 2, "Severity");
        cellH(hdr, s.colHeader, 3, "Description");
        cellH(hdr, s.colHeader, 4, "Action Required");
        cellH(hdr, s.colHeader, 5, "Done?");
        sheet.createFreezePane(0, r);

        CellStyle wrap = wrapStyle(wb);

        // IBM findings deduplicated by rule ID, sorted by severity
        Map<String, IbmFinding> dedupIbm = new LinkedHashMap<>();
        ibmFindingsByComponent.values().stream().flatMap(List::stream)
                .forEach(f -> dedupIbm.putIfAbsent(f.ruleId(), f));

        List<IbmFinding> sortedIbm = dedupIbm.values().stream()
                .sorted(Comparator.comparingInt(f -> severityOrder(f.severity())))
                .collect(Collectors.toList());

        for (IbmFinding f : sortedIbm) {
            Row row = sheet.createRow(r++);
            CellStyle sev = severityCellStyle(wb, s, f.severity());
            row.createCell(0).setCellValue("Java 21 (IBM)");
            row.createCell(1).setCellValue(f.ruleId());
            Cell sevCell = row.createCell(2);
            sevCell.setCellValue(f.severity());
            if (sev != null) sevCell.setCellStyle(sev);
            Cell desc = row.createCell(3);
            desc.setCellValue(f.ruleTitle());
            desc.setCellStyle(wrap);
            Cell action = row.createCell(4);
            action.setCellValue(
                    "Search IBM WAMT docs for rule ID: " + f.ruleId() + "\n"
                    + "Visit: https://www.ibm.com/docs/en/wamt\n"
                    + "Fix each class listed in the '☕ Java 21 Issues' sheet.");
            action.setCellStyle(wrap);
            row.createCell(5).setCellValue("☐");
            row.setHeightInPoints(40);
        }

        // Library findings deduplicated by class + method, sorted by severity
        Map<String, LibraryUpgradeAnalyzer.Finding> dedupSp = new LinkedHashMap<>();
        spring.getFindingsByJar().values().stream().flatMap(List::stream)
                .forEach(f -> dedupSp.putIfAbsent(f.deprecatedClass() + "#" + f.methodName(), f));

        List<LibraryUpgradeAnalyzer.Finding> sortedSp = dedupSp.values().stream()
                .sorted(Comparator.comparingInt(f -> severityOrder(f.severity())))
                .collect(Collectors.toList());

        for (LibraryUpgradeAnalyzer.Finding f : sortedSp) {
            Row row = sheet.createRow(r++);
            CellStyle sev = severityCellStyle(wb, s, f.severity());
            row.createCell(0).setCellValue(f.library());
            String api = (f.methodName() != null && !f.methodName().isBlank())
                    ? f.deprecatedClass() + "#" + f.methodName() : f.deprecatedClass();
            row.createCell(1).setCellValue(api);
            Cell sevCell = row.createCell(2);
            sevCell.setCellValue(f.severity());
            if (sev != null) sevCell.setCellStyle(sev);
            Cell desc = row.createCell(3);
            desc.setCellValue(f.description());
            desc.setCellStyle(wrap);
            Cell action = row.createCell(4);
            action.setCellValue(f.replacement());
            action.setCellStyle(wrap);
            row.createCell(5).setCellValue("☐");
            row.setHeightInPoints(36);
        }

        if (sortedIbm.isEmpty() && sortedSp.isEmpty()) {
            sheet.createRow(r).createCell(0)
                    .setCellValue("✅  No issues found — nothing to remediate. Your codebase looks good!");
        }
    }

    // ── Sheet 6: Excluded Rules ───────────────────────────────────────────────

    private void writeExcludedRulesSheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("🚫 Excluded Rules");
        sheet.setColumnWidth(0, 46 * 256);
        sheet.setColumnWidth(1, 75 * 256);

        int r = 0;

        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue("Excluded Rules — Filtered From This Report");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

        Row inst = sheet.createRow(r++);
        Cell ic = inst.createCell(0);
        ic.setCellValue(
                "These rules were suppressed because they are informational or low-risk. "
                + "To re-enable a rule: remove it from upgrade-excluded-rules.txt (next to the JAR) and re-run. "
                + "To exclude additional rules: add the IBM WAMT rule ID (one per line) to that file.");
        ic.setCellStyle(infoBarStyle(wb));
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 1));
        inst.setHeightInPoints(40);
        r++;

        Row hdr = sheet.createRow(r++);
        cellH(hdr, s.colHeader, 0, "Excluded Rule ID  (" + excludedRules.size() + " total)");
        cellH(hdr, s.colHeader, 1, "Reason for Exclusion");

        // Human-readable reasons for the 16 IBM TA defaults
        Map<String, String> reasons = Map.ofEntries(
                Map.entry("CLDRLocaleDataByDefault",
                        "Informational — CLDR locale data behaviour change; low risk for most apps"),
                Map.entry("RemovedTransactionModule",
                        "Informational — Only affects apps that directly import JTA from the JDK module"),
                Map.entry("RemovedJavaXMLWSModuleNotProvided",
                        "Informational — JAX-WS is provided by the application server, not the JDK"),
                Map.entry("RemovedJaxBModuleNotProvided",
                        "Informational — JAXB is provided by the application server, not the JDK"),
                Map.entry("DetectCorbaJava",
                        "Informational — CORBA removed from JDK; only relevant if app uses CORBA directly"),
                Map.entry("URLClassLoaderArrayContainsNull",
                        "Informational — Only relevant if app dynamically loads classes via URLClassLoader"),
                Map.entry("DetectJaxApiRawTypeMethods",
                        "Informational — Raw-type JAX API usage; low risk for standard EE usage"),
                Map.entry("RunJDeps",
                        "Informational — Tool recommendation to run jdeps; not a code-level issue"),
                Map.entry("Java11GeneralInfoAndPotentialIssues",
                        "Informational — General Java 11 migration advisory; not a specific code issue"),
                Map.entry("Java17GeneralInfoAndPotentialIssues",
                        "Informational — General Java 17 migration advisory; not a specific code issue"),
                Map.entry("Java21GeneralInfoAndPotentialIssues",
                        "Informational — General Java 21 migration advisory; not a specific code issue"),
                Map.entry("DefaultCharsetPrintWriter",
                        "Informational — PrintWriter now uses UTF-8 by default; low risk if app uses explicit charset"),
                Map.entry("jre17RegexBehaviorChange",
                        "Informational — Minor regex edge-case change in JDK 17; low risk for standard patterns"),
                Map.entry("DetectWeakNamedCurves",
                        "Informational — Security advisory for weak EC named curves; review separately"),
                Map.entry("DeprecatedPrimitiveClassConstructors",
                        "Informational — Deprecated in Java 9; use factory methods (e.g. Integer.valueOf()). "
                        + "Not removed in Java 21, but plan to update."),
                Map.entry("jre17EJBRemotePotentialIssues",
                        "Informational — EJB remote potential issues already covered by the wl-jboss module")
        );

        CellStyle wrap = wrapStyle(wb);
        List<String> sorted = new ArrayList<>(excludedRules);
        sorted.sort(String::compareToIgnoreCase);

        for (String rule : sorted) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(rule);
            Cell rc = row.createCell(1);
            rc.setCellValue(reasons.getOrDefault(rule,
                    "Excluded via upgrade-excluded-rules.txt"));
            rc.setCellStyle(wrap);
        }

        if (excludedRules.isEmpty()) {
            sheet.createRow(r).createCell(0).setCellValue("No rules are currently excluded.");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String findJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path java = Path.of(javaHome, "bin",
                    System.getProperty("os.name", "").toLowerCase().contains("win")
                            ? "java.exe" : "java");
            if (Files.exists(java)) return java.toString();
        }
        return "java";
    }

    private void deleteDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder())
                          .map(Path::toFile)
                          .forEach(File::delete);
                }
            }
        } catch (IOException ignored) {}
    }

    private static int severityOrder(String sev) {
        return switch (sev == null ? "" : sev) {
            case "CRITICAL" -> 0;
            case "HIGH"     -> 1;
            case "MEDIUM"   -> 2;
            case "LOW"      -> 3;
            default         -> 4;
        };
    }

    private static final Map<String, IndexedColors> SEV_COLORS = Map.of(
            "CRITICAL", IndexedColors.ROSE,
            "HIGH",     IndexedColors.LIGHT_ORANGE,
            "MEDIUM",   IndexedColors.LIGHT_YELLOW,
            "LOW",      IndexedColors.LIGHT_GREEN
    );

    private final Map<String, CellStyle> sevStyleCache = new HashMap<>();

    private CellStyle severityCellStyle(Workbook wb, Styles s, String severity) {
        if (!SEV_COLORS.containsKey(severity)) return null;
        return sevStyleCache.computeIfAbsent(severity, sev -> {
            CellStyle cs = wb.createCellStyle();
            cs.setFillForegroundColor(SEV_COLORS.get(sev).getIndex());
            cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font f = wb.createFont();
            f.setBold("CRITICAL".equals(sev) || "HIGH".equals(sev));
            cs.setFont(f);
            return cs;
        });
    }

    private static CellStyle infoBarStyle(Workbook wb) {
        CellStyle cs = wb.createCellStyle();
        cs.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        cs.setWrapText(true);
        cs.setVerticalAlignment(VerticalAlignment.CENTER);
        return cs;
    }

    private static CellStyle wrapStyle(Workbook wb) {
        CellStyle cs = wb.createCellStyle();
        cs.setWrapText(true);
        cs.setVerticalAlignment(VerticalAlignment.TOP);
        return cs;
    }

    private static void cellH(Row row, CellStyle style, int col, String value) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    // ── Styles ────────────────────────────────────────────────────────────────

    private static class Styles {
        final CellStyle title;
        final CellStyle colHeader;
        final CellStyle sectionHeader;
        final CellStyle jarHeader;

        Styles(Workbook wb) {
            title = wb.createCellStyle();
            Font tf = wb.createFont();
            tf.setBold(true);
            tf.setFontHeightInPoints((short) 14);
            title.setFont(tf);

            colHeader = ExcelUtils.createHeaderStyle(wb);

            sectionHeader = wb.createCellStyle();
            sectionHeader.setFillForegroundColor(IndexedColors.CORNFLOWER_BLUE.getIndex());
            sectionHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font sf = wb.createFont();
            sf.setBold(true);
            sf.setColor(IndexedColors.WHITE.getIndex());
            sectionHeader.setFont(sf);

            jarHeader = wb.createCellStyle();
            jarHeader.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
            jarHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font jf = wb.createFont();
            jf.setBold(true);
            jf.setColor(IndexedColors.WHITE.getIndex());
            jarHeader.setFont(jf);
        }
    }
}
