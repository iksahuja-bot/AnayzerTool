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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Writes source-inventory checkout and source-scan results. */
public class SourceInventoryReportWriter {

    private static final Logger logger = LogManager.getLogger(SourceInventoryReportWriter.class);
    private static final String INSTRUCTIONS_SHEET = "📋 Instructions";
    private static final String SUMMARY_SHEET = "📊 Summary";
    private static final String ACTION_ITEMS_SHEET = "🎯 Action Items";
    private static final String ACTION_ITEMS_RAW_SHEET = "Action Items Raw";
    private static final String SOURCE_INVENTORY_SHEET = "Source Inventory";
    private static final String SOURCE_FINDINGS_SHEET = "Source Findings";
    private static final String CHECKOUT_ERRORS_SHEET = "Checkout Errors";

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
            Styles styles = new Styles(wb);
            removeSheetIfPresent(wb, SOURCE_INVENTORY_SHEET);
            removeSheetIfPresent(wb, SOURCE_FINDINGS_SHEET);
            removeSheetIfPresent(wb, ACTION_ITEMS_SHEET);
            removeSheetIfPresent(wb, ACTION_ITEMS_RAW_SHEET);
            removeSheetIfPresent(wb, CHECKOUT_ERRORS_SHEET);
            augmentInstructionsSheet(wb, styles, module);
            writeSourceSheets(wb, styles, module, results);
            orderSourceSheets(wb);
            writeWorkbook(wb, outPath);
        }
        logger.info("Source inventory sheets appended to report: {}", outPath);
    }

    private void writeStandaloneReport(Workbook wb, String module, List<SourceScanResult> results) {
        Styles s = new Styles(wb);
        writeInstructionsSheet(wb, s, module);
        writeSummarySheet(wb, s, module, results);
        writeSourceSheets(wb, s, module, results);
        orderSourceSheets(wb);
        writeChecklistSheet(wb, s, module, results);
        writeEffortSheet(wb, s, module, results);
    }

    private void writeSourceSheets(Workbook wb, Styles s, String module, List<SourceScanResult> results) {
        writeActionItemsSheet(wb, s, module, results);
        writeActionItemsRawSheet(wb, s, module, results);
        writeInventorySheet(wb, s, module, results);
        writeFindingsSheet(wb, s, module, results);
        writeCheckoutErrorsSheet(wb, s, results);
    }

    private void orderSourceSheets(Workbook wb) {
        int summaryIndex = wb.getSheetIndex(SUMMARY_SHEET);
        if (summaryIndex < 0) {
            summaryIndex = Math.min(1, Math.max(0, wb.getNumberOfSheets() - 1));
        }

        moveSheetAfter(wb, ACTION_ITEMS_SHEET, summaryIndex);
        int actionIndex = wb.getSheetIndex(ACTION_ITEMS_SHEET);
        moveSheetAfter(wb, SOURCE_INVENTORY_SHEET, actionIndex);
        moveSheetAfter(wb, SOURCE_FINDINGS_SHEET, wb.getSheetIndex(SOURCE_INVENTORY_SHEET));
        moveSheetAfter(wb, CHECKOUT_ERRORS_SHEET, wb.getSheetIndex(SOURCE_FINDINGS_SHEET));
        moveSheetAfter(wb, ACTION_ITEMS_RAW_SHEET, wb.getSheetIndex(CHECKOUT_ERRORS_SHEET));
        int rawIndex = wb.getSheetIndex(ACTION_ITEMS_RAW_SHEET);
        if (rawIndex >= 0) wb.setSheetHidden(rawIndex, true);
    }

    private void moveSheetAfter(Workbook wb, String sheetName, int afterIndex) {
        int currentIndex = wb.getSheetIndex(sheetName);
        if (currentIndex < 0 || afterIndex < 0) return;
        int targetIndex = Math.min(afterIndex + 1, wb.getNumberOfSheets() - 1);
        if (currentIndex == targetIndex) return;
        wb.setSheetOrder(sheetName, targetIndex);
    }

    private void augmentInstructionsSheet(Workbook wb, Styles s, String module) {
        Sheet sheet = wb.getSheet(INSTRUCTIONS_SHEET);
        if (sheet == null || instructionSheetAlreadyMentionsSource(sheet)) return;

        int r = sheet.getLastRowNum() + 2;
        addSection(sheet, s, r++, "SOURCE INVENTORY SHEETS ADDED");
        addSourceInstructionRows(sheet, s, r, module);
    }

    private static boolean instructionSheetAlreadyMentionsSource(Sheet sheet) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING && ACTION_ITEMS_SHEET.equals(cell.getStringCellValue())) {
                    return true;
                }
            }
        }
        return false;
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
        Sheet sheet = wb.createSheet(INSTRUCTIONS_SHEET);
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

        r = addSourceInstructionRows(sheet, s, r, module);
        addInstRow(sheet, s, r++, "⏱ Effort Analysis", "Component-level remediation estimate using the same severity-based planning model as normal migration reports.");
        r++;

        addSection(sheet, s, r++, "SEVERITY GUIDE");
        addInstRow(sheet, s, r++, "CRITICAL  (pink)", "Blocking source issue. Treat as required remediation before deployment/migration validation.");
        addInstRow(sheet, s, r++, "HIGH  (orange)", "Likely migration/runtime risk. Prioritize with CRITICAL items.");
        addInstRow(sheet, s, r++, "WARNING  (yellow)", "Known compatibility or cleanup item. Plan and track before the target cutover.");
        addInstRow(sheet, s, r++, "INFO  (green)", "Informational or low-risk modernization item.");
        r++;

        addSection(sheet, s, r++, "VALIDATION / CONFIDENCE GUIDE");
        addInstRow(sheet, s, r++, "CONFIRMED_SOURCE_AND_BYTECODE", "The same component/rule/API was found in source and in declared generated JAR bytecode. Confidence is HIGH.");
        addInstRow(sheet, s, r++, "CONFIRMED_BYTECODE_ONLY", "The generated JAR contains bytecode evidence that was not found in scanned source. Treat as real until ownership/source mapping is clarified.");
        addInstRow(sheet, s, r++, "CANDIDATE_SOURCE_ONLY", "Source evidence was found but no declared generated JAR confirmed it. Do not drop it; verify whether it is compiled, test-only, generated, or inactive code.");
        addInstRow(sheet, s, r++, "CONFIG_ONLY / BUILD_ONLY / TEST_ONLY", "Source evidence is config/build/test scoped and may not appear in class bytecode. Severity and confidence remain separate fields.");
        r++;

        addSection(sheet, s, r++, "RECOMMENDED STEPS");
        addInstRow(sheet, s, r++, "Step 1 — Validate checkouts", "Open Checkout Errors and fix SCM/PATH/credential failures first.");
        addInstRow(sheet, s, r++, "Step 2 — Assess scope", "Open 📊 Summary to see the action-item-based component backlog and checkout coverage.");
        addInstRow(sheet, s, r++, "Step 3 — Remediate", "Open 🎯 Action Items next. It is the primary working backlog; Source Findings is raw drill-down evidence.");
        addInstRow(sheet, s, r++, "Step 4 — Re-run", "After fixes land, re-run the repository scan and confirm findings/checkouts improve.");
    }

    private static int addSourceInstructionRows(Sheet sheet, Styles s, int r, String module) {
        addInstRow(sheet, s, r++, SUMMARY_SHEET,
                "Executive view based on the focused 🎯 Action Items backlog, plus repository checkout and scan coverage.");
        addInstRow(sheet, s, r++, ACTION_ITEMS_SHEET,
                "Primary remediation backlog. Start here after Summary; duplicates are grouped, priorities/confidence are labeled, source-only components remain visible, and automation columns identify patchable work.");
        addInstRow(sheet, s, r++, ACTION_ITEMS_RAW_SHEET,
                "Hidden normalized action-item extract for scripts/skills. It repeats stable IDs, remediation type, readiness, symbols, files, validation, guardrails, fixable source workspace paths, and trunk workspace paths without component heading rows.");
        addInstRow(sheet, s, r++, SOURCE_INVENTORY_SHEET,
                "One row per component/repository from the input workbook, including checkout status, generated JARs, application packages, and scan totals.");
        addInstRow(sheet, s, r++, SOURCE_FINDINGS_SHEET,
                "Full raw source + generated-JAR evidence. Filter by Validation Status, Confidence, Detection Source, Severity, Component, Scanner, Category, Rule, or File.");
        addInstRow(sheet, s, r++, CHECKOUT_ERRORS_SHEET,
                "Only failed repository checkouts/updates. Fix these when source rows are missing or incomplete.");
        addInstRow(sheet, s, r++, "✅ Remediation Checklist",
                "Deduplicated action list for project tracking. Mark Done?, Owner, and Notes as work progresses.");
        addInstRow(sheet, s, r++, "⏱ Effort Analysis",
                "Component-level remediation estimate for the " + productName(module) + " source scan.");
        return r;
    }

    private void writeSummarySheet(Workbook wb, Styles s, String module, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet(SUMMARY_SHEET);
        int[] widths = {36, 14, 14, 14, 14, 14, 14, 14, 14, 18};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = 0;
        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue(productName(module) + " Repository Source Scan — Summary");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, widths.length - 1));

        Row info = sheet.createRow(r++);
        List<ActionItem> actionItems = focusedActionItems(results);
        Cell ic = info.createCell(0);
        ic.setCellValue("Generated: " + LocalDate.now()
                + "   |   Components: " + results.size()
                + "   |   Checkout failures: " + checkoutFailureCount(results)
                + "   |   Files scanned: " + totalFilesScanned(results)
                + "   |   Action items: " + actionItems.size());
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
                {"Focused action items", String.valueOf(actionItems.size())},
                {"Raw correlated findings", String.valueOf(allFindings(results).size())}
        };
        for (String[] metric : metrics) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(metric[0]);
            row.createCell(1).setCellValue(Integer.parseInt(metric[1]));
        }

        r++;
        Row sevHdr = sheet.createRow(r++);
        cellH(sevHdr, s.colHeader, 0, "Action Items by Severity");
        cellH(sevHdr, s.colHeader, 1, "Count");
        for (String sev : List.of("CRITICAL", "HIGH", "MEDIUM", "WARNING", "INFO", "OTHER")) {
            long count = actionItems.stream().filter(item -> severityBucket(item.first.severity()).equals(sev)).count();
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(sev);
            Cell countCell = row.createCell(1);
            countCell.setCellValue(count);
            CellStyle sevStyle = s.severityStyle(sev);
            if (sevStyle != null) row.getCell(0).setCellStyle(sevStyle);
        }

        r++;
        Row compHdr = sheet.createRow(r++);
        String[] cols = {"Component", "Checkout", "Files", "Action Items", "Bytecode Findings", "Confirmed", "Source-only", "Generated JARs", "Medium/Warning", "High"};
        for (int i = 0; i < cols.length; i++) cellH(compHdr, s.colHeader, i, cols[i]);
        sheet.setAutoFilter(new CellRangeAddress(compHdr.getRowNum(), compHdr.getRowNum(), 0, cols.length - 1));
        sheet.createFreezePane(0, compHdr.getRowNum() + 1);
        for (SourceScanResult result : results) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(result.component().displayName());
            row.createCell(1).setCellValue(result.checkout().status());
            List<SourceFinding> correlated = SourceFindingCorrelator.correlate(result);
            List<ActionItem> componentActions = actionItems.stream()
                    .filter(item -> displayComponent(item.first).equals(result.component().displayName()))
                    .toList();
            row.createCell(2).setCellValue(result.filesScanned());
            row.createCell(3).setCellValue(componentActions.size());
            row.createCell(4).setCellValue(result.bytecodeFindings().size());
            row.createCell(5).setCellValue(componentActions.stream().filter(ActionItem::hasConfirmedEvidence).count());
            row.createCell(6).setCellValue(componentActions.stream().filter(item -> item.evidenceLabel().startsWith("Source only")).count());
            row.createCell(7).setCellValue(result.component().generatedJars().size());
            row.createCell(8).setCellValue(countActionSeverity(componentActions, "MEDIUM") + countActionSeverity(componentActions, "WARNING"));
            row.createCell(9).setCellValue(countActionSeverity(componentActions, "HIGH"));
        }
    }

    private void writeInventorySheet(Workbook wb, Styles s, String module, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet(SOURCE_INVENTORY_SHEET);
        int[] widths = {14, 28, 58, 58, 10, 10, 18, 58, 18, 58, 14, 12, 18, 55, 35, 24};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = addTitleAndInfo(sheet, s, "Source Inventory", "One row per repository component from the input workbook.", widths.length);
        String[] headers = {"Module", "Component", "Repository", "Trunk", "Type", "Enabled", "Checkout Status", "Checkout Path", "Trunk Checkout Status", "Trunk Checkout Path", "Files Scanned", "Findings", "Bytecode Findings", "Generated JARs", "Application Packages", "Ownership"};
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
                row.createCell(3).setCellValue(result.component().trunk());
                row.createCell(4).setCellValue(result.component().type().name());
                row.createCell(5).setCellValue(result.component().enabled());
                row.createCell(6).setCellValue(result.checkout().status());
                row.createCell(7).setCellValue(result.checkout().checkoutPath() == null ? "" : result.checkout().checkoutPath().toString());
                row.createCell(8).setCellValue(checkoutStatus(result.trunkCheckout()));
                row.createCell(9).setCellValue(checkoutPath(result.trunkCheckout()));
                row.createCell(10).setCellValue(result.filesScanned());
                row.createCell(11).setCellValue(SourceFindingCorrelator.correlate(result).size());
                row.createCell(12).setCellValue(result.bytecodeFindings().size());
                row.createCell(13).setCellValue(result.component().generatedJarsDisplay());
                row.createCell(14).setCellValue(result.component().applicationPackagesDisplay());
                row.createCell(15).setCellValue(result.component().ownership());
                if (!result.checkout().success()) row.getCell(6).setCellStyle(s.checkoutError);
                if (result.trunkCheckout() != null && !result.trunkCheckout().success()
                        && !"TRUNK_NOT_PROVIDED".equals(result.trunkCheckout().status())) row.getCell(8).setCellStyle(s.checkoutError);
            }
        }
    }

    private void writeFindingsSheet(Workbook wb, Styles s, String module, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet(SOURCE_FINDINGS_SHEET);
        int[] widths = {28, 52, 14, 24, 12, 18, 26, 14, 14, 14, 38, 20, 50, 8, 38, 54, 54, 70, 40, 24};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = addTitleAndInfo(sheet, s, productName(module) + " Source Findings — Full Detail",
                "Line-level source findings correlated with declared generated JAR bytecode. Source-only findings are downgraded as candidates, not silently dropped.", widths.length);

        List<SourceFinding> all = sortedFindings(results);
        if (all.isEmpty()) {
            sheet.createRow(r).createCell(0).setCellValue("✅  No source findings found.");
            return;
        }

        String[] headers = {"Component", "Repository", "Scanner", "Category", "Severity", "Detection Source", "Validation Status", "Confidence", "Matched In Source", "Matched In Bytecode", "Matched JARs", "Reason Code", "File", "Line", "Rule", "Description", "Remediation", "Context", "Matched Classes", "Ownership"};
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

            row.createCell(5).setCellValue(f.detectionSource());
            row.createCell(6).setCellValue(f.validationStatus());
            row.createCell(7).setCellValue(f.confidence());
            row.createCell(8).setCellValue(f.matchedInSource());
            row.createCell(9).setCellValue(f.matchedInBytecode());
            row.createCell(10).setCellValue(f.matchedJars());
            row.createCell(11).setCellValue(f.reasonCode());
            row.createCell(12).setCellValue(f.file());
            row.createCell(13).setCellValue(f.line() > 0 ? f.line() : 0);
            row.createCell(14).setCellValue(f.rule());

            Cell desc = row.createCell(15);
            desc.setCellValue(f.description());
            desc.setCellStyle(s.wrap);

            Cell rem = row.createCell(16);
            rem.setCellValue(f.remediation());
            rem.setCellStyle(s.wrap);

            Cell ctx = row.createCell(17);
            ctx.setCellValue(f.context());
            ctx.setCellStyle(s.wrap);

            set(row, 18, f.bytecodeClass(), s.wrap);
            set(row, 19, f.ownership(), s.wrap);
        }
    }

    private void writeActionItemsSheet(Workbook wb, Styles s, String module, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet(ACTION_ITEMS_SHEET);
        int[] widths = {18, 10, 18, 24, 28, 22, 34, 14, 14, 14, 16, 22, 22, 28, 28, 36, 70, 70, 42, 58, 58, 70, 32, 50, 40, 24};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = addTitleAndInfo(sheet, s, productName(module) + " Focused Action Items",
                "Primary remediation backlog. Items are visibly grouped by component, then ranked by actionable priority within each component. Raw sheets keep every finding.", widths.length);

        String[] headers = {"Action Item ID", "Priority", "Confidence", "Remediation Type", "Component", "Ownership", "Issue / Rule / API", "Severity",
                "Evidence", "Raw Count", "Files", "Detection Source", "Validation Status", "Automation Readiness", "Detected Symbol", "Replacement Symbol",
                "Recommended Code Change", "Automation Starting Point", "Primary File", "Fixable Source Location", "Trunk Source Location", "Trunk Fix Guidance", "Line Hints",
                "Sample Files", "Matched JARs / Classes", "Noise / Review Reason"};
        writeHeader(sheet, s.colHeader, r++, headers);
        sheet.setAutoFilter(new CellRangeAddress(r - 1, r - 1, 0, headers.length - 1));
        sheet.createFreezePane(0, r);

        List<ActionItem> items = focusedActionItems(results);
        Map<String, SourceComponent> components = componentsByDisplayName(results);
        if (items.isEmpty()) {
            Row row = sheet.createRow(r);
            Cell c = row.createCell(0);
            c.setCellValue("✅ No focused action items were detected. Use Source Findings for raw evidence if needed.");
            c.setCellStyle(s.wrap);
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, headers.length - 1));
            return;
        }

        String currentComponent = null;
        int componentStartRow = -1;
        for (ActionItem item : items) {
            String component = displayComponent(item.first);
            if (!component.equals(currentComponent)) {
                groupActionItemRows(sheet, componentStartRow, r - 1);
                currentComponent = component;
                SourceComponent sourceComponent = components.get(component);
                Row compRow = sheet.createRow(r++);
                Cell cc = compRow.createCell(0);
                cc.setCellValue(componentActionHeading(component, sourceComponent, items));
                cc.setCellStyle(s.sectionHeader);
                sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, headers.length - 1));
                componentStartRow = r;
            }
            SourceFinding f = item.first;
            SourceComponent sourceComponent = components.get(component);
            Row row = sheet.createRow(r++);
            row.setHeightInPoints(58);

            set(row, 0, actionItemId(module, item), s.wrap);
            row.createCell(1).setCellValue(item.priorityLabel());
            row.createCell(2).setCellValue(item.confidenceLabel());
            set(row, 3, remediationType(f), s.wrap);
            set(row, 4, component, s.wrap);
            set(row, 5, f.ownership(), s.wrap);
            set(row, 6, f.rule(), s.wrap);

            Cell sev = row.createCell(7);
            sev.setCellValue(severityBucket(f.severity()));
            CellStyle sevStyle = s.severityStyle(f.severity());
            if (sevStyle != null) sev.setCellStyle(sevStyle);

            row.createCell(8).setCellValue(item.evidenceLabel());
            row.createCell(9).setCellValue(item.count);
            row.createCell(10).setCellValue(item.files.size());
            set(row, 11, joinLimited(item.detectionSources, 4), s.wrap);
            set(row, 12, joinLimited(item.validationStatuses, 4), s.wrap);
            set(row, 13, automationReadiness(f, item), s.wrap);
            set(row, 14, detectedSymbol(f), s.wrap);
            set(row, 15, replacementSymbol(f), s.wrap);
            set(row, 16, recommendedCodeChange(f), s.wrap);
            set(row, 17, automationStartingPoint(f, item), s.wrap);
            set(row, 18, item.primaryFile(), s.wrap);
            set(row, 19, fixableSourceLocation(sourceComponent, results), s.wrap);
            set(row, 20, trunkSourceLocation(sourceComponent, results), s.wrap);
            set(row, 21, trunkFixGuidance(sourceComponent, f, item, results), s.wrap);
            set(row, 22, item.lineHints(), s.wrap);
            set(row, 23, joinLimited(item.files, 6), s.wrap);
            set(row, 24, matchedEvidence(item), s.wrap);
            set(row, 25, noiseReason(f, item), s.wrap);
        }
        groupActionItemRows(sheet, componentStartRow, r - 1);
        sheet.setRowSumsBelow(false);
    }

    private void writeActionItemsRawSheet(Workbook wb, Styles s, String module, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet(ACTION_ITEMS_RAW_SHEET);
        int[] widths = {18, 24, 28, 22, 34, 14, 18, 28, 28, 28, 36, 40, 70, 70, 70, 58, 58, 18, 58, 18, 50, 22, 20, 18, 18, 18, 22, 22};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        String[] headers = {"action_item_id", "remediation_type", "component", "ownership", "rule", "severity", "confidence",
                "automation_readiness", "detected_symbol", "replacement_symbol", "primary_file", "line_hints", "all_candidate_files",
                "recommended_code_change", "automation_starting_point", "fixable_source_url", "fixable_source_path", "trunk_source_url", "trunk_source_path", "trunk_checkout_status", "trunk_fix_guidance", "raw_count", "file_count",
                "evidence", "detection_sources", "validation_statuses", "suggested_validation", "automation_guardrails"};
        writeHeader(sheet, s.colHeader, 0, headers);
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headers.length - 1));
        sheet.createFreezePane(0, 1);

        List<ActionItem> items = focusedActionItems(results);
        Map<String, SourceComponent> components = componentsByDisplayName(results);
        int r = 1;
        for (ActionItem item : items) {
            SourceFinding f = item.first;
            SourceComponent sourceComponent = components.get(displayComponent(f));
            Row row = sheet.createRow(r++);
            set(row, 0, actionItemId(module, item), s.wrap);
            set(row, 1, remediationType(f), s.wrap);
            set(row, 2, displayComponent(f), s.wrap);
            set(row, 3, f.ownership(), s.wrap);
            set(row, 4, f.rule(), s.wrap);
            set(row, 5, severityBucket(f.severity()), s.wrap);
            set(row, 6, item.confidenceLabel(), s.wrap);
            set(row, 7, automationReadiness(f, item), s.wrap);
            set(row, 8, detectedSymbol(f), s.wrap);
            set(row, 9, replacementSymbol(f), s.wrap);
            set(row, 10, item.primaryFile(), s.wrap);
            set(row, 11, item.lineHints(), s.wrap);
            set(row, 12, joinLimited(item.files, 1000), s.wrap);
            set(row, 13, recommendedCodeChange(f), s.wrap);
            set(row, 14, automationStartingPoint(f, item), s.wrap);
            SourceScanResult sourceResult = sourceResultForComponent(sourceComponent, results);
            set(row, 15, sourceComponent == null ? "" : sourceComponent.repository(), s.wrap);
            set(row, 16, sourceResult == null ? "" : checkoutPath(sourceResult.checkout()), s.wrap);
            set(row, 17, sourceComponent == null ? "" : sourceComponent.trunk(), s.wrap);
            set(row, 18, sourceResult == null ? "" : checkoutPath(sourceResult.trunkCheckout()), s.wrap);
            set(row, 19, sourceResult == null ? "" : checkoutStatus(sourceResult.trunkCheckout()), s.wrap);
            set(row, 20, trunkFixGuidance(sourceComponent, f, item, results), s.wrap);
            row.createCell(21).setCellValue(item.count);
            row.createCell(22).setCellValue(item.files.size());
            set(row, 23, item.evidenceLabel(), s.wrap);
            set(row, 24, joinLimited(item.detectionSources, 1000), s.wrap);
            set(row, 25, joinLimited(item.validationStatuses, 1000), s.wrap);
            set(row, 26, suggestedValidation(), s.wrap);
            set(row, 27, automationGuardrails(sourceComponent, f, item, sourceResult), s.wrap);
        }
    }

    private static void groupActionItemRows(Sheet sheet, int startRow, int endRow) {
        if (startRow < 0 || endRow < startRow) return;
        sheet.groupRow(startRow, endRow);
    }

    private void writeCheckoutErrorsSheet(Workbook wb, Styles s, List<SourceScanResult> results) {
        Sheet sheet = wb.createSheet(CHECKOUT_ERRORS_SHEET);
        int[] widths = {28, 18, 58, 10, 18, 85};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int r = addTitleAndInfo(sheet, s, "Repository Checkout Errors",
                "Components listed here did not checkout/update successfully. Fix these before treating repository scan coverage as complete.", widths.length);
        String[] headers = {"Component", "Checkout Role", "Repository", "Type", "Status", "Message"};
        writeHeader(sheet, s.colHeader, r++, headers);
        sheet.setAutoFilter(new CellRangeAddress(r - 1, r - 1, 0, headers.length - 1));
        sheet.createFreezePane(0, r);
        for (SourceScanResult result : results) {
            if (result.checkout().success()) continue;
            SourceComponent c = result.component();
            Row row = sheet.createRow(r++);
            row.setHeightInPoints(38);
            set(row, 0, c.component(), s.wrap);
            set(row, 1, "FIXABLE", s.wrap);
            set(row, 2, c.repository(), s.wrap);
            set(row, 3, c.type().name(), s.wrap);
            set(row, 4, result.checkout().status(), s.checkoutError);
            set(row, 5, result.checkout().message(), s.wrap);
        }
        for (SourceScanResult result : results) {
            if (result.trunkCheckout() == null || result.trunkCheckout().success()
                    || "TRUNK_NOT_PROVIDED".equals(result.trunkCheckout().status())) continue;
            SourceComponent c = result.component();
            Row row = sheet.createRow(r++);
            row.setHeightInPoints(38);
            set(row, 0, c.component(), s.wrap);
            set(row, 1, "TRUNK", s.wrap);
            set(row, 2, c.trunk(), s.wrap);
            set(row, 3, result.trunkCheckout().component().type().name(), s.wrap);
            set(row, 4, result.trunkCheckout().status(), s.checkoutError);
            set(row, 5, result.trunkCheckout().message(), s.wrap);
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
            String key = severityBucket(f.severity()) + "|" + safe(f.component()) + "|" + safe(f.category()) + "|" + safe(f.rule()) + "|" + safe(f.validationStatus()) + "|" + safe(f.remediation());
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
            List<SourceFinding> correlated = SourceFindingCorrelator.correlate(result);
            double hours = estimateHours(correlated);
            grandTotal += hours;
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(result.component().displayName());
            row.createCell(1).setCellValue(result.filesScanned());
            row.createCell(2).setCellValue(correlated.size());
            row.createCell(3).setCellValue(countSeverity(correlated, "CRITICAL"));
            row.createCell(4).setCellValue(countSeverity(correlated, "HIGH"));
            row.createCell(5).setCellValue(countSeverity(correlated, "MEDIUM") + countSeverity(correlated, "WARNING"));
            row.createCell(6).setCellValue(countSeverity(correlated, "INFO"));
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
        return SourceFindingCorrelator.correlatedFindings(results);
    }


    private static Map<String, List<SourceScanResult>> resultsByComponent(List<SourceScanResult> results) {
        Map<String, List<SourceScanResult>> grouped = new LinkedHashMap<>();
        results.stream()
                .sorted(Comparator.comparing(r -> r.component().displayName(), String.CASE_INSENSITIVE_ORDER))
                .forEach(result -> grouped.computeIfAbsent(result.component().displayName(), k -> new ArrayList<>()).add(result));
        return grouped;
    }

    private static Map<String, SourceComponent> componentsByDisplayName(List<SourceScanResult> results) {
        Map<String, SourceComponent> components = new LinkedHashMap<>();
        results.stream()
                .sorted(Comparator.comparing(r -> r.component().displayName(), String.CASE_INSENSITIVE_ORDER))
                .forEach(result -> components.putIfAbsent(result.component().displayName(), result.component()));
        return components;
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

    private static List<ActionItem> focusedActionItems(List<SourceScanResult> results) {
        Map<String, ActionItem> grouped = new LinkedHashMap<>();
        for (SourceFinding f : sortedFindings(results)) {
            if (isLowSignalNoise(f)) continue;
            String key = displayComponent(f) + "|" + safe(f.scanner()) + "|" + safe(f.category())
                    + "|" + safe(f.rule()) + "|" + severityBucket(f.severity()) + "|" + safe(f.ownership());
            grouped.computeIfAbsent(key, ignored -> new ActionItem(f)).add(f);
        }
        return grouped.values().stream()
                .sorted(Comparator.comparing((ActionItem item) -> displayComponent(item.first), String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(ActionItem::priorityRank)
                        .thenComparing(item -> safe(item.first.rule()), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static boolean isLowSignalNoise(SourceFinding f) {
        String severity = severityBucket(f.severity());
        String status = safe(f.validationStatus()).toUpperCase();
        String ownership = safe(f.ownership()).toUpperCase();
        return "INFO".equals(severity)
                && ("TEST_ONLY".equals(status) || "BUILD_ONLY".equals(status))
                && !"APPLICATION_CODE".equals(ownership);
    }

    private static String actionItemId(String module, ActionItem item) {
        SourceFinding f = item.first;
        String key = String.join("|", safe(module).toLowerCase(), displayComponent(f), safe(f.scanner()), safe(f.category()),
                safe(f.rule()), severityBucket(f.severity()), safe(f.ownership()), remediationType(f), item.primaryFile());
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return safe(module).toUpperCase() + "-AI-" + String.format("%08X", crc.getValue());
    }

    private static String remediationType(SourceFinding f) {
        String status = safe(f.validationStatus()).toUpperCase();
        String category = safe(f.category()).toLowerCase();
        String scanner = safe(f.scanner()).toLowerCase();
        String file = safe(f.file()).toLowerCase();
        String rule = safe(f.rule()).toLowerCase();
        if ("BUILD_ONLY".equals(status) || file.endsWith("pom.xml") || file.endsWith("build.gradle")
                || file.endsWith("settings.gradle") || category.contains("library") || scanner.contains("library")) {
            return "DEPENDENCY_OR_BUILD_UPDATE";
        }
        if ("CONFIG_ONLY".equals(status) || file.endsWith(".xml") || file.endsWith(".properties") || file.endsWith(".yaml") || file.endsWith(".yml")) {
            return "CONFIG_UPDATE";
        }
        if (category.contains("spring") || category.contains("guava") || category.contains("guice")) return "API_UPGRADE";
        if (category.contains("java") || rule.startsWith("javax.")) return "JAVA_API_MIGRATION";
        if (category.contains("weblogic") || rule.contains("weblogic")) return "WEBLOGIC_API_REPLACEMENT";
        if ("TEST_ONLY".equals(status)) return "TEST_UPDATE";
        return "SOURCE_REWRITE";
    }

    private static String automationReadiness(SourceFinding f, ActionItem item) {
        String status = safe(f.validationStatus()).toUpperCase();
        if (item.files.isEmpty()) return "LOW — no source file candidate";
        if (item.files.size() > 10) return "LOW — broad multi-file change";
        if ("TEST_ONLY".equals(status)) return "LOW — test-scoped/manual priority decision";
        if ("CONFIRMED_BYTECODE_ONLY".equals(status)) return "LOW — map bytecode back to source first";
        if ("BUILD_ONLY".equals(status) || "CONFIG_ONLY".equals(status)) return "MEDIUM — structured non-Java edit";
        if (item.hasConfirmedEvidence()) return "HIGH — confirmed focused source edit";
        if ("CANDIDATE_SOURCE_ONLY".equals(status)) return "MEDIUM — source candidate; verify generated artifact mapping";
        return "MEDIUM — review evidence before automated patch";
    }

    private static String detectedSymbol(SourceFinding f) {
        if (!safe(f.rule()).isBlank()) return f.rule();
        if (!safe(f.sourceClass()).isBlank()) return f.sourceClass();
        if (!safe(f.bytecodeClass()).isBlank()) return f.bytecodeClass();
        return safe(f.context());
    }

    private static String replacementSymbol(SourceFinding f) {
        String remediation = safe(f.remediation());
        String rule = safe(f.rule());
        if (rule.startsWith("javax.")) return "jakarta." + rule.substring("javax.".length());
        if (remediation.toLowerCase().contains("jakarta")) return "jakarta.* equivalent";
        if (remediation.toLowerCase().contains("supported replacement")) return "supported replacement from rule guidance";
        return "";
    }

    private static String recommendedCodeChange(SourceFinding f) {
        String remediation = safe(f.remediation()).trim();
        if (!remediation.isBlank()) return remediation;

        String rule = safe(f.rule());
        String category = safe(f.category()).toLowerCase();
        if (category.contains("java") || rule.startsWith("javax.")) {
            return "Replace or remove the Java/API usage reported by the rule. Prefer the supported Java 21/Jakarta equivalent, then rebuild the generated JAR and re-run the scan.";
        }
        if (category.contains("library") || category.contains("spring") || category.contains("guava") || category.contains("guice")) {
            return "Upgrade the dependency to the target version and replace this deprecated/removed API with the rule's supported replacement.";
        }
        if (category.contains("weblogic") || rule.toLowerCase().contains("weblogic")) {
            return "Remove WebLogic-specific API usage or isolate it behind target-platform-neutral code. Replace deployment/config APIs with supported target-server equivalents.";
        }
        return actionText(f);
    }

    private static String automationStartingPoint(SourceFinding f, ActionItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("Find rule/API: ").append(safe(f.rule())).append('\n');
        if (!item.files.isEmpty()) sb.append("Edit sample files: ").append(joinLimited(item.files, 3)).append('\n');
        if (!item.primaryFile().isBlank()) sb.append("Primary file: ").append(item.primaryFile()).append('\n');
        if (!safe(f.context()).isBlank()) sb.append("Match context: ").append(f.context()).append('\n');
        sb.append("Recipe candidate: ").append(recipeCandidate(f)).append('\n');
        sb.append("Validation: rebuild component, confirm generated artifacts in Generated JARs, then re-run both mode.");
        return sb.toString();
    }

    private static String trunkFixGuidance(SourceComponent component, SourceFinding f, ActionItem item, List<SourceScanResult> results) {
        String trunk = component == null ? "" : safe(component.trunk());
        if (trunk.isBlank()) {
            return "No Trunk value was supplied for this component. Use the recommended code change and source findings, or add a trunk/reference URL to ComponentList.xlsx to enable comparison with the latest fixed code.";
        }

        SourceScanResult result = sourceResultForComponent(component, results);
        String trunkPath = result == null ? "" : checkoutPath(result.trunkCheckout());
        StringBuilder sb = new StringBuilder();
        sb.append("Compare fixable source with trunk before editing. ");
        if (!trunkPath.isBlank()) {
            sb.append("Trunk is available locally at: ").append(trunkPath).append(". ");
        } else {
            sb.append("Trunk URL/reference: ").append(trunk).append(". ");
        }
        sb.append("Look for existing fixes for rule/API '").append(safe(f.rule())).append("'");
        if (!item.files.isEmpty()) sb.append(" in or near: ").append(joinLimited(item.files, 3));
        sb.append(". Port the smallest relevant change, then rebuild and re-run both mode.");
        return sb.toString();
    }

    private static String fixableSourceLocation(SourceComponent component, List<SourceScanResult> results) {
        SourceScanResult result = sourceResultForComponent(component, results);
        String path = result == null ? "" : checkoutPath(result.checkout());
        String url = component == null ? "" : safe(component.repository());
        if (path.isBlank()) return url;
        if (url.isBlank()) return path;
        return "URL: " + url + "\nWorkspace: " + path;
    }

    private static String trunkSourceLocation(SourceComponent component, List<SourceScanResult> results) {
        SourceScanResult result = sourceResultForComponent(component, results);
        String path = result == null ? "" : checkoutPath(result.trunkCheckout());
        String url = component == null ? "" : safe(component.trunk());
        if (path.isBlank()) return url;
        if (url.isBlank()) return path;
        return "URL: " + url + "\nWorkspace: " + path;
    }

    private static SourceScanResult sourceResultForComponent(SourceComponent component, List<SourceScanResult> results) {
        if (component == null) return null;
        String name = component.displayName();
        return results.stream()
                .filter(result -> result.component().displayName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static String checkoutPath(CheckoutResult checkout) {
        return checkout == null || checkout.checkoutPath() == null ? "" : checkout.checkoutPath().toString();
    }

    private static String checkoutStatus(CheckoutResult checkout) {
        return checkout == null ? "" : safe(checkout.status());
    }

    private static String componentActionHeading(String component, SourceComponent sourceComponent, List<ActionItem> allItems) {
        List<ActionItem> componentItems = allItems.stream()
                .filter(item -> displayComponent(item.first).equals(component))
                .toList();
        long confirmed = componentItems.stream().filter(ActionItem::hasConfirmedEvidence).count();
        String trunk = sourceComponent == null ? "" : safe(sourceComponent.trunk());
        String heading = "▶  " + component + " — " + componentItems.size() + " action item(s), " + confirmed + " confirmed";
        if (!trunk.isBlank()) heading += " — trunk/reference available";
        return heading;
    }

    private static String recipeCandidate(SourceFinding f) {
        String scanner = safe(f.scanner()).toLowerCase();
        String category = safe(f.category()).toLowerCase();
        String rule = safe(f.rule()).toLowerCase();
        if (scanner.contains("java") || category.contains("java") || rule.startsWith("javax.")) {
            return "OpenRewrite Java 21/Jakarta migration recipe or targeted import/member replacement.";
        }
        if (category.contains("spring")) return "OpenRewrite Spring upgrade recipe plus targeted API replacement.";
        if (category.contains("weblogic") || rule.contains("weblogic")) return "Custom WebLogic API rewrite/adapter recipe.";
        if (safe(f.file()).endsWith(".xml") || safe(f.file()).endsWith(".properties")) return "Configuration rewrite recipe.";
        return "Targeted source rewrite using rule/API token and replacement text.";
    }

    private static String noiseReason(SourceFinding f, ActionItem item) {
        String status = safe(f.validationStatus()).toUpperCase();
        if (item.hasConfirmedEvidence()) return "Confirmed by generated-JAR bytecode; focus first.";
        if ("CANDIDATE_SOURCE_ONLY".equals(status)) {
            return "Source-only candidate. Keep visible because component/source owns this code, but verify it is compiled and mapped in Generated JARs before fixing.";
        }
        if ("TEST_ONLY".equals(status)) return "Test scoped; fix if tests must run on target platform, otherwise lower priority.";
        if ("BUILD_ONLY".equals(status)) return "Build configuration only; action may be dependency/plugin/config update rather than Java code edit.";
        if ("CONFIG_ONLY".equals(status)) return "Configuration only; action may be XML/properties/deployment descriptor update.";
        if ("CONFIRMED_BYTECODE_ONLY".equals(status)) return "Bytecode-only. Treat as real, but map JAR/class back to source owner before automated edits.";
        return "Review raw Source Findings for line-level evidence.";
    }

    private static String matchedEvidence(ActionItem item) {
        List<String> parts = new ArrayList<>();
        if (!item.matchedJars.isEmpty()) parts.add("JARs: " + joinLimited(item.matchedJars, 4));
        if (!item.bytecodeClasses.isEmpty()) parts.add("Classes: " + joinLimited(item.bytecodeClasses, 4));
        return String.join("\n", parts);
    }

    private static String suggestedValidation() {
        return "Rebuild component, regenerate declared Generated JARs, then rerun wl14 both mode and confirm the action item/finding count decreases.";
    }

    private static String automationGuardrails(SourceComponent component, SourceFinding f, ActionItem item, SourceScanResult result) {
        List<String> guardrails = new ArrayList<>();
        guardrails.add("Patch only listed candidate files unless review finds the same symbol in adjacent source.");
        guardrails.add("Do not edit generated output, target/classes, target/test-classes, or binary archives directly.");
        if (component != null && !safe(component.trunk()).isBlank()) {
            String trunkPath = result == null ? "" : checkoutPath(result.trunkCheckout());
            if (trunkPath.isBlank()) {
                guardrails.add("Use Trunk Source Location as comparison input; trunk checkout was not available in this report workspace.");
            } else {
                guardrails.add("Use the local trunk workspace as read-only comparison input: " + trunkPath);
            }
        }
        if ("LOW".equals(item.confidenceLabel())) guardrails.add("Require human review before applying automated changes because confidence is low.");
        if ("TEST_UPDATE".equals(remediationType(f))) guardrails.add("Confirm tests must run on WL14/Java 21 before patching test-only code.");
        return String.join("\n", guardrails);
    }

    private static String joinLimited(Set<String> values, int limit) {
        List<String> clean = values.stream().filter(v -> v != null && !v.isBlank()).toList();
        String joined = clean.stream().limit(limit).collect(java.util.stream.Collectors.joining("\n"));
        if (clean.size() > limit) joined += "\n... and " + (clean.size() - limit) + " more";
        return joined;
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

    private static long countActionSeverity(List<ActionItem> items, String severity) {
        return items.stream().filter(item -> severityBucket(item.first.severity()).equals(severity)).count();
    }

    private static long countValidation(List<SourceFinding> findings, String validationStatus) {
        return findings.stream().filter(f -> validationStatus.equalsIgnoreCase(safe(f.validationStatus()))).count();
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

    private static class ActionItem {
        private final SourceFinding first;
        private final Set<String> files = new LinkedHashSet<>();
        private final Set<String> rawFiles = new LinkedHashSet<>();
        private final Set<String> lineHints = new LinkedHashSet<>();
        private final Set<String> detectionSources = new LinkedHashSet<>();
        private final Set<String> validationStatuses = new LinkedHashSet<>();
        private final Set<String> matchedJars = new LinkedHashSet<>();
        private final Set<String> bytecodeClasses = new LinkedHashSet<>();
        private int count;

        private ActionItem(SourceFinding first) {
            this.first = first;
        }

        private void add(SourceFinding f) {
            count++;
            if (!safe(f.file()).isBlank()) {
                rawFiles.add(f.file());
                files.add(f.file() + (f.line() > 0 ? ":" + f.line() : ""));
            }
            if (!safe(f.file()).isBlank() && f.line() > 0) lineHints.add(f.file() + ":" + f.line());
            if (!safe(f.detectionSource()).isBlank()) detectionSources.add(f.detectionSource());
            if (!safe(f.validationStatus()).isBlank()) validationStatuses.add(f.validationStatus());
            splitValues(f.matchedJars(), matchedJars);
            splitValues(f.bytecodeClass(), bytecodeClasses);
        }

        private int priorityRank() {
            int base = severityOrder(first.severity()) * 10;
            if (hasConfirmedEvidence()) return base;
            String status = safe(first.validationStatus()).toUpperCase();
            if ("CONFIRMED_BYTECODE_ONLY".equals(status)) return base + 1;
            if ("CONFIG_ONLY".equals(status) || "BUILD_ONLY".equals(status)) return base + 4;
            if ("CANDIDATE_SOURCE_ONLY".equals(status)) return base + 5;
            if ("TEST_ONLY".equals(status)) return base + 8;
            return base + 6;
        }

        private String priorityLabel() {
            return String.format("P%02d", priorityRank() + 1);
        }

        private boolean hasConfirmedEvidence() {
            return validationStatuses.stream().anyMatch(s -> s != null && s.toUpperCase().startsWith("CONFIRMED"));
        }

        private String confidenceLabel() {
            if (hasConfirmedEvidence()) return "HIGH";
            if (validationStatuses.stream().anyMatch(s -> "CONFIG_ONLY".equalsIgnoreCase(s) || "BUILD_ONLY".equalsIgnoreCase(s))) {
                return "MEDIUM";
            }
            return "LOW";
        }

        private String evidenceLabel() {
            if (hasConfirmedEvidence()) return "Confirmed";
            if (validationStatuses.stream().anyMatch(s -> "CONFIG_ONLY".equalsIgnoreCase(s) || "BUILD_ONLY".equalsIgnoreCase(s))) {
                return "Config/Build";
            }
            if (validationStatuses.stream().anyMatch(s -> "TEST_ONLY".equalsIgnoreCase(s))) return "Test only";
            return "Source only";
        }

        private String primaryFile() {
            return rawFiles.stream().findFirst().orElse(safe(first.file()));
        }

        private String lineHints() {
            return joinLimited(lineHints, 12);
        }

        private static void splitValues(String value, Set<String> target) {
            if (value == null || value.isBlank()) return;
            for (String part : value.split("[;,]")) {
                String trimmed = part.trim();
                if (!trimmed.isBlank()) target.add(trimmed);
            }
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
