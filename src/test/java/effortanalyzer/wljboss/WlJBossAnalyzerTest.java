package effortanalyzer.wljboss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class WlJBossAnalyzerTest {

    // Constructor variants

    @Test
    void defaultConstructorUsesWildFly27Profile() {
        WlJBossAnalyzer analyzer = new WlJBossAnalyzer();
        assertEquals(WlJBossRules.TargetProfile.WILDFLY27_JAVA21, analyzer.getTargetProfile());
    }

    @Test
    void stringConstructorDelegatesToTargetProfileFrom() {
        WlJBossAnalyzer analyzer = new WlJBossAnalyzer("java8");
        assertEquals(WlJBossRules.TargetProfile.WILDFLY26_JAVA8, analyzer.getTargetProfile());
    }

    @Test
    void enumConstructorSetsProfileCorrectly() {
        WlJBossAnalyzer analyzer = new WlJBossAnalyzer(WlJBossRules.TargetProfile.WILDFLY26_JAVA8);
        assertEquals(WlJBossRules.TargetProfile.WILDFLY26_JAVA8, analyzer.getTargetProfile());
    }

    // Accessors on a fresh analyzer

    @Test
    void freshAnalyzerHasEmptyFindings() {
        WlJBossAnalyzer analyzer = new WlJBossAnalyzer();
        assertTrue(analyzer.getFindings().isEmpty());
    }

    @Test
    void freshAnalyzerHasEmptyJarStats() {
        WlJBossAnalyzer analyzer = new WlJBossAnalyzer();
        assertTrue(analyzer.getJarStats().isEmpty());
    }

    // Finding model

    @Test
    void findingRecordIncrementsOccurrenceCount() {
        WlJBossRules.Rule rule = new WlJBossRules.Rule(
                "TEST", "weblogic.servlet", "CRITICAL", "desc", "remed",
                WlJBossRules.ScanMode.BOTH);
        WlJBossAnalyzer.Finding f = new WlJBossAnalyzer.Finding("app.jar", rule);
        f.record("com/example/Foo.java", 10, "import weblogic.servlet.http;");
        f.record("com/example/Bar.java", 20, "import weblogic.servlet.annotation;");
        assertEquals(2, f.occurrenceCount);
    }

    @Test
    void findingRecordTracksDistinctFiles() {
        WlJBossRules.Rule rule = new WlJBossRules.Rule(
                "TEST", "weblogic.ejb", "CRITICAL", "desc", "remed",
                WlJBossRules.ScanMode.BOTH);
        WlJBossAnalyzer.Finding f = new WlJBossAnalyzer.Finding("app.jar", rule);
        f.record("Foo.java", 1, "weblogic.ejb");
        f.record("Foo.java", 2, "weblogic.ejb again");  // same file
        f.record("Bar.java", 3, "weblogic.ejb");
        assertEquals(2, f.affectedFiles.size());
    }

    @Test
    void findingRecordSetsExampleContextOnFirstNonBlankHit() {
        WlJBossRules.Rule rule = new WlJBossRules.Rule(
                "TEST", "weblogic.jndi", "CRITICAL", "desc", "remed",
                WlJBossRules.ScanMode.BOTH);
        WlJBossAnalyzer.Finding f = new WlJBossAnalyzer.Finding("app.jar", rule);
        f.record("Foo.java", 5, "  import weblogic.jndi.*;  ");
        assertTrue(f.exampleContext.contains("Line 5"));
        assertTrue(f.exampleContext.contains("import weblogic.jndi.*"));
    }

    @Test
    void findingRecordDoesNotOverwriteExampleContext() {
        WlJBossRules.Rule rule = new WlJBossRules.Rule(
                "TEST", "weblogic.security", "CRITICAL", "desc", "remed",
                WlJBossRules.ScanMode.BOTH);
        WlJBossAnalyzer.Finding f = new WlJBossAnalyzer.Finding("app.jar", rule);
        f.record("A.java", 1, "first hit");
        f.record("B.java", 2, "second hit");
        assertTrue(f.exampleContext.contains("first hit"));
        assertFalse(f.exampleContext.contains("second hit"));
    }

    @Test
    void findingExampleContextTruncatedAt200Chars() {
        WlJBossRules.Rule rule = new WlJBossRules.Rule(
                "TEST", "weblogic.rmi", "HIGH", "desc", "remed",
                WlJBossRules.ScanMode.BOTH);
        WlJBossAnalyzer.Finding f = new WlJBossAnalyzer.Finding("app.jar", rule);
        String longContext = "x".repeat(300);
        f.record("Long.java", 1, longContext);
        assertTrue(f.exampleContext.length() <= 200);
        assertTrue(f.exampleContext.endsWith("..."));
    }

    @Test
    void findingIsGeneratedStubDefaultFalse() {
        WlJBossRules.Rule rule = new WlJBossRules.Rule(
                "TEST", "weblogic.jdbc", "HIGH", "desc", "remed",
                WlJBossRules.ScanMode.BOTH);
        WlJBossAnalyzer.Finding f = new WlJBossAnalyzer.Finding("app.jar", rule);
        assertFalse(f.isGeneratedStub);
    }

    @Test
    void findingStoresJarNameAndRule() {
        WlJBossRules.Rule rule = new WlJBossRules.Rule(
                "WEBLOGIC_API", "weblogic.work", "HIGH", "desc", "remed",
                WlJBossRules.ScanMode.BOTH);
        WlJBossAnalyzer.Finding f = new WlJBossAnalyzer.Finding("myapp.jar", rule);
        assertEquals("myapp.jar", f.jarName);
        assertSame(rule, f.rule);
    }

    // Scanning a real JAR

    @Test
    void scanJarWithWebLogicReferenceProducesFindings(@TempDir Path tmpDir) throws IOException {
        // Build a minimal JAR that contains a .java source file referencing weblogic.servlet
        Path jarPath = tmpDir.resolve("sample.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            JarEntry entry = new JarEntry("com/example/MyServlet.java");
            jos.putNextEntry(entry);
            jos.write("import weblogic.servlet.http.HttpServlet;\n".getBytes());
            jos.closeEntry();
        }

        String outputPath = tmpDir.resolve("out.xlsx").toString();
        WlJBossAnalyzer analyzer = new WlJBossAnalyzer();
        analyzer.run(jarPath.toString(), outputPath);

        assertFalse(analyzer.getFindings().isEmpty(), "Expected at least one finding for weblogic.servlet reference");
        assertTrue(analyzer.getJarStats().containsKey("sample.jar"));
    }

    @Test
    void scanDirectoryCollectsAllJars(@TempDir Path tmpDir) throws IOException {
        // Create two JARs in the temp directory
        for (String name : new String[]{"first.jar", "second.jar"}) {
            Path jarPath = tmpDir.resolve(name);
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
                JarEntry entry = new JarEntry("placeholder.txt");
                jos.putNextEntry(entry);
                jos.write("no weblogic here".getBytes());
                jos.closeEntry();
            }
        }

        String outputPath = tmpDir.resolve("out.xlsx").toString();
        WlJBossAnalyzer analyzer = new WlJBossAnalyzer();
        analyzer.run(tmpDir.toString(), outputPath);

        assertEquals(2, analyzer.getJarStats().size());
    }
}