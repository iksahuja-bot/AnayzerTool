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
 *   3. Import-context guard   - when a file explicitly imports a different class with the same
 *                               simple name, usages of that name in code are not flagged.
 *
 * Wildcard-import behaviour is also verified: import net.sf.cglib.proxy.* does NOT
 * add an entry to the import-context map, so CGLib simple-name matches still fire correctly.
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

    private static boolean hasFinding(List<LibraryUpgradeAnalyzer.Finding> findings, String deprecatedClass) {
        return findings.stream().anyMatch(f -> f.deprecatedClass().equals(deprecatedClass));
    }

    // ---- Guard 1: string-literal guard -------------------------------------

    @Test
    void jerseyFormSimpleNameInStringLiteralIsNotFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import com.netcracker.am.SomeOtherClass;\n"
                + "public class SIMModelDownloadingAction {\n"
                + "    private String generateColumnsNamesLine() {\n"
                + "        StringBuilder result = new StringBuilder();\n"
                + "        result.append(\"Form Factor\");\n"
                + "        return result.toString();\n"
                + "    }\n"
                + "}\n";

        Path jar = buildJar(tmp, "com/example/SIMModelDownloadingAction.java", source);
        assertFalse(
                hasFinding(analyze(jar), "com.sun.jersey.api.representation.Form"),
                "\"Form Factor\" in a string literal must not trigger the Jersey Form rule");
    }

    @Test
    void multipleFormStringLiteralsProduceNoJerseyFindings(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "public class FormHelper {\n"
                + "    static final String A = \"Form Factor\";\n"
                + "    static final String B = \"Submit the Form now\";\n"
                + "    static final String C = \"form.submit\";\n"
                + "}\n";

        Path jar = buildJar(tmp, "com/example/FormHelper.java", source);
        assertFalse(
                hasFinding(analyze(jar), "com.sun.jersey.api.representation.Form"),
                "Word Form only in string literals must not produce Jersey Form findings");
    }

    // ---- Guard 2: import-line guard ----------------------------------------

    @Test
    void gwtCallbackImportLineIsNotFlaggedByCglibRule(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import com.google.gwt.core.client.Callback;\n"
                + "public class MyWidget {}\n";

        Path jar = buildJar(tmp, "com/example/MyWidget.java", source);
        assertFalse(
                hasFinding(analyze(jar), "net.sf.cglib.proxy.Callback"),
                "import of com.google.gwt.core.client.Callback must not match the CGLib Callback rule");
    }

    @Test
    void formFactorImportIsNotFlaggedByJerseyFormRule(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import com.netcracker.handbook.FormFactor;\n"
                + "public class EquipmentPage {\n"
                + "    private FormFactor formFactor;\n"
                + "}\n";

        Path jar = buildJar(tmp, "com/example/EquipmentPage.java", source);
        assertFalse(
                hasFinding(analyze(jar), "com.sun.jersey.api.representation.Form"),
                "import of FormFactor must not trigger the Jersey Form rule");
    }

    // ---- Guard 3: import-context guard -------------------------------------

    @Test
    void gwtCallbackUsedInCodeIsNotFlaggedWhenGwtImportPresent(@TempDir Path tmp) throws IOException {
        // Reproduces TfnukHistoricTableCtrlPager: getDataCallback() return type and anonymous class.
        String source =
                "package com.example;\n"
                + "import com.google.gwt.core.client.Callback;\n"
                + "public class TfnukHistoricTableCtrlPager {\n"
                + "    public Callback<Object, Object> getDataCallback() {\n"
                + "        return new Callback<Object, Object>() {\n"
                + "            public void onFailure(Object reason) {}\n"
                + "            public void onSuccess(Object result) {}\n"
                + "        };\n"
                + "    }\n"
                + "}\n";

        Path jar = buildJar(tmp, "com/example/TfnukHistoricTableCtrlPager.java", source);
        assertFalse(
                hasFinding(analyze(jar), "net.sf.cglib.proxy.Callback"),
                "Callback in code must not be flagged when com.google.gwt.core.client.Callback is imported");
    }

    @Test
    void specificImportWinsOverCglibWildcardForSameSimpleName(@TempDir Path tmp) throws IOException {
        // Java specific-import-wins rule: the GWT Callback specific import overrides cglib wildcard.
        String source =
                "package com.example;\n"
                + "import net.sf.cglib.proxy.*;\n"
                + "import com.google.gwt.core.client.Callback;\n"
                + "public class MixedImports {\n"
                + "    public Callback<Object, Object> getCallback() { return null; }\n"
                + "}\n";

        Path jar = buildJar(tmp, "com/example/MixedImports.java", source);
        List<LibraryUpgradeAnalyzer.Finding> findings = analyze(jar);

        assertFalse(
                hasFinding(findings, "net.sf.cglib.proxy.Callback"),
                "Code usage of Callback must not be flagged when GWT Callback specific-import overrides the CGLib wildcard");
        assertTrue(
                hasFinding(findings, "net.sf.cglib.proxy"),
                "CGLib wildcard import line must still trigger the package-level rule");
    }

    // ---- Wildcard import of the deprecated package -------------------------

    @Test
    void cglibWildcardImportTriggersPackageRule(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import net.sf.cglib.proxy.*;\n"
                + "public class MyEnhancer {\n"
                + "    Object e = null;\n"
                + "}\n";

        Path jar = buildJar(tmp, "com/example/MyEnhancer.java", source);
        assertTrue(
                hasFinding(analyze(jar), "net.sf.cglib.proxy"),
                "import net.sf.cglib.proxy.* must trigger the package-level CGLib rule");
    }

    @Test
    void cglibCallbackInCodeIsFlaggedWhenOnlyCglibWildcardImported(@TempDir Path tmp) throws IOException {
        // Wildcard import excluded from importedSimpleNames on purpose,
        // so Callback in code must still be flagged as genuinely CGLib.
        String source =
                "package com.example;\n"
                + "import net.sf.cglib.proxy.*;\n"
                + "public class ProxyFactory {\n"
                + "    public void setCallbacks(Callback[] callbacks) {\n"
                + "        callbacks[0] = null;\n"
                + "    }\n"
                + "}\n";

        Path jar = buildJar(tmp, "com/example/ProxyFactory.java", source);
        assertTrue(
                hasFinding(analyze(jar), "net.sf.cglib.proxy.Callback"),
                "Callback usage after import net.sf.cglib.proxy.* must still be flagged");
    }

    // ---- True positives ----------------------------------------------------

    @Test
    void realJerseyFormImportIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import com.sun.jersey.api.representation.Form;\n"
                + "public class LegacyClient {\n"
                + "    Form form = new Form();\n"
                + "}\n";

        Path jar = buildJar(tmp, "com/example/LegacyClient.java", source);
        assertTrue(
                hasFinding(analyze(jar), "com.sun.jersey.api.representation.Form"),
                "Explicit import of com.sun.jersey.api.representation.Form must be flagged");
    }

    @Test
    void realCglibCallbackImportIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import net.sf.cglib.proxy.Callback;\n"
                + "public class ProxyFactory {\n"
                + "    Callback cb = null;\n"
                + "}\n";

        Path jar = buildJar(tmp, "com/example/ProxyFactory.java", source);
        assertTrue(
                hasFinding(analyze(jar), "net.sf.cglib.proxy.Callback"),
                "Explicit import of net.sf.cglib.proxy.Callback must be flagged");
    }

    @Test
    void jerseyFormUsedInCodeIsFlaggedWhenJerseyFormImported(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import com.sun.jersey.api.representation.Form;\n"
                + "public class ResourceClient {\n"
                + "    public void post() {\n"
                + "        Form form = new Form();\n"
                + "        form.add(\"key\", \"value\");\n"
                + "    }\n"
                + "}\n";

        Path jar = buildJar(tmp, "com/example/ResourceClient.java", source);
        assertTrue(
                hasFinding(analyze(jar), "com.sun.jersey.api.representation.Form"),
                "Form usage after importing Jersey Form must be flagged");
    }

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
        assertTrue(
                analyze(jar).isEmpty(),
                "A file with no deprecated API references must produce no findings");
    }
}