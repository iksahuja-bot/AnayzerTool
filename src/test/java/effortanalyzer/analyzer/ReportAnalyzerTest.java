package effortanalyzer.analyzer;

import effortanalyzer.config.AnalyzerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ReportAnalyzerTest {

    // Minimal IBM TA JSON format with one rule having one result
    private static final String SIMPLE_REPORT = """
            {
              "rules": [
                {
                  "ruleId": "ThreadStopRule",
                  "severity": "CRITICAL",
                  "results": [
                    {
                      "fileName": "com/example/MyThread.java",
                      "details": [
                        {
                          "reference": "Thread.stop()",
                          "match": "thread.stop()",
                          "lineNumber": 42
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

    private static final String MULTI_RULE_REPORT = """
            {
              "rules": [
                {
                  "ruleId": "RuleA",
                  "severity": "HIGH",
                  "results": [
                    { "fileName": "Foo.java", "details": [] },
                    { "fileName": "Bar.java", "details": [] }
                  ]
                },
                {
                  "ruleId": "RuleB",
                  "severity": "MEDIUM",
                  "results": [
                    { "fileName": "Baz.java", "details": [] }
                  ]
                }
              ]
            }
            """;

    private static final String EXCLUDED_RULE_REPORT = """
            {
              "rules": [
                {
                  "ruleId": "CLDRLocaleDataByDefault",
                  "severity": "INFO",
                  "results": [
                    { "fileName": "X.java", "details": [] }
                  ]
                }
              ]
            }
            """;

    private static final String EMPTY_RULES_REPORT = """
            {
              "rules": []
            }
            """;

    // AnalyzerConfig helper

    private AnalyzerConfig configWithInputDir(Path dir, Path outputFile) {
        Properties props = new Properties();
        props.setProperty("input.path", dir.toString());
        props.setProperty("analyzer.output.file", outputFile.toString());
        props.setProperty("analyzer.parallel.enabled", "false");
        return AnalyzerConfig.fromProperties(props);
    }

    // Tests

    @Test
    void parsesSingleRuleAndReturnsOneResult(@TempDir Path tmpDir) throws IOException {
        Path json = tmpDir.resolve("app.jar_AnalysisReport.json");
        Files.writeString(json, SIMPLE_REPORT, StandardCharsets.UTF_8);

        Path out = tmpDir.resolve("out.xlsx");
        AnalyzerConfig cfg = configWithInputDir(tmpDir, out);

        ReportAnalyzer analyzer = new ReportAnalyzer(cfg);
        analyzer.run();

        assertEquals(1, analyzer.getResultCount());
    }

    @Test
    void parsesMultipleRulesFromSingleFile(@TempDir Path tmpDir) throws IOException {
        Path json = tmpDir.resolve("myapp.jar_AnalysisReport.json");
        Files.writeString(json, MULTI_RULE_REPORT, StandardCharsets.UTF_8);

        Path out = tmpDir.resolve("out.xlsx");
        AnalyzerConfig cfg = configWithInputDir(tmpDir, out);

        ReportAnalyzer analyzer = new ReportAnalyzer(cfg);
        analyzer.run();

        assertEquals(2, analyzer.getResultCount());
    }

    @Test
    void groupsRulesFromMultipleFiles(@TempDir Path tmpDir) throws IOException {
        Files.writeString(tmpDir.resolve("compA.jar_AnalysisReport.json"), SIMPLE_REPORT, StandardCharsets.UTF_8);
        Files.writeString(tmpDir.resolve("compB.jar_AnalysisReport.json"), SIMPLE_REPORT, StandardCharsets.UTF_8);

        Path out = tmpDir.resolve("out.xlsx");
        AnalyzerConfig cfg = configWithInputDir(tmpDir, out);

        ReportAnalyzer analyzer = new ReportAnalyzer(cfg);
        analyzer.run();

        // Each file has one unique (file, rule) grouping key, so 2 groups expected
        assertEquals(2, analyzer.getResultCount());
    }

    @Test
    void excludesDefaultExcludedRules(@TempDir Path tmpDir) throws IOException {
        Path json = tmpDir.resolve("excluded_test.jar_AnalysisReport.json");
        Files.writeString(json, EXCLUDED_RULE_REPORT, StandardCharsets.UTF_8);

        Path out = tmpDir.resolve("out.xlsx");
        // Use default config (which excludes CLDRLocaleDataByDefault)
        Properties props = new Properties();
        props.setProperty("input.path", tmpDir.toString());
        props.setProperty("analyzer.output.file", out.toString());
        props.setProperty("analyzer.parallel.enabled", "false");
        AnalyzerConfig cfg = AnalyzerConfig.fromProperties(props);

        ReportAnalyzer analyzer = new ReportAnalyzer(cfg);
        analyzer.run();

        assertEquals(0, analyzer.getResultCount(),
                "CLDRLocaleDataByDefault is a default-excluded rule and should be skipped");
    }

    @Test
    void customExclusionSkipsSpecifiedRule(@TempDir Path tmpDir) throws IOException {
        Path json = tmpDir.resolve("app.jar_AnalysisReport.json");
        Files.writeString(json, SIMPLE_REPORT, StandardCharsets.UTF_8);

        Path out = tmpDir.resolve("out.xlsx");
        Properties props = new Properties();
        props.setProperty("input.path", tmpDir.toString());
        props.setProperty("analyzer.output.file", out.toString());
        props.setProperty("analyzer.parallel.enabled", "false");
        props.setProperty("analyzer.excluded.rules", "ThreadStopRule");
        AnalyzerConfig cfg = AnalyzerConfig.fromProperties(props);

        ReportAnalyzer analyzer = new ReportAnalyzer(cfg);
        analyzer.run();

        assertEquals(0, analyzer.getResultCount());
    }

    @Test
    void emptyRulesArrayProducesZeroResults(@TempDir Path tmpDir) throws IOException {
        Path json = tmpDir.resolve("empty.jar_AnalysisReport.json");
        Files.writeString(json, EMPTY_RULES_REPORT, StandardCharsets.UTF_8);

        Path out = tmpDir.resolve("out.xlsx");
        AnalyzerConfig cfg = configWithInputDir(tmpDir, out);

        ReportAnalyzer analyzer = new ReportAnalyzer(cfg);
        analyzer.run();

        assertEquals(0, analyzer.getResultCount());
    }

    @Test
    void writesExcelOutputFile(@TempDir Path tmpDir) throws IOException {
        Path json = tmpDir.resolve("app.jar_AnalysisReport.json");
        Files.writeString(json, SIMPLE_REPORT, StandardCharsets.UTF_8);

        Path out = tmpDir.resolve("out.xlsx");
        AnalyzerConfig cfg = configWithInputDir(tmpDir, out);

        ReportAnalyzer analyzer = new ReportAnalyzer(cfg);
        analyzer.run();

        assertTrue(Files.exists(out), "Excel output file should be created");
        assertTrue(Files.size(out) > 0, "Excel output file should not be empty");
    }

    @Test
    void emptyInputDirectoryThrowsIllegalState(@TempDir Path tmpDir) {
        Path out = tmpDir.resolve("out.xlsx");
        AnalyzerConfig cfg = configWithInputDir(tmpDir, out);

        ReportAnalyzer analyzer = new ReportAnalyzer(cfg);
        assertThrows(IllegalStateException.class, analyzer::run,
                "Should throw when no JSON files are found");
    }
}