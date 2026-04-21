package effortanalyzer.java21;

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
import java.util.jar.*;
import java.util.stream.*;

/**
 * Java 8 → Java 21 Upgrade Compatibility Analyzer.
 *
 * Inspired by the IBM Migration Toolkit for Application Binaries (WAMT).
 *
 * Scans a directory of JARs (or a single JAR/WAR/EAR) for:
 *   - APIs removed in Java 9–21 (JEP 320, JEP 407, JEP 398, JEP 372 …)
 *   - JDK internal API usage (sun.*, jdk.internal.*, com.sun.* internals)
 *   - APIs deprecated-for-removal (Thread.stop, SecurityManager, finalize …)
 *   - Behavioural changes (default charset, TLS, reflection encapsulation …)
 *   - JVM flags removed/renamed (PermSize, UseConcMarkSweepGC …)
 *   - Module system access issues (URLClassLoader cast, add-opens …)
 *
 * Produces a multi-sheet Excel workbook:
 *   Sheet 1 – Summary       Severity + category breakdown, JAR counts
 *   Sheet 2 – All Findings  Full detail: one row per (JAR × rule) hit
 *   Sheet 3 – Checklist     Deduplicated action list sorted by severity
 *   Sheet 4 – Rule Catalog  Every rule with IBM-aligned ID, category, and remediation
 */
public class Java21Analyzer {

    private static final Logger logger = LogManager.getLogger(Java21Analyzer.class);

    private final Java21Rules rules;

    /** jar name → list of findings */
    private final Map<String, List<Finding>> findingsByJar = new LinkedHashMap<>();

    /** Deduplication: jarName + ruleId → prevent multiple hits from the same class */
    private final Set<String> seen = new HashSet<>();

    private int totalJarsScanned = 0;
    private int totalClassesScanned = 0;

    public Java21Analyzer() {
        this.rules = Java21Rules.load();
        logger.info("Loaded {} Java 21 compatibility rules", rules.getRules().size());
    }

    /**
     * Constructor used by {@code UpgradeAnalyzer} to pass a pre-loaded exclusion set.
     * Excluded rules are filtered out before scanning begins.
     */
    public Java21Analyzer(Set<String> excluded) {
        this.rules = Java21Rules.load(excluded);
        logger.info("Loaded {} Java 21 compatibility rules ({} exclusions applied)",
                rules.getRules().size(), excluded.size());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    public void analyze(String targetPath) throws IOException {
        Path path = Path.of(targetPath);
        if (!Files.exists(path)) throw new FileNotFoundException("Path not found: " + targetPath);

        List<Path> jars = collectJars(path);
        logger.info("Found {} JAR/WAR/EAR files to scan", jars.size());
        System.out.println("  Scanning " + jars.size() + " archive(s) for Java 21 compatibility issues...");

        for (Path jar : jars) {
            scanArchive(jar);
        }
        printSummary();
    }

    public void generateReport(String outputFile) throws IOException {
        Path outPath = Path.of(outputFile).toAbsolutePath();
        ExcelUtils.validateAndPrepareOutput(outPath, logger);

        try (Workbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);
            writeSummarySheet(wb, s);
            writeFindingsSheet(wb, s);
            writeChecklistSheet(wb, s);
            writeRuleCatalogSheet(wb, s);

            try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                wb.write(fos);
            } catch (IOException e) {
                String detail = ExcelUtils.diagnoseWriteFailure(outPath, e);
                logger.error("Report write failed: {}", detail);
                throw new IOException(detail, e);
            }
        }

        logger.info("Report written → {}", outPath);
        System.out.println("\nReport written → " + outPath);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scanning
    // ──────────────────────────────────────────────────────────────────────────

    private List<Path> collectJars(Path root) throws IOException {
        if (Files.isRegularFile(root)) return List.of(root);
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear");
            }).sorted().collect(Collectors.toList());
        }
    }

    private void scanArchive(Path archivePath) {
        String jarName = archivePath.getFileName().toString();
        logger.debug("Scanning: {}", jarName);

        try (JarFile jar = new JarFile(archivePath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.endsWith(".class")) {
                    totalClassesScanned++;
                    try (InputStream is = jar.getInputStream(entry)) {
                        byte[] bytes = is.readAllBytes();
                        scanBytecode(jarName, entryName, bytes);
                    } catch (IOException e) {
                        logger.warn("Could not read class {}: {}", entryName, e.getMessage());
                    }
                } else if (isTextEntry(entryName)) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        String content = new String(is.readAllBytes());
                        scanSourceText(jarName, entryName, content);
                    } catch (IOException e) {
                        logger.warn("Could not read entry {}: {}", entryName, e.getMessage());
                    }
                } else if (isNestedArchive(entryName)) {
                    // Scan nested JARs (e.g., WEB-INF/lib/*.jar inside a WAR)
                    try (InputStream is = jar.getInputStream(entry)) {
                        Path tmp = Files.createTempFile("ea-nested-", ".jar");
                        Files.write(tmp, is.readAllBytes());
                        scanArchive(tmp);
                        Files.deleteIfExists(tmp);
                    } catch (IOException e) {
                        logger.warn("Could not expand nested archive {}: {}", entryName, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Could not open archive {}: {}", jarName, e.getMessage());
            return;
        }
        totalJarsScanned++;
    }

    private boolean isTextEntry(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".properties") || lower.endsWith(".sh")
                || lower.endsWith(".bat") || lower.endsWith(".cmd")
                || lower.endsWith(".xml") || lower.endsWith(".conf")
                || lower.endsWith(".env") || lower.endsWith(".txt");
    }

    private boolean isNestedArchive(String name) {
        String lower = name.toLowerCase();
        return (lower.endsWith(".jar") || lower.endsWith(".war"))
                && !lower.contains("sources") && !lower.contains("javadoc");
    }

    private void scanBytecode(String jarName, String entryName, byte[] bytes) {
        String text = bytecodeConstantPoolAsText(bytes);
        for (Java21Rules.Rule rule : rules.getRules()) {
            if (rule.scanMode() == Java21Rules.ScanMode.SOURCE) continue;
            String pattern = rule.bytecodePattern(); // slash-separated
            if (text.contains(pattern) || text.contains(rule.apiPattern())) {
                recordFinding(jarName, entryName, rule, entryName);
            }
        }
    }

    private void scanSourceText(String jarName, String entryName, String content) {
        for (Java21Rules.Rule rule : rules.getRules()) {
            if (rule.scanMode() == Java21Rules.ScanMode.BYTECODE) continue;
            if (content.contains(rule.apiPattern())) {
                recordFinding(jarName, entryName, rule, entryName);
            }
        }
    }

    private void recordFinding(String jarName, String location, Java21Rules.Rule rule, String context) {
        String dedupKey = jarName + "|" + rule.id();
        if (!seen.add(dedupKey)) return;

        findingsByJar
                .computeIfAbsent(jarName, k -> new ArrayList<>())
                .add(new Finding(jarName, location, rule, context));
    }

    /**
     * Converts the raw class bytes to a text that includes the constant pool
     * strings — sufficient for pattern matching without a full bytecode parser.
     */
    private static String bytecodeConstantPoolAsText(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            char c = (char) (bytes[i] & 0xFF);
            if (c >= 32 && c < 127) sb.append(c);
            else if (c == 0)        sb.append('/'); // null bytes between utf8 strings
            else                    sb.append(' ');
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Console summary
    // ──────────────────────────────────────────────────────────────────────────

    private void printSummary() {
        List<Finding> all = allFindings();
        long critical = all.stream().filter(f -> "CRITICAL".equals(f.rule.severity())).count();
        long high     = all.stream().filter(f -> "HIGH".equals(f.rule.severity())).count();
        long medium   = all.stream().filter(f -> "MEDIUM".equals(f.rule.severity())).count();
        long info     = all.stream().filter(f -> "INFO".equals(f.rule.severity())).count();

        System.out.println();
        System.out.println("  ── Java 21 Compatibility Analysis Results ──────────────");
        System.out.printf("     JARs scanned:   %d%n", totalJarsScanned);
        System.out.printf("     Classes scanned: %d%n", totalClassesScanned);
        System.out.printf("     CRITICAL issues: %d%n", critical);
        System.out.printf("     HIGH issues:     %d%n", high);
        System.out.printf("     MEDIUM issues:   %d%n", medium);
        System.out.printf("     INFO items:      %d%n", info);
        System.out.printf("     Total findings:  %d%n", all.size());
        System.out.println("  ─────────────────────────────────────────────────────────");
    }

    private List<Finding> allFindings() {
        return findingsByJar.values().stream()
                .flatMap(Collection::stream)
                .sorted(Comparator.comparingInt(f -> severityOrder(f.rule.severity())))
                .collect(Collectors.toList());
    }

    private static int severityOrder(String sev) {
        return switch (sev) { case "CRITICAL" -> 0; case "HIGH" -> 1;
                               case "MEDIUM" -> 2; default -> 3; };
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Finding record
    // ──────────────────────────────────────────────────────────────────────────

    public record Finding(String jarName, String location, Java21Rules.Rule rule, String context) {} // already public

    /** Returns all findings grouped by JAR name — used by {@code UpgradeAnalyzer}. */
    public Map<String, List<Finding>> getFindingsByJar() {
        return Collections.unmodifiableMap(findingsByJar);
    }

    public int getTotalJarsScanned()    { return totalJarsScanned; }
    public int getTotalClassesScanned() { return totalClassesScanned; }
    public int getActiveRuleCount()     { return rules.getRules().size(); }
    public Java21Rules getRules()       { return rules; }

    // ──────────────────────────────────────────────────────────────────────────
    // Sheet 1 – Summary
    // ──────────────────────────────────────────────────────────────────────────

    private void writeSummarySheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("Summary");
        sheet.setColumnWidth(0, 14_000);
        sheet.setColumnWidth(1, 5_000);
        int r = 0;

        // Title
        row(sheet, r++, s.title, "Java 8 → Java 21 Upgrade Compatibility Report  [IBM WAMT-inspired]", null);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 2));
        row(sheet, r++, null, "Analysis date: " + LocalDate.now(), null);
        row(sheet, r++, null, "JARs scanned: " + totalJarsScanned
                + "    Classes scanned: " + totalClassesScanned, null);
        r++;

        // Severity breakdown
        List<Finding> all = allFindings();
        r = sectionHeader(sheet, s, r, "Issues by Severity");
        for (String sev : List.of("CRITICAL", "HIGH", "MEDIUM", "INFO")) {
            long cnt = all.stream().filter(f -> sev.equals(f.rule.severity())).count();
            Row row = sheet.createRow(r++);
            Cell lbl = row.createCell(0); lbl.setCellValue(sev); lbl.setCellStyle(severityStyle(wb, sev));
            row.createCell(1).setCellValue(cnt);
        }
        r++;

        // Category breakdown
        r = sectionHeader(sheet, s, r, "Issues by Category  (IBM WAMT taxonomy)");
        Row ch = sheet.createRow(r++);
        cellH(ch, s.header, 0, "Category");
        cellH(ch, s.header, 1, "Findings");
        cellH(ch, s.header, 2, "Description");

        Map<String, String> catDesc = Map.of(
                "JAVA_REMOVED",   "APIs removed in Java 9–21 (JEP 320, 398, 407, 372…)",
                "JAVA_INTERNAL",  "JDK internal APIs (sun.*, jdk.internal.*) – requires --add-opens",
                "JAVA_DEPRECATED","APIs deprecated-for-removal (Thread.stop, SecurityManager…)",
                "JAVA_BEHAVIOR",  "APIs with changed behaviour (default charset, TLS, serialization…)",
                "JVM_ARGS",       "JVM flags removed/renamed (PermSize, CMS GC, AggressiveOpts…)",
                "JDK_MODULES",    "Module system access issues (URLClassLoader cast, add-opens…)"
        );

        all.stream()
                .collect(Collectors.groupingBy(f -> f.rule.category(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> {
                    Row row = sheet.createRow(/* will be set */0);
                    // re-create with proper r below
                });

        // Proper pass
        var grouped = all.stream()
                .collect(Collectors.groupingBy(f -> f.rule.category(), Collectors.counting()));
        for (var e : grouped.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .toList()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(e.getKey());
            row.createCell(1).setCellValue(e.getValue());
            row.createCell(2).setCellValue(catDesc.getOrDefault(e.getKey(), "Custom rule category"));
        }
        r++;

        // Per-JAR breakdown
        r = sectionHeader(sheet, s, r, "JARs Analyzed");
        Row jh = sheet.createRow(r++);
        cellH(jh, s.header, 0, "JAR");
        cellH(jh, s.header, 1, "Total Issues");
        cellH(jh, s.header, 2, "Critical");

        for (var e : findingsByJar.entrySet()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(e.getKey());
            row.createCell(1).setCellValue(e.getValue().size());
            long crit = e.getValue().stream().filter(f -> "CRITICAL".equals(f.rule.severity())).count();
            row.createCell(2).setCellValue(crit);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sheet 2 – All Findings
    // ──────────────────────────────────────────────────────────────────────────

    private void writeFindingsSheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("All Findings");
        sheet.setColumnWidth(0, 3_000);   // Rule ID
        sheet.setColumnWidth(1, 5_000);   // Category
        sheet.setColumnWidth(2, 3_500);   // Severity
        sheet.setColumnWidth(3, 3_500);   // Java version
        sheet.setColumnWidth(4, 8_000);   // JAR
        sheet.setColumnWidth(5, 16_000);  // Description
        sheet.setColumnWidth(6, 18_000);  // What to do

        int r = 0;
        Row header = sheet.createRow(r++);
        cellH(header, s.header, 0, "Rule ID");
        cellH(header, s.header, 1, "Category");
        cellH(header, s.header, 2, "Severity");
        cellH(header, s.header, 3, "First Affected");
        cellH(header, s.header, 4, "Detected In (JAR)");
        cellH(header, s.header, 5, "Issue");
        cellH(header, s.header, 6, "What to Do (Remediation)");

        for (Finding f : allFindings()) {
            Row row = sheet.createRow(r++);
            row.setHeightInPoints(Math.max(30, f.rule.remediation().length() / 6f));
            row.createCell(0).setCellValue(f.rule.id());
            row.createCell(1).setCellValue(f.rule.category());
            Cell sevCell = row.createCell(2);
            sevCell.setCellValue(f.rule.severity());
            sevCell.setCellStyle(severityStyle(wb, f.rule.severity()));
            row.createCell(3).setCellValue(f.rule.versionLabel());
            row.createCell(4).setCellValue(f.jarName());

            Cell desc = row.createCell(5);
            desc.setCellValue(f.rule.description());
            CellStyle wrapStyle = wb.createCellStyle();
            wrapStyle.setWrapText(true);
            wrapStyle.setVerticalAlignment(VerticalAlignment.TOP);
            desc.setCellStyle(wrapStyle);

            Cell rem = row.createCell(6);
            rem.setCellValue(f.rule.remediation());
            rem.setCellStyle(wrapStyle);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sheet 3 – Checklist
    // ──────────────────────────────────────────────────────────────────────────

    private void writeChecklistSheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("Checklist");
        sheet.setColumnWidth(0, 2_000);   // done checkbox
        sheet.setColumnWidth(1, 3_000);   // Rule ID
        sheet.setColumnWidth(2, 5_000);   // Category
        sheet.setColumnWidth(3, 3_500);   // Severity
        sheet.setColumnWidth(4, 3_500);   // First affected Java
        sheet.setColumnWidth(5, 16_000);  // What to do
        sheet.setColumnWidth(6, 8_000);   // JARs affected

        int r = 0;
        Row hdr = sheet.createRow(r++);
        cellH(hdr, s.header, 0, "Done");
        cellH(hdr, s.header, 1, "Rule ID");
        cellH(hdr, s.header, 2, "Category");
        cellH(hdr, s.header, 3, "Severity");
        cellH(hdr, s.header, 4, "Since Java");
        cellH(hdr, s.header, 5, "What to Do");
        cellH(hdr, s.header, 6, "Affected JARs");

        // Deduplicate by rule ID, collect all affected JARs per rule
        Map<String, Set<String>> ruleJars = new LinkedHashMap<>();
        Map<String, Java21Rules.Rule> ruleMap = new LinkedHashMap<>();
        for (Finding f : allFindings()) {
            ruleJars.computeIfAbsent(f.rule.id(), k -> new LinkedHashSet<>()).add(f.jarName());
            ruleMap.put(f.rule.id(), f.rule);
        }

        CellStyle wrapTop = wb.createCellStyle();
        wrapTop.setWrapText(true);
        wrapTop.setVerticalAlignment(VerticalAlignment.TOP);

        for (var e : ruleJars.entrySet()) {
            Java21Rules.Rule rule = ruleMap.get(e.getKey());
            Row row = sheet.createRow(r++);
            row.setHeightInPoints(Math.max(30, rule.remediation().length() / 5f));
            row.createCell(0).setCellValue("☐");
            row.createCell(1).setCellValue(rule.id());
            row.createCell(2).setCellValue(rule.category());
            Cell sevCell = row.createCell(3);
            sevCell.setCellValue(rule.severity());
            sevCell.setCellStyle(severityStyle(wb, rule.severity()));
            row.createCell(4).setCellValue(rule.versionLabel());
            Cell remCell = row.createCell(5);
            remCell.setCellValue(rule.remediation());
            remCell.setCellStyle(wrapTop);
            row.createCell(6).setCellValue(String.join(", ", e.getValue()));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sheet 4 – Rule Catalog
    // ──────────────────────────────────────────────────────────────────────────

    private void writeRuleCatalogSheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("Rule Catalog");
        sheet.setColumnWidth(0, 2_800);   // Rule ID
        sheet.setColumnWidth(1, 4_500);   // Category
        sheet.setColumnWidth(2, 3_200);   // Severity
        sheet.setColumnWidth(3, 3_000);   // Since
        sheet.setColumnWidth(4, 3_500);   // Scan mode
        sheet.setColumnWidth(5, 12_000);  // Pattern
        sheet.setColumnWidth(6, 16_000);  // Description
        sheet.setColumnWidth(7, 18_000);  // Remediation

        int r = 0;

        // Title
        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue("Java 21 Compatibility Rule Catalog  —  IBM Migration Toolkit Inspired");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));

        Row note = sheet.createRow(r++);
        note.createCell(0).setCellValue(
                "Rules sourced from JDK release notes, JEP specifications, and IBM WAMT rule taxonomy. "
                + "Add custom rules by placing java21-custom-rules.txt next to the JAR.");
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 7));
        r++;

        // Header
        Row hdr = sheet.createRow(r++);
        cellH(hdr, s.header, 0, "Rule ID");
        cellH(hdr, s.header, 1, "Category");
        cellH(hdr, s.header, 2, "Severity");
        cellH(hdr, s.header, 3, "First Affected");
        cellH(hdr, s.header, 4, "Scan Mode");
        cellH(hdr, s.header, 5, "API Pattern");
        cellH(hdr, s.header, 6, "Issue Description");
        cellH(hdr, s.header, 7, "Remediation / What to Do");

        CellStyle wrapTop = wb.createCellStyle();
        wrapTop.setWrapText(true);
        wrapTop.setVerticalAlignment(VerticalAlignment.TOP);

        String currentCat = "";
        for (Java21Rules.Rule rule : rules.getRules()) {
            if (!rule.category().equals(currentCat)) {
                currentCat = rule.category();
                r = sectionHeader(sheet, s, r, categoryLabel(currentCat));
            }
            Row row = sheet.createRow(r++);
            row.setHeightInPoints(Math.max(28, rule.remediation().length() / 7f));
            row.createCell(0).setCellValue(rule.id());
            row.createCell(1).setCellValue(rule.category());
            Cell sev = row.createCell(2);
            sev.setCellValue(rule.severity());
            sev.setCellStyle(severityStyle(wb, rule.severity()));
            row.createCell(3).setCellValue(rule.versionLabel());
            row.createCell(4).setCellValue(rule.scanMode().name());
            row.createCell(5).setCellValue(rule.apiPattern());
            Cell desc = row.createCell(6);
            desc.setCellValue(rule.description());
            desc.setCellStyle(wrapTop);
            Cell rem = row.createCell(7);
            rem.setCellValue(rule.remediation());
            rem.setCellStyle(wrapTop);
        }
    }

    private String categoryLabel(String cat) {
        return switch (cat) {
            case "JAVA_REMOVED"   -> "JAVA_REMOVED  —  APIs removed in Java 9–21";
            case "JAVA_INTERNAL"  -> "JAVA_INTERNAL  —  JDK internal APIs (sun.*, jdk.internal.*)";
            case "JAVA_DEPRECATED"-> "JAVA_DEPRECATED  —  APIs deprecated-for-removal";
            case "JAVA_BEHAVIOR"  -> "JAVA_BEHAVIOR  —  Changed behaviour in Java 9–21";
            case "JVM_ARGS"       -> "JVM_ARGS  —  Removed / renamed JVM flags";
            case "JDK_MODULES"    -> "JDK_MODULES  —  Module system access issues";
            default               -> cat + "  (custom)";
        };
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Style helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static final Map<String, IndexedColors> SEV_COLORS = Map.of(
            "CRITICAL", IndexedColors.ROSE,
            "HIGH",     IndexedColors.LIGHT_ORANGE,
            "MEDIUM",   IndexedColors.LIGHT_YELLOW,
            "INFO",     IndexedColors.LIGHT_TURQUOISE
    );

    private static final Map<String, CellStyle> SEV_STYLE_CACHE = new WeakHashMap<>();

    private static CellStyle severityStyle(Workbook wb, String severity) {
        String key = severity + "@" + System.identityHashCode(wb);
        return SEV_STYLE_CACHE.computeIfAbsent(key, k -> {
            CellStyle cs = wb.createCellStyle();
            cs.setFillForegroundColor(SEV_COLORS.getOrDefault(severity, IndexedColors.WHITE).getIndex());
            cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font f = wb.createFont();
            f.setBold("CRITICAL".equals(severity));
            cs.setFont(f);
            return cs;
        });
    }

    private static int sectionHeader(Sheet sheet, Styles s, int r, String text) {
        Row row = sheet.createRow(r++);
        Cell c = row.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(s.catHeader);
        return r;
    }

    private static void row(Sheet sheet, int r, CellStyle style, String v0, String v1) {
        Row row = sheet.createRow(r);
        Cell c = row.createCell(0);
        c.setCellValue(v0);
        if (style != null) c.setCellStyle(style);
        if (v1 != null) row.createCell(1).setCellValue(v1);
    }

    private static void cellH(Row row, CellStyle style, int col, String value) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Styles
    // ──────────────────────────────────────────────────────────────────────────

    private static class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle catHeader;

        Styles(Workbook wb) {
            title = wb.createCellStyle();
            Font tf = wb.createFont();
            tf.setBold(true);
            tf.setFontHeightInPoints((short) 13);
            title.setFont(tf);

            header = ExcelUtils.createHeaderStyle(wb);

            catHeader = wb.createCellStyle();
            catHeader.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            catHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font cf = wb.createFont();
            cf.setBold(true);
            cf.setColor(IndexedColors.WHITE.getIndex());
            catHeader.setFont(cf);
        }
    }
}
