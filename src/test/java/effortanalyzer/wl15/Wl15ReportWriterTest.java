package effortanalyzer.wl15;

import effortanalyzer.library.LibraryUpgradeAnalyzer;
import effortanalyzer.version.LibraryVersionAnalyzer.VersionFinding;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Wl15ReportWriter.
 *
 * The report now has 6 sheets:
 *   📋 Instructions, 📊 Summary, 📦 Library Issues,
 *   ✅ Remediation Checklist, ⏱ Effort Analysis, 🔢 Library Versions
 */
class Wl15ReportWriterTest {

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private static LibraryUpgradeAnalyzer.Finding finding(
            String library, String cls, String method,
            String severity, String replacement) {
        return new LibraryUpgradeAnalyzer.Finding(
                "test.jar", "com/example/Foo.java", 10,
                library, cls, method, severity, replacement,
                "Test description", "test context");
    }

    private static Map<String, List<LibraryUpgradeAnalyzer.Finding>> wrapFindings(
            List<LibraryUpgradeAnalyzer.Finding> findings) {
        return Map.of("test.jar", findings);
    }

    // ── Report written ────────────────────────────────────────────────────────

    @Test
    void reportFileIsCreated(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("WL15-Test-Report.xlsx");
        List<LibraryUpgradeAnalyzer.Finding> findings = List.of(
                finding("Spring Security 6.5.9",
                        "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter",
                        null, "CRITICAL", "Replace with @Bean SecurityFilterChain"),
                finding("EhCache 3.11.1", "net.sf.ehcache", null, "CRITICAL",
                        "Migrate to org.ehcache")
        );

        new Wl15ReportWriter(wrapFindings(findings), 80).write(out.toString());

        assertTrue(Files.exists(out), "Report file must be created");
        assertTrue(Files.size(out) > 0, "Report file must not be empty");
    }

    @Test
    void reportHasCorrectSheetNames(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("WL15-Test-Report.xlsx");
        List<LibraryUpgradeAnalyzer.Finding> findings = List.of(
                finding("Spring 6.2.11",
                        "org.springframework.web.servlet.handler.HandlerInterceptorAdapter",
                        null, "CRITICAL", "Implement HandlerInterceptor directly"),
                finding("Jetty 12.0.33", "javax.servlet",
                        null, "CRITICAL", "Replace with jakarta.servlet"),
                finding("Jackson 2.18.9",
                        "com.fasterxml.jackson.databind.ObjectMapper",
                        "enableDefaultTyping", "CRITICAL", "Use activateDefaultTyping()")
        );

        new Wl15ReportWriter(wrapFindings(findings), 80).write(out.toString());

        try (FileInputStream fis = new FileInputStream(out.toFile());
             Workbook wb = new XSSFWorkbook(fis)) {

            Set<String> sheetNames = IntStream.range(0, wb.getNumberOfSheets())
                    .mapToObj(wb::getSheetName)
                    .collect(Collectors.toSet());

            assertAll("Expected sheet names present",
                    () -> assertTrue(sheetNames.contains("📋 Instructions"),   "Instructions sheet expected"),
                    () -> assertTrue(sheetNames.contains("📊 Summary"),         "Summary sheet expected"),
                    () -> assertTrue(sheetNames.contains("📦 Library Issues"),  "Library Issues sheet expected"),
                    () -> assertTrue(sheetNames.contains("✅ Remediation Checklist"), "Checklist sheet expected"),
                    () -> assertTrue(sheetNames.contains("⏱ Effort Analysis"),  "Effort Analysis sheet expected"),
                    () -> assertTrue(sheetNames.contains("🔢 Library Versions"), "Library Versions sheet expected")
            );
            assertEquals(6, wb.getNumberOfSheets(), "Report must have exactly 6 sheets");
        }
    }

    @Test
    void libraryIssuesSheetContainsRows(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("WL15-Test-Report.xlsx");
        List<LibraryUpgradeAnalyzer.Finding> findings = List.of(
                finding("EhCache 3.11.1", "net.sf.ehcache.CacheManager",
                        null, "CRITICAL", "Use org.ehcache.CacheManager"),
                finding("EhCache 3.11.1", "net.sf.ehcache.Element",
                        null, "CRITICAL", "Remove Element wrapper"),
                finding("Netty 4.1.135.Final",
                        "io.netty.channel.ChannelHandlerContext",
                        "attr", "HIGH", "Use Channel.attr(key)")
        );

        new Wl15ReportWriter(wrapFindings(findings), 80).write(out.toString());

        try (FileInputStream fis = new FileInputStream(out.toFile());
             Workbook wb = new XSSFWorkbook(fis)) {

            var findingsSheet = wb.getSheet("📦 Library Issues");
            assertNotNull(findingsSheet, "Library Issues sheet must exist");
            // Rows 0-1: title + info bar; row 2: blank; row 3: column headers; rows 4+: data
            int dataRows = findingsSheet.getLastRowNum() - 3;
            assertTrue(dataRows >= findings.size(),
                    "Library Issues sheet must contain at least " + findings.size()
                    + " data rows (found " + dataRows + ")");
        }
    }

    @Test
    void checklistSheetDeduplicatesFindings(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("WL15-Test-Report.xlsx");
        // Same deprecated API in 3 different files — checklist should deduplicate to 1 row
        List<LibraryUpgradeAnalyzer.Finding> findingsInJar1 = List.of(
                new LibraryUpgradeAnalyzer.Finding(
                        "app1.jar", "com/a/Foo.java", 5, "EhCache 3.11.1",
                        "net.sf.ehcache.CacheManager", null, "CRITICAL",
                        "Use org.ehcache.CacheManager", "desc", "ctx"),
                new LibraryUpgradeAnalyzer.Finding(
                        "app1.jar", "com/b/Bar.java", 12, "EhCache 3.11.1",
                        "net.sf.ehcache.CacheManager", null, "CRITICAL",
                        "Use org.ehcache.CacheManager", "desc", "ctx"),
                new LibraryUpgradeAnalyzer.Finding(
                        "app1.jar", "com/c/Baz.java", 8, "EhCache 3.11.1",
                        "net.sf.ehcache.CacheManager", null, "CRITICAL",
                        "Use org.ehcache.CacheManager", "desc", "ctx")
        );

        Map<String, List<LibraryUpgradeAnalyzer.Finding>> byJar = Map.of("app1.jar", findingsInJar1);
        new Wl15ReportWriter(byJar, 80).write(out.toString());

        try (FileInputStream fis = new FileInputStream(out.toFile());
             Workbook wb = new XSSFWorkbook(fis)) {

            var checklist = wb.getSheet("✅ Remediation Checklist");
            assertNotNull(checklist, "Remediation Checklist sheet must exist");
            // Rows 0-1: title + info bar; row 2: blank; row 3: column headers; row 4: 1 data row
            int dataRows = checklist.getLastRowNum() - 3;
            assertEquals(1, dataRows,
                    "3 findings for the same API should yield exactly 1 deduplicated checklist row");
        }
    }

    @Test
    void emptyFindingsProducesValidReport(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("WL15-Empty-Report.xlsx");
        new Wl15ReportWriter(Map.of(), 80).write(out.toString());

        assertTrue(Files.exists(out), "Report must be created even with zero findings");
        try (FileInputStream fis = new FileInputStream(out.toFile());
             Workbook wb = new XSSFWorkbook(fis)) {
            assertEquals(6, wb.getNumberOfSheets(),
                    "Empty report must still have all 6 sheets");
        }
    }

    @Test
    void libraryIssuesSheetSortsBySeverity(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("WL15-SevSort-Report.xlsx");
        List<LibraryUpgradeAnalyzer.Finding> findings = List.of(
                finding("Spring 6.2.11", "org.springframework.util.AntPathMatcher",
                        null, "WARNING", "Use PathPatternParser"),
                finding("Spring Security 6.5.9",
                        "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter",
                        null, "CRITICAL", "Replace with SecurityFilterChain"),
                finding("Jetty 12.0.33", "javax.servlet",
                        null, "CRITICAL", "Replace with jakarta.servlet")
        );

        new Wl15ReportWriter(wrapFindings(findings), 80).write(out.toString());

        try (FileInputStream fis = new FileInputStream(out.toFile());
             Workbook wb = new XSSFWorkbook(fis)) {

            var sheet = wb.getSheet("📦 Library Issues");
            assertNotNull(sheet, "Library Issues sheet must exist");
            // Row 3 = headers; row 4 = first data row
            // First data row's Severity column (col 5) should be CRITICAL (sorted first)
            String firstSeverity = sheet.getRow(4).getCell(5).getStringCellValue();
            assertEquals("CRITICAL", firstSeverity,
                    "Library Issues sheet must sort CRITICAL findings before WARNING");

            // All 3 findings should be present
            int dataRows = sheet.getLastRowNum() - 3;
            assertEquals(findings.size(), dataRows, "All 3 findings must appear in Library Issues");
        }
    }

    // ── Library Versions sheet ────────────────────────────────────────────────

    private static VersionFinding versionFinding(String artifact, String found, String target,
                                                 String status, String severity) {
        return new VersionFinding("legacyapp.war", "WEB-INF/lib/" + artifact + "-" + found + ".jar",
                artifact, found, target, status, severity, "Test Library",
                "OUTDATED".equals(status) ? "Upgrade to " + target + " or later"
                                          : "Target version satisfied");
    }

    @Test
    void versionsSheetListsOutdatedAndOkRows(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("WL15-Versions-Report.xlsx");
        List<VersionFinding> versions = List.of(
                versionFinding("spring-core", "5.3.20", "6.2.11", "OUTDATED", "CRITICAL"),
                versionFinding("spring-web",  "6.2.11", "6.2.11", "OK",       ""),
                versionFinding("c3p0",        "0.9.5",  "0.12.0", "OUTDATED", "WARNING")
        );

        new Wl15ReportWriter(Map.of(), 80, versions, Wl15ReportWriter.ReportProduct.WL15)
                .write(out.toString());

        try (FileInputStream fis = new FileInputStream(out.toFile());
             Workbook wb = new XSSFWorkbook(fis)) {

            var sheet = wb.getSheet("🔢 Library Versions");
            assertNotNull(sheet, "Library Versions sheet must exist");

            // Rows 0-1: title + info bar; row 2: blank; row 3: headers; rows 4+: data
            int dataRows = sheet.getLastRowNum() - 3;
            assertEquals(versions.size(), dataRows, "All version findings must appear as rows");

            // Outdated rows sort first; CRITICAL before WARNING
            assertEquals("spring-core", sheet.getRow(4).getCell(2).getStringCellValue(),
                    "First row must be the CRITICAL outdated artifact");
            assertEquals("OUTDATED",    sheet.getRow(4).getCell(5).getStringCellValue());
            assertEquals("CRITICAL",    sheet.getRow(4).getCell(1).getStringCellValue());
            assertEquals("c3p0",        sheet.getRow(5).getCell(2).getStringCellValue(),
                    "Second row must be the WARNING outdated artifact");
            assertEquals("OK",          sheet.getRow(6).getCell(5).getStringCellValue(),
                    "OK rows sort after outdated rows");
        }
    }

    @Test
    void emptyVersionsSheetStillPresent(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("WL15-NoVersions-Report.xlsx");
        new Wl15ReportWriter(Map.of(), 80, List.of(), Wl15ReportWriter.ReportProduct.WL14)
                .write(out.toString());

        try (FileInputStream fis = new FileInputStream(out.toFile());
             Workbook wb = new XSSFWorkbook(fis)) {
            var sheet = wb.getSheet("🔢 Library Versions");
            assertNotNull(sheet, "Library Versions sheet must exist even with no version findings");
            assertTrue(sheet.getRow(0).getCell(0).getStringCellValue().contains("WL14"),
                    "WL14 product identity must appear in the sheet title");
        }
    }
}
