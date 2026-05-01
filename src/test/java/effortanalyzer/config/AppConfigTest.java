package effortanalyzer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    // ── Argument parsing ─────────────────────────────────────────────────────

    @Test
    void parseSetsModuleFromEqualsForm() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=upgrade"});
        assertEquals("upgrade", cfg.getModule());
    }

    @Test
    void parseSetsModuleFromSpaceForm() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module", "analyze"});
        assertEquals("analyze", cfg.getModule());
    }

    @Test
    void parseSetsModuleFromPositionalArg() {
        // Positional arg sets the module only when no properties file has already set it.
        // The classpath analyzer.properties was removed, and no working-dir file exists,
        // so the positional arg is the only source and is picked up correctly.
        AppConfig cfg = AppConfig.parse(new String[]{"wl-jboss27"});
        assertEquals("wl-jboss27", cfg.getModule());
    }

    @Test
    void parseSetsInputPath() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=upgrade", "--input=/some/path"});
        assertEquals("/some/path", cfg.getInputPath());
    }

    @Test
    void parseSetsOutputFile() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=upgrade", "--output=custom.xlsx"});
        assertEquals("custom.xlsx", cfg.getOutputFile());
    }

    @Test
    void parseShortHelpFlagSetsHelpRequested() {
        AppConfig cfg = AppConfig.parse(new String[]{"-h"});
        assertTrue(cfg.isHelpRequested());
    }

    @Test
    void parseLongHelpFlagSetsHelpRequested() {
        AppConfig cfg = AppConfig.parse(new String[]{"--help"});
        assertTrue(cfg.isHelpRequested());
    }

    @Test
    void parseShortVersionFlagSetsVersionRequested() {
        AppConfig cfg = AppConfig.parse(new String[]{"-v"});
        assertTrue(cfg.isVersionRequested());
    }

    @Test
    void parseLongVersionFlagSetsVersionRequested() {
        AppConfig cfg = AppConfig.parse(new String[]{"--version"});
        assertTrue(cfg.isVersionRequested());
    }

    // ── Default output filenames ─────────────────────────────────────────────

    @Test
    void defaultOutputForUpgradeModule() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=upgrade"});
        assertEquals("Upgrade-Compatibility-Report.xlsx", cfg.getOutputFile());
    }

    @Test
    void defaultOutputForAnalyzeModule() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=analyze"});
        assertEquals("AnalyzerOutput.xlsx", cfg.getOutputFile());
    }

    @Test
    void defaultOutputForMergeModule() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=merge"});
        assertEquals("MergedOutput.xlsx", cfg.getOutputFile());
    }

    @Test
    void defaultOutputForWlJboss26Module() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=wl-jboss26"});
        assertEquals("WlToJBoss-WildFly26-Report.xlsx", cfg.getOutputFile());
    }

    @Test
    void defaultOutputForWlJboss27Module() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=wl-jboss27"});
        assertEquals("WlToJBoss-WildFly27-Report.xlsx", cfg.getOutputFile());
    }

    @Test
    void explicitOutputOverridesDefault() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=upgrade", "--output=my-report.xlsx"});
        assertEquals("my-report.xlsx", cfg.getOutputFile());
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    void validateFailsWhenNoModule() {
        AppConfig cfg = AppConfig.parse(new String[]{});
        assertFalse(cfg.validate().isEmpty());
    }

    @Test
    void validatePassesForAnalyzeWithNoInput() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=analyze"});
        assertEquals("", cfg.validate());
    }

    @Test
    void validateFailsForAnalyzeWithNonDirectoryInput() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=analyze", "--input=/nonexistent/file123.txt"});
        assertFalse(cfg.validate().isEmpty());
    }

    @Test
    void validatePassesForAnalyzeWithExistingDirectoryInput(@TempDir Path tmpDir) {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=analyze", "--input=" + tmpDir});
        assertEquals("", cfg.validate());
    }

    @Test
    void validateFailsForUpgradeWithNoInput() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=upgrade"});
        assertFalse(cfg.validate().isEmpty());
    }

    @Test
    void validateFailsForMergeWhenFilesAbsent() {
        // TicketReport.xlsx and ComponentList.xlsx exist in the project working directory,
        // so we must supply paths that are guaranteed not to exist.
        AppConfig cfg = AppConfig.parse(new String[]{
                "--module=merge",
                "--ticket-file=/nonexistent_xyz_abc/ticket99.xlsx",
                "--component-file=/nonexistent_xyz_abc/comp99.xlsx"
        });
        String error = cfg.validate();
        assertFalse(error.isEmpty());
        assertTrue(error.contains("merge"));
    }

    @Test
    void validateFailsForUnknownModule() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=totally-bogus"});
        assertFalse(cfg.validate().isEmpty());
    }

    // ── Miscellaneous ────────────────────────────────────────────────────────

    @Test
    void toStringContainsModuleAndInput() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=upgrade", "--input=/apps"});
        String s = cfg.toString();
        assertTrue(s.contains("upgrade"));
        assertTrue(s.contains("/apps"));
    }

    @Test
    void excludedRulesAccessible() {
        AppConfig cfg = AppConfig.parse(new String[]{"--module=analyze", "--excluded-rules=RuleA,RuleB"});
        String rules = cfg.getExcludedRules();
        assertTrue(rules.contains("RuleA"));
        assertTrue(rules.contains("RuleB"));
    }

    @Test
    void emptyArgsDoesNotThrow() {
        assertDoesNotThrow(() -> AppConfig.parse(new String[]{}));
    }
}