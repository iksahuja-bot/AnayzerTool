package effortanalyzer.source;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceInventoryReportWriterTest {

    @Test
    void actionItemsSheetKeepsSourceOnlyComponentAndAddsAutomationGuidance(@TempDir Path tmpDir) throws Exception {
        SourceComponent component = new SourceComponent("cluster", "file:///repo/cluster", RepositoryType.LOCAL,
                "", "", "", true, 2, List.of("target/cluster.jar"), List.of("com.example.cluster"), "application code");
        CheckoutResult checkout = CheckoutResult.success(component, tmpDir.resolve("cluster"), "LOCAL", "Using local source directory");
        SourceFinding finding = new SourceFinding("cluster", component.repository(), component.type(), "java21-source",
                "Java 21", "HIGH", "src/main/java/com/example/cluster/ClusterService.java", 42,
                "javax.xml.bind", "JAXB APIs are not available by default on Java 21.",
                "Replace JAXB usage with jakarta.xml.bind APIs and add the supported Jakarta JAXB dependency.",
                "import javax.xml.bind.JAXBContext;");
        SourceFinding secondFinding = new SourceFinding("cluster", component.repository(), component.type(), "java21-source",
                "Java 21", "MEDIUM", "src/main/java/com/example/cluster/ClusterConfig.java", 9,
                "javax.annotation", "Java EE annotations are no longer bundled with the JDK.",
                "Replace javax.annotation imports with jakarta.annotation APIs and add the supported Jakarta annotation dependency.",
                "import javax.annotation.PostConstruct;");
        SourceScanResult result = new SourceScanResult(component, checkout, 12, List.of(finding, secondFinding), List.of());

        Path report = tmpDir.resolve("source-report.xlsx");
        new SourceInventoryReportWriter().write(report.toString(), "wl14", List.of(result));

        try (FileInputStream in = new FileInputStream(report.toFile());
             Workbook wb = WorkbookFactory.create(in)) {
            var actionItems = wb.getSheet("🎯 Action Items");
            var rawActionItems = wb.getSheet("Action Items Raw");
            assertNotNull(actionItems, "focused action-items sheet must exist");
            assertNotNull(rawActionItems, "normalized raw action item sheet must exist");

            String sheetText = sheetText(actionItems);
            String rawSheetText = sheetText(rawActionItems);
            assertAll(
                    () -> assertEquals(wb.getSheetIndex("📊 Summary") + 1, wb.getSheetIndex("🎯 Action Items"),
                            "action items should be immediately after summary"),
                    () -> assertTrue(sheetText.contains("Action Item ID"), "human sheet should include stable action item IDs"),
                    () -> assertTrue(sheetText.contains("Remediation Type"), "human sheet should classify remediation type"),
                    () -> assertTrue(sheetText.contains("Automation Readiness"), "human sheet should score automation readiness"),
                    () -> assertTrue(sheetText.contains("Fixable Source Location"), "human sheet should expose the source workspace path"),
                    () -> assertTrue(sheetText.contains("Trunk Source Location"), "human sheet should expose the trunk workspace path"),
                    () -> assertTrue(sheetText.contains("WL14-AI-"), "action item IDs should be populated"),
                    () -> assertTrue(sheetText.contains("JAVA_API_MIGRATION"), "Java API findings should be classified for automation"),
                    () -> assertTrue(sheetText.contains("javax.xml.bind"), "detected symbols should be populated"),
                    () -> assertTrue(sheetText.contains("jakarta.xml.bind"), "replacement symbols should be populated"),
                    () -> assertTrue(sheetText.contains("src/main/java/com/example/cluster/ClusterService.java"), "primary file should be visible"),
                    () -> assertTrue(sheetText.contains("42"), "line hints should be visible"),
                    () -> assertTrue(rawSheetText.contains("action_item_id"), "raw sheet should use normalized headers"),
                    () -> assertTrue(rawSheetText.contains("fixable_source_path"), "raw sheet should expose source workspace paths"),
                    () -> assertTrue(rawSheetText.contains("trunk_source_path"), "raw sheet should expose trunk workspace paths"),
                    () -> assertTrue(rawSheetText.contains("automation_guardrails"), "raw sheet should include guardrails"),
                    () -> assertTrue(wb.isSheetHidden(wb.getSheetIndex("Action Items Raw")), "raw automation sheet should be hidden"),
                    () -> assertTrue(hasGroupedRows(actionItems), "action items should use row grouping for component-level work")
            );
        }
    }

    @Test
    void actionItemsAndInventoryExposeFixableAndTrunkLocations(@TempDir Path tmpDir) throws Exception {
        SourceComponent component = new SourceComponent("cluster", "file:///repo/cluster", "file:///repo/cluster/trunk",
                RepositoryType.LOCAL, "", "", "", true, 2, List.of("target/cluster.jar"),
                List.of("com.example.cluster"), "application code");
        CheckoutResult checkout = CheckoutResult.success(component, tmpDir.resolve("cluster-current"), "LOCAL", "Using local source directory");
        CheckoutResult trunkCheckout = CheckoutResult.success(component, tmpDir.resolve("cluster-trunk"), "LOCAL", "Using local trunk source directory");
        SourceFinding finding = new SourceFinding("cluster", component.repository(), component.type(), "java21-source",
                "Java 21", "HIGH", "src/main/java/com/example/cluster/ClusterService.java", 42,
                "javax.xml.bind", "JAXB APIs are not available by default on Java 21.",
                "Replace JAXB usage with jakarta.xml.bind APIs and add the supported Jakarta JAXB dependency.",
                "import javax.xml.bind.JAXBContext;");

        Path report = tmpDir.resolve("source-report.xlsx");
        new SourceInventoryReportWriter().write(report.toString(), "wl14",
                List.of(new SourceScanResult(component, checkout, trunkCheckout, 12, List.of(finding), List.of())));

        try (FileInputStream in = new FileInputStream(report.toFile());
             Workbook wb = WorkbookFactory.create(in)) {
            String actionText = sheetText(wb.getSheet("🎯 Action Items"));
            String rawText = sheetText(wb.getSheet("Action Items Raw"));
            String inventoryText = sheetText(wb.getSheet("Source Inventory"));
            assertAll(
                    () -> assertTrue(actionText.contains("file:///repo/cluster"), "action items should expose the fixable source URL"),
                    () -> assertTrue(actionText.contains("file:///repo/cluster/trunk"), "action items should expose the trunk source URL"),
                    () -> assertTrue(actionText.contains(tmpDir.resolve("cluster-current").toString()), "action items should expose the fixable workspace path"),
                    () -> assertTrue(actionText.contains(tmpDir.resolve("cluster-trunk").toString()), "action items should expose the trunk workspace path"),
                    () -> assertTrue(actionText.contains("Compare fixable source with trunk before editing"), "action items should guide trunk-assisted fixes"),
                    () -> assertTrue(rawText.contains("fixable_source_url"), "raw sheet should include fixable source URL header"),
                    () -> assertTrue(rawText.contains("fixable_source_path"), "raw sheet should include fixable source workspace header"),
                    () -> assertTrue(rawText.contains("trunk_source_url"), "raw sheet should include trunk source URL header"),
                    () -> assertTrue(rawText.contains("trunk_source_path"), "raw sheet should include trunk source workspace header"),
                    () -> assertTrue(rawText.contains("Use the local trunk workspace as read-only comparison input"), "raw guardrails should reference local trunk comparison input"),
                    () -> assertTrue(inventoryText.contains("Trunk Checkout Path"), "inventory should include the trunk checkout path column"),
                    () -> assertTrue(inventoryText.contains("file:///repo/cluster/trunk"), "inventory should carry trunk values from ComponentList"),
                    () -> assertTrue(inventoryText.contains(tmpDir.resolve("cluster-trunk").toString()), "inventory should include the prepared trunk workspace path")
            );
        }
    }

    @Test
    void appendAddsSourceInstructionsAndPlacesActionItemsAfterExistingSummary(@TempDir Path tmpDir) throws Exception {
        Path report = tmpDir.resolve("combined-report.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream out = new FileOutputStream(report.toFile())) {
            wb.createSheet("📋 Instructions").createRow(0).createCell(0).setCellValue("Existing module instructions");
            wb.createSheet("📊 Summary").createRow(0).createCell(0).setCellValue("Existing module summary");
            wb.createSheet("📦 Library Issues");
            wb.write(out);
        }

        SourceComponent component = new SourceComponent("cluster", "file:///repo/cluster", RepositoryType.LOCAL,
                "", "", "", true, 2, List.of(), List.of(), "application code");
        CheckoutResult checkout = CheckoutResult.success(component, tmpDir.resolve("cluster"), "LOCAL", "Using local source directory");
        SourceFinding finding = new SourceFinding("cluster", component.repository(), component.type(), "java21-source",
                "Java 21", "HIGH", "src/main/java/com/example/cluster/ClusterService.java", 42,
                "javax.xml.bind", "JAXB APIs are not available by default on Java 21.",
                "Replace JAXB usage with jakarta.xml.bind APIs and add the supported Jakarta JAXB dependency.",
                "import javax.xml.bind.JAXBContext;");

        new SourceInventoryReportWriter().append(report.toString(), "wl14",
                List.of(new SourceScanResult(component, checkout, 12, List.of(finding), List.of())));

        try (FileInputStream in = new FileInputStream(report.toFile());
             Workbook wb = WorkbookFactory.create(in)) {
            assertAll(
                    () -> assertEquals(wb.getSheetIndex("📊 Summary") + 1, wb.getSheetIndex("🎯 Action Items"),
                            "appended action items should be immediately after existing summary"),
                    () -> assertTrue(wb.isSheetHidden(wb.getSheetIndex("Action Items Raw")),
                            "appended normalized raw sheet should exist and stay hidden"),
                    () -> assertTrue(sheetText(wb.getSheet("📋 Instructions")).contains("🎯 Action Items"),
                            "existing module instructions should document appended source sheets"),
                    () -> assertTrue(sheetText(wb.getSheet("📋 Instructions")).contains("Source Inventory"),
                            "existing module instructions should document source inventory")
            );
        }
    }

    private static boolean hasGroupedRows(org.apache.poi.ss.usermodel.Sheet sheet) {
        for (org.apache.poi.ss.usermodel.Row row : sheet) {
            if (row.getOutlineLevel() > 0) return true;
        }
        return false;
    }

    private static String sheetText(org.apache.poi.ss.usermodel.Sheet sheet) {
        StringBuilder sb = new StringBuilder();
        for (org.apache.poi.ss.usermodel.Row row : sheet) {
            for (org.apache.poi.ss.usermodel.Cell cell : row) {
                sb.append(cell).append('\n');
            }
        }
        return sb.toString();
    }
}