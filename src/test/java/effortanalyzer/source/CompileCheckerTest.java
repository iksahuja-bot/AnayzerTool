package effortanalyzer.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CompileCheckerTest {

    private static final SourceComponent COMPONENT = new SourceComponent("pal", "file:///repo/pal", RepositoryType.LOCAL,
            "", "", "", true, 2);

    @Test
    void classifiesJava21DiagnosticsAndCountsClasspathNoiseSeparately(@TempDir Path root) throws Exception {
        write(root, "src/main/java/com/example/A.java", """
                package com.example;

                import sun.misc.Service;
                import java.security.acl.Group;
                import sun.security.x509.X500Name;
                import java.util.List;

                public class A {
                    Object a() throws Exception { return A.class.newInstance(); }
                    Integer b() { return new Integer(1); }
                    void c(Thread t) { t.stop(); }
                    sun.misc.Unsafe d() { return null; }
                    void e(Thread t) { t.destroy(); }
                }
                """);
        write(root, "src/main/java/com/example/B.java", """
                package com.example;

                public class B {
                    int value() { return 1; }
                }
                """);
        write(root, "src/main/java/com/example/D.java", """
                package com.example;

                import java.sql.*;
                import org.missing.Lib;

                public class D {
                    Object type() { return JDBCType.STRING; }
                }
                """);

        CompileCheckResult result = new CompileChecker("wl14", List.of(), null).check(COMPONENT, root);

        Map<String, String> byRule = result.findings().stream()
                .collect(Collectors.toMap(SourceFinding::rule, SourceFinding::category, (x, y) -> x));
        assertAll(
                () -> assertEquals(CompileCheckResult.JAVA21_FINDINGS, result.status()),
                () -> assertEquals(JdkToolScanner.JDK_REMOVED_INTERNAL_API, byRule.get("sun.misc.Service")),
                () -> assertEquals(JdkToolScanner.JDK_REMOVED_API, byRule.get("java.security.acl")),
                () -> assertEquals(JdkToolScanner.JDK_INTERNAL_API, byRule.get("sun.security.x509")),
                () -> assertEquals(JdkToolScanner.JDK_DEPRECATED, byRule.get("java.lang.Class.newInstance()")),
                () -> assertEquals(JdkToolScanner.JDK_DEPRECATED_FOR_REMOVAL, byRule.get("new java.lang.Integer(...)")),
                () -> assertEquals(JdkToolScanner.JDK_UNSUPPORTED_AT_RUNTIME, byRule.get("java.lang.Thread.stop()")),
                () -> assertEquals(JdkToolScanner.JDK_UNSUPPORTED_API, byRule.get("sun.misc.Unsafe")),
                () -> assertEquals(JdkToolScanner.JDK_REMOVED_API, byRule.get("java.lang.Thread.destroy()")),
                () -> assertFalse(byRule.keySet().stream().anyMatch(r -> r.contains("org.missing")), "non-JDK symbols are never findings"),
                () -> assertFalse(byRule.containsKey("java.sql.JDBCType.STRING"),
                        "a missing JDK member in a file with unresolved project types is ambiguous, not a finding"),
                () -> assertTrue(result.unresolvedSymbols() >= 1, "missing org.missing.Lib is counted as classpath noise"),
                () -> assertNull(result.classesDir(), "javac writes no classes when any file has errors"),
                () -> assertTrue(result.findings().stream().allMatch(f -> "CONFIRMED_COMPILER".equals(f.validationStatus())
                        && "HIGH".equals(f.confidence()) && CompileChecker.SCANNER.equals(f.scanner()))),
                () -> assertEquals(10, result.findings().stream()
                        .filter(f -> f.rule().equals("new java.lang.Integer(...)")).findFirst().orElseThrow().line()),
                () -> assertEquals("src/main/java/com/example/A.java", result.findings().get(0).file())
        );
    }

    @Test
    void cleanCompileKeepsOutputForJdkToolsAndDeleteOutputRemovesIt(@TempDir Path root) throws Exception {
        write(root, "src/main/java/com/example/B.java", """
                package com.example;

                public class B {
                    int value() { return 1; }
                }
                """);
        write(root, "src/test/java/com/example/BTest.java", "package com.example; class BTest { org.junit.Missing m; }");

        CompileCheckResult result = new CompileChecker("wl14", List.of(), null).check(COMPONENT, root);

        assertEquals(CompileCheckResult.CLEAN, result.status());
        assertEquals(1, result.sourceFiles(), "test sources are not compiled");
        assertNotNull(result.classesDir());
        assertTrue(Files.exists(result.classesDir().resolve("com/example/B.class")));
        CompileChecker.deleteOutput(result);
        assertFalse(Files.exists(result.classesDir()));
    }

    @Test
    void wl14ProvidedJavaxIsBuildClasspathButRemovedElsewhere(@TempDir Path root) throws Exception {
        write(root, "src/com/example/X.java", """
                package com.example;

                import javax.xml.bind.JAXBContext;

                public class X {
                }
                """);

        CompileCheckResult wl14 = new CompileChecker("wl14", List.of(), null).check(COMPONENT, root);
        CompileCheckResult upgrade = new CompileChecker("upgrade", List.of(), null).check(COMPONENT, root);

        assertEquals(CompileChecker.BUILD_CLASSPATH, wl14.findings().get(0).category());
        assertTrue(wl14.findings().get(0).remediation().contains("javax.xml.bind:jaxb-api:2.3.1"));
        assertEquals(JdkToolScanner.JDK_REMOVED_API, upgrade.findings().get(0).category());
    }

    @Test
    void extraClasspathResolvesOtherwiseMissingTypes(@TempDir Path root) throws Exception {
        write(root, "app/src/com/example/Uses.java", """
                package com.example;

                public class Uses {
                    lib.Helper helper;
                }
                """);
        Path lib = root.resolve("libsrc");
        write(lib, "lib/Helper.java", "package lib; public class Helper {}");
        Path libClasses = Files.createDirectories(root.resolve("libclasses"));
        javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", libClasses.toString(), lib.resolve("lib/Helper.java").toString());
        CompileChecker.deleteRecursively(lib);

        CompileCheckResult without = new CompileChecker("wl14", List.of(), null).check(COMPONENT, root.resolve("app"));
        CompileCheckResult with = new CompileChecker("wl14", List.of(libClasses), null).check(COMPONENT, root.resolve("app"));

        assertEquals(CompileCheckResult.INCOMPLETE_CLASSPATH, without.status());
        assertEquals(CompileCheckResult.CLEAN, with.status());
        CompileChecker.deleteOutput(with);
    }

    @Test
    void noSourcesIsSkipped(@TempDir Path root) {
        CompileCheckResult result = new CompileChecker("wl14", List.of(), null).check(COMPONENT, root);
        assertEquals(CompileCheckResult.SKIPPED, result.status());
        assertFalse(result.ran());
    }

    @Test
    void mergeConfirmsSameLinePatternFindingAndKeepsCompilerOnlyFindings() {
        SourceFinding pattern = new SourceFinding("pal", "repo", RepositoryType.LOCAL, "Java 21", "JAVA_REMOVED", "CRITICAL",
                "src/A.java", 3, "sun.misc.Service", "removed", "Use ServiceLoader", "import sun.misc.Service;");
        SourceFinding unrelated = new SourceFinding("pal", "repo", RepositoryType.LOCAL, "Java 21", "JAVA_BEHAVIOR", "INFO",
                "src/A.java", 9, "getClassLoader", "behavior", "review", "x.getClassLoader()");
        SourceFinding compiledSame = compileFinding("src/A.java", 3, "sun.misc.Service");
        SourceFinding compiledOther = compileFinding("src/A.java", 9, "java.lang.Thread.stop()");

        List<SourceFinding> merged = CompileChecker.mergeWithSourceFindings(List.of(pattern, unrelated), List.of(compiledSame, compiledOther));

        assertEquals(3, merged.size());
        assertEquals("CONFIRMED_SOURCE_AND_COMPILER", merged.get(0).validationStatus());
        assertEquals("CANDIDATE_SOURCE_ONLY", merged.get(1).validationStatus(), "different API on the same line is not confirmed");
        assertEquals("java.lang.Thread.stop()", merged.get(2).rule());
    }

    private static SourceFinding compileFinding(String file, int line, String rule) {
        return new SourceFinding("pal", "repo", RepositoryType.LOCAL, CompileChecker.SCANNER, JdkToolScanner.JDK_REMOVED_API,
                "CRITICAL", file, line, rule, "javac", "fix", "javac: x")
                .withValidation("COMPILER", "CONFIRMED_COMPILER", "HIGH", true, false, "", "JAVAC_RELEASE_21", "", "");
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
