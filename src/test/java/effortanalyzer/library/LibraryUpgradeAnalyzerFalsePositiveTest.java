package effortanalyzer.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for false-positive suppression in LibraryUpgradeAnalyzer.
 *
 * Three guards are verified:
 *   1. String-literal guard   - a rule simple name inside a quoted string is not flagged.
 *   2. Import-line guard      - only a full-FQN match triggers a hit on an import line.
 *   3. Import-context guard   - when a file explicitly imports a different class with the
 *                               same simple name, usages in code are not flagged.
 *
 * Wildcard-import behaviour: import net.sf.cglib.proxy.* does NOT add an entry to the
 * import-context map, so CGLib simple-name matches still fire correctly.
 */
class LibraryUpgradeAnalyzerFalsePositiveTest {

    // ---- helpers -----------------------------------------------------------

    private static Path buildJar(Path dir, String entryName, String source) throws IOException {
        Path jarPath = dir.resolve("test.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry(entryName));
            jos.write(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jarPath;
    }

    private static List<LibraryUpgradeAnalyzer.Finding> analyze(Path jarPath) throws IOException {
        LibraryUpgradeAnalyzer analyzer = new LibraryUpgradeAnalyzer();
        analyzer.analyze(jarPath.toString());
        Map<String, List<LibraryUpgradeAnalyzer.Finding>> byJar = analyzer.getFindingsByJar();
        return byJar.isEmpty() ? List.of() : byJar.values().iterator().next();
    }

    private static boolean hasFinding(List<LibraryUpgradeAnalyzer.Finding> findings, String cls) {
        return findings.stream().anyMatch(f -> f.deprecatedClass().equals(cls));
    }

    // ---- Guard 1: string-literal guard -------------------------------------

    /** "Form Factor" in a string literal must not trigger the Jersey Form rule.
     *  Reproduces: SIMModelDownloadingAction.java .append("Form Factor") */
    @Test
    void jerseyFormSimpleNameInStringLiteralIsNotFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import com.netcracker.am.SomeOtherClass;\n"
                + "public class SIMModelDownloadingAction {\n"
                + "    private String buildLine() {\n"
                + "        StringBuilder r = new StringBuilder();\n"
                + "        r.append(\"Form Factor\");\n"
                + "        return r.toString();\n"
                + "    }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/SIMModelDownloadingAction.java", source);
        assertFalse(hasFinding(analyze(jar), "com.sun.jersey.api.representation.Form"),
                "Form Factor in a string literal must not trigger the Jersey Form rule");
    }

    /** Multiple Form occurrences only in string literals must not fire. */
    @Test
    void multipleFormStringLiteralsProduceNoJerseyFindings(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "public class FormHelper {\n"
                + "    static final String A = \"Form Factor\";\n"
                + "    static final String B = \"Submit the Form now\";\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/FormHelper.java", source);
        assertFalse(hasFinding(analyze(jar), "com.sun.jersey.api.representation.Form"),
                "Form only in string literals must not produce Jersey Form findings");
    }

    // ---- Guard 2: import-line guard ----------------------------------------

    /** GWT Callback import line must not match the CGLib Callback rule. */
    @Test
    void gwtCallbackImportLineIsNotFlaggedByCglibRule(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import com.google.gwt.core.client.Callback;\n"
                + "public class MyWidget {}\n";
        Path jar = buildJar(tmp, "com/example/MyWidget.java", source);
        assertFalse(hasFinding(analyze(jar), "net.sf.cglib.proxy.Callback"),
                "import of com.google.gwt.core.client.Callback must not match the CGLib Callback rule");
    }

    /** FormFactor import must not match the Jersey Form rule. */
    @Test
    void formFactorImportIsNotFlaggedByJerseyFormRule(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import com.netcracker.handbook.FormFactor;\n"
                + "public class EquipmentPage {\n"
                + "    private FormFactor formFactor;\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/EquipmentPage.java", source);
        assertFalse(hasFinding(analyze(jar), "com.sun.jersey.api.representation.Form"),
                "import of FormFactor must not trigger the Jersey Form rule");
    }

    // ---- Guard 3: import-context guard -------------------------------------

    /** Callback in code must not be flagged when GWT Callback is explicitly imported.
     *  Reproduces: TfnukHistoricTableCtrlPager.java getDataCallback() */
    @Test
    void gwtCallbackUsedInCodeIsNotFlaggedWhenGwtImportPresent(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import com.google.gwt.core.client.Callback;\n"
                + "public class TfnukPager {\n"
                + "    public Callback<Object, Object> getDataCallback() {\n"
                + "        return new Callback<Object, Object>() {\n"
                + "            public void onFailure(Object r) {}\n"
                + "            public void onSuccess(Object r) {}\n"
                + "        };\n"
                + "    }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/TfnukPager.java", source);
        assertFalse(hasFinding(analyze(jar), "net.sf.cglib.proxy.Callback"),
                "Callback in code must not be flagged when com.google.gwt.core.client.Callback is imported");
    }

    /** Java specific-import-wins rule: explicit GWT Callback import overrides CGLib wildcard.
     *  The code usage of Callback must NOT be flagged.
     *  The wildcard import line itself MUST still trigger the package-level rule (net.sf.cglib). */
    @Test
    void specificImportWinsOverCglibWildcardForSameSimpleName(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import net.sf.cglib.proxy.*;\n"
                + "import com.google.gwt.core.client.Callback;\n"
                + "public class MixedImports {\n"
                + "    public Callback<Object, Object> getCallback() { return null; }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/MixedImports.java", source);
        List<LibraryUpgradeAnalyzer.Finding> findings = analyze(jar);
        assertFalse(hasFinding(findings, "net.sf.cglib.proxy.Callback"),
                "Code Callback must not be flagged when GWT Callback specific-import overrides CGLib wildcard");
        assertTrue(hasFinding(findings, "net.sf.cglib"),
                "CGLib wildcard import line must still trigger the package-level rule (net.sf.cglib)");
    }

    // ---- Wildcard import of the deprecated package -------------------------

    /** import net.sf.cglib.proxy.* must trigger the net.sf.cglib package rule. */
    @Test
    void cglibWildcardImportTriggersPackageRule(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import net.sf.cglib.proxy.*;\n"
                + "public class MyEnhancer { Object e = null; }\n";
        Path jar = buildJar(tmp, "com/example/MyEnhancer.java", source);
        assertTrue(hasFinding(analyze(jar), "net.sf.cglib"),
                "import net.sf.cglib.proxy.* must trigger the package-level CGLib rule (net.sf.cglib)");
    }

    /** Wildcard imports are excluded from importedSimpleNames on purpose,
     *  so Callback in code must still be flagged as genuinely CGLib. */
    @Test
    void cglibCallbackInCodeIsFlaggedWhenOnlyCglibWildcardImported(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import net.sf.cglib.proxy.*;\n"
                + "public class ProxyFactory {\n"
                + "    public void setCallbacks(Callback[] callbacks) { callbacks[0] = null; }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/ProxyFactory.java", source);
        assertTrue(hasFinding(analyze(jar), "net.sf.cglib.proxy.Callback"),
                "Callback usage after import net.sf.cglib.proxy.* must still be flagged");
    }

    // ---- True positives ----------------------------------------------------

    /** Explicit Jersey Form import is a true positive. */
    @Test
    void realJerseyFormImportIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import com.sun.jersey.api.representation.Form;\n"
                + "public class LegacyClient { Form form = new Form(); }\n";
        Path jar = buildJar(tmp, "com/example/LegacyClient.java", source);
        assertTrue(hasFinding(analyze(jar), "com.sun.jersey.api.representation.Form"),
                "Explicit import of Jersey Form must be flagged");
    }

    /** Explicit CGLib Callback import is a true positive. */
    @Test
    void realCglibCallbackImportIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import net.sf.cglib.proxy.Callback;\n"
                + "public class ProxyFactory { Callback cb = null; }\n";
        Path jar = buildJar(tmp, "com/example/ProxyFactory.java", source);
        assertTrue(hasFinding(analyze(jar), "net.sf.cglib.proxy.Callback"),
                "Explicit import of CGLib Callback must be flagged");
    }

    /** Jersey Form used in code after importing it is a true positive. */
    @Test
    void jerseyFormUsedInCodeIsFlaggedWhenJerseyFormImported(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import com.sun.jersey.api.representation.Form;\n"
                + "public class ResourceClient {\n"
                + "    public void post() { Form form = new Form(); form.add(\"k\",\"v\"); }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/ResourceClient.java", source);
        assertTrue(hasFinding(analyze(jar), "com.sun.jersey.api.representation.Form"),
                "Form usage after importing Jersey Form must be flagged");
    }

    /** A file with no deprecated API references must produce zero findings. */
    @Test
    void cleanFileProducesNoFindings(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import java.util.List;\n"
                + "import java.util.ArrayList;\n"
                + "public class CleanService {\n"
                + "    private List<String> items = new ArrayList<>();\n"
                + "    public void add(String item) { items.add(item); }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/CleanService.java", source);
        assertTrue(analyze(jar).isEmpty(),
                "A file with no deprecated API references must produce no findings");
    }
}
