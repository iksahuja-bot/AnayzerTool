package effortanalyzer.library;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import effortanalyzer.util.ExcelUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Library Upgrade Compatibility Analyzer.
 *
 * Scans JARs for deprecated/removed APIs when upgrading to:
 *   - Spring Framework 5.3.39  (from any 3.x / 4.x / 5.x)
 *   - Guava 31.1-jre           (from any prior version)
 *   - Guice 5.1.0              (from 3.x / 4.x)
 *   - Jersey 2.22.2            (from 1.x — complete API rewrite)
 *   - Replacing CGLib with ByteBuddy
 *
 * Rule definitions live in individual per-library classes:
 *   {@link SpringRules}, {@link GuavaRules}, {@link GuiceRules},
 *   {@link CglibRules}, {@link JerseyRules}
 *
 * All rules are aggregated by {@link LibraryUpgradeRules}.
 *
 * Output: multi-sheet Excel report (Findings + Summary).
 */
public class LibraryUpgradeAnalyzer {

    private static final Logger logger = LogManager.getLogger(LibraryUpgradeAnalyzer.class);

    private final LibraryUpgradeRules rules;
    private final List<CompiledApi> compiledApis;
    private final Map<String, List<Finding>> findings;

    /** Deduplication key: jarName + fileName + ruleKey → one entry per (file, rule). */
    private final Set<String> seen = new HashSet<>();

    /** Loads all library rules with no exclusions. */
    public LibraryUpgradeAnalyzer() {
        this(Set.of());
    }

    /**
     * Constructor used by {@code UpgradeAnalyzer} to pass a pre-loaded exclusion set.
     * Excluded library names or class patterns are filtered out before scanning begins.
     */
    public LibraryUpgradeAnalyzer(Set<String> excluded) {
        this.rules = LibraryUpgradeRules.load(excluded);
        this.findings = new LinkedHashMap<>();
        this.compiledApis = rules.getRules().stream()
                .map(CompiledApi::of)
                .toList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Analysis
    // ══════════════════════════════════════════════════════════════════════════

    public void analyze(String targetPath) throws IOException {
        Path path = Paths.get(targetPath);
        if (!Files.exists(path)) throw new FileNotFoundException("Path not found: " + targetPath);

        List<Path> jarFiles = findJarFiles(path);
        logger.info("Found {} JAR/WAR/EAR files to analyze", jarFiles.size());

        for (Path jarFile : jarFiles) {
            analyzeJar(jarFile);
        }

        logger.info("Analysis complete. Total findings: {}",
                findings.values().stream().mapToInt(List::size).sum());
    }

    private List<Path> findJarFiles(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            String name = path.toString().toLowerCase();
            if (name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear")) {
                return List.of(path);
            }
            return List.of();
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.walk(path)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.toString().toLowerCase();
                            return n.endsWith(".jar") || n.endsWith(".war") || n.endsWith(".ear");
                        })
                        .toList();
            }
        }
        return List.of();
    }

    private void analyzeJar(Path jarPath) {
        logger.info("Analyzing: {}", jarPath.getFileName());
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.endsWith(".java")) {
                    analyzeSourceFile(jar, entry, jarPath);
                } else if (name.endsWith(".class")) {
                    analyzeClassFile(jar, entry, jarPath);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to analyze JAR: {}", jarPath, e);
        }
    }

    private void analyzeSourceFile(JarFile jar, JarEntry entry, Path jarPath) {
        try (InputStream is = jar.getInputStream(entry)) {
            List<String> lines = new java.io.BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.toList());

            // First pass: build simple-name → FQN map from explicit (non-wildcard, non-static) imports.
            // Used to reject simple-name matches when the name is already bound to a different class.
            Map<String, String> importedSimpleNames = new java.util.HashMap<>();
            for (String l : lines) {
                String t = l.trim();
                if (t.startsWith("import ")
                        && !t.startsWith("import static ")
                        && !t.contains("*")) {
                    String fqn = t.substring("import ".length()).replace(";", "").trim();
                    int dot = fqn.lastIndexOf('.');
                    if (dot >= 0) {
                        importedSimpleNames.put(fqn.substring(dot + 1), fqn);
                    }
                }
            }

            // Second pass: line-by-line rule check with import context.
            int lineNumber = 0;
            for (String line : lines) {
                lineNumber++;
                checkLineForDeprecations(line, lineNumber, entry.getName(), jarPath, importedSimpleNames);
            }
        } catch (IOException e) {
            logger.debug("Failed to read source file: {}", entry.getName(), e);
        }
    }

    /**
     * Analyzes a class file by scanning the constant-pool region as Latin-1 text.
     * Both dot-form (java.util.List) and slash-form (java/util/List) are tried.
     * Only one finding per (file, rule) pair is recorded.
     */
    private void analyzeClassFile(JarFile jar, JarEntry entry, Path jarPath) {
        try (InputStream is = jar.getInputStream(entry)) {
            String content = new String(is.readAllBytes(), StandardCharsets.ISO_8859_1);
            for (CompiledApi ca : compiledApis) {
                if (bytecodeContains(content, ca)) {
                    addFinding(jarPath, entry.getName(), -1, ca.api(),
                            "Reference found in bytecode (constant pool)");
                }
            }
        } catch (IOException e) {
            logger.debug("Failed to read class file: {}", entry.getName(), e);
        }
    }

    /**
     * Returns true when the bytecode content references this rule's class/package.
     *
     * Matching strategy:
     *  1. Slash-form used in JVM descriptors  (net/sf/cglib/proxy/Dispatcher)
     *  2. Dot-form as it may appear in string constants (net.sf.cglib.proxy.Dispatcher)
     *
     * When a methodName filter is present the method name must ALSO appear in the
     * bytecode. The method name is never checked in isolation.
     */
    private static boolean bytecodeContains(String content, CompiledApi ca) {
        boolean contextFound = content.contains(ca.slashForm()) || content.contains(ca.api().className());
        if (!contextFound) return false;
        return ca.api().methodName() == null || content.contains(ca.api().methodName());
    }

    /**
     * Checks a single source line against all rules using precompiled patterns.
     * Package-prefix rules are matched against import statements only.
     * Class-level rules also check for simple-name usage in code.
     * Method-level rules look for call-site patterns.
     *
     * @param importedSimpleNames map of simple class name → FQN for every explicit import in
     *                            the file being scanned; used to suppress simple-name matches
     *                            when that name is already bound to a different (non-deprecated) class.
     */
    private void checkLineForDeprecations(String line, int lineNumber, String fileName, Path jarPath,
                                          Map<String, String> importedSimpleNames) {
        String trimmed = line.trim();
        boolean isImport = trimmed.startsWith("import ");

        for (CompiledApi ca : compiledApis) {
            String cls = ca.api().className();

            if (ca.isPackageRule()) {
                String pkg = cls.replace(".*", "");
                if (isImport && trimmed.contains(pkg)) {
                    addFinding(jarPath, fileName, lineNumber, ca.api(), "Package import: " + trimmed);
                }
                continue;
            }

            if (isImport) {
                // Import lines: only a full-class-name match is valid.
                // Simple-name checks are never applied to import lines.
                if (trimmed.contains(cls)) {
                    addFinding(jarPath, fileName, lineNumber, ca.api(), "Import: " + trimmed);
                }
                continue;
            }

            if (ca.simpleNamePattern() != null) {
                // If the file imports a *different* class with the same simple name, any
                // usage of that name in code refers to the other class, not the deprecated one.
                // E.g. "import com.google.gwt.core.client.Callback" binds the name "Callback"
                // so it cannot also mean "net.sf.cglib.proxy.Callback".
                String simpleName = cls.substring(cls.lastIndexOf('.') + 1);
                String mappedFqn  = importedSimpleNames.get(simpleName);
                if (mappedFqn != null && !mappedFqn.equals(cls)) {
                    continue;
                }

                java.util.regex.Matcher m = ca.simpleNamePattern().matcher(trimmed);
                boolean outsideString = false;
                while (m.find()) {
                    if (!isInsideStringLiteral(trimmed, m.start())) {
                        outsideString = true;
                        break;
                    }
                }
                if (outsideString) {
                    addFinding(jarPath, fileName, lineNumber, ca.api(), "Usage: " + trimmed);
                    continue;
                }
            }

            if (ca.methodPattern() != null && ca.methodPattern().matcher(trimmed).find()) {
                addFinding(jarPath, fileName, lineNumber, ca.api(), "Method call: " + trimmed);
            }
        }
    }

    /**
     * Returns true when the character at {@code position} in {@code line} is inside a
     * Java string literal (an odd number of unescaped double-quote characters precedes it).
     * Prevents false positives when a short class simple-name (e.g. {@code Form}) appears
     * as a natural word inside a string constant such as {@code "Form Factor"}.
     */
    private static boolean isInsideStringLiteral(String line, int position) {
        int quoteCount = 0;
        for (int i = 0; i < position; i++) {
            if (line.charAt(i) == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                quoteCount++;
            }
        }
        return (quoteCount % 2) != 0;
    }

    /**
     * Adds a finding, deduplicating per (jar, file, rule) so that a single file
     * does not generate hundreds of identical entries for the same removed package.
     */
    private void addFinding(Path jarPath, String fileName, int lineNumber,
                            DeprecatedApi api, String context) {
        String jarName   = jarPath.getFileName().toString();
        String dedupeKey = jarName + "|" + fileName + "|" + api.className() + "|" + api.methodName();

        if (lineNumber < 0 && !seen.add(dedupeKey)) return;
        if (lineNumber >= 0 && !seen.add(dedupeKey + "|L" + lineNumber)) return;

        findings.computeIfAbsent(jarName, k -> new ArrayList<>()).add(new Finding(
                jarName, fileName, lineNumber,
                api.library(), api.className(), api.methodName(),
                api.severity(), api.replacement(), api.description(), context
        ));
        logger.debug("Found: {} [{}] in {} ({}:{})",
                api.className(), api.library(), jarName, fileName, lineNumber);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Precompiled rule wrapper — patterns compiled once at startup
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Wraps a {@link DeprecatedApi} with patterns compiled at construction time.
     * Eliminates repeated {@code Pattern.compile()} calls during line-by-line scanning.
     */
    private record CompiledApi(
            DeprecatedApi api,
            boolean isPackageRule,
            String slashForm,
            Pattern simpleNamePattern,  // null when name is too short or this is a package rule
            Pattern methodPattern       // null when no methodName filter is defined
    ) {
        static CompiledApi of(DeprecatedApi api) {
            String cls = api.className();

            boolean isPkg = cls.endsWith(".*")
                    || !cls.contains(".")
                    || Character.isLowerCase(cls.charAt(cls.lastIndexOf('.') + 1));

            String slashForm = cls.replace('.', '/');

            Pattern simpleP = null;
            if (!isPkg) {
                String simple = cls.substring(cls.lastIndexOf('.') + 1);
                if (simple.length() >= 4) {
                    simpleP = Pattern.compile("\\b" + Pattern.quote(simple) + "\\b");
                }
            }

            Pattern methodP = api.methodName() != null
                    ? Pattern.compile("\\." + Pattern.quote(api.methodName()) + "\\s*\\(")
                    : null;

            return new CompiledApi(api, isPkg, slashForm, simpleP, methodP);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Report generation
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Generates a 5-sheet Excel report:
     *   1. Summary        – dashboard with metrics, severity breakdown, library & JAR counts
     *   2. All Findings   – every finding, color-coded by severity, with auto-filter
     *   3. CRITICAL       – critical findings only
     *   4. WARNING        – warning findings only
     *   5. INFO           – info findings only
     */
    public void generateReport(String outputFile) throws IOException {
        logger.info("Generating library upgrade report...");

        List<Finding> allFindings = findings.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        logger.info("Total findings: {}", allFindings.size());

        // Single pass to partition by severity — avoids three separate filter scans
        Map<String, List<Finding>> bySev = allFindings.stream()
                .collect(Collectors.groupingBy(Finding::severity));

        List<Finding> critical = bySev.getOrDefault("CRITICAL", List.of());
        List<Finding> warning  = bySev.getOrDefault("WARNING",  List.of());
        List<Finding> info     = bySev.getOrDefault("INFO",     List.of());

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            ReportStyles styles = new ReportStyles(wb);

            writeSummarySheet(wb, styles, allFindings);
            writeFindingsSheet(wb, styles, "All Findings", allFindings);
            writeFindingsSheet(wb, styles, "CRITICAL",     critical);
            writeFindingsSheet(wb, styles, "WARNING",      warning);
            writeFindingsSheet(wb, styles, "INFO",         info);

            Path out = Path.of(outputFile).toAbsolutePath();
            ExcelUtils.validateAndPrepareOutput(out, logger);
            try (FileOutputStream fos = new FileOutputStream(out.toFile())) {
                wb.write(fos);
            } catch (IOException e) {
                String detail = ExcelUtils.diagnoseWriteFailure(out, e);
                logger.error("Report write failed: {}", detail);
                throw new IOException(detail, e);
            }
            logger.info("Report written: {}", out);
        }
    }

    // ── Findings sheet ────────────────────────────────────────────────────────

    private static final List<String> FINDING_HEADERS = List.of(
            "#", "Severity", "Library", "JAR File", "Deprecated Class / Package",
            "Method", "Replacement", "Description", "File", "Line");

    private static final int[] COL_WIDTHS = {
            5  * 256,  // #
            12 * 256,  // Severity
            22 * 256,  // Library
            30 * 256,  // JAR File
            45 * 256,  // Deprecated Class
            22 * 256,  // Method
            45 * 256,  // Replacement
            55 * 256,  // Description
            40 * 256,  // File
             7 * 256   // Line
    };

    private void writeFindingsSheet(XSSFWorkbook wb, ReportStyles styles,
                                    String sheetName, List<Finding> data) {
        XSSFSheet sheet = wb.createSheet(sheetName);
        for (int i = 0; i < COL_WIDTHS.length; i++) sheet.setColumnWidth(i, COL_WIDTHS[i]);
        sheet.setColumnWidth(6, 55 * 256);
        sheet.setColumnWidth(7, 55 * 256);

        XSSFRow headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(20);
        for (int i = 0; i < FINDING_HEADERS.size(); i++) {
            XSSFCell c = headerRow.createCell(i);
            c.setCellValue(FINDING_HEADERS.get(i));
            c.setCellStyle(styles.columnHeader);
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, FINDING_HEADERS.size() - 1));

        if (data.isEmpty()) {
            XSSFCell c = sheet.createRow(1).createCell(0);
            c.setCellValue("No findings for this severity level.");
            c.setCellStyle(styles.noData);
            return;
        }

        int rowNum = 1;
        for (Finding f : data) {
            XSSFRow row = sheet.createRow(rowNum);
            row.setHeightInPoints(30);

            XSSFCellStyle rowBg   = styles.rowBg(f.severity());
            XSSFCellStyle rowWrap = styles.rowBgWrap(f.severity());
            XSSFCellStyle badge   = styles.severityBadge(f.severity());

            row.createCell(0).setCellValue(rowNum);                                         row.getCell(0).setCellStyle(rowBg);
            row.createCell(1).setCellValue(f.severity());                                   row.getCell(1).setCellStyle(badge);
            row.createCell(2).setCellValue(f.library());                                    row.getCell(2).setCellStyle(rowBg);
            row.createCell(3).setCellValue(f.jarName());                                    row.getCell(3).setCellStyle(rowBg);
            row.createCell(4).setCellValue(f.deprecatedClass());                            row.getCell(4).setCellStyle(rowBg);
            row.createCell(5).setCellValue(f.methodName() != null ? f.methodName() : "");  row.getCell(5).setCellStyle(rowBg);
            row.createCell(6).setCellValue(f.replacement());                                row.getCell(6).setCellStyle(rowWrap);
            row.createCell(7).setCellValue(f.description());                                row.getCell(7).setCellStyle(rowWrap);
            row.createCell(8).setCellValue(f.fileName());                                   row.getCell(8).setCellStyle(rowBg);
            row.createCell(9).setCellValue(f.lineNumber() > 0 ? String.valueOf(f.lineNumber()) : "—"); row.getCell(9).setCellStyle(rowBg);

            rowNum++;
        }
    }

    // ── Summary / Dashboard sheet ─────────────────────────────────────────────

    private void writeSummarySheet(XSSFWorkbook wb, ReportStyles styles, List<Finding> all) {
        XSSFSheet sheet = wb.createSheet("Summary");
        sheet.setColumnWidth(0, 35 * 256);
        sheet.setColumnWidth(1, 18 * 256);
        sheet.setColumnWidth(2, 18 * 256);

        int r = 0;

        XSSFRow titleRow = sheet.createRow(r++);
        titleRow.setHeightInPoints(28);
        XSSFCell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Library Upgrade Compatibility Report");
        titleCell.setCellStyle(styles.reportTitle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

        XSSFRow subtitleRow = sheet.createRow(r++);
        subtitleRow.setHeightInPoints(16);
        XSSFCell sub = subtitleRow.createCell(0);
        sub.setCellValue("Libraries: Spring 5.3.39  |  Guava 31.1-jre  |  Guice 5.1.0  |  Jersey 2.22.2  |  CGLib → ByteBuddy");
        sub.setCellStyle(styles.subtitle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 2));
        r++;

        r = writeSectionHeader(sheet, styles, r, "Overview", 3);
        r = writeSummaryRow(sheet, styles, r, "Total Findings",     all.size(),      styles.metricNeutral);
        r = writeSummaryRow(sheet, styles, r, "JARs with Findings", findings.size(), styles.metricNeutral);
        r = writeSummaryRow(sheet, styles, r, "JARs Scanned",       findings.size(), styles.metricNeutral);
        r++;

        // Single groupBy for severity counts — reuse the same map for both the
        // bar chart display and the numeric column (avoids 3 separate filter scans)
        r = writeSectionHeader(sheet, styles, r, "Findings by Severity", 3);
        Map<String, Long> bySev = all.stream()
                .collect(Collectors.groupingBy(Finding::severity, Collectors.counting()));

        for (String sev : List.of("CRITICAL", "WARNING", "INFO")) {
            long count = bySev.getOrDefault(sev, 0L);
            int barCols = all.isEmpty() ? 0 : (int) Math.round((count * 20.0) / all.size());

            XSSFRow row = sheet.createRow(r++);
            row.setHeightInPoints(18);
            XSSFCell label = row.createCell(0); label.setCellValue(sev);   label.setCellStyle(styles.severityBadge(sev));
            XSSFCell cnt   = row.createCell(1); cnt.setCellValue(count);   cnt.setCellStyle(styles.rowBg(sev));
            XSSFCell bar   = row.createCell(2); bar.setCellValue("█".repeat(Math.max(0, barCols))); bar.setCellStyle(styles.rowBg(sev));
        }
        r++;

        r = writeSectionHeader(sheet, styles, r, "Findings by Library", 3);
        Map<String, Long> byLib = all.stream()
                .collect(Collectors.groupingBy(Finding::library, LinkedHashMap::new, Collectors.counting()));
        for (var e : byLib.entrySet()) {
            XSSFRow row = sheet.createRow(r++);
            row.setHeightInPoints(16);
            XSSFCell label = row.createCell(0); label.setCellValue(e.getKey());   label.setCellStyle(styles.dataLabel);
            XSSFCell count = row.createCell(1); count.setCellValue(e.getValue()); count.setCellStyle(styles.dataCount);
        }
        r++;

        if (!findings.isEmpty()) {
            r = writeSectionHeader(sheet, styles, r, "Findings by JAR File", 3);
            findings.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                    .forEach(e -> {
                        XSSFRow row = sheet.createRow(sheet.getLastRowNum() + 1);
                        row.setHeightInPoints(16);
                        XSSFCell label = row.createCell(0); label.setCellValue(e.getKey());          label.setCellStyle(styles.dataLabel);
                        XSSFCell count = row.createCell(1); count.setCellValue(e.getValue().size()); count.setCellStyle(styles.dataCount);
                    });
        }
    }

    private int writeSectionHeader(XSSFSheet sheet, ReportStyles styles, int r, String title, int spanCols) {
        XSSFRow row = sheet.createRow(r);
        row.setHeightInPoints(18);
        XSSFCell cell = row.createCell(0);
        cell.setCellValue(title);
        cell.setCellStyle(styles.sectionHeader);
        if (spanCols > 1) sheet.addMergedRegion(new CellRangeAddress(r, r, 0, spanCols - 1));
        return r + 1;
    }

    private int writeSummaryRow(XSSFSheet sheet, ReportStyles styles, int r,
                                String label, long value, XSSFCellStyle valueStyle) {
        XSSFRow row = sheet.createRow(r);
        row.setHeightInPoints(16);
        XSSFCell lbl = row.createCell(0); lbl.setCellValue(label); lbl.setCellStyle(styles.dataLabel);
        XSSFCell val = row.createCell(1); val.setCellValue(value);  val.setCellStyle(valueStyle);
        return r + 1;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Style factory — all styles pre-built once per workbook
    // ══════════════════════════════════════════════════════════════════════════

    private static final class ReportStyles {

        private static final byte[] CLR_CRITICAL_BG = rgb(255, 199, 206);
        private static final byte[] CLR_WARNING_BG  = rgb(255, 235, 156);
        private static final byte[] CLR_INFO_BG      = rgb(189, 215, 238);
        private static final byte[] CLR_CRITICAL_FG = rgb(156,   0,   6);
        private static final byte[] CLR_INFO_FG      = rgb(  0,  70, 127);
        private static final byte[] CLR_HEADER_BG   = rgb( 31,  73, 125);
        private static final byte[] CLR_SECTION_BG  = rgb( 68, 114, 196);
        private static final byte[] CLR_TITLE_BG    = rgb( 17,  86, 163);
        private static final byte[] CLR_WHITE        = rgb(255, 255, 255);
        private static final byte[] CLR_DARK_TEXT    = rgb( 50,  50,  50);
        private static final byte[] CLR_NEUTRAL_BG  = rgb(217, 225, 242);

        final XSSFCellStyle reportTitle;
        final XSSFCellStyle subtitle;
        final XSSFCellStyle columnHeader;
        final XSSFCellStyle sectionHeader;
        final XSSFCellStyle dataLabel;
        final XSSFCellStyle dataCount;
        final XSSFCellStyle metricNeutral;
        final XSSFCellStyle noData;

        private final Map<String, XSSFCellStyle> rowBgCache     = new HashMap<>();
        private final Map<String, XSSFCellStyle> rowBgWrapCache = new HashMap<>();
        private final Map<String, XSSFCellStyle> badgeCache     = new HashMap<>();

        ReportStyles(XSSFWorkbook wb) {
            reportTitle   = makeTitle(wb);
            subtitle      = makeSubtitle(wb);
            columnHeader  = makeColumnHeader(wb);
            sectionHeader = makeSectionHeader(wb);
            dataLabel     = makeDataLabel(wb);
            dataCount     = makeDataCount(wb);
            metricNeutral = makeMetricNeutral(wb);
            noData        = makeNoData(wb);

            for (String sev : List.of("CRITICAL", "WARNING", "INFO")) {
                rowBgCache    .put(sev, makeRowBg(wb, sev, false));
                rowBgWrapCache.put(sev, makeRowBg(wb, sev, true));
                badgeCache    .put(sev, makeBadge(wb, sev));
            }
        }

        XSSFCellStyle rowBg(String severity)     { return rowBgCache    .getOrDefault(severity, rowBgCache.get("INFO")); }
        XSSFCellStyle rowBgWrap(String severity) { return rowBgWrapCache.getOrDefault(severity, rowBgWrapCache.get("INFO")); }
        XSSFCellStyle severityBadge(String sev)  { return badgeCache    .getOrDefault(sev, badgeCache.get("INFO")); }

        private static XSSFCellStyle makeTitle(XSSFWorkbook wb) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setFillForegroundColor(color(CLR_TITLE_BG));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setAlignment(HorizontalAlignment.CENTER);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            XSSFFont f = wb.createFont(); f.setBold(true); f.setFontHeightInPoints((short) 16);
            f.setColor(color(CLR_WHITE)); s.setFont(f);
            return s;
        }

        private static XSSFCellStyle makeSubtitle(XSSFWorkbook wb) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setFillForegroundColor(color(CLR_SECTION_BG));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setAlignment(HorizontalAlignment.CENTER);
            XSSFFont f = wb.createFont(); f.setItalic(true); f.setFontHeightInPoints((short) 10);
            f.setColor(color(CLR_WHITE)); s.setFont(f);
            return s;
        }

        private static XSSFCellStyle makeColumnHeader(XSSFWorkbook wb) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setFillForegroundColor(color(CLR_HEADER_BG));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setAlignment(HorizontalAlignment.CENTER);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(s, BorderStyle.THIN);
            XSSFFont f = wb.createFont(); f.setBold(true); f.setFontHeightInPoints((short) 11);
            f.setColor(color(CLR_WHITE)); s.setFont(f);
            return s;
        }

        private static XSSFCellStyle makeSectionHeader(XSSFWorkbook wb) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setFillForegroundColor(color(CLR_SECTION_BG));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            setBorder(s, BorderStyle.THIN);
            XSSFFont f = wb.createFont(); f.setBold(true); f.setFontHeightInPoints((short) 11);
            f.setColor(color(CLR_WHITE)); s.setFont(f);
            return s;
        }

        private static XSSFCellStyle makeDataLabel(XSSFWorkbook wb) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorderBottom(s, BorderStyle.HAIR);
            XSSFFont f = wb.createFont(); f.setFontHeightInPoints((short) 10);
            f.setColor(color(CLR_DARK_TEXT)); s.setFont(f);
            return s;
        }

        private static XSSFCellStyle makeDataCount(XSSFWorkbook wb) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setAlignment(HorizontalAlignment.CENTER);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorderBottom(s, BorderStyle.HAIR);
            XSSFFont f = wb.createFont(); f.setBold(true); f.setFontHeightInPoints((short) 10);
            f.setColor(color(CLR_DARK_TEXT)); s.setFont(f);
            return s;
        }

        private static XSSFCellStyle makeMetricNeutral(XSSFWorkbook wb) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setFillForegroundColor(color(CLR_NEUTRAL_BG));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setAlignment(HorizontalAlignment.CENTER);
            setBorder(s, BorderStyle.HAIR);
            XSSFFont f = wb.createFont(); f.setBold(true); f.setFontHeightInPoints((short) 10); s.setFont(f);
            return s;
        }

        private static XSSFCellStyle makeNoData(XSSFWorkbook wb) {
            XSSFCellStyle s = wb.createCellStyle();
            XSSFFont f = wb.createFont(); f.setItalic(true); f.setFontHeightInPoints((short) 10); s.setFont(f);
            return s;
        }

        private static XSSFCellStyle makeRowBg(XSSFWorkbook wb, String sev, boolean wrap) {
            XSSFCellStyle s = wb.createCellStyle();
            byte[] bg = switch (sev) {
                case "CRITICAL" -> CLR_CRITICAL_BG;
                case "WARNING"  -> CLR_WARNING_BG;
                default         -> CLR_INFO_BG;
            };
            s.setFillForegroundColor(color(bg));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setVerticalAlignment(VerticalAlignment.TOP);
            setBorderBottom(s, BorderStyle.HAIR);
            if (wrap) s.setWrapText(true);
            XSSFFont f = wb.createFont(); f.setFontHeightInPoints((short) 10);
            f.setColor(color(CLR_DARK_TEXT)); s.setFont(f);
            return s;
        }

        private static XSSFCellStyle makeBadge(XSSFWorkbook wb, String sev) {
            XSSFCellStyle s = wb.createCellStyle();
            byte[] bg = switch (sev) {
                case "CRITICAL" -> CLR_CRITICAL_FG;
                case "WARNING"  -> rgb(204, 102, 0);
                default         -> CLR_INFO_FG;
            };
            s.setFillForegroundColor(color(bg));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setAlignment(HorizontalAlignment.CENTER);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(s, BorderStyle.THIN);
            XSSFFont f = wb.createFont(); f.setBold(true); f.setFontHeightInPoints((short) 10);
            f.setColor(color(CLR_WHITE)); s.setFont(f);
            return s;
        }

        private static byte[] rgb(int r, int g, int b) { return new byte[]{(byte) r, (byte) g, (byte) b}; }
        private static XSSFColor color(byte[] rgb)      { return new XSSFColor(rgb, null); }

        private static void setBorder(XSSFCellStyle s, BorderStyle bs) {
            s.setBorderTop(bs); s.setBorderBottom(bs);
            s.setBorderLeft(bs); s.setBorderRight(bs);
        }

        private static void setBorderBottom(XSSFCellStyle s, BorderStyle bs) {
            s.setBorderBottom(bs);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Public API for UpgradeAnalyzer integration
    // ══════════════════════════════════════════════════════════════════════════

    /** Returns all library findings grouped by JAR — consumed by {@code UpgradeAnalyzer}. */
    public Map<String, List<Finding>> getFindingsByJar() {
        return Collections.unmodifiableMap(findings);
    }

    public int getActiveRuleCount() {
        return rules.getRules().size();
    }

    /** A single finding: one deprecated API usage in one JAR entry. */
    public record Finding(
            String jarName,
            String fileName,
            int lineNumber,
            String library,
            String deprecatedClass,
            String methodName,
            String severity,
            String replacement,
            String description,
            String context
    ) {}
}
