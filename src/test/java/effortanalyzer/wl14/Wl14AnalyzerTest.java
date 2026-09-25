package effortanalyzer.wl14;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the WL14 module — it must run the same check set as WL15
 * (API scan + library version scan) and produce the 6-sheet report
 * carrying the WL14 identity.
 */
class Wl14AnalyzerTest {

    @Test
    void wl14RunsApiAndVersionChecksAndWritesReport(@TempDir Path tmp) throws IOException {
        // Synthetic WAR: deprecated Spring Security API source + outdated bundled jars
        Path war = tmp.resolve("legacyapp.war");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(war))) {
            jos.putNextEntry(new JarEntry("WEB-INF/classes/com/example/SecurityConfig.java"));
            jos.write(("package com.example;\n"
                    + "import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;\n"
                    + "public class SecurityConfig extends WebSecurityConfigurerAdapter {}\n")
                    .getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("WEB-INF/lib/spring-core-5.3.20.jar"));
            jos.write(new byte[0]);
            jos.closeEntry();
        }

        Wl14Analyzer analyzer = new Wl14Analyzer();
        analyzer.analyze(war.toString());

        assertEquals(1, analyzer.getVersionAnalyzer().countOutdated(),
                "spring-core 5.3.20 must be flagged outdated by the version check");

        Path out = tmp.resolve("WL14-Report.xlsx");
        analyzer.generateReport(out.toString());
        assertTrue(Files.exists(out), "WL14 report must be written");

        try (FileInputStream fis = new FileInputStream(out.toFile());
             Workbook wb = new XSSFWorkbook(fis)) {
            assertEquals(6, wb.getNumberOfSheets(), "WL14 report must have the same 6 sheets");
            assertNotNull(wb.getSheet("🔢 Library Versions"), "Library Versions sheet expected");
            var versions = wb.getSheet("🔢 Library Versions");
            assertTrue(versions.getRow(0).getCell(0).getStringCellValue().startsWith("WL14"),
                    "versions sheet must carry the WL14 identity");
        }
    }

    @Test
    void libraryVersionsOverrideAppliesToWl14() throws IOException {
        // Custom override file raising the spring-core target must be picked up.
        Path tmp = Files.createTempDirectory("wl14-override-");
        Path props = tmp.resolve("library-versions.properties");
        Files.writeString(props, "spring-core=99.0.0\n");

        Wl14Analyzer analyzer = new Wl14Analyzer(props.toString());
        // Rule count = built-ins (spring-core row retargeted, none added/removed)
        assertEquals(effortanalyzer.version.LibraryVersionRules.builtIn().size(),
                analyzer.getVersionAnalyzer().getRuleCount());
    }
}