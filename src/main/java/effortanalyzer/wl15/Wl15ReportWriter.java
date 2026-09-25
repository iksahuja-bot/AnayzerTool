package effortanalyzer.wl15;

import effortanalyzer.EffortConfig;
import effortanalyzer.library.LibraryUpgradeAnalyzer.Finding;
import effortanalyzer.util.ExcelUtils;
import effortanalyzer.version.LibraryVersionAnalyzer.VersionFinding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates the WebLogic 15 Library Migration Excel report.
 *
 * <p>Sheets produced:
 * <ol>
 *   <li>📋 Instructions        — how to read and act on the report</li>
 *   <li>📊 Summary             — total findings by severity and by library</li>
 *   <li>📦 Library Issues      — full detail table, one row per finding, sorted by severity</li>
 *   <li>✅ Remediation Checklist — deduplicated action items with progress-tracking checkbox</li>
 *   <li>⏱ Effort Analysis      — estimated remediation effort per JAR, subtotals, grand total</li>
 * </ol>
 */
public class Wl15ReportWriter {

    private static final Logger logger = LogManager.getLogger(Wl15ReportWriter.class);

    /** Severity → background colour for data cells. */
    private static final Map<String, IndexedColors> SEV_COLORS = Map.of(
            "CRITICAL", IndexedColors.ROSE,
            "HIGH",     IndexedColors.LIGHT_ORANGE,
            "WARNING",  IndexedColors.LIGHT_YELLOW,
            "INFO",     IndexedColors.LIGHT_GREEN
    );

    private final Map<String, List<Finding>> findingsByJar;
    private final int totalRules;
    private final List<VersionFinding> versionFindings;
    private final ReportProduct product;

    /** Report identity — lets WL14 reuse this writer with its own labels. */
    public record ReportProduct(String code, String name) {
        public static final ReportProduct WL15 = new ReportProduct("WL15", "WebLogic 15");
        public static final ReportProduct WL14 = new ReportProduct("WL14", "WebLogic 14");
    }

    public Wl15ReportWriter(Map<String, List<Finding>> findingsByJar, int totalRules) {
        this(findingsByJar, totalRules, List.of(), ReportProduct.WL15);
    }

    public Wl15ReportWriter(Map<String, List<Finding>> findingsByJar,
                            int totalRules,
                            List<VersionFinding> versionFindings,
                            ReportProduct product) {
        this.findingsByJar   = findingsByJar;
        this.totalRules      = totalRules;
        this.versionFindings = versionFindings == null ? List.of() : List.copyOf(versionFindings);
        this.product         = product == null ? ReportProduct.WL15 : product;
    }

    private long outdatedVersionCount() {
        return versionFindings.stream().filter(VersionFinding::outdated).count();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public void write(String outputFile) throws IOException {
        Path outPath = Path.of(outputFile).toAbsolutePath();
        ExcelUtils.validateAndPrepareOutput(outPath, logger);

        List<Finding> allFindings = findingsByJar.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        try (Workbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);

            writeInstructionsSheet(wb, s);
            writeSummarySheet     (wb, s, allFindings);
            writeLibraryIssuesSheet(wb, s, allFindings);
            writeChecklistSheet   (wb, s, allFindings);
            writeEffortSheet      (wb, s);
            writeVersionsSheet    (wb, s);

            try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                wb.write(fos);
            } catch (IOException e) {
                String detail = ExcelUtils.diagnoseWriteFailure(outPath, e);
                logger.error("Report write failed: {}", detail);
                throw new IOException(detail, e);
            }
        }

        logger.info("{} report written: {} ({} findings, {} outdated libraries)",
                product.code(), outPath, allFindings.size(), outdatedVersionCount());
        System.out.println("  [" + product.code().toLowerCase(java.util.Locale.ROOT)
                + "] Report written → " + outPath);
    }

    // ── Sheet 1: Instructions ─────────────────────────────────────────────────

    private void writeInstructionsSheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("📋 Instructions");
        sheet.setColumnWidth(0, 32 * 256);
        sheet.setColumnWidth(1, 85 * 256);

        int r = 0;

        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue("How to Read and Use the " + product.code() + " Library Migration Report");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

        Row sub = sheet.createRow(r++);
        sub.createCell(0).setCellValue(
                "Generated: " + LocalDate.now()
                + "   |   EffortAnalyzer v2.0.0"
                + "   |   Active rules: " + totalRules
                + "   |   JARs scanned: " + findingsByJar.size());
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 1));
        r++;

        // ── WHAT IS THIS? ─────────────────────────────────────────────────────
        addInstSection(sheet, s, r++, "WHAT IS THIS REPORT?");
        addInstRow(sheet, s, r++, "Purpose",
                "Identifies all third-party library API changes required for WebLogic 15 target versions: "
                + "Spring 6.2.11, Spring Security 6.5.9, Jackson 2.18.9, Netty 4.1.135.Final, "
                + "Log4j 2.25.4, Jetty 12.0.33, JasperReports 7.0.4, and 10+ more libraries.");
        addInstRow(sheet, s, r++, "How it works",
                "Scans every .class file inside all JARs, WARs and EARs under the input path. "
                + "Each class's constant pool is inspected for references to deprecated or removed APIs. "
                + "One row in '📦 Library Issues' = one API reference in one class file. "
                + "In addition, every archive and embedded library JAR is version-matched against "
                + "the target library table — see '🔢 Library Versions'.");
        r++;

        // ── SHEETS ────────────────────────────────────────────────────────────
        addInstSection(sheet, s, r++, "REPORT SHEETS — WHERE TO LOOK");
        addInstRow(sheet, s, r++, "📋 Instructions (here)",
                "Start here. Read once, then go to 📊 Summary.");
        addInstRow(sheet, s, r++, "📊 Summary",
                "Findings broken down by severity and by library. "
                + "Quickly see which libraries need the most work and assess overall migration scope.");
        addInstRow(sheet, s, r++, "📦 Library Issues",
                "Full detail: JAR | File | Line | Deprecated API | Library | Severity | Replacement | What to Do. "
                + "Sorted by severity — CRITICAL first. Use the auto-filter ▼ to focus on a specific "
                + "library or severity level.");
        addInstRow(sheet, s, r++, "✅ Remediation Checklist",
                "Deduplicated: one row per unique deprecated API (not one per file). "
                + "Shows the exact replacement and description. Mark 'Done?' (☐ → ✔) as fixes land. "
                + "The '# Files' column shows how many class files are affected. This is your team task list.");
        addInstRow(sheet, s, r++, "⏱ Effort Analysis",
                "Estimated hours per JAR with subtotals and grand total. "
                + "Formula: base + (distinct files × rate), capped. "
                + "Override defaults with effort-overrides.properties next to the EffortAnalyzer JAR.");
        addInstRow(sheet, s, r++, "🔢 Library Versions",
                "Every rule-matched third-party artifact detected in the scanned archives "
                + "(by file name or embedded pom.properties), with the detected version compared "
                + "against the " + product.code() + " target version table. Outdated entries are listed "
                + "first and color-coded; entries already at/above target are listed as OK.");
        r++;

        // ── SEVERITY GUIDE ────────────────────────────────────────────────────
        addInstSection(sheet, s, r++, "SEVERITY GUIDE");
        addInstRow(sheet, s, r++, "CRITICAL  (pink)",
                "API completely removed in the target version. "
                + "Will cause compile-time or runtime failure. Must fix BEFORE any deployment.");
        addInstRow(sheet, s, r++, "HIGH  (orange)",
                "API removed or behaviour changed in a breaking way. "
                + "Will likely cause runtime failure. Fix before going live.");
        addInstRow(sheet, s, r++, "WARNING  (yellow)",
                "API deprecated with planned removal in the next major version. "
                + "Works today but will break on the next upgrade cycle. Plan to fix.");
        addInstRow(sheet, s, r++, "INFO  (green)",
                "Soft-deprecated or minor API change. Low risk now; address before the next major upgrade.");
        r++;

        // ── STEPS ─────────────────────────────────────────────────────────────
        addInstSection(sheet, s, r++, "RECOMMENDED MIGRATION STEPS");
        addInstRow(sheet, s, r++, "Step 1 — Assess",
                "Open 📊 Summary. Libraries with CRITICAL or HIGH findings are your deployment blockers.");
        addInstRow(sheet, s, r++, "Step 2 — Detail",
                "Open 📦 Library Issues. Filter Severity = CRITICAL. "
                + "The 'Replacement' column shows the exact API to switch to. "
                + "The 'What to Do' column explains the code change.");
        addInstRow(sheet, s, r++, "Step 3 — Track",
                "Open ✅ Remediation Checklist. Assign rows to team members. "
                + "Mark 'Done?' as fixes land in source control.");
        addInstRow(sheet, s, r++, "Step 4 — Verify",
                "After fixes: rebuild your JARs, re-run EffortAnalyzer wl15, "
                + "and confirm the issues no longer appear in the new report.");
    }

    private void addInstSection(Sheet sheet, Styles s, int r, String heading) {
        Row row = sheet.createRow(r);
        Cell c  = row.createCell(0);
        c.setCellValue(heading);
        c.setCellStyle(s.sectionHeader);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 1));
        row.setHeightInPoints(20);
    }

    private void addInstRow(Sheet sheet, Styles s, int r, String label, String text) {
        Row  row = sheet.createRow(r);
        row.setHeightInPoints(36);
        Cell lc  = row.createCell(0);
        lc.setCellValue(label);
        lc.setCellStyle(s.instLabel);
        Cell tc  = row.createCell(1);
        tc.setCellValue(text);
        tc.setCellStyle(s.wrap);
    }

    // ── Sheet 2: Summary ──────────────────────────────────────────────────────

    private void writeSummarySheet(Workbook wb, Styles s, List<Finding> all) {
        Sheet sheet = wb.createSheet("📊 Summary");
        sheet.setColumnWidth(0, 38 * 256);
        sheet.setColumnWidth(1, 12 * 256);
        sheet.setColumnWidth(2, 12 * 256);
        sheet.setColumnWidth(3, 12 * 256);
        sheet.setColumnWidth(4, 12 * 256);
        sheet.setColumnWidth(5, 12 * 256);

        int r = 0;

        Row titleRow = sheet.createRow(r++);
        Cell tc = titleRow.createCell(0);
        tc.setCellValue(product.name() + " Library Migration — Summary");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

        Row sub = sheet.createRow(r++);
        sub.createCell(0).setCellValue(
                "Generated: " + LocalDate.now()
                + "   |   JARs scanned: " + findingsByJar.size()
                + "   |   Total findings: " + all.size()
                + "   |   Active rules: " + totalRules
                + (versionFindings.isEmpty() ? ""
                        : "   |   Outdated libraries: " + outdatedVersionCount()));
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 5));

        // ── Library version checks (shown even when there are no API findings) ──
        if (!versionFindings.isEmpty()) {
            r++;
            Row verHdr = sheet.createRow(r++);
            cellH(verHdr, s.colHeader, 0, "Library version checks");
            cellH(verHdr, s.colHeader, 1, "Outdated");
            cellH(verHdr, s.colHeader, 2, "OK");
            Row vrow = sheet.createRow(r++);
            vrow.createCell(0).setCellValue(
                    "Bundled artifacts matched against the target version table "
                    + "(details in '🔢 Library Versions')");
            vrow.createCell(1).setCellValue(outdatedVersionCount());
            vrow.createCell(2).setCellValue(versionFindings.size() - outdatedVersionCount());
        }

        if (all.isEmpty()) {
            r++;
            sheet.createRow(r).createCell(0)
                    .setCellValue("✅  No " + product.code() + " library migration issues found across scanned JARs.");
            return;
        }

        r++;
        Row tip = sheet.createRow(r++);
        Cell tipC = tip.createCell(0);
        tipC.setCellValue(
                "📋 HOW TO USE: Libraries with CRITICAL/HIGH issues must be fixed before deployment. "
                + "Click '📦 Library Issues' for details. Use '✅ Remediation Checklist' to track your progress.");
        tipC.setCellStyle(s.infoBar);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 5));
        tip.setHeightInPoints(40);
        r++;

        // ── Severity breakdown ────────────────────────────────────────────────
        Row sevHdr = sheet.createRow(r++);
        cellH(sevHdr, s.colHeader, 0, "Severity");
        cellH(sevHdr, s.colHeader, 1, "Findings");
        for (String sev : List.of("CRITICAL", "HIGH", "WARNING", "INFO")) {
            long cnt = countBySeverity(all, sev);
            Row row = sheet.createRow(r++);
            Cell sc = row.createCell(0);
            sc.setCellValue(sev);
            sc.setCellStyle(s.severityStyle(sev));
            row.createCell(1).setCellValue(cnt);
        }
        r++;

        // ── Library breakdown ─────────────────────────────────────────────────
        Row libHdr = sheet.createRow(r++);
        cellH(libHdr, s.colHeader, 0, "Library");
        cellH(libHdr, s.colHeader, 1, "CRITICAL");
        cellH(libHdr, s.colHeader, 2, "HIGH");
        cellH(libHdr, s.colHeader, 3, "WARNING");
        cellH(libHdr, s.colHeader, 4, "INFO");
        cellH(libHdr, s.colHeader, 5, "Total");
        sheet.createFreezePane(0, r);

        Map<String, List<Finding>> byLib = all.stream()
                .collect(Collectors.groupingBy(Finding::library, LinkedHashMap::new, Collectors.toList()));

        List<Map.Entry<String, List<Finding>>> libEntries = new ArrayList<>(byLib.entrySet());
        libEntries.sort(Map.Entry.<String, List<Finding>>comparingByValue(
                Comparator.comparingInt(List::size)).reversed());

        for (Map.Entry<String, List<Finding>> e : libEntries) {
            List<Finding> libF = e.getValue();
            long crit = countBySeverity(libF, "CRITICAL");
            long high = countBySeverity(libF, "HIGH");
            Row row = sheet.createRow(r++);
            Cell nameCell = row.createCell(0);
            nameCell.setCellValue(e.getKey());
            if (crit > 0)      nameCell.setCellStyle(s.severityStyle("CRITICAL"));
            else if (high > 0) nameCell.setCellStyle(s.severityStyle("HIGH"));
            row.createCell(1).setCellValue(crit);
            row.createCell(2).setCellValue(high);
            row.createCell(3).setCellValue(countBySeverity(libF, "WARNING"));
            row.createCell(4).setCellValue(countBySeverity(libF, "INFO"));
            row.createCell(5).setCellValue((long) libF.size());
        }
    }

    // ── Sheet 3: Library Issues ───────────────────────────────────────────────

    private void writeLibraryIssuesSheet(Workbook wb, Styles s, List<Finding> findings) {
        Sheet sheet = wb.createSheet("📦 Library Issues");
        int[] widths = {30, 35, 7, 42, 25, 12, 42, 52};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = 0;

        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue(product.code() + " Library Migration Issues — Full Detail");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, widths.length - 1));

        Row inst = sheet.createRow(r++);
        Cell ic  = inst.createCell(0);
        ic.setCellValue(
                "Each row = one deprecated/removed API reference in one class file.  "
                + "CRITICAL = removed API (will fail at runtime).  HIGH = breaking change.  "
                + "WARNING = deprecated (plan to replace).  INFO = soft-deprecated (low risk).  "
                + "The 'Replacement' column shows exactly what to change to.  "
                + "Use the column filter ▼ to focus on specific libraries or severities.");
        ic.setCellStyle(s.infoBar);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, widths.length - 1));
        inst.setHeightInPoints(40);
        r++;

        if (findings.isEmpty()) {
            sheet.createRow(r).createCell(0).setCellValue("✅  No " + product.code() + " library migration issues found.");
            return;
        }

        String[] cols = {
                "JAR / Archive", "File / Class", "Line",
                "Deprecated API", "Library", "Severity",
                "Replacement", "What to Do"
        };
        Row hdr = sheet.createRow(r++);
        for (int i = 0; i < cols.length; i++) cellH(hdr, s.colHeader, i, cols[i]);
        sheet.setAutoFilter(new CellRangeAddress(r - 1, r - 1, 0, cols.length - 1));
        sheet.createFreezePane(0, r);

        // Sort: severity first, then library, then file
        List<Finding> sorted = findings.stream()
                .sorted(Comparator.comparingInt((Finding f) -> severityOrder(f.severity()))
                        .thenComparing(Finding::library)
                        .thenComparing(Finding::fileName))
                .toList();

        for (Finding f : sorted) {
            Row row = sheet.createRow(r++);
            row.setHeightInPoints(18);

            CellStyle sev = s.severityStyle(f.severity());

            row.createCell(0).setCellValue(f.jarName());
            row.createCell(1).setCellValue(f.fileName());
            row.createCell(2).setCellValue(f.lineNumber() > 0 ? String.valueOf(f.lineNumber()) : "");

            String api = apiLabel(f);
            Cell apiCell = row.createCell(3);
            apiCell.setCellValue(api);
            if (sev != null) apiCell.setCellStyle(sev);

            row.createCell(4).setCellValue(f.library());

            Cell sevCell = row.createCell(5);
            sevCell.setCellValue(f.severity());
            if (sev != null) sevCell.setCellStyle(sev);

            Cell repCell = row.createCell(6);
            repCell.setCellValue(f.replacement());
            repCell.setCellStyle(s.wrap);

            Cell descCell = row.createCell(7);
            descCell.setCellValue(f.description());
            descCell.setCellStyle(s.wrap);
        }
    }

    // ── Sheet 4: Remediation Checklist ────────────────────────────────────────

    private void writeChecklistSheet(Workbook wb, Styles s, List<Finding> all) {
        Sheet sheet = wb.createSheet("✅ Remediation Checklist");
        sheet.setColumnWidth(0, 25 * 256);
        sheet.setColumnWidth(1, 44 * 256);
        sheet.setColumnWidth(2, 12 * 256);
        sheet.setColumnWidth(3, 52 * 256);
        sheet.setColumnWidth(4, 52 * 256);
        sheet.setColumnWidth(5,  8 * 256);
        sheet.setColumnWidth(6, 10 * 256);

        int r = 0;

        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue("Remediation Checklist — Track Your " + product.code() + " Migration Progress");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));

        Row inst = sheet.createRow(r++);
        Cell ic  = inst.createCell(0);
        ic.setCellValue(
                "One row per unique deprecated API (deduplicated across all files and JARs). "
                + "Sorted by severity — CRITICAL first. "
                + "Mark the 'Done?' column (☐ → ✔) as you complete each fix. "
                + "The '# Files' column shows how many class files reference this API.");
        ic.setCellStyle(s.infoBar);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 6));
        inst.setHeightInPoints(40);
        r++;

        Row hdr = sheet.createRow(r++);
        cellH(hdr, s.colHeader, 0, "Library");
        cellH(hdr, s.colHeader, 1, "Deprecated API");
        cellH(hdr, s.colHeader, 2, "Severity");
        cellH(hdr, s.colHeader, 3, "Replacement / Action");
        cellH(hdr, s.colHeader, 4, "Description");
        cellH(hdr, s.colHeader, 5, "# Files");
        cellH(hdr, s.colHeader, 6, "Done?");
        sheet.setAutoFilter(new CellRangeAddress(r - 1, r - 1, 0, 6));
        sheet.createFreezePane(0, r);

        // Deduplicate by library + deprecatedClass + methodName
        record DedupeKey(String library, String cls, String method) {}
        Map<DedupeKey, Finding> seen       = new LinkedHashMap<>();
        Map<DedupeKey, Long>    fileCounts = new LinkedHashMap<>();

        for (Finding f : all) {
            DedupeKey k = new DedupeKey(f.library(), f.deprecatedClass(), nvl(f.methodName()));
            seen.putIfAbsent(k, f);
            fileCounts.merge(k, 1L, Long::sum);
        }

        List<Map.Entry<DedupeKey, Finding>> entries = new ArrayList<>(seen.entrySet());
        entries.sort(Comparator
                .comparingInt((Map.Entry<DedupeKey, Finding> e) -> severityOrder(e.getValue().severity()))
                .thenComparing(e -> e.getValue().library())
                .thenComparing(e -> e.getKey().cls()));

        for (Map.Entry<DedupeKey, Finding> entry : entries) {
            Finding   f     = entry.getValue();
            long      count = fileCounts.getOrDefault(entry.getKey(), 1L);
            CellStyle sev   = s.severityStyle(f.severity());

            Row row = sheet.createRow(r++);
            row.setHeightInPoints(36);

            row.createCell(0).setCellValue(f.library());

            String api = apiLabel(f);
            Cell apiCell = row.createCell(1);
            apiCell.setCellValue(api);
            if (sev != null) apiCell.setCellStyle(sev);

            Cell sevCell = row.createCell(2);
            sevCell.setCellValue(f.severity());
            if (sev != null) sevCell.setCellStyle(sev);

            Cell repCell = row.createCell(3);
            repCell.setCellValue(f.replacement());
            repCell.setCellStyle(s.wrap);

            Cell descCell = row.createCell(4);
            descCell.setCellValue(f.description());
            descCell.setCellStyle(s.wrap);

            row.createCell(5).setCellValue(count);
            row.createCell(6).setCellValue("☐");
        }

        if (seen.isEmpty()) {
            sheet.createRow(r).createCell(0).setCellValue("✅  No issues found — nothing to remediate.");
        }
    }

    // ── Sheet 5: Effort Analysis ──────────────────────────────────────────────

    /**
     * Effort Analysis sheet — one row per unique deprecated API per JAR, deduplicated,
     * with a subtotal per JAR and a grand total.
     *
     * Effort formula:  effort = min(cap, base + max(1, distinctFiles) × perFile)
     *   CRITICAL : base 2 h + 0.50 h/file, cap 20 h
     *   HIGH     : base 1 h + 0.25 h/file, cap 12 h
     *   WARNING  : base 0.5 h + 0.10 h/file, cap  8 h
     *   INFO     : flat 0.25 h
     *
     * Overridable via effort-overrides.properties placed next to the JAR.
     */
    private void writeEffortSheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("⏱ Effort Analysis");
        sheet.setColumnWidth(0, 36 * 256);
        sheet.setColumnWidth(1, 42 * 256);
        sheet.setColumnWidth(2, 18 * 256);
        sheet.setColumnWidth(3, 14 * 256);
        sheet.setColumnWidth(4, 16 * 256);
        sheet.setColumnWidth(5, 14 * 256);

        int r = 0;

        Row titleRow = sheet.createRow(r++);
        Cell tc = titleRow.createCell(0);
        tc.setCellValue("Technical Debt Effort Analysis — Issues per JAR / Component");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

        Row infoRow = sheet.createRow(r++);
        Cell ic = infoRow.createCell(0);
        ic.setCellValue(
                "Effort = base + (distinct files × rate), capped per unique API.  "
                + "CRITICAL: 2h + 0.5h/file (cap 20h)  |  HIGH: 1h + 0.25h/file (cap 12h)  "
                + "|  WARNING: 0.5h + 0.1h/file (cap 8h)  |  INFO: 0.25h flat  "
                + "|  Override per-severity or per-API via effort-overrides.properties next to the JAR.");
        ic.setCellStyle(s.infoBar);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 5));
        infoRow.setHeightInPoints(40);
        r++;

        Row hRow = sheet.createRow(r++);
        cellH(hRow, s.colHeader, 0, "Component / JAR");
        cellH(hRow, s.colHeader, 1, "Issue / API");
        cellH(hRow, s.colHeader, 2, "Library");
        cellH(hRow, s.colHeader, 3, "Severity");
        cellH(hRow, s.colHeader, 4, "Files Affected");
        cellH(hRow, s.colHeader, 5, "Effort (h)");
        sheet.setAutoFilter(new CellRangeAddress(r - 1, r - 1, 0, 5));
        sheet.createFreezePane(0, r);

        if (findingsByJar.isEmpty()) {
            sheet.createRow(r).createCell(0).setCellValue("No issues found — nothing to report.");
            return;
        }

        double grandTotal = 0.0;

        for (Map.Entry<String, List<Finding>> entry : findingsByJar.entrySet()) {
            String        jarName     = entry.getKey();
            List<Finding> jarFindings = entry.getValue();
            if (jarFindings.isEmpty()) continue;

            // Deduplicate by (deprecatedClass + "#" + methodName), tracking distinct source files
            Map<String, Finding>     dedup    = new LinkedHashMap<>();
            Map<String, Set<String>> fileSets = new LinkedHashMap<>();
            for (Finding f : jarFindings) {
                String dk = f.deprecatedClass() + "#" + nvl(f.methodName());
                dedup.putIfAbsent(dk, f);
                fileSets.computeIfAbsent(dk, x -> new LinkedHashSet<>()).add(f.fileName());
            }

            // JAR header row
            int jarIdx = r;
            Row jarRow = sheet.createRow(r++);
            Cell cc = jarRow.createCell(0);
            cc.setCellValue("▶  " + jarName);
            cc.setCellStyle(s.jarHeader);
            sheet.addMergedRegion(new CellRangeAddress(jarIdx, jarIdx, 0, 5));

            double jarTotal = 0.0;

            List<Map.Entry<String, Finding>> dedupEntries = new ArrayList<>(dedup.entrySet());
            dedupEntries.sort(Comparator.comparingInt(e -> severityOrder(e.getValue().severity())));

            for (Map.Entry<String, Finding> de : dedupEntries) {
                Finding f      = de.getValue();
                int     files  = fileSets.getOrDefault(de.getKey(), Set.of()).size();
                double  effort = wl15EffortHours(de.getKey(), f.severity(), files);
                jarTotal += effort;

                Row row = sheet.createRow(r++);
                row.setHeightInPoints(18);
                row.createCell(0).setCellValue(jarName);

                Cell apiCell = row.createCell(1);
                apiCell.setCellValue(apiLabel(f));
                apiCell.setCellStyle(s.wrap);

                row.createCell(2).setCellValue(f.library());

                Cell sevCell = row.createCell(3);
                sevCell.setCellValue(f.severity());
                CellStyle sevStyle = s.severityStyle(f.severity());
                if (sevStyle != null) sevCell.setCellStyle(sevStyle);

                row.createCell(4).setCellValue(files);

                Cell effortCell = row.createCell(5);
                effortCell.setCellValue(effort);
                effortCell.setCellStyle(s.effortNum);
            }

            // Subtotal row
            grandTotal += jarTotal;
            int stIdx = r;
            Row stRow = sheet.createRow(r++);
            Cell stLabel = stRow.createCell(0);
            stLabel.setCellValue("Subtotal — " + jarName);
            stLabel.setCellStyle(s.subtotalRowStyle);
            sheet.addMergedRegion(new CellRangeAddress(stIdx, stIdx, 0, 4));
            Cell stVal = stRow.createCell(5);
            stVal.setCellValue(jarTotal);
            stVal.setCellStyle(s.subtotalValueStyle);

            r++; // blank row between JARs
        }

        // Grand total
        int gtIdx = r;
        Row gtRow  = sheet.createRow(r);
        Cell gtLabel = gtRow.createCell(0);
        gtLabel.setCellValue("GRAND TOTAL — All Components");
        gtLabel.setCellStyle(s.grandTotalRowStyle);
        sheet.addMergedRegion(new CellRangeAddress(gtIdx, gtIdx, 0, 4));
        Cell gtVal = gtRow.createCell(5);
        gtVal.setCellValue(grandTotal);
        gtVal.setCellStyle(s.grandTotalValueStyle);
    }

    /**
     * Effort formula for WL15 findings.
     * Handles CRITICAL / HIGH / WARNING / INFO severities.
     * Respects {@link EffortConfig} flat per-rule overrides and per-severity scale overrides.
     */
    private static double wl15EffortHours(String ruleKey, String severity, int fileCount) {
        EffortConfig   cfg  = EffortConfig.INSTANCE;
        OptionalDouble flat = cfg.flatOverride(ruleKey);
        if (flat.isPresent()) return flat.getAsDouble();

        String sev = severity == null ? "" : severity.toUpperCase();

        double base    = switch (sev) { case "CRITICAL" -> 2.0;  case "HIGH" -> 1.0;  case "WARNING" -> 0.5;  default -> 0.25; };
        double perFile = switch (sev) { case "CRITICAL" -> 0.50; case "HIGH" -> 0.25; case "WARNING" -> 0.10; default -> 0.0;  };
        double cap     = switch (sev) { case "CRITICAL" -> 20.0; case "HIGH" -> 12.0; case "WARNING" -> 8.0;  default -> 0.25; };

        Double[] ov = cfg.scaleOverrides(sev);
        if (ov[0] != null) base    = ov[0];
        if (ov[1] != null) perFile = ov[1];
        if (ov[2] != null) cap     = ov[2];

        return Math.min(cap, base + Math.max(1, fileCount) * perFile);
    }

    // ── Sheet 6: Library Versions ─────────────────────────────────────────────

    /**
     * One row per rule-matched artifact detected in the scanned archives.
     * OUTDATED rows are sorted first (CRITICAL → INFO), OK rows follow.
     */
    private void writeVersionsSheet(Workbook wb, Styles s) {
        Sheet sheet = wb.createSheet("🔢 Library Versions");
        int[] widths = {5, 12, 30, 18, 18, 12, 26, 32, 42, 34};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = 0;

        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue(product.code() + " Library Version Checks — Detected vs. Target");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, widths.length - 1));

        Row inst = sheet.createRow(r++);
        Cell ic  = inst.createCell(0);
        ic.setCellValue(
                "Each row = one rule-matched third-party artifact detected in a scanned archive "
                + "(via its file name or an embedded META-INF/maven/.../pom.properties).  "
                + "OUTDATED = detected version is below the " + product.code() + " target version.  "
                + "OK = detected version meets or exceeds the target.  "
                + "Customize the target table with library-versions.properties next to the EffortAnalyzer JAR.");
        ic.setCellStyle(s.infoBar);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, widths.length - 1));
        inst.setHeightInPoints(40);
        r++;

        String[] cols = {
                "#", "Severity", "Artifact", "Found Version", "Target Version",
                "Status", "Library", "JAR / Archive", "Where Found", "Action"
        };
        Row hdr = sheet.createRow(r++);
        for (int i = 0; i < cols.length; i++) cellH(hdr, s.colHeader, i, cols[i]);
        sheet.setAutoFilter(new CellRangeAddress(r - 1, r - 1, 0, cols.length - 1));
        sheet.createFreezePane(0, r);

        if (versionFindings.isEmpty()) {
            sheet.createRow(r).createCell(0).setCellValue(
                    "✅  No rule-matched third-party artifacts were detected in the scanned archives.");
            return;
        }

        List<VersionFinding> sorted = new ArrayList<>(versionFindings);
        sorted.sort(java.util.Comparator
                .comparingInt((VersionFinding v) -> v.outdated() ? 0 : 1)
                .thenComparingInt(v -> severityOrder(v.severity()))
                .thenComparing(v -> v.artifact())
                .thenComparing(v -> v.jarName()));

        int n = 0;
        for (VersionFinding v : sorted) {
            Row row = sheet.createRow(r++);
            row.setHeightInPoints(18);
            n++;

            row.createCell(0).setCellValue(n);

            Cell sevCell = row.createCell(1);
            sevCell.setCellValue(v.outdated() ? v.severity() : "—");
            CellStyle sev = v.outdated() ? s.severityStyle(v.severity()) : s.severityStyle("INFO");
            if (sev != null) sevCell.setCellStyle(sev);

            row.createCell(2).setCellValue(v.artifact());
            row.createCell(3).setCellValue(v.detectedVersion());
            row.createCell(4).setCellValue(v.targetVersion());

            Cell stCell = row.createCell(5);
            stCell.setCellValue(v.status());
            if (sev != null) stCell.setCellStyle(sev);

            row.createCell(6).setCellValue(v.library());
            row.createCell(7).setCellValue(v.jarName());

            Cell locCell = row.createCell(8);
            locCell.setCellValue(v.location());
            locCell.setCellStyle(s.wrap);

            Cell actCell = row.createCell(9);
            actCell.setCellValue(v.action());
            actCell.setCellStyle(s.wrap);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String apiLabel(Finding f) {
        return (f.methodName() != null && !f.methodName().isBlank())
                ? f.deprecatedClass() + "#" + f.methodName()
                : f.deprecatedClass();
    }

    private static long countBySeverity(List<Finding> findings, String sev) {
        return findings.stream().filter(f -> sev.equals(f.severity())).count();
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private static int severityOrder(String sev) {
        return switch (sev == null ? "" : sev) {
            case "CRITICAL" -> 0;
            case "HIGH"     -> 1;
            case "WARNING"  -> 2;
            case "INFO"     -> 3;
            default         -> 4;
        };
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
        final CellStyle wrap;
        final CellStyle instLabel;
        final CellStyle infoBar;
        private final Map<String, CellStyle> sevStyles = new HashMap<>();

        // Effort sheet styles
        final CellStyle effortNum;
        final CellStyle subtotalRowStyle;
        final CellStyle subtotalValueStyle;
        final CellStyle grandTotalRowStyle;
        final CellStyle grandTotalValueStyle;

        CellStyle severityStyle(String severity) {
            return sevStyles.get(severity);
        }

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

            wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);

            instLabel = wb.createCellStyle();
            Font lf = wb.createFont();
            lf.setBold(true);
            instLabel.setFont(lf);
            instLabel.setWrapText(true);
            instLabel.setVerticalAlignment(VerticalAlignment.TOP);

            infoBar = wb.createCellStyle();
            infoBar.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            infoBar.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            infoBar.setWrapText(true);
            infoBar.setVerticalAlignment(VerticalAlignment.CENTER);

            // Severity styles
            for (Map.Entry<String, IndexedColors> e : SEV_COLORS.entrySet()) {
                String sev = e.getKey();
                CellStyle cs = wb.createCellStyle();
                cs.setFillForegroundColor(e.getValue().getIndex());
                cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                Font sevFont = wb.createFont();
                sevFont.setBold("CRITICAL".equals(sev) || "HIGH".equals(sev));
                cs.setFont(sevFont);
                sevStyles.put(sev, cs);
            }

            // ── Effort sheet styles ────────────────────────────────────────────
            DataFormat df        = wb.createDataFormat();
            short      oneDecFmt = df.getFormat("0.0");

            effortNum = wb.createCellStyle();
            effortNum.setAlignment(HorizontalAlignment.RIGHT);
            effortNum.setDataFormat(oneDecFmt);

            subtotalRowStyle = wb.createCellStyle();
            subtotalRowStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            subtotalRowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font stLFont = wb.createFont();
            stLFont.setBold(true);
            subtotalRowStyle.setFont(stLFont);

            subtotalValueStyle = wb.createCellStyle();
            subtotalValueStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            subtotalValueStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            subtotalValueStyle.setAlignment(HorizontalAlignment.RIGHT);
            subtotalValueStyle.setDataFormat(oneDecFmt);
            Font stVFont = wb.createFont();
            stVFont.setBold(true);
            subtotalValueStyle.setFont(stVFont);

            grandTotalRowStyle = wb.createCellStyle();
            grandTotalRowStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            grandTotalRowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font gtLFont = wb.createFont();
            gtLFont.setBold(true);
            gtLFont.setColor(IndexedColors.WHITE.getIndex());
            grandTotalRowStyle.setFont(gtLFont);

            grandTotalValueStyle = wb.createCellStyle();
            grandTotalValueStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            grandTotalValueStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            grandTotalValueStyle.setAlignment(HorizontalAlignment.RIGHT);
            grandTotalValueStyle.setDataFormat(oneDecFmt);
            Font gtVFont = wb.createFont();
            gtVFont.setBold(true);
            gtVFont.setColor(IndexedColors.WHITE.getIndex());
            grandTotalValueStyle.setFont(gtVFont);
        }
    }
}
