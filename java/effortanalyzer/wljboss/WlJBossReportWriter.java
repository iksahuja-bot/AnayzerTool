package effortanalyzer.wljboss;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import effortanalyzer.util.ExcelUtils;

import java.io.*;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.*;

/**
 * Writes the WebLogic → JBoss migration analysis to a multi-sheet Excel workbook.
 *
 * Sheet 1 – Summary           Executive overview: severity + category counts, JARs analyzed
 * Sheet 2 – All Findings      Full detail: one row per (JAR × rule), sorted by severity
 * Sheet 3 – JAR Inventory     Per-JAR breakdown of class/file counts and issue counts
 * Sheet 4 – Checklist         Deduplicated action list grouped by category, with checkboxes
 * Sheet 5 – Migration Playbook Step-by-step numbered guides for the most complex migration scenarios
 */
public class WlJBossReportWriter {

    private static final Logger logger = LogManager.getLogger(WlJBossReportWriter.class);

    private static final int COL_WIDTH_MAX = 18_000; // ~70 chars

    // Severity order for sorting
    private static final Map<String, Integer> SEVERITY_ORDER = Map.of(
            "CRITICAL", 0, "HIGH", 1, "MEDIUM", 2, "INFO", 3
    );

    // Severity → Excel foreground colour
    private static final Map<String, IndexedColors> SEVERITY_COLORS = Map.of(
            "CRITICAL", IndexedColors.ROSE,
            "HIGH",     IndexedColors.LIGHT_ORANGE,
            "MEDIUM",   IndexedColors.LIGHT_YELLOW,
            "INFO",     IndexedColors.LIGHT_TURQUOISE
    );

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    public static void write(String outputFile,
                             List<WlJBossAnalyzer.Finding> findings,
                             List<WlJBossAnalyzer.JarStats> jarStats,
                             WlJBossRules.TargetProfile targetProfile) throws IOException {

        // Sort findings: CRITICAL first, then alphabetically by jar + category
        findings.sort(Comparator
                .comparingInt(f -> SEVERITY_ORDER.getOrDefault(f.rule.severity(), 99))
        );

        Path outPath = Path.of(outputFile).toAbsolutePath();
        ExcelUtils.validateAndPrepareOutput(outPath, logger);

        try (Workbook wb = new XSSFWorkbook()) {
            StyleSet styles = new StyleSet(wb);

            writeSummarySheet          (wb, styles, findings, jarStats, targetProfile);
            writeFindingsSheet         (wb, styles, findings);
            writeInventorySheet        (wb, styles, jarStats);
            writeChecklistSheet        (wb, styles, findings);
            writeMigrationPlaybookSheet(wb, styles, targetProfile);

            try (FileOutputStream out = new FileOutputStream(outPath.toFile())) {
                wb.write(out);
            } catch (IOException e) {
                String detail = ExcelUtils.diagnoseWriteFailure(outPath, e);
                logger.error("Report write failed: {}", detail);
                throw new IOException(detail, e);
            }
        }

        logger.info("Report written successfully: {}", outPath);
        System.out.println("\nReport written → " + outPath);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sheet 1 – Summary
    // ──────────────────────────────────────────────────────────────────────────

    private static void writeSummarySheet(Workbook wb, StyleSet s,
                                          List<WlJBossAnalyzer.Finding> findings,
                                          List<WlJBossAnalyzer.JarStats> jarStats,
                                          WlJBossRules.TargetProfile targetProfile) {
        Sheet sheet = wb.createSheet("Summary");
        sheet.setColumnWidth(0, 12_000);
        sheet.setColumnWidth(1, 6_000);

        int r = 0;

        // Title
        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue("WebLogic to JBoss/WildFly Migration Analysis");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 3));

        // Target profile
        Row targetRow = sheet.createRow(r++);
        targetRow.createCell(0).setCellValue("Migration target:");
        targetRow.createCell(1).setCellValue(targetProfile.displayLabel());

        // Date
        Row dateRow = sheet.createRow(r++);
        dateRow.createCell(0).setCellValue("Analysis date:");
        dateRow.createCell(1).setCellValue(LocalDate.now().toString());

        r++; // blank

        // ── Severity breakdown ──
        r = addSectionHeader(sheet, s, r, "Issues by Severity");
        r = addKV(sheet, s.header, r, "Severity", "Unique Issues", "");
        for (String sev : List.of("CRITICAL", "HIGH", "MEDIUM", "INFO")) {
            long cnt = findings.stream()
                    .filter(f -> sev.equals(f.rule.severity())).count();
            Row row = sheet.createRow(r++);
            Cell label = row.createCell(0);
            label.setCellValue(sev);
            label.setCellStyle(severityStyle(wb, sev));
            row.createCell(1).setCellValue(cnt);
        }

        r++; // blank

        // ── Category breakdown ──
        r = addSectionHeader(sheet, s, r, "Issues by Category");
        r = addKV(sheet, s.header, r, "Category", "Unique Issues", "");

        Map<String, Long> byCategory = findings.stream()
                .collect(Collectors.groupingBy(f -> f.rule.category(), Collectors.counting()));
        byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> {
                    Row row = sheet.createRow(/* r captured below */0);
                    // we need to mutate r, so use holder
                });

        // Re-do with proper row index
        for (Map.Entry<String, Long> e : byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toList())) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(e.getKey());
            row.createCell(1).setCellValue(e.getValue());
        }

        r++; // blank

        // ── JAR summary ──
        r = addSectionHeader(sheet, s, r, "JARs Analyzed");
        Row jh = sheet.createRow(r++);
        jh.createCell(0).setCellValue("JAR");
        jh.createCell(1).setCellValue("Issues");
        jh.createCell(2).setCellValue("Critical");
        jh.createCell(3).setCellValue("High");
        jh.getCell(0).setCellStyle(s.header);
        jh.getCell(1).setCellStyle(s.header);
        jh.getCell(2).setCellStyle(s.header);
        jh.getCell(3).setCellStyle(s.header);

        for (WlJBossAnalyzer.JarStats js : jarStats) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(js.jarName);
            row.createCell(1).setCellValue(js.critical + js.high + js.medium + js.info);
            row.createCell(2).setCellValue(js.critical);
            row.createCell(3).setCellValue(js.high);
        }

        // Note about excluded stubs
        int totalStubs = jarStats.stream().mapToInt(js -> js.wlGeneratedStubs).sum();
        if (totalStubs > 0) {
            r++;
            Row noteRow = sheet.createRow(r++);
            noteRow.createCell(0).setCellValue(
                    "NOTE: " + totalStubs + " WebLogic appc-generated stub/skeleton class files were detected "
                    + "and excluded from individual rule counts (see WL_GENERATED_STUBS in All Findings). "
                    + "These are not developer code — they must be removed from the JARs before WildFly deployment.");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sheet 2 – All Findings
    // ──────────────────────────────────────────────────────────────────────────

    private static void writeFindingsSheet(Workbook wb, StyleSet s,
                                           List<WlJBossAnalyzer.Finding> findings) {
        Sheet sheet = wb.createSheet("All Findings");

        // Row 0 – legend
        Row legend = sheet.createRow(0);
        Cell lc = legend.createCell(0);
        lc.setCellValue(
                "LEGEND:  Rows highlighted in PINK/ORANGE/YELLOW = developer-written code that needs migration.  "
                + "Rows in GREY = WebLogic appc-generated stubs (NOT developer code) — must be stripped from the JAR.");
        lc.setCellStyle(s.legend);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 9));

        // Row 1 – column headers
        String[] headers = {
                "Source", "Severity", "Category", "JAR File",
                "Detected API / Pattern", "Files Affected", "Occurrences",
                "Description", "What to Do", "Example / First Affected Class"
        };
        Row hRow = sheet.createRow(1);
        for (int i = 0; i < headers.length; i++) {
            Cell c = hRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(s.header);
        }

        int r = 2;
        for (WlJBossAnalyzer.Finding f : findings) {
            Row row = sheet.createRow(r++);

            // Col 0 – Source type: developer code vs generated stub
            Cell srcCell = row.createCell(0);
            if (f.isGeneratedStub) {
                srcCell.setCellValue("WL appc-generated");
                srcCell.setCellStyle(s.stubSource);
            } else {
                srcCell.setCellValue("Developer code");
                srcCell.setCellStyle(s.devSource);
            }

            // Col 1 – Severity (colour-coded)
            Cell sevCell = row.createCell(1);
            if (f.isGeneratedStub) {
                sevCell.setCellValue(f.rule.severity());
                sevCell.setCellStyle(s.stubCell);
            } else {
                sevCell.setCellValue(f.rule.severity());
                sevCell.setCellStyle(severityStyle(wb, f.rule.severity()));
            }

            // Cols 2-9
            CellStyle rowStyle = f.isGeneratedStub ? s.stubCell : null;

            Cell catCell = row.createCell(2);
            catCell.setCellValue(f.rule.category());
            if (rowStyle != null) catCell.setCellStyle(rowStyle);

            Cell jarCell = row.createCell(3);
            jarCell.setCellValue(f.jarName);
            if (rowStyle != null) jarCell.setCellStyle(rowStyle);

            // WL_GENERATED_STUBS uses internal key; show a readable label instead
            String patternLabel = "WL_GENERATED_STUBS".equals(f.rule.apiPattern())
                    ? "*_WLStub / *_WLSkel / *_EOImpl / *_HomeImpl (appc-generated)"
                    : f.rule.apiPattern();
            Cell patCell = row.createCell(4);
            patCell.setCellValue(patternLabel);
            if (rowStyle != null) patCell.setCellStyle(rowStyle);

            Cell filesCell = row.createCell(5);
            filesCell.setCellValue(f.affectedFiles.size());
            if (rowStyle != null) filesCell.setCellStyle(rowStyle);

            Cell occCell = row.createCell(6);
            occCell.setCellValue(f.occurrenceCount);
            if (rowStyle != null) occCell.setCellStyle(rowStyle);

            Cell descCell = row.createCell(7);
            descCell.setCellValue(f.rule.description());
            if (rowStyle != null) descCell.setCellStyle(rowStyle);

            Cell remCell = row.createCell(8);
            remCell.setCellValue(f.rule.remediation());
            if (rowStyle != null) remCell.setCellStyle(rowStyle);

            Cell exCell = row.createCell(9);
            exCell.setCellValue(f.exampleContext);
            if (rowStyle != null) exCell.setCellStyle(rowStyle);
        }

        // Column widths
        int[] widths = { 5500, 4000, 7000, 8000, 9000, 4000, 4000, 14000, 14000, 14000 };
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, Math.min(widths[i], COL_WIDTH_MAX));
        }

        // Auto-filter (start from header row)
        if (r > 2) {
            sheet.setAutoFilter(new CellRangeAddress(1, r - 1, 0, headers.length - 1));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sheet 3 – JAR Inventory
    // ──────────────────────────────────────────────────────────────────────────

    private static void writeInventorySheet(Workbook wb, StyleSet s,
                                            List<WlJBossAnalyzer.JarStats> stats) {
        Sheet sheet = wb.createSheet("JAR Inventory");

        String[] headers = {
                "JAR File", "Is WebLogic JAR?",
                "Class Files", "WL-Generated Stubs *", "Source Files", "XML Files",
                "CRITICAL", "HIGH", "MEDIUM", "INFO", "Total Issues"
        };

        Row hRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell c = hRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(s.header);
        }

        int r = 1;
        for (WlJBossAnalyzer.JarStats js : stats) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(js.jarName);
            row.createCell(1).setCellValue(js.isWebLogicJar ? "YES" : "no");
            row.createCell(2).setCellValue(js.classes);
            row.createCell(3).setCellValue(js.wlGeneratedStubs);
            row.createCell(4).setCellValue(js.sourceFiles);
            row.createCell(5).setCellValue(js.xmlFiles);
            row.createCell(6).setCellValue(js.critical);
            row.createCell(7).setCellValue(js.high);
            row.createCell(8).setCellValue(js.medium);
            row.createCell(9).setCellValue(js.info);
            row.createCell(10).setCellValue(js.critical + js.high + js.medium + js.info);
        }

        // Footnote
        r++;
        sheet.createRow(r).createCell(0).setCellValue(
                "* WL-Generated Stubs: classes auto-generated by WebLogic appc compiler (*_WLStub, *_WLSkel, "
                + "*_EOImpl, *_HomeImpl). Excluded from rule scans to prevent false positives. "
                + "Must be removed before WildFly deployment — see WL_GENERATED_STUBS finding.");

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
            if (sheet.getColumnWidth(i) > COL_WIDTH_MAX) {
                sheet.setColumnWidth(i, COL_WIDTH_MAX);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sheet 4 – Checklist (deduplicated action items)
    // ──────────────────────────────────────────────────────────────────────────

    private static void writeChecklistSheet(Workbook wb, StyleSet s,
                                            List<WlJBossAnalyzer.Finding> findings) {
        Sheet sheet = wb.createSheet("Migration Checklist");
        sheet.setColumnWidth(0, 2_000);   // checkbox column
        sheet.setColumnWidth(1, 8_000);   // API / pattern
        sheet.setColumnWidth(2, 16_000);  // what to do
        sheet.setColumnWidth(3, 4_000);   // severity
        sheet.setColumnWidth(4, 4_000);   // JARs affected
        sheet.setColumnWidth(5, 8_000);   // source type

        int r = 0;

        // Title
        Row titleRow = sheet.createRow(r++);
        Cell tc = titleRow.createCell(0);
        tc.setCellValue("Migration Checklist  –  tick each item as you complete it");
        tc.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));
        r++; // blank

        // Group by category, then by apiPattern (deduplicate across JARs)
        Map<String, List<WlJBossAnalyzer.Finding>> byCategory = findings.stream()
                .collect(Collectors.groupingBy(
                        f -> f.rule.category(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        for (Map.Entry<String, List<WlJBossAnalyzer.Finding>> entry : byCategory.entrySet()) {
            String category = entry.getKey();
            List<WlJBossAnalyzer.Finding> group = entry.getValue();

            // Category header row
            Row catRow = sheet.createRow(r++);
            Cell catCell = catRow.createCell(0);
            catCell.setCellValue(category.replace("_", " "));
            catCell.setCellStyle(s.categoryHeader);
            sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 4));

            // Column headers
            Row hRow = sheet.createRow(r++);
            hRow.createCell(0).setCellValue("Done");
            hRow.createCell(1).setCellValue("Detected API / Pattern");
            hRow.createCell(2).setCellValue("What to Do");
            hRow.createCell(3).setCellValue("Severity");
            hRow.createCell(4).setCellValue("Files Affected");
            hRow.createCell(5).setCellValue("Source");
            for (int i = 0; i < 6; i++) hRow.getCell(i).setCellStyle(s.header);

            // Deduplicate by apiPattern
            Map<String, WlJBossAnalyzer.Finding> deduped = new LinkedHashMap<>();
            for (WlJBossAnalyzer.Finding f : group) {
                deduped.merge(f.rule.apiPattern(), f, (existing, newer) -> {
                    existing.affectedFiles.addAll(newer.affectedFiles);
                    return existing;
                });
            }

            for (WlJBossAnalyzer.Finding f : deduped.values()) {
                Row row = sheet.createRow(r++);
                boolean isStub = f.isGeneratedStub;

                row.createCell(0).setCellValue(isStub ? "—" : "☐");
                String patternLabel = "WL_GENERATED_STUBS".equals(f.rule.apiPattern())
                        ? "*_WLStub / *_WLSkel / *_EOImpl / *_HomeImpl (appc-generated)"
                        : f.rule.apiPattern();
                row.createCell(1).setCellValue(patternLabel);
                row.createCell(2).setCellValue(f.rule.remediation());

                Cell sevCell = row.createCell(3);
                sevCell.setCellValue(f.rule.severity());
                sevCell.setCellStyle(isStub ? s.stubCell : severityStyle(wb, f.rule.severity()));

                row.createCell(4).setCellValue(f.affectedFiles.size());

                Cell srcCell = row.createCell(5);
                srcCell.setCellValue(isStub ? "WL appc-generated (not developer code)" : "Developer code");
                srcCell.setCellStyle(isStub ? s.stubSource : s.devSource);
            }

            r++; // blank between categories
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sheet 5 – Migration Playbook
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Detailed, numbered step-by-step migration guides for the most complex
     * migration scenarios discovered during analysis.
     *
     * Each scenario block has:
     *   Row 0   — dark-green title bar (scenario name + detected rule)
     *   Row 1…n — numbered steps (Col 0 = step #, Col 1 = instruction + code)
     *   NOTE    — light-blue informational footnote
     *   DONE    — light-green "already done / not needed" note where applicable
     */
    private static void writeMigrationPlaybookSheet(Workbook wb, StyleSet s,
                                                    WlJBossRules.TargetProfile profile) {
        Sheet sheet = wb.createSheet("Migration Playbook");
        sheet.setColumnWidth(0, 2_500);    // step # / label
        sheet.setColumnWidth(1, 22_000);   // instruction / code
        sheet.setColumnWidth(2, 10_000);   // notes / status badge

        int r = 0;

        // ── Page title ────────────────────────────────────────────────────────
        Row titleRow = sheet.createRow(r++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Migration Playbook  —  Step-by-step guides for complex migration scenarios");
        titleCell.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 2));

        Row targetRow = sheet.createRow(r++);
        targetRow.createCell(1).setCellValue("Target: " + profile.displayLabel());

        r++; // blank

        // ── SCENARIO 1: HomeInterfaceHelper / EJB Entity Bean Migration ───────
        r = pbScenario(sheet, s, r,
                "SCENARIO 1 — HomeInterfaceHelper / EJB 2.x Entity Bean Migration",
                "Detected rules: HomeInterfaceHelper  |  javax.ejb.EntityBean  |  javax.ejb.EJBHome");
        r = pbNote(sheet, s, r,
                "WHY: WildFly does not support EJB 2.x entity beans (CMP/BMP). The recommended approach "
                + "for large codebases is the POJOHome pattern — it lets all HomeInterfaceHelper.lookupHome() "
                + "call sites work UNCHANGED while entity bean management is moved to in-memory JDBC objects.");

        r = pbStep(sheet, s, r, "1",
                "Verify POJOHome classes exist in your project",
                "For each entity bean that has a *Home interface, there must be a matching *POJOHome class "
                + "(e.g. AttributePOJOHome, UserPOJOHome) that implements EJBHome and manages instances using "
                + "direct JDBC (not the EJB container). "
                + "Check: src/main/java/**/pojo/**/*POJOHome.java  OR  search for 'implements.*EJBHome' in source.",
                null);
        r = pbDone(sheet, s, r,
                "In Kernel project: 5 POJOHome classes already exist. Step 1 is DONE if file count matches "
                + "your entity bean count.");

        r = pbStep(sheet, s, r, "2",
                "Enable the POJO container via server property",
                "Set nc.core.metamodel.pojo_access=true in your WildFly server configuration. "
                + "HomeInterfaceHelper.lookupHome() checks this property: when true it returns "
                + "the matching POJOHome without doing a JNDI lookup at all.",
                "<!-- In standalone.xml, inside <system-properties>: -->\n"
                + "<property name=\"nc.core.metamodel.pojo_access\" value=\"true\"/>\n\n"
                + "-- OR in standalone.conf / nc.properties:\n"
                + "nc.core.metamodel.pojo_access=true");

        r = pbStep(sheet, s, r, "3",
                "Add jboss-ejb3.xml for SESSION beans only — do NOT add entity beans",
                "Copy META-INF/jboss-ejb3.xml from the WildFly reference project. "
                + "It must list every session bean and MDB, but must NOT list any entity bean. "
                + "WildFly ignores entity beans declared in ejb-jar.xml — they are handled by POJOHome.",
                "<jboss:ejb-jar xmlns:jboss=\"http://www.jboss.com/xml/ns/javaee\" ...>\n"
                + "  <enterprise-beans>\n"
                + "    <session>\n"
                + "      <ejb-name>IDGeneratorBean</ejb-name>\n"
                + "      <home>com.netcracker.ejb.guid.IDGeneratorHome</home>\n"
                + "      <remote>com.netcracker.ejb.guid.IDGenerator</remote>\n"
                + "      <ejb-class>com.netcracker.ejb.guid.IDGeneratorBean</ejb-class>\n"
                + "      <session-type>Stateless</session-type>\n"
                + "      <transaction-type>Container</transaction-type>\n"
                + "    </session>\n"
                + "    <!-- repeat for every session bean -->\n"
                + "    <!-- NO entity beans here! -->\n"
                + "  </enterprise-beans>\n"
                + "</jboss:ejb-jar>");

        r = pbStep(sheet, s, r, "4",
                "Replace the Platform.jndi() WebLogic implementation JAR",
                "The WebLogic build links platform-abstraction-layer-impl-weblogic.jar which uses "
                + "WLInitialContextFactory / t3:// for JNDI. Replace it with the WildFly version that "
                + "returns a plain new InitialContext(). The API surface is identical; only the JAR changes.",
                "// WildFly Platform.jndi() implementation (simplified):\n"
                + "public InitialContext getInitialContext() throws NamingException {\n"
                + "    return new InitialContext();  // standard; no WL factory needed\n"
                + "}\n"
                + "public String getUserTransactionJndiName() {\n"
                + "    return \"java:comp/UserTransaction\";\n"
                + "}");

        r = pbStep(sheet, s, r, "5",
                "Exclude weblogic-ejb-jar.xml from the WildFly deployment",
                "WildFly ignores this file but including it can cause warnings. "
                + "Exclude it in your Maven build or move it outside META-INF for WildFly profiles.",
                "<!-- In pom.xml for WildFly profile -->\n"
                + "<plugin>\n"
                + "  <artifactId>maven-war-plugin</artifactId>\n"
                + "  <configuration>\n"
                + "    <packagingExcludes>WEB-INF/weblogic.xml,META-INF/weblogic-ejb-jar.xml</packagingExcludes>\n"
                + "  </configuration>\n"
                + "</plugin>");

        r++; // blank between scenarios

        // ── SCENARIO 2: Session EJB Exposure (weblogic-ejb-jar.xml → jboss-ejb3.xml) ──
        r = pbScenario(sheet, s, r,
                "SCENARIO 2 — Session EJB Exposure: weblogic-ejb-jar.xml → jboss-ejb3.xml",
                "Detected rules: weblogic-ejb-jar.xml  |  javax.ejb.SessionBean  |  javax.ejb.MessageDrivenBean");
        r = pbNote(sheet, s, r,
                "KEY FACT: Session bean Java classes do NOT need to change. "
                + "Only the deployment descriptor changes. WildFly auto-binds the home interface JNDI name "
                + "from the <home> class name, matching the weblogic-ejb-jar.xml <jndi-name> automatically.");

        r = pbStep(sheet, s, r, "1",
                "For each session bean: add a <session> block to jboss-ejb3.xml",
                "The ejb-name must match ejb-jar.xml. The <home> class name becomes the JNDI binding key.",
                "<session>\n"
                + "  <ejb-name>CalculationManagerBean</ejb-name>\n"
                + "  <home>com.netcracker.ejb.core.CalculationManagerHome</home>\n"
                + "  <remote>com.netcracker.ejb.core.CalculationManager</remote>\n"
                + "  <ejb-class>com.netcracker.ejb.core.CalculationManagerBean</ejb-class>\n"
                + "  <session-type>Stateless</session-type>  <!-- or Stateful -->\n"
                + "  <transaction-type>Container</transaction-type>\n"
                + "</session>");

        r = pbStep(sheet, s, r, "2",
                "For each MDB: add a <message-driven> block with WildFly/Artemis activation config",
                "The destinationLookup must use a WildFly JNDI name, not a WebLogic JNDI name.",
                "<message-driven>\n"
                + "  <ejb-name>JMSLoggerBean</ejb-name>\n"
                + "  <ejb-class>com.netcracker.ejb.core.jms.JMSLoggerBean</ejb-class>\n"
                + "  <transaction-type>Container</transaction-type>\n"
                + "  <message-destination-type>javax.jms.Topic</message-destination-type>\n"
                + "  <activation-config>\n"
                + "    <activation-config-property>\n"
                + "      <activation-config-property-name>destinationLookup</activation-config-property-name>\n"
                + "      <activation-config-property-value>com.netcracker.ErrorDestination</activation-config-property-value>\n"
                + "    </activation-config-property>\n"
                + "    <activation-config-property>\n"
                + "      <activation-config-property-name>maxSession</activation-config-property-name>\n"
                + "      <activation-config-property-value>16</activation-config-property-value>\n"
                + "    </activation-config-property>\n"
                + "  </activation-config>\n"
                + "</message-driven>");

        r = pbStep(sheet, s, r, "3",
                "Add transaction configuration in <assembly-descriptor>",
                "Mirror the transaction timeouts from weblogic-ejb-jar.xml using the JBoss trans-timeout extension.",
                "<assembly-descriptor>\n"
                + "  <container-transaction>\n"
                + "    <method><ejb-name>CalculationManagerBean</ejb-name><method-name>*</method-name></method>\n"
                + "    <trans-attribute>Required</trans-attribute>\n"
                + "    <tx:trans-timeout><tx:timeout>300</tx:timeout><tx:unit>Seconds</tx:unit></tx:trans-timeout>\n"
                + "  </container-transaction>\n"
                + "</assembly-descriptor>");

        r++; // blank

        // ── SCENARIO 3: PortableRemoteObject.narrow() / RemoteHelper.narrow() ─
        r = pbScenario(sheet, s, r,
                "SCENARIO 3 — Remove PortableRemoteObject.narrow() / RemoteHelper.narrow()",
                "Detected rules: javax.rmi.PortableRemoteObject  |  RemoteHelper.narrow");
        r = pbNote(sheet, s, r,
                "narrow() is IIOP/CORBA-specific. WildFly uses JBoss Remoting — no narrowing needed. "
                + "With the POJOHome pattern, create() already returns the correct type so narrow() is entirely a no-op.");

        r = pbStep(sheet, s, r, "1",
                "Find all narrow() call sites",
                "Search for: PortableRemoteObject.narrow(  AND  RemoteHelper.narrow(",
                "// WebLogic pattern (before):\n"
                + "Object obj = ctx.lookup(\"com.example.MyHome\");\n"
                + "MyHome home = (MyHome) PortableRemoteObject.narrow(obj, MyHome.class);\n"
                + "MyRemote bean = home.create();\n\n"
                + "// WildFly pattern (after) — plain Java cast is sufficient:\n"
                + "MyHome home = (MyHome) ctx.lookup(\"com.example.MyHome\");\n"
                + "MyRemote bean = home.create();\n\n"
                + "// Even simpler with POJOHome — lookupHome() returns correct type:\n"
                + "MyHome home = HomeInterfaceHelper.getInstance().lookupHome(\"com.example.MyHome\", MyHome.class);\n"
                + "MyRemote bean = home.create();   // no narrow() needed at all");

        r = pbStep(sheet, s, r, "2",
                "Remove the narrow() import and any unused PortableRemoteObject import",
                "After removing all narrow() calls, remove the import statement to clean up.",
                "// Remove these imports:\n"
                + "import javax.rmi.PortableRemoteObject;\n"
                + "import com.netcracker.ejb.framework.RemoteHelper;  // if no other usages remain");

        r++; // blank

        // ── SCENARIO 4: Platform.jndi() / WLInitialContextFactory / t3:// ────
        r = pbScenario(sheet, s, r,
                "SCENARIO 4 — Platform.jndi() / JNDI Context Factory Migration",
                "Detected rules: weblogic.jndi  |  WLInitialContextFactory  |  t3://");
        r = pbNote(sheet, s, r,
                "Platform.jndi() is an abstraction layer. On WebLogic it creates a T3 context; "
                + "on WildFly it just returns new InitialContext(). Entity bean JNDI lookups are "
                + "bypassed entirely by POJOHome, so only datasources and UserTransaction still use JNDI.");

        r = pbStep(sheet, s, r, "1",
                "Swap the Platform.jndi() implementation JAR (no source code change)",
                "Remove platform-abstraction-layer-impl-weblogic.jar from your WildFly build's classpath. "
                + "Add the WildFly-compatible implementation that returns standard InitialContext.",
                "<!-- In pom.xml, switch the JAR for the WildFly profile: -->\n"
                + "<dependency>\n"
                + "  <groupId>com.netcracker.platform</groupId>\n"
                + "  <!-- Remove: platform-abstraction-layer-impl-weblogic -->\n"
                + "  <artifactId>platform-abstraction-layer-impl</artifactId>\n"
                + "  <version>${platform.version}</version>\n"
                + "</dependency>");

        r = pbStep(sheet, s, r, "2",
                "Verify datasource JNDI names are updated for WildFly",
                "WildFly datasources use java:jboss/datasources/<name>. "
                + "WebLogic used plain names like jdbc/NCProduction. Update all references.",
                "// WebLogic JNDI (before):\n"
                + "ds = (DataSource) ctx.lookup(\"jdbc/NCProduction\");\n\n"
                + "// WildFly JNDI (after):\n"
                + "ds = (DataSource) ctx.lookup(\"java:jboss/datasources/NCProduction\");\n\n"
                + "<!-- In standalone.xml datasource entry: -->\n"
                + "<datasource jndi-name=\"java:jboss/datasources/NCProduction\" pool-name=\"NCProduction\">");

        r = pbStep(sheet, s, r, "3",
                "Remove all t3:// / WLInitialContextFactory references from properties files",
                "Search for: t3://  WLInitialContextFactory  weblogic.jndi in .properties and .xml files.",
                "# WebLogic (before) — remove these:\n"
                + "java.naming.factory.initial=weblogic.jndi.WLInitialContextFactory\n"
                + "java.naming.provider.url=t3://localhost:7001\n\n"
                + "# WildFly (after) — for remote lookups only:\n"
                + "java.naming.factory.initial=org.jboss.naming.remote.client.InitialContextFactory\n"
                + "java.naming.provider.url=remote+http://localhost:8080\n\n"
                + "# For in-VM lookups (most cases): no properties needed —\n"
                + "# new InitialContext() with no args works automatically inside WildFly");

        r++; // blank

        // ── SCENARIO 5: weblogic.security → WildFly Elytron ─────────────────
        r = pbScenario(sheet, s, r,
                "SCENARIO 5 — WebLogic Security API → WildFly Elytron",
                "Detected rules: weblogic.security  |  weblogic.security.Security");
        r = pbNote(sheet, s, r,
                "weblogic.security.Security.getCurrentSubject() retrieves the authenticated principal. "
                + "The WildFly equivalent uses the Elytron security framework.");

        r = pbStep(sheet, s, r, "1",
                "Replace weblogic.security.Security.getCurrentSubject()",
                "Each call site (typically in PrincipalHelper or similar) must be replaced with the Elytron API.",
                "// WebLogic (before):\n"
                + "import weblogic.security.Security;\n"
                + "Subject subject = Security.getCurrentSubject();\n\n"
                + "// WildFly Elytron (after):\n"
                + "import org.wildfly.security.auth.server.SecurityDomain;\n"
                + "import org.wildfly.security.auth.server.SecurityIdentity;\n"
                + "SecurityIdentity identity = SecurityDomain.getCurrent().getCurrentSecurityIdentity();\n"
                + "// To get a javax.security.auth.Subject (legacy compatibility):\n"
                + "Subject subject = identity.createRunAsSubject();");

        r = pbStep(sheet, s, r, "2",
                "Add WildFly Elytron dependency to pom.xml",
                "Elytron is provided by WildFly — scope it as 'provided' so it is not bundled in the JAR.",
                "<dependency>\n"
                + "  <groupId>org.wildfly.security</groupId>\n"
                + "  <artifactId>wildfly-elytron-auth-server</artifactId>\n"
                + "  <scope>provided</scope>\n"
                + "</dependency>");

        r = pbStep(sheet, s, r, "3",
                "Configure security-domain in standalone.xml",
                "Ensure an Elytron security-domain is configured and the application deployment references it "
                + "in jboss-web.xml or jboss-ejb3.xml.",
                "<!-- jboss-web.xml -->\n"
                + "<jboss-web>\n"
                + "  <security-domain>my-security-domain</security-domain>\n"
                + "</jboss-web>\n\n"
                + "<!-- standalone.xml (Elytron subsystem): -->\n"
                + "<security-domain name=\"my-security-domain\" default-realm=\"ApplicationRealm\"\n"
                + "                 permission-mapper=\"default-permission-mapper\">\n"
                + "  <realm name=\"ApplicationRealm\" role-decoder=\"groups-to-roles\"/>\n"
                + "</security-domain>");

        r++; // blank

        // ── SCENARIO 6: javax.* → jakarta.* (WildFly 27 / Java 21 only) ─────
        if (profile == WlJBossRules.TargetProfile.WILDFLY27_JAVA21) {
            r = pbScenario(sheet, s, r,
                    "SCENARIO 6 — javax.* → jakarta.* Namespace Migration (WildFly 27+ / Jakarta EE 10)",
                    "Detected rules: JAVAX_TO_JAKARTA category");
            r = pbNote(sheet, s, r,
                    "Jakarta EE 10 (WildFly 27+) renamed every javax.* package to jakarta.*. "
                    + "This is a mandatory, mechanical rename — no logic changes are required. "
                    + "Tools like the Eclipse Transformer can automate most of it.");

            r = pbStep(sheet, s, r, "1",
                    "Update pom.xml dependencies to Jakarta EE 10 versions",
                    "Replace javax.* BOM/API artifacts with their jakarta.* equivalents.",
                    "<!-- Before: -->\n"
                    + "<dependency>\n"
                    + "  <groupId>javax</groupId>\n"
                    + "  <artifactId>javaee-api</artifactId>\n"
                    + "  <version>8.0</version>\n"
                    + "</dependency>\n\n"
                    + "<!-- After: -->\n"
                    + "<dependency>\n"
                    + "  <groupId>jakarta.platform</groupId>\n"
                    + "  <artifactId>jakarta.jakartaee-api</artifactId>\n"
                    + "  <version>10.0.0</version>\n"
                    + "  <scope>provided</scope>\n"
                    + "</dependency>");

            r = pbStep(sheet, s, r, "2",
                    "Run global find-and-replace across all source files",
                    "Perform the following replacements in all .java and .xml source files.",
                    "javax.servlet     →  jakarta.servlet\n"
                    + "javax.ejb         →  jakarta.ejb\n"
                    + "javax.persistence →  jakarta.persistence\n"
                    + "javax.jms         →  jakarta.jms\n"
                    + "javax.inject      →  jakarta.inject\n"
                    + "javax.enterprise  →  jakarta.enterprise\n"
                    + "javax.transaction →  jakarta.transaction\n"
                    + "javax.annotation  →  jakarta.annotation\n"
                    + "javax.ws.rs       →  jakarta.ws.rs\n"
                    + "javax.validation  →  jakarta.validation\n"
                    + "javax.xml.bind    →  jakarta.xml.bind\n"
                    + "javax.faces       →  jakarta.faces\n\n"
                    + "# PowerShell one-liner to preview affected files:\n"
                    + "Get-ChildItem -Recurse -Include *.java | Select-String 'import javax\\.' | Select-Object Path -Unique");

            r = pbStep(sheet, s, r, "3",
                    "Update XML descriptor namespaces",
                    "persistence.xml, beans.xml, ejb-jar.xml namespaces must use the jakarta.* URIs.",
                    "<!-- persistence.xml: change xmlns -->\n"
                    + "<!-- Before: xmlns=\"http://xmlns.jcp.org/xml/ns/persistence\" version=\"2.2\" -->\n"
                    + "<!-- After:  xmlns=\"https://jakarta.ee/xml/ns/persistence\"   version=\"3.0\" -->\n\n"
                    + "<!-- beans.xml: -->\n"
                    + "<!-- Before: xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"2.0\" -->\n"
                    + "<!-- After:  xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"4.0\" -->");

            r = pbStep(sheet, s, r, "4",
                    "Upgrade Spring to 6.x (if used)",
                    "Spring 5.x uses javax.* and will NOT work with WildFly 27+. Spring 6.x uses jakarta.*.",
                    "<!-- pom.xml: -->\n"
                    + "<spring.version>6.1.x</spring.version>   <!-- requires Java 17+ -->");

            r++; // blank
        }

        // ── SCENARIO 7: WebLogic classloading → jboss-deployment-structure.xml ─
        r = pbScenario(sheet, s, r,
                "SCENARIO 7 — WebLogic Classloading → jboss-deployment-structure.xml",
                "Detected rules: weblogic.xml  |  prefer-application-packages  |  prefer-web-inf-classes  |  weblogic-application.xml");
        r = pbNote(sheet, s, r,
                "WildFly uses JBoss Modules — a strict module system. "
                + "WebLogic classloading directives (prefer-application-packages, FilteringClassLoader, etc.) "
                + "have direct equivalents in jboss-deployment-structure.xml.");

        r = pbStep(sheet, s, r, "1",
                "Create WEB-INF/jboss-web.xml to replace weblogic.xml",
                "Migrate the context-root and security-domain settings.",
                "<!-- WEB-INF/jboss-web.xml -->\n"
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jboss-web xmlns=\"http://www.jboss.com/xml/ns/javaee\">\n"
                + "  <context-root>/myapp</context-root>\n"
                + "  <security-domain>my-security-domain</security-domain>\n"
                + "</jboss-web>");

        r = pbStep(sheet, s, r, "2",
                "Create META-INF/jboss-deployment-structure.xml for module isolation",
                "Use this to exclude conflicting WildFly modules and add required dependencies.",
                "<!-- META-INF/jboss-deployment-structure.xml -->\n"
                + "<jboss-deployment-structure>\n"
                + "  <deployment>\n"
                + "    <exclusions>\n"
                + "      <!-- Exclude WildFly's built-in Hibernate if bundling your own -->\n"
                + "      <module name=\"org.hibernate\" />\n"
                + "      <!-- Exclude WildFly's Logging if using Log4j directly -->\n"
                + "      <module name=\"org.apache.log4j\" />\n"
                + "    </exclusions>\n"
                + "    <dependencies>\n"
                + "      <!-- Allow access to a WildFly module not exposed by default -->\n"
                + "      <module name=\"org.apache.commons.lang\" />\n"
                + "    </dependencies>\n"
                + "  </deployment>\n"
                + "</jboss-deployment-structure>");

        r = pbStep(sheet, s, r, "3",
                "Replace prefer-web-inf-classes with local-last in jboss-web.xml",
                "WebLogic's prefer-web-inf-classes=true has a direct WildFly equivalent.",
                "<!-- jboss-web.xml -->\n"
                + "<jboss-web>\n"
                + "  <class-loading>\n"
                + "    <local-last value=\"true\"/>\n"
                + "  </class-loading>\n"
                + "</jboss-web>");

        // ── Wrap-up footer ────────────────────────────────────────────────────
        r++;
        Row footerRow = sheet.createRow(r);
        Cell footerCell = footerRow.createCell(0);
        footerCell.setCellValue(
                "For the complete list of detected issues refer to the 'All Findings' and 'Checklist' sheets. "
                + "Use this Playbook as the step-by-step execution guide for the highest-impact items.");
        footerCell.setCellStyle(s.playbookNote);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 2));
    }

    // ── Playbook row helpers ───────────────────────────────────────────────────

    /** Dark-green scenario title bar (merged across all 3 cols). */
    private static int pbScenario(Sheet sheet, StyleSet s, int r, String title, String subtitle) {
        Row row = sheet.createRow(r++);
        Cell c = row.createCell(0);
        c.setCellValue(title);
        c.setCellStyle(s.playbookScenarioTitle);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 2));

        Row sub = sheet.createRow(r++);
        Cell sc = sub.createCell(0);
        sc.setCellValue("  " + subtitle);
        sc.setCellStyle(s.playbookNote);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 2));
        return r;
    }

    /** Light-blue NOTE row (merged). */
    private static int pbNote(Sheet sheet, StyleSet s, int r, String text) {
        Row row = sheet.createRow(r++);
        row.setHeightInPoints(Math.max(30, text.length() / 4f));
        Cell c = row.createCell(0);
        c.setCellValue("NOTE: " + text);
        c.setCellStyle(s.playbookNote);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 2));
        return r;
    }

    /** Light-green "Already done" row (merged). */
    private static int pbDone(Sheet sheet, StyleSet s, int r, String text) {
        Row row = sheet.createRow(r++);
        row.setHeightInPoints(Math.max(25, text.length() / 4f));
        Cell c = row.createCell(0);
        c.setCellValue("✔ " + text);
        c.setCellStyle(s.playbookDone);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 2));
        return r;
    }

    /**
     * Numbered step row: Col 0 = step number, Col 1 = instruction text.
     * If codeExample is provided, an additional pale-blue code row is added.
     */
    private static int pbStep(Sheet sheet, StyleSet s, int r,
                               String stepNum, String heading,
                               String instruction, String codeExample) {
        // Step heading row
        Row headRow = sheet.createRow(r++);
        headRow.setHeightInPoints(18);
        Cell numCell = headRow.createCell(0);
        numCell.setCellValue("Step " + stepNum);
        numCell.setCellStyle(s.playbookStepNumber);

        Cell headCell = headRow.createCell(1);
        headCell.setCellValue(heading);
        CellStyle bold = sheet.getWorkbook().createCellStyle();
        bold.cloneStyleFrom(s.playbookStepText);
        Font boldFont = sheet.getWorkbook().createFont();
        boldFont.setBold(true);
        bold.setFont(boldFont);
        headCell.setCellStyle(bold);

        // Instruction row
        if (instruction != null && !instruction.isEmpty()) {
            Row instrRow = sheet.createRow(r++);
            int lines = (int) instruction.chars().filter(c -> c == '\n').count() + 1;
            instrRow.setHeightInPoints(Math.max(30, lines * 14f));
            instrRow.createCell(0);  // blank step-num col
            Cell instrCell = instrRow.createCell(1);
            instrCell.setCellValue(instruction);
            instrCell.setCellStyle(s.playbookStepText);
        }

        // Code example row
        if (codeExample != null && !codeExample.isEmpty()) {
            Row codeRow = sheet.createRow(r++);
            int lines = (int) codeExample.chars().filter(c -> c == '\n').count() + 1;
            codeRow.setHeightInPoints(Math.max(30, lines * 13f));
            codeRow.createCell(0);  // blank step-num col
            Cell codeCell = codeRow.createCell(1);
            codeCell.setCellValue(codeExample);
            codeCell.setCellStyle(s.playbookCode);
        }
        return r;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static int addSectionHeader(Sheet sheet, StyleSet s, int r, String text) {
        Row row = sheet.createRow(r++);
        Cell c = row.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(s.categoryHeader);
        return r;
    }

    private static int addKV(Sheet sheet, CellStyle headerStyle, int r,
                              String k, String v1, String v2) {
        Row row = sheet.createRow(r++);
        Cell ck = row.createCell(0); ck.setCellValue(k); ck.setCellStyle(headerStyle);
        Cell cv = row.createCell(1); cv.setCellValue(v1); cv.setCellStyle(headerStyle);
        if (!v2.isEmpty()) { Cell cv2 = row.createCell(2); cv2.setCellValue(v2); cv2.setCellStyle(headerStyle); }
        return r;
    }

    /** Lazily creates one cell style per severity level per workbook. */
    private static final Map<String, CellStyle> severityStyleCache = new WeakHashMap<>();

    private static CellStyle severityStyle(Workbook wb, String severity) {
        String key = severity + "@" + System.identityHashCode(wb);
        return severityStyleCache.computeIfAbsent(key, k -> {
            CellStyle style = wb.createCellStyle();
            IndexedColors color = SEVERITY_COLORS.getOrDefault(severity, IndexedColors.WHITE);
            style.setFillForegroundColor(color.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = wb.createFont();
            font.setBold("CRITICAL".equals(severity));
            style.setFont(font);
            return style;
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Style set (shared across all sheets in one workbook)
    // ──────────────────────────────────────────────────────────────────────────

    private static class StyleSet {
        final CellStyle title;
        final CellStyle header;
        final CellStyle categoryHeader;
        final CellStyle legend;
        final CellStyle devSource;
        final CellStyle stubSource;
        final CellStyle stubCell;

        // Migration Playbook styles
        final CellStyle playbookScenarioTitle; // dark green bar — one per scenario
        final CellStyle playbookStepNumber;    // bold step number (Col 0)
        final CellStyle playbookStepText;      // main step instruction (Col 1)
        final CellStyle playbookCode;          // inline code example (Col 1, indented)
        final CellStyle playbookNote;          // INFO / NOTE row (light blue)
        final CellStyle playbookDone;          // "Already done" row (light green)
        final CellStyle playbookWrap;          // plain wrap-text style for wide cells

        StyleSet(Workbook wb) {
            // Title style
            title = wb.createCellStyle();
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title.setFont(titleFont);

            // Column header style (grey)
            header = ExcelUtils.createHeaderStyle(wb);

            // Category header (dark blue + white text)
            categoryHeader = wb.createCellStyle();
            categoryHeader.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            categoryHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font catFont = wb.createFont();
            catFont.setBold(true);
            catFont.setColor(IndexedColors.WHITE.getIndex());
            categoryHeader.setFont(catFont);

            // Legend row (pale yellow, italic)
            legend = wb.createCellStyle();
            legend.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            legend.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font legendFont = wb.createFont();
            legendFont.setItalic(true);
            legendFont.setFontHeightInPoints((short) 9);
            legend.setFont(legendFont);
            legend.setWrapText(true);

            // "Developer code" label — green background
            devSource = wb.createCellStyle();
            devSource.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            devSource.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font devFont = wb.createFont();
            devFont.setBold(true);
            devFont.setFontHeightInPoints((short) 9);
            devSource.setFont(devFont);

            // "WL appc-generated" label — grey background, italic
            stubSource = wb.createCellStyle();
            stubSource.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            stubSource.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font stubSrcFont = wb.createFont();
            stubSrcFont.setItalic(true);
            stubSrcFont.setFontHeightInPoints((short) 9);
            stubSource.setFont(stubSrcFont);

            // Stub row cells — same grey, italic, slightly muted
            stubCell = wb.createCellStyle();
            stubCell.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            stubCell.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font stubFont = wb.createFont();
            stubFont.setItalic(true);
            stubFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            stubCell.setFont(stubFont);

            // ── Playbook styles ────────────────────────────────────────────────

            // Scenario title bar — dark green, white bold text, full-width merged
            playbookScenarioTitle = wb.createCellStyle();
            playbookScenarioTitle.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            playbookScenarioTitle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font pbTitleFont = wb.createFont();
            pbTitleFont.setBold(true);
            pbTitleFont.setColor(IndexedColors.WHITE.getIndex());
            pbTitleFont.setFontHeightInPoints((short) 11);
            playbookScenarioTitle.setFont(pbTitleFont);

            // Step number — bold, right-aligned
            playbookStepNumber = wb.createCellStyle();
            Font stepNumFont = wb.createFont();
            stepNumFont.setBold(true);
            playbookStepNumber.setFont(stepNumFont);
            playbookStepNumber.setAlignment(HorizontalAlignment.RIGHT);
            playbookStepNumber.setVerticalAlignment(VerticalAlignment.TOP);

            // Step text — wrap, left-aligned, top-aligned
            playbookStepText = wb.createCellStyle();
            playbookStepText.setWrapText(true);
            playbookStepText.setVerticalAlignment(VerticalAlignment.TOP);

            // Code block — Courier New, light steel blue background
            playbookCode = wb.createCellStyle();
            playbookCode.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            playbookCode.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font codeFont = wb.createFont();
            codeFont.setFontName("Courier New");
            codeFont.setFontHeightInPoints((short) 9);
            playbookCode.setFont(codeFont);
            playbookCode.setWrapText(true);
            playbookCode.setVerticalAlignment(VerticalAlignment.TOP);

            // Note row — light turquoise, italic
            playbookNote = wb.createCellStyle();
            playbookNote.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
            playbookNote.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font noteFont = wb.createFont();
            noteFont.setItalic(true);
            noteFont.setFontHeightInPoints((short) 9);
            playbookNote.setFont(noteFont);
            playbookNote.setWrapText(true);
            playbookNote.setVerticalAlignment(VerticalAlignment.TOP);

            // "Already done" row — light green
            playbookDone = wb.createCellStyle();
            playbookDone.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            playbookDone.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font doneFont = wb.createFont();
            doneFont.setItalic(true);
            doneFont.setFontHeightInPoints((short) 9);
            playbookDone.setFont(doneFont);
            playbookDone.setWrapText(true);
            playbookDone.setVerticalAlignment(VerticalAlignment.TOP);

            // Plain wrap style
            playbookWrap = wb.createCellStyle();
            playbookWrap.setWrapText(true);
            playbookWrap.setVerticalAlignment(VerticalAlignment.TOP);
        }
    }
}
