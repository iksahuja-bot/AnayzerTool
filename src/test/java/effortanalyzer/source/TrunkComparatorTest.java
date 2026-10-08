package effortanalyzer.source;

import effortanalyzer.wljboss.WlJBossRules;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TrunkComparatorTest {

    @Test
    void classifiesFixedSameAndRemovedAcrossDifferentLayouts(@TempDir Path tmp) throws Exception {
        SourceScanResult result = scanBoth(tmp, false);
        List<SourceFinding> correlated = SourceFindingCorrelator.correlate(result);

        assertAll(
                () -> assertEquals(TrunkComparator.FIXED, statusFor(correlated, "src/com/x/A.java", "sun.misc.Service")),
                () -> assertTrue(evidenceFor(correlated, "src/com/x/A.java", "sun.misc.Service").contains("module/src/main/java/com/x/A.java"),
                        "trunk evidence names the trunk file even though the layout differs"),
                () -> assertEquals(TrunkComparator.SAME, statusFor(correlated, "src/com/x/B.java", "newInstance()")),
                () -> assertEquals(TrunkComparator.REMOVED, statusFor(correlated, "src/com/x/C.java", "sun.misc.Service")),
                () -> assertEquals(TrunkComparator.FIXED, correlated.stream()
                        .filter(f -> "pom.xml".equals(f.file())).findFirst().orElseThrow().trunkStatus(), "non-Java files compare by path")
        );
    }

    @Test
    void validatedTrunkMakesSameFindingsNotRequired(@TempDir Path tmp) throws Exception {
        SourceScanResult result = scanBoth(tmp, true);
        List<SourceFinding> correlated = SourceFindingCorrelator.correlate(result);
        assertEquals(TrunkComparator.SAME_VALIDATED, statusFor(correlated, "src/com/x/B.java", "newInstance()"));

        Path report = tmp.resolve("report.xlsx");
        new SourceInventoryReportWriter().write(report.toString(), "wl14", List.of(result));
        try (FileInputStream in = new FileInputStream(report.toFile()); Workbook wb = WorkbookFactory.create(in)) {
            Sheet raw = wb.getSheet("Action Items Raw");
            Row header = raw.getRow(0);
            int readiness = column(header, "automation_readiness");
            int trunkStatus = column(header, "trunk_status");
            int guidance = column(header, "trunk_fix_guidance");
            Row sameRow = rowWhere(raw, column(header, "rule"), "newInstance()").orElseThrow();
            Row fixedRow = rowWhere(raw, trunkStatus, TrunkComparator.FIXED).orElseThrow();
            String checklist = text(wb.getSheet("✅ Remediation Checklist"));
            assertAll(
                    () -> assertTrue(sameRow.getCell(readiness).getStringCellValue().startsWith("NOT REQUIRED")),
                    () -> assertEquals(TrunkComparator.SAME_VALIDATED, sameRow.getCell(trunkStatus).getStringCellValue()),
                    () -> assertTrue(fixedRow.getCell(guidance).getStringCellValue().startsWith("Fixed in trunk")),
                    () -> assertFalse(checklist.contains("newInstance()"), "not-required findings leave the checklist"),
                    () -> assertTrue(text(wb.getSheet("📊 Summary")).contains("Not required: same code in validated trunk")),
                    () -> assertTrue(text(wb.getSheet("Source Inventory")).contains("Yes"), "Trunk Validated is visible")
            );
        }
    }

    @Test
    void bytecodeOnlyFindingIsMappedToTheDeclaringSourceFile(@TempDir Path tmp) throws Exception {
        SourceScanResult scanned = scanBoth(tmp, false);
        GeneratedArtifactScanner.BytecodeFinding bytecode = new GeneratedArtifactScanner.BytecodeFinding("pal", "file:///cur",
                RepositoryType.LOCAL, "pal.jar", "com/x/B$Inner.class", "com.x.B$Inner", JdkToolScanner.SCANNER,
                JdkToolScanner.JDK_DEPRECATED, "INFO", "java.lang.Class.newInstance()", "deprecated", "use constructor");
        SourceScanResult result = new SourceScanResult(scanned.component(), scanned.checkout(), scanned.trunkCheckout(),
                scanned.filesScanned(), List.of(), List.of(bytecode), scanned.trunkFindings(), null);

        SourceFinding mapped = SourceFindingCorrelator.correlate(result).get(0);

        assertEquals("src/com/x/B.java", mapped.file());
        assertEquals(5, mapped.line());
        assertEquals("BYTECODE_ONLY_MAPPED_TO_SOURCE", mapped.reasonCode());
        assertEquals(TrunkComparator.SAME, mapped.trunkStatus(), "the trunk class still calls Class.newInstance()");
    }

    @Test
    void noTrunkMeansNotCompared(@TempDir Path tmp) throws Exception {
        SourceScanResult scanned = scanBoth(tmp, false);
        SourceScanResult withoutTrunk = new SourceScanResult(scanned.component(), scanned.checkout(),
                CheckoutResult.failure(scanned.component(), null, "TRUNK_NOT_PROVIDED", "No Trunk value was supplied"),
                scanned.filesScanned(), scanned.findings(), List.of());

        assertTrue(SourceFindingCorrelator.correlate(withoutTrunk).stream()
                .allMatch(f -> TrunkComparator.NOT_COMPARED.equals(f.trunkStatus())));
    }

    private static SourceScanResult scanBoth(Path tmp, boolean trunkValidated) throws Exception {
        Path current = tmp.resolve("current");
        write(current, "src/com/x/A.java", """
                package com.x;

                import sun.misc.Service;

                public class A {
                    Object load() { return Service.providers(Runnable.class); }
                }
                """);
        write(current, "src/com/x/B.java", """
                package com.x;

                public class B {
                    Object make(Class<?> clazz) throws Exception {
                        return clazz.newInstance();
                    }
                }
                """);
        write(current, "src/com/x/C.java", """
                package com.x;

                import sun.misc.Service;

                public class C {
                }
                """);
        write(current, "pom.xml", "<project><properties><maven.compiler.source>1.8</maven.compiler.source></properties></project>\n");

        Path trunk = tmp.resolve("trunk");
        write(trunk, "module/src/main/java/com/x/A.java", """
                package com.x;

                import java.util.ServiceLoader;

                public class A {
                    Object load() { return ServiceLoader.load(Runnable.class); }
                }
                """);
        write(trunk, "module/src/main/java/com/x/B.java", """
                package com.x;

                public class B {
                    Object make(Class<?> clazz) throws Exception {
                        return clazz.newInstance();
                    }
                }
                """);
        write(trunk, "pom.xml", "<project><properties><maven.compiler.release>21</maven.compiler.release></properties></project>\n");

        SourceComponent component = new SourceComponent("pal", "file:///cur", "file:///trunk", RepositoryType.LOCAL,
                "", "", "", true, 2, List.of(), List.of(), "", trunkValidated);
        SourceTreeScanner scanner = new SourceTreeScanner(SourceScanProfile.forModule("wl14", WlJBossRules.TargetProfile.WILDFLY27_JAVA21));
        CheckoutResult checkout = CheckoutResult.success(component, current, "LOCAL", "local");
        CheckoutResult trunkCheckout = CheckoutResult.success(component, trunk, "LOCAL", "local");
        SourceScanResult currentScan = scanner.scan(checkout);
        return new SourceScanResult(component, checkout, trunkCheckout, currentScan.filesScanned(), currentScan.findings(),
                List.of(), scanner.scan(trunkCheckout).findings(), null);
    }

    private static String statusFor(List<SourceFinding> findings, String file, String rule) {
        return find(findings, file, rule).trunkStatus();
    }

    private static String evidenceFor(List<SourceFinding> findings, String file, String rule) {
        return find(findings, file, rule).trunkEvidence();
    }

    private static SourceFinding find(List<SourceFinding> findings, String file, String rule) {
        return findings.stream().filter(f -> file.equals(f.file()) && rule.equals(f.rule())).findFirst()
                .orElseThrow(() -> new AssertionError("No finding " + rule + " in " + file + ": " + findings));
    }

    private static int column(Row header, String name) {
        for (Cell cell : header) {
            if (name.equals(cell.getStringCellValue())) return cell.getColumnIndex();
        }
        throw new AssertionError("Missing column " + name);
    }

    private static Optional<Row> rowWhere(Sheet sheet, int column, String value) {
        for (Row row : sheet) {
            Cell cell = row.getCell(column);
            if (row.getRowNum() > 0 && cell != null && value.equals(cell.toString())) return Optional.of(row);
        }
        return Optional.empty();
    }

    private static String text(Sheet sheet) {
        StringBuilder sb = new StringBuilder();
        for (Row row : sheet) {
            for (Cell cell : row) sb.append(cell).append('\n');
        }
        return sb.toString();
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
