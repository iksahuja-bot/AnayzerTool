package effortanalyzer.wl14;

import effortanalyzer.library.LibraryUpgradeAnalyzer;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the WL14 module — runs a single combined library scan with the
 * general {@code upgrade} library rules plus dedicated WebLogic 12c → 14c API
 * rules, and bundled-library version checks. The output report still carries the
 * WL14 identity.
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

        assertTrue(analyzer.getUpgradeAnalyzer().isAnalyzed(),
                "WL14 combined library scan must complete successfully");
        assertEquals(1, analyzer.getVersionAnalyzer().countOutdated(),
                "spring-core 5.3.20 must be flagged outdated by the version check");

        Path out = tmp.resolve("WL14-Report.xlsx");
        analyzer.generateReport(out.toString());
        assertTrue(Files.exists(out), "WL14 report must be written");

        try (FileInputStream fis = new FileInputStream(out.toFile());
             Workbook wb = new XSSFWorkbook(fis)) {
            assertEquals(8, wb.getNumberOfSheets(),
                    "WL14 report must have 8 sheets including IBM Java 21 and WebLogic API Issues sheets");
            assertNotNull(wb.getSheet("☕ Java 21 Issues (IBM)"),
                    "Java 21 Issues sheet expected for WL14");
            assertNotNull(wb.getSheet("🏛 WebLogic API Issues"),
                    "WebLogic API Issues sheet expected for WL14");
            assertNotNull(wb.getSheet("🔢 Library Versions"), "Library Versions sheet expected");
            var versions = wb.getSheet("🔢 Library Versions");
            assertTrue(versions.getRow(0).getCell(0).getStringCellValue().startsWith("WL14"),
                    "versions sheet must carry the WL14 identity");
        }
    }

    @Test
    void wl14DetectsWebLogicSpecificDeprecatedApi(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("legacy-weblogic.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("com/example/LegacyStartup.java"));
            jos.write(("package com.example;\n"
                    + "import weblogic.common.T3StartupDef;\n"
                    + "public class LegacyStartup implements T3StartupDef {\n"
                    + "    public String startup(String name, Map args) { return null; }\n"
                    + "}\n").getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Wl14Analyzer analyzer = new Wl14Analyzer();
        analyzer.analyze(jar.toString());

        Map<String, List<LibraryUpgradeAnalyzer.Finding>> wl14Findings =
                analyzer.getWl14ApiAnalyzer().getFindingsByJar();

        assertFalse(wl14Findings.isEmpty(),
                "WL14 combined scan must produce findings for deprecated WebLogic classes");

        boolean foundT3Startup = wl14Findings.values().stream()
                .flatMap(List::stream)
                .anyMatch(f -> f.deprecatedClass().contains("T3StartupDef")
                        && "WebLogic 14.1.2".equals(f.library()));
        assertTrue(foundT3Startup,
                "Expected finding for weblogic.common.T3StartupDef in WebLogic 14.1.2 library");
    }

    @Test
    void wl14SpecificRuleDoesNotTriggerOnReplacementApi(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("modern-weblogic.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("com/example/ModernListener.java"));
            jos.write(("package com.example;\n"
                    + "import weblogic.application.ApplicationLifecycleListener;\n"
                    + "public class ModernListener extends ApplicationLifecycleListener {}\n")
                    .getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Wl14Analyzer analyzer = new Wl14Analyzer();
        analyzer.analyze(jar.toString());

        Map<String, List<LibraryUpgradeAnalyzer.Finding>> wl14Findings =
                analyzer.getWl14ApiAnalyzer().getFindingsByJar();

        assertFalse(wl14Findings.values().stream()
                        .flatMap(List::stream)
                        .anyMatch(f -> f.deprecatedClass().contains("T3StartupDef")),
                "The replacement listener must not be flagged as T3StartupDef");
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