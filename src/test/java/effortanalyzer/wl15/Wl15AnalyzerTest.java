package effortanalyzer.wl15;

import effortanalyzer.library.LibraryUpgradeAnalyzer;
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
 * Integration tests for the WL15 module scanner.
 *
 * Each test builds a synthetic in-memory JAR containing Java source that
 * uses a deprecated WL15-era API, then verifies that the expected finding
 * is (or is not) reported.
 *
 * The scanner is exercised through LibraryUpgradeAnalyzer(List<DeprecatedApi>)
 * with Wl15LibraryRules.load(), which is exactly what Wl15Analyzer uses.
 */
class Wl15AnalyzerTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Path buildJar(Path dir, String entryName, String source) throws IOException {
        Path jarPath = dir.resolve("test.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry(entryName));
            jos.write(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jarPath;
    }

    private static List<LibraryUpgradeAnalyzer.Finding> scan(Path jarPath) throws IOException {
        LibraryUpgradeAnalyzer analyzer = new LibraryUpgradeAnalyzer(Wl15LibraryRules.load());
        analyzer.analyze(jarPath.toString());
        Map<String, List<LibraryUpgradeAnalyzer.Finding>> byJar = analyzer.getFindingsByJar();
        return byJar.isEmpty() ? List.of() : byJar.values().iterator().next();
    }

    private static boolean hasFindingForClass(List<LibraryUpgradeAnalyzer.Finding> findings,
                                               String deprecatedClass) {
        return findings.stream().anyMatch(f -> f.deprecatedClass().equals(deprecatedClass));
    }

    private static boolean hasFindingForMethod(List<LibraryUpgradeAnalyzer.Finding> findings,
                                                String methodName) {
        return findings.stream().anyMatch(f -> methodName.equals(f.methodName()));
    }

    // ── True positives: Spring Security 6 ───────────────────────────────────

    /** import of WebSecurityConfigurerAdapter must be flagged as CRITICAL. */
    @Test
    void webSecurityConfigurerAdapterImportIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.security;\n"
                + "import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;\n"
                + "public class SecurityConfig extends WebSecurityConfigurerAdapter {\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/security/SecurityConfig.java", source);
        List<LibraryUpgradeAnalyzer.Finding> findings = scan(jar);
        assertTrue(hasFindingForClass(findings,
                "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter"),
                "Importing WebSecurityConfigurerAdapter must produce a CRITICAL finding");
        assertTrue(findings.stream()
                .filter(f -> f.deprecatedClass().contains("WebSecurityConfigurerAdapter"))
                .allMatch(f -> "CRITICAL".equals(f.severity())),
                "WebSecurityConfigurerAdapter finding must be CRITICAL");
    }

    /** .antMatchers( call must be flagged as CRITICAL. */
    @Test
    void antMatchersCallIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.security;\n"
                + "import org.springframework.security.config.annotation.web.builders.HttpSecurity;\n"
                + "public class SecurityConfig {\n"
                + "    protected void configure(HttpSecurity http) throws Exception {\n"
                + "        http.authorizeRequests()\n"
                + "            .antMatchers(\"/api/**\").authenticated()\n"
                + "            .anyRequest().permitAll();\n"
                + "    }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/security/SecurityConfig.java", source);
        List<LibraryUpgradeAnalyzer.Finding> findings = scan(jar);
        assertTrue(hasFindingForMethod(findings, "antMatchers"),
                ".antMatchers() call must be flagged");
    }

    /** .mvcMatchers( call must be flagged as CRITICAL. */
    @Test
    void mvcMatchersCallIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.security;\n"
                + "public class SecurityConfig {\n"
                + "    void configure(Object http) {\n"
                + "        http.mvcMatchers(\"/admin/**\").hasRole(\"ADMIN\");\n"
                + "    }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/security/SecurityConfig.java", source);
        assertTrue(hasFindingForMethod(scan(jar), "mvcMatchers"),
                ".mvcMatchers() call must be flagged");
    }

    /** @EnableGlobalMethodSecurity annotation import must be flagged. */
    @Test
    void enableGlobalMethodSecurityIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;\n"
                + "@EnableGlobalMethodSecurity(prePostEnabled = true)\n"
                + "public class MethodSecurityConfig {}\n";
        Path jar = buildJar(tmp, "com/example/MethodSecurityConfig.java", source);
        assertTrue(hasFindingForClass(scan(jar),
                "org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity"),
                "@EnableGlobalMethodSecurity import must be flagged");
    }

    // ── True positives: Spring Framework 6 ──────────────────────────────────

    /** import of org.springframework.remoting.httpinvoker.* triggers the package rule. */
    @Test
    void springRemotingHttpInvokerPackageIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import org.springframework.remoting.httpinvoker.HttpInvokerServiceExporter;\n"
                + "public class RemotingConfig {\n"
                + "    HttpInvokerServiceExporter exporter;\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/RemotingConfig.java", source);
        assertTrue(hasFindingForClass(scan(jar), "org.springframework.remoting.httpinvoker"),
                "import of org.springframework.remoting.httpinvoker.* must be flagged");
    }

    /** import of HandlerInterceptorAdapter must be flagged as CRITICAL. */
    @Test
    void handlerInterceptorAdapterImportIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.web;\n"
                + "import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;\n"
                + "public class LoggingInterceptor extends HandlerInterceptorAdapter {\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/web/LoggingInterceptor.java", source);
        assertTrue(hasFindingForClass(scan(jar),
                "org.springframework.web.servlet.handler.HandlerInterceptorAdapter"),
                "HandlerInterceptorAdapter import must be flagged");
    }

    /** import of CommonsMultipartResolver must be flagged. */
    @Test
    void commonsMultipartResolverImportIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.config;\n"
                + "import org.springframework.web.multipart.commons.CommonsMultipartResolver;\n"
                + "public class WebConfig {\n"
                + "    CommonsMultipartResolver multipartResolver() { return new CommonsMultipartResolver(); }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/config/WebConfig.java", source);
        assertTrue(hasFindingForClass(scan(jar),
                "org.springframework.web.multipart.commons.CommonsMultipartResolver"),
                "CommonsMultipartResolver import must be flagged");
    }

    // ── True positives: EhCache 3 ─────────────────────────────────────────────

    /** import from net.sf.ehcache triggers the package-level EhCache rule. */
    @Test
    void ehcachePackageImportIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.cache;\n"
                + "import net.sf.ehcache.CacheManager;\n"
                + "import net.sf.ehcache.Cache;\n"
                + "public class CacheService {\n"
                + "    CacheManager manager = CacheManager.getInstance();\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/cache/CacheService.java", source);
        assertTrue(hasFindingForClass(scan(jar), "net.sf.ehcache"),
                "import from net.sf.ehcache must trigger the EhCache 3 package rule");
    }

    /** net.sf.ehcache.Element usage must be flagged (class-level rule). */
    @Test
    void ehcacheElementImportIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.cache;\n"
                + "import net.sf.ehcache.Element;\n"
                + "public class CacheHelper {\n"
                + "    void put(Object key, Object val) { new Element(key, val); }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/cache/CacheHelper.java", source);
        List<LibraryUpgradeAnalyzer.Finding> findings = scan(jar);
        assertTrue(
                hasFindingForClass(findings, "net.sf.ehcache") ||
                hasFindingForClass(findings, "net.sf.ehcache.Element"),
                "net.sf.ehcache.Element import must be flagged");
    }

    // ── True positives: Jetty 12 ─────────────────────────────────────────────

    /** import from javax.servlet must be flagged by the Jetty 12 rule. */
    @Test
    void javaxServletImportIsFlaggedByJettyRule(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.servlet;\n"
                + "import javax.servlet.http.HttpServletRequest;\n"
                + "import javax.servlet.http.HttpServletResponse;\n"
                + "public class MyServlet {\n"
                + "    void doGet(HttpServletRequest req, HttpServletResponse resp) {}\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/servlet/MyServlet.java", source);
        assertTrue(hasFindingForClass(scan(jar), "javax.servlet"),
                "import of javax.servlet.* must be flagged by Jetty 12 rule");
    }

    /** import of AbstractHandler (Jetty 9/10/11) must be flagged. */
    @Test
    void jettyAbstractHandlerImportIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.server;\n"
                + "import org.eclipse.jetty.server.handler.AbstractHandler;\n"
                + "public class MyHandler extends AbstractHandler {\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/server/MyHandler.java", source);
        assertTrue(hasFindingForClass(scan(jar),
                "org.eclipse.jetty.server.handler.AbstractHandler"),
                "AbstractHandler import must be flagged as CRITICAL");
    }

    // ── True positives: Jackson 2.18 ─────────────────────────────────────────

    /** .enableDefaultTyping( call must be flagged as CRITICAL. */
    @Test
    void enableDefaultTypingCallIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.config;\n"
                + "import com.fasterxml.jackson.databind.ObjectMapper;\n"
                + "public class JacksonConfig {\n"
                + "    ObjectMapper mapper() {\n"
                + "        ObjectMapper om = new ObjectMapper();\n"
                + "        om.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);\n"
                + "        return om;\n"
                + "    }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/config/JacksonConfig.java", source);
        List<LibraryUpgradeAnalyzer.Finding> findings = scan(jar);
        assertTrue(hasFindingForMethod(findings, "enableDefaultTyping"),
                ".enableDefaultTyping() call must be flagged as CRITICAL");
        assertTrue(findings.stream()
                .filter(f -> "enableDefaultTyping".equals(f.methodName()))
                .allMatch(f -> "CRITICAL".equals(f.severity())),
                "enableDefaultTyping finding must be CRITICAL");
    }

    // ── True positives: Log4j ────────────────────────────────────────────────

    /** import of org.apache.log4j (log4j 1.x) must be flagged as CRITICAL. */
    @Test
    void log4j1xPackageIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import org.apache.log4j.Logger;\n"
                + "public class LegacyService {\n"
                + "    private static final Logger LOG = Logger.getLogger(LegacyService.class);\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/LegacyService.java", source);
        assertTrue(hasFindingForClass(scan(jar), "org.apache.log4j"),
                "import of org.apache.log4j (log4j 1.x) must be flagged");
    }

    // ── True positives: JasperReports 7 ─────────────────────────────────────

    /** import of JRPdfExporter must be flagged. */
    @Test
    void jrPdfExporterImportIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.report;\n"
                + "import net.sf.jasperreports.engine.export.JRPdfExporter;\n"
                + "public class PdfReportGenerator {\n"
                + "    JRPdfExporter exporter = new JRPdfExporter();\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/report/PdfReportGenerator.java", source);
        assertTrue(hasFindingForClass(scan(jar),
                "net.sf.jasperreports.engine.export.JRPdfExporter"),
                "JRPdfExporter import must be flagged");
    }

    // ── True positives: misc libraries ──────────────────────────────────────

    /** import of commons-fileupload FileUpload must be flagged. */
    @Test
    void commonsFileUploadIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.upload;\n"
                + "import org.apache.commons.fileupload.FileUpload;\n"
                + "public class UploadHandler {\n"
                + "    FileUpload upload = new FileUpload();\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/upload/UploadHandler.java", source);
        assertTrue(hasFindingForClass(scan(jar),
                "org.apache.commons.fileupload.FileUpload"),
                "commons-fileupload FileUpload import must be flagged");
    }

    /** import of NoOpPasswordEncoder must be flagged as CRITICAL. */
    @Test
    void noOpPasswordEncoderIsFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.security;\n"
                + "import org.springframework.security.crypto.password.NoOpPasswordEncoder;\n"
                + "public class InsecureConfig {\n"
                + "    Object encoder() { return NoOpPasswordEncoder.getInstance(); }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/security/InsecureConfig.java", source);
        assertTrue(hasFindingForClass(scan(jar),
                "org.springframework.security.crypto.password.NoOpPasswordEncoder"),
                "NoOpPasswordEncoder import must be flagged as CRITICAL");
    }

    // ── False positives ──────────────────────────────────────────────────────

    /** A class with the simple name "HttpSecurity" imported from a non-Spring package
     *  must not trigger the antMatchers method rule in code. */
    @Test
    void httpSecurityFromDifferentPackageDoesNotTriggerAntMatchersCodeMatch(
            @TempDir Path tmp) throws IOException {
        // No call to .antMatchers( -- just usage of a similarly-named local class
        String source =
                "package com.example;\n"
                + "import com.example.custom.HttpSecurity;\n"
                + "public class MyConfig {\n"
                + "    HttpSecurity sec = new HttpSecurity();\n"
                + "    void setup() { sec.configure(); }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/MyConfig.java", source);
        List<LibraryUpgradeAnalyzer.Finding> findings = scan(jar);
        assertFalse(hasFindingForMethod(findings, "antMatchers"),
                "No .antMatchers() call present, so no antMatchers finding expected");
    }

    /** HandlerInterceptorAdapter in a string literal must not be flagged. */
    @Test
    void handlerInterceptorAdapterInStringIsNotFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "public class MigrationNotes {\n"
                + "    static final String MSG = \"HandlerInterceptorAdapter is removed in Spring 6\";\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/MigrationNotes.java", source);
        assertFalse(hasFindingForClass(scan(jar),
                "org.springframework.web.servlet.handler.HandlerInterceptorAdapter"),
                "HandlerInterceptorAdapter in a string literal must not be flagged");
    }

    /** CacheManager from a non-EhCache package must not trigger the EhCache 3 rule. */
    @Test
    void nonEhcacheCacheManagerIsNotFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.cache;\n"
                + "import org.springframework.cache.CacheManager;\n"
                + "public class CacheConfig {\n"
                + "    CacheManager cacheManager;\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/cache/CacheConfig.java", source);
        assertFalse(hasFindingForClass(scan(jar), "net.sf.ehcache"),
                "Spring CacheManager import must not trigger the EhCache net.sf.ehcache rule");
    }

    /** jakarta.servlet import (correct post-migration) must not trigger the Jetty 12 javax rule. */
    @Test
    void jakartaServletIsNotFlagged(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example.servlet;\n"
                + "import jakarta.servlet.http.HttpServletRequest;\n"
                + "public class ModernServlet {\n"
                + "    void handle(HttpServletRequest req) {}\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/servlet/ModernServlet.java", source);
        assertFalse(hasFindingForClass(scan(jar), "javax.servlet"),
                "import jakarta.servlet.* must not trigger the javax.servlet rule");
    }

    /** File with no deprecated WL15 APIs must produce zero findings. */
    @Test
    void cleanFileProducesNoFindings(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import java.util.List;\n"
                + "import java.util.ArrayList;\n"
                + "import jakarta.enterprise.context.ApplicationScoped;\n"
                + "public class CleanService {\n"
                + "    private List<String> items = new ArrayList<>();\n"
                + "    public void add(String item) { items.add(item); }\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/CleanService.java", source);
        assertTrue(scan(jar).isEmpty(),
                "A file with no deprecated WL15 APIs must produce zero findings");
    }

    // ── Multi-library scenario ────────────────────────────────────────────────

    /** A class using both Spring Security and EhCache legacy APIs must produce findings
     *  from both libraries in a single scan. */
    @Test
    void multipleLegacyLibrariesAllDetectedInSingleScan(@TempDir Path tmp) throws IOException {
        String source =
                "package com.example;\n"
                + "import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;\n"
                + "import net.sf.ehcache.CacheManager;\n"
                + "import net.sf.ehcache.Cache;\n"
                + "public class LegacyAppConfig extends WebSecurityConfigurerAdapter {\n"
                + "    CacheManager manager = CacheManager.getInstance();\n"
                + "}\n";
        Path jar = buildJar(tmp, "com/example/LegacyAppConfig.java", source);
        List<LibraryUpgradeAnalyzer.Finding> findings = scan(jar);

        assertTrue(hasFindingForClass(findings,
                "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter"),
                "WebSecurityConfigurerAdapter finding expected");
        assertTrue(hasFindingForClass(findings, "net.sf.ehcache"),
                "EhCache net.sf.ehcache finding expected");

        long libs = findings.stream()
                .map(LibraryUpgradeAnalyzer.Finding::library)
                .distinct()
                .count();
        assertTrue(libs >= 2,
                "Findings from at least 2 different libraries expected, got: " + libs);
    }
}