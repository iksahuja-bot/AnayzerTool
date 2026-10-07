package effortanalyzer.source;

import effortanalyzer.util.ExcelUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes source-inventory checkout and source-scan results. */
public class SourceInventoryReportWriter {

    private static final Logger logger = LogManager.getLogger(SourceInventoryReportWriter.class);

    private static final Map<String, IndexedColors> SEV_COLORS = Map.of(
            "CRITICAL", IndexedColors.ROSE,
            "HIGH", IndexedColors.LIGHT_ORANGE,
            "MEDIUM", IndexedColors.LIGHT_YELLOW,
            "WARNING", IndexedColors.LIGHT_YELLOW,
            "INFO", IndexedColors.LIGHT_GREEN
    );

    public void write(String outputFile, String module, List<SourceScanResult> results) throws IOException {
        Path outPath = Path.of(outputFile).toAbsolutePath();
        ExcelUtils.validateAndPrepareOutput(outPath, logger);

        try (Workbook wb = new XSSFWorkbook()) {
            writeStandaloneReport(wb, module, results);
            writeWorkbook(wb, outPath);
        }
        logger.info("Source inventory report written: {}", outPath);
    }

    public void append(String outputFile, String module, List<SourceScanResult> results) throws IOException {
        Path outPath = Path.of(outputFile).toAbsolutePath();
        if (!Files.isRegularFile(outPath)) {
            throw new IOException("Cannot append source inventory sheets because report does not exist: " + outPath);
        }

        byte[] workbookBytes = Files.readAllBytes(outPath);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            removeSheetIfPresent(wb, "Source Inventory");
            removeSheetIfPresent(wb, "Source Findings");
            removeSheetIfPresent(wb, "Checkout Errors");
            writeSourceSheets(wb, new Styles(wb), module, results);
            writeWorkbook(wb, outPath);
        }
        logger.info("Source inventory sheets appended to report: {}", outPath);
    }

    private void writeStandaloneReport(Workbook wb, String module, List<SourceScanResult> results) {
        Styles s = new Styles(wb);
        writeInstructionsSheet(wb, s, module);
        writeSummarySheet(wb, s, module, results);
        writeSourceSheets(wb, s, module, results);
        writeChecklistSheet(wb, s, module, results);
        writeEffortSheet(wb, s, module, results);
    }

    private void writeSourceSheets(Workbook wb, Styles s, String module, List<SourceScanResult> results) {
        writeInventorySheet(wb, s, module, results);
        writeFindingsSheet(wb, s, module, results);
        writeCheckoutErrorsSheet(wb, s, results);
    }

    private void writeWorkbook(Workbook wb, Path outPath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
            wb.write(fos);
        } catch (IOException e) {
            throw new IOException(ExcelUtils.diagnoseWriteFailure(outPath, e), e);
        }
    }

    private static void removeSheetIfPresent(Workbook wb, String name) {
        int index = wb.getSheetIndex(name);
        if (index >= 0) wb.removeSheetAt(index);
    }

    private void writeInstructionsSheet(Workbook wb, Styles s, String module) {
        Sheet sheet = wb.createSheet("📋 Instructions");
        sheet.setColumnWidth(0, 32 * 256);
        sheet.setColumnWidth(1, 88 * 256);

        int r = 0;
        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue(productName(module) + " Repository Source Report — How to Use This Workbook");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

        Row sub = sheet.createRow(r++);
        Cell sc = sub.createCell(0);
        sc.setCellValue("Generated: " + LocalDate.now()
                + "   |   Mode: repository source scan   |   Module: " + module);
        sc.setCellStyle(s.infoBar);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 1));
        r++;

        addSection(sheet, s, r++, "REPORT SHEETS — WHERE TO LOOK");
        addInstRow(sheet, s, r++, "📊 Summary", "Executive view of repository checkout status, files scanned, and findings by severity/category.");
        addInstRow(sheet, s, r++, "Source Inventory", "One row per component/repository from the input workbook, including checkout status and scan totals.");
        addInstRow(sheet, s, r++, "Source Findings", "Full line-level source findings. Filter by Severity, Component, Scanner, Category, Rule, or File.");
        addInstRow(sheet, s, r++, "Checkout Errors", "Only failed repository checkouts/updates. Start here when source rows are missing or incomplete.");
        addInstRow(sheet, s, r++, "✅ Remediation Checklist", "Deduplicated action list for project tracking. Mark Done?, Owner, and Notes as work progresses.");
        addInstRow(sheet, s, r++, "⏱ Effort Analysis", "Component-level remediation estimate using the same severity-based planning model as normal migration reports.");
        r++;

        addSection(sheet, s, r++, "SEVERITY GUIDE");
        addInstRow(sheet, s, r++, "CRITICAL  (pink)", "Blocking source issue. Treat as required remediation before deployment/migration validation.");
        addInstRow(sheet, s, r++, "HIGH  (orange)", "Likely migration/runtime risk. Prioritize with CRITICAL items.");
        addInstRow(sheet, s, r++, "WARNING  (yellow)", "Known compatibility or cleanup item. Plan and track before the target cutover.");
        addInstRow(sheet, s, r++, "INFO  (green)", "Informational or low-risk modernization item.");
        r++;

        addSection(sheet, s, r++, "RECOMMENDED STEPS");
        addInstRow(sheet, s, r++, "Step 1 — Validate checkouts", "Open Checkout Errors and fix SCM/PATH/credential failures first.");
        addInstRow(sheet, s, r++, "Step 2 — Assess scope", "Open 📊 Summary to identify components and severity buckets with the largest volume.");
        addInstRow(sheet, s, r++, "Step 3 — Remediate", "Use Source Findings for evidence and ✅ Remediation Checklist for assignment/progress tracking.");
        addInstRow(sheet, s, r++, "Step 4 — Re-run", "After fixes land, re-run the repository scan and confirm findings/checkouts improve.");
    }

    private void writeSummarySheet(Workbook wb, Styles s, String module, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet("📊 Summary");
        int[] widths = {36, 14, 14, 14, 14, 14, 18};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = 0;
        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue(productName(module) + " Repository Source Scan — Summary");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, widths.length - 1));

        Row info = sheet.createRow(r++);
        Cell ic = info.createCell(0);
        ic.setCellValue("Generated: " + LocalDate.now()
                + "   |   Components: " + results.size()
                + "   |   Checkout failures: " + checkoutFailureCount(results)
                + "   |   Files scanned: " + totalFilesScanned(results)
                + "   |   Findings: " + allFindings(results).size());
        ic.setCellStyle(s.infoBar);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, widths.length - 1));
        r++;

        Row overview = sheet.createRow(r++);
        cellH(overview, s.colHeader, 0, "Metric");
        cellH(overview, s.colHeader, 1, "Count");
        String[][] metrics = {
                {"Components in inventory", String.valueOf(results.size())},
                {"Successful checkouts/updates", String.valueOf(results.stream().filter(x -> x.checkout().success()).count())},
                {"Checkout/update failures", String.valueOf(checkoutFailureCount(results))},
                {"Files scanned", String.valueOf(totalFilesScanned(results))},
                {"Source findings", String.valueOf(allFindings(results).size())}
        };
        for (String[] metric : metrics) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(metric[0]);
            row.createCell(1).setCellValue(Integer.parseInt(metric[1]));
        }

        r++;
        Row sevHdr = sheet.createRow(r++);
        cellH(sevHdr, s.colHeader, 0, "Findings by Severity");
        cellH(sevHdr, s.colHeader, 1, "Count");
        for (String sev : List.of("CRITICAL", "HIGH", "MEDIUM", "WARNING", "INFO", "OTHER")) {
            long count = allFindings(results).stream().filter(f -> severityBucket(f.severity()).equals(sev)).count();
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(sev);
            Cell countCell = row.createCell(1);
            countCell.setCellValue(count);
            CellStyle sevStyle = s.severityStyle(sev);
            if (sevStyle != null) row.getCell(0).setCellStyle(sevStyle);
        }

        r++;
        Row compHdr = sheet.createRow(r++);
        String[] cols = {"Component", "Checkout", "Files", "Findings", "Critical", "High", "Medium/Warning"};
        for (int i = 0; i < cols.length; i++) cellH(compHdr, s.colHeader, i, cols[i]);
        sheet.setAutoFilter(new CellRangeAddress(compHdr.getRowNum(), compHdr.getRowNum(), 0, cols.length - 1));
        sheet.createFreezePane(0, compHdr.getRowNum() + 1);
        for (SourceScanResult result : results) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(result.component().displayName());
            row.createCell(1).setCellValue(result.checkout().status());
            row.createCell(2).setCellValue(result.filesScanned());
            row.createCell(3).setCellValue(result.findings().size());
            row.createCell(4).setCellValue(countSeverity(result.findings(), "CRITICAL"));
            row.createCell(5).setCellValue(countSeverity(result.findings(), "HIGH"));
            row.createCell(6).setCellValue(countSeverity(result.findings(), "MEDIUM") + countSeverity(result.findings(), "WARNING"));
        }
    }

    private void writeInventorySheet(Workbook wb, Styles s, String module, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet("Source Inventory");
        int[] widths = {14, 28, 58, 10, 10, 18, 58, 14, 12};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = addTitleAndInfo(sheet, s, "Source Inventory", "One row per repository component from the input workbook.", widths.length);
        String[] headers = {"Module", "Component", "Repository", "Type", "Enabled", "Checkout Status", "Checkout Path", "Files Scanned", "Findings"};
        writeHeader(sheet, s.colHeader, r++, headers);
        sheet.setAutoFilter(new CellRangeAddress(r - 1, r - 1, 0, headers.length - 1));
        sheet.createFreezePane(0, r);

        for (Map.Entry<String, List<SourceScanResult>> entry : resultsByComponent(results).entrySet()) {
            Row compRow = sheet.createRow(r++);
            Cell cc = compRow.createCell(0);
            cc.setCellValue("▶  " + entry.getKey());
            cc.setCellStyle(s.sectionHeader);
            sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, headers.length - 1));

            for (SourceScanResult result : entry.getValue()) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(module);
                row.createCell(1).setCellValue(result.component().displayName());
                row.createCell(2).setCellValue(result.component().repository());
                row.createCell(3).setCellValue(result.component().type().name());
                row.createCell(4).setCellValue(result.component().enabled());
                row.createCell(5).setCellValue(result.checkout().status());
                row.createCell(6).setCellValue(result.checkout().checkoutPath() == null ? "" : result.checkout().checkoutPath().toString());
                row.createCell(7).setCellValue(result.filesScanned());
                row.createCell(8).setCellValue(result.findings().size());
                if (!result.checkout().success()) row.getCell(5).setCellStyle(s.checkoutError);
            }
        }
    }

    private void writeFindingsSheet(Workbook wb, Styles s, String module, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet("Source Findings");
        int[] widths = {28, 52, 14, 24, 12, 50, 8, 38, 54, 54, 70};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = addTitleAndInfo(sheet, s, productName(module) + " Source Findings — Full Detail",
                "Line-level source findings grouped by component, matching the component-oriented layout of the normal migration workbook.", widths.length);

        List<SourceFinding> all = sortedFindings(results);
        if (all.isEmpty()) {
            sheet.createRow(r).createCell(0).setCellValue("✅  No source findings found.");
            return;
        }

        String[] headers = {"Component", "Repository", "Scanner", "Category", "Severity", "File", "Line", "Rule", "Description", "Remediation", "Context"};
        writeHeader(sheet, s.colHeader, r++, headers);
        sheet.setAutoFilter(new CellRangeAddress(r - 1, r - 1, 0, headers.length - 1));
        sheet.createFreezePane(0, r);

        String currentComponent = null;
        for (SourceFinding f : all) {
            String component = displayComponent(f);
            if (!component.equals(currentComponent)) {
                currentComponent = component;
                Row compRow = sheet.createRow(r++);
                Cell cc = compRow.createCell(0);
                cc.setCellValue("▶  " + currentComponent);
                cc.setCellStyle(s.sectionHeader);
                sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, headers.length - 1));
            }

            Row row = sheet.createRow(r++);
            row.setHeightInPoints(28);
            row.createCell(0).setCellValue(component);
            row.createCell(1).setCellValue(f.repository());
            row.createCell(2).setCellValue(f.scanner());
            row.createCell(3).setCellValue(f.category());

            Cell sevCell = row.createCell(4);
            sevCell.setCellValue(f.severity());
            CellStyle sevStyle = s.severityStyle(f.severity());
            if (sevStyle != null) sevCell.setCellStyle(sevStyle);

            row.createCell(5).setCellValue(f.file());
            row.createCell(6).setCellValue(f.line());
            row.createCell(7).setCellValue(f.rule());

            Cell desc = row.createCell(8);
            desc.setCellValue(f.description());
            desc.setCellStyle(s.wrap);

            Cell rem = row.createCell(9);
            rem.setCellValue(f.remediation());
            rem.setCellStyle(s.wrap);

            Cell ctx = row.createCell(10);
            ctx.setCellValue(f.context());
            ctx.setCellStyle(s.wrap);
        }
    }

    private void writeCheckoutErrorsSheet(Workbook wb, Styles s, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet("Checkout Errors");
        int[] widths = {28, 58, 10, 18, 85};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = addTitleAndInfo(sheet, s, "Repository Checkout Errors",
                "Components listed here did not checkout/update successfully. Fix these before treating repository scan coverage as complete.", widths.length);
        String[] headers = {"Component", "Repository", "Type", "Status", "Message"};
        writeHeader(sheet, s.colHeader, r++, headers);
        sheet.setAutoFilter(new CellRangeAddress(r - 1, r - 1, 0, headers.length - 1));
        sheet.createFreezePane(0, r);
        for (SourceScanResult result : results) {
            if (result.checkout().success()) continue;
            SourceComponent c = result.component();
            Row row = sheet.createRow(r++);
            row.setHeightInPoints(38);
            set(row, 0, c.component(), s.wrap);
            set(row, 1, c.repository(), s.wrap);
            set(row, 2, c.type().name(), s.wrap);
            set(row, 3, result.checkout().status(), s.checkoutError);
            set(row, 4, result.checkout().message(), s.wrap);
        }
        if (r == 4) {
            Row row = sheet.createRow(r);
            Cell c = row.createCell(0);
            c.setCellValue("✅ No repository checkout/update errors were reported.");
            c.setCellStyle(s.wrap);
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, headers.length - 1));
        }
    }

    private void writeChecklistSheet(Workbook wb, Styles s, String module, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet("✅ Remediation Checklist");
        int[] widths = {7, 9, 16, 26, 22, 38, 24, 60, 22, 36};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = addTitleAndInfo(sheet, s, productName(module) + " Source Remediation Checklist",
                "Deduplicated tracking list. Fill Owner, Target Sprint/Date, Done?, and Notes as remediation progresses.", widths.length);
        String[] headers = {"Done?", "#", "Severity", "Component", "Category", "Rule", "Files Affected", "Recommended Action", "Owner", "Notes"};
        writeHeader(sheet, s.colHeader, r++, headers);
        sheet.setAutoFilter(new CellRangeAddress(r - 1, r - 1, 0, headers.length - 1));
        sheet.createFreezePane(0, r);

        Map<String, ChecklistItem> items = new LinkedHashMap<>();
        for (SourceFinding f : sortedFindings(results)) {
            String key = severityBucket(f.severity()) + "|" + safe(f.component()) + "|" + safe(f.category()) + "|" + safe(f.rule()) + "|" + safe(f.remediation());
            ChecklistItem item = items.computeIfAbsent(key, ignored -> new ChecklistItem(f));
            item.files.add(safe(f.file()));
        }

        if (items.isEmpty()) {
            Row row = sheet.createRow(r);
            Cell c = row.createCell(0);
            c.setCellValue("✅ No source remediation items were detected.");
            c.setCellStyle(s.wrap);
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, headers.length - 1));
            return;
        }

        int n = 1;
        for (ChecklistItem item : items.values()) {
            SourceFinding f = item.first;
            Row row = sheet.createRow(r++);
            row.setHeightInPoints(34);
            row.createCell(0).setCellValue("☐");
            row.createCell(1).setCellValue(n++);
            Cell sev = row.createCell(2);
            sev.setCellValue(severityBucket(f.severity()));
            CellStyle sevStyle = s.severityStyle(f.severity());
            if (sevStyle != null) sev.setCellStyle(sevStyle);
            set(row, 3, f.component(), s.wrap);
            set(row, 4, f.category(), s.wrap);
            set(row, 5, f.rule(), s.wrap);
            row.createCell(6).setCellValue(item.files.size());
            set(row, 7, actionText(f), s.wrap);
            set(row, 8, "", s.wrap);
            set(row, 9, "", s.wrap);
        }
    }

    private void writeEffortSheet(Workbook wb, Styles s, String module, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet("⏱ Effort Analysis");
        int[] widths = {30, 14, 14, 14, 14, 14, 14, 18, 54};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = addTitleAndInfo(sheet, s, productName(module) + " Source Effort Analysis",
                "Planning estimate based on finding severity and affected file counts. Review and adjust with application owners.", widths.length);
        String[] headers = {"Component", "Files Scanned", "Findings", "Critical", "High", "Medium/Warning", "Info", "Est. Hours", "Notes"};
        writeHeader(sheet, s.colHeader, r++, headers);
        sheet.setAutoFilter(new CellRangeAddress(r - 1, r - 1, 0, headers.length - 1));
        sheet.createFreezePane(0, r);

        double grandTotal = 0.0;
        for (SourceScanResult result : results) {
            double hours = estimateHours(result.findings());
            grandTotal += hours;
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(result.component().displayName());
            row.createCell(1).setCellValue(result.filesScanned());
            row.createCell(2).setCellValue(result.findings().size());
            row.createCell(3).setCellValue(countSeverity(result.findings(), "CRITICAL"));
            row.createCell(4).setCellValue(countSeverity(result.findings(), "HIGH"));
            row.createCell(5).setCellValue(countSeverity(result.findings(), "MEDIUM") + countSeverity(result.findings(), "WARNING"));
            row.createCell(6).setCellValue(countSeverity(result.findings(), "INFO"));
            Cell effort = row.createCell(7);
            effort.setCellValue(hours);
            effort.setCellStyle(s.effortNum);
            set(row, 8, result.checkout().success() ? "" : "Checkout failed; estimate may be incomplete.", s.wrap);
        }

        Row total = sheet.createRow(r);
        Cell label = total.createCell(0);
        label.setCellValue("GRAND TOTAL — Repository Source Remediation");
        label.setCellStyle(s.grandTotalRowStyle);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 6));
        Cell value = total.createCell(7);
        value.setCellValue(grandTotal);
        value.setCellStyle(s.grandTotalValueStyle);
    }

    private static void writeHeader(Sheet sheet, CellStyle style, String[] headers) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    private static void writeHeader(Sheet sheet, CellStyle style, int rowNumber, String[] headers) {
        Row row = sheet.createRow(rowNumber);
        for (int i = 0; i < headers.length; i++) cellH(row, style, i, headers[i]);
    }

    private static void cellH(Row row, CellStyle style, int column, String value) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void set(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void size(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
            if (sheet.getColumnWidth(i) > 70 * 256) sheet.setColumnWidth(i, 70 * 256);
        }
    }

    private static int addTitleAndInfo(Sheet sheet, Styles s, String title, String info, int columns) {
        int r = 0;
        Row titleRow = sheet.createRow(r++);
        Cell tc = titleRow.createCell(0);
        tc.setCellValue(title);
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, columns - 1));

        Row infoRow = sheet.createRow(r++);
        Cell ic = infoRow.createCell(0);
        ic.setCellValue(info);
        ic.setCellStyle(s.infoBar);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, columns - 1));
        infoRow.setHeightInPoints(32);
        return r + 1;
    }

    private static void addSection(Sheet sheet, Styles s, int r, String heading) {
        Row row = sheet.createRow(r);
        Cell cell = row.createCell(0);
        cell.setCellValue(heading);
        cell.setCellStyle(s.sectionHeader);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 1));
    }

    private static void addInstRow(Sheet sheet, Styles s, int r, String label, String text) {
        Row row = sheet.createRow(r);
        row.setHeightInPoints(36);
        Cell lc = row.createCell(0);
        lc.setCellValue(label);
        lc.setCellStyle(s.instLabel);
        Cell tc = row.createCell(1);
        tc.setCellValue(text);
        tc.setCellStyle(s.wrap);
    }

    private static List<SourceFinding> allFindings(List<SourceScanResult> results) {
        List<SourceFinding> all = new ArrayList<>();
        for (SourceScanResult result : results) all.addAll(result.findings());
        return all;
    }


    private static Map<String, List<SourceScanResult>> resultsByComponent(List<SourceScanResult> results) {
        Map<String, List<SourceScanResult>> grouped = new LinkedHashMap<>();
        results.stream()
                .sorted(Comparator.comparing(r -> r.component().displayName(), String.CASE_INSENSITIVE_ORDER))
                .forEach(result -> grouped.computeIfAbsent(result.component().displayName(), k -> new ArrayList<>()).add(result));
        return grouped;
    }

    private static String displayComponent(SourceFinding finding) {
        if (finding.component() != null && !finding.component().isBlank()) return finding.component();
        if (finding.repository() != null && !finding.repository().isBlank()) return finding.repository();
        return "Unknown Component";
    }

    private static List<SourceFinding> sortedFindings(List<SourceScanResult> results) {
        return allFindings(results).stream()
                .sorted(Comparator.comparing(SourceInventoryReportWriter::displayComponent, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt((SourceFinding f) -> severityOrder(f.severity()))
                        .thenComparing(SourceFinding::category, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(SourceFinding::file, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparingInt(SourceFinding::line))
                .toList();
    }

    private static int severityOrder(String severity) {
        return switch (severityBucket(severity)) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM", "WARNING" -> 2;
            case "INFO" -> 3;
            default -> 4;
        };
    }

    private static String severityBucket(String severity) {
        if (severity == null || severity.isBlank()) return "OTHER";
        String s = severity.trim().toUpperCase();
        return switch (s) {
            case "CRITICAL", "HIGH", "MEDIUM", "WARNING", "INFO" -> s;
            default -> "OTHER";
        };
    }

    private static long countSeverity(List<SourceFinding> findings, String severity) {
        return findings.stream().filter(f -> severityBucket(f.severity()).equals(severity)).count();
    }

    private static long checkoutFailureCount(List<SourceScanResult> results) {
        return results.stream().filter(r -> !r.checkout().success()).count();
    }

    private static int totalFilesScanned(List<SourceScanResult> results) {
        return results.stream().mapToInt(SourceScanResult::filesScanned).sum();
    }

    private static String productName(String module) {
        return "wl14".equalsIgnoreCase(module) ? "WL14" : module;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String actionText(SourceFinding f) {
        if (f.remediation() != null && !f.remediation().isBlank()) return f.remediation();
        if (f.description() != null && !f.description().isBlank()) return f.description();
        return "Review rule " + safe(f.rule()) + " in " + safe(f.file());
    }

    private static double estimateHours(List<SourceFinding> findings) {
        double total = 0.0;
        for (SourceFinding f : findings) {
            total += switch (severityBucket(f.severity())) {
                case "CRITICAL" -> 2.5;
                case "HIGH" -> 1.5;
                case "MEDIUM", "WARNING" -> 0.75;
                case "INFO" -> 0.25;
                default -> 0.5;
            };
        }
        return total;
    }

    private static class ChecklistItem {
        private final SourceFinding first;
        private final java.util.Set<String> files = new java.util.LinkedHashSet<>();

        private ChecklistItem(SourceFinding first) {
            this.first = first;
        }
    }

    private static class Styles {
        final CellStyle title;
        final CellStyle colHeader;
        final CellStyle sectionHeader;
        final CellStyle infoBar;
        final CellStyle wrap;
        final CellStyle instLabel;
        final CellStyle checkoutError;
        final CellStyle effortNum;
        final CellStyle grandTotalRowStyle;
        final CellStyle grandTotalValueStyle;
        final Map<String, CellStyle> severityStyles = new LinkedHashMap<>();

        Styles(Workbook wb) {
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleFont.setColor(IndexedColors.WHITE.getIndex());
            title = wb.createCellStyle();
            title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            title.setFont(titleFont);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            colHeader = wb.createCellStyle();
            colHeader.setFillForegroundColor(IndexedColors.BLUE_GREY.getIndex());
            colHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            colHeader.setFont(headerFont);
            colHeader.setWrapText(true);

            Font sectionFont = wb.createFont();
            sectionFont.setBold(true);
            sectionFont.setColor(IndexedColors.WHITE.getIndex());
            sectionHeader = wb.createCellStyle();
            sectionHeader.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
            sectionHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sectionHeader.setFont(sectionFont);

            infoBar = wb.createCellStyle();
            infoBar.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            infoBar.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            infoBar.setWrapText(true);
            infoBar.setVerticalAlignment(VerticalAlignment.CENTER);

            wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);

            Font labelFont = wb.createFont();
            labelFont.setBold(true);
            instLabel = wb.createCellStyle();
            instLabel.setFont(labelFont);
            instLabel.setWrapText(true);
            instLabel.setVerticalAlignment(VerticalAlignment.TOP);

            checkoutError = wb.createCellStyle();
            checkoutError.cloneStyleFrom(wrap);
            checkoutError.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            checkoutError.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            for (Map.Entry<String, IndexedColors> entry : SEV_COLORS.entrySet()) {
                CellStyle style = wb.createCellStyle();
                style.setFillForegroundColor(entry.getValue().getIndex());
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                Font font = wb.createFont();
                font.setBold("CRITICAL".equals(entry.getKey()) || "HIGH".equals(entry.getKey()));
                style.setFont(font);
                severityStyles.put(entry.getKey(), style);
            }

            DataFormat df = wb.createDataFormat();
            effortNum = wb.createCellStyle();
            effortNum.setAlignment(HorizontalAlignment.RIGHT);
            effortNum.setDataFormat(df.getFormat("0.0"));

            Font totalFont = wb.createFont();
            totalFont.setBold(true);
            totalFont.setColor(IndexedColors.WHITE.getIndex());
            grandTotalRowStyle = wb.createCellStyle();
            grandTotalRowStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            grandTotalRowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            grandTotalRowStyle.setFont(totalFont);

            grandTotalValueStyle = wb.createCellStyle();
            grandTotalValueStyle.cloneStyleFrom(grandTotalRowStyle);
            grandTotalValueStyle.setAlignment(HorizontalAlignment.RIGHT);
            grandTotalValueStyle.setDataFormat(df.getFormat("0.0"));
        }

        CellStyle severityStyle(String severity) {
            return severityStyles.get(severityBucket(severity));
        }
    }
}
