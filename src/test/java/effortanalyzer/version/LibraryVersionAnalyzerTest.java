package effortanalyzer.version;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link LibraryVersionAnalyzer} using synthetic
 * in-memory archives. Uses small explicit rule tables to keep the checks
 * focused (plus one test against the full built-in table).
 */
class LibraryVersionAnalyzerTest {

    private static final List<LibraryVersionRule> RULES = List.of(
            new LibraryVersionRule("spring-core",   "Spring Framework", "6.2.11",       "CRITICAL"),
            new LibraryVersionRule("netty-",        "Netty",            "4.1.135.Final","CRITICAL"),
            new LibraryVersionRule("commons-vfs2",  "Commons VFS2",     "2.9.0",        "WARNING"),
            new LibraryVersionRule("log4j-1.2-api", "Log4j",            "2.25.4",       "HIGH")
    );

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a WAR-like archive containing the given nested entry names. */
    private static Path buildWar(Path dir, String warName, String... entryNames) throws IOException {
        Path war = dir.resolve(warName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(war))) {
            jos.putNextEntry(new JarEntry("WEB-INF/web.xml"));
            jos.write("<web-app/>".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            for (String entryName : entryNames) {
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(new byte[0]);
                jos.closeEntry();
            }
        }
        return war;
    }

    private static List<LibraryVersionAnalyzer.VersionFinding> scan(Path archive,
                                                                    List<LibraryVersionRule> rules)
            throws IOException {
        LibraryVersionAnalyzer analyzer = new LibraryVersionAnalyzer(rules);
        analyzer.analyze(archive.toString());
        return analyzer.getFindings();
    }

    private static LibraryVersionAnalyzer.VersionFinding findByName(
            List<LibraryVersionAnalyzer.VersionFinding> findings, String artifact) {
        return findings.stream()
                .filter(f -> f.artifact().equals(artifact))
                .findFirst()
                .orElse(null);
    }

    // ── File-name detection ───────────────────────────────────────────────────

    @Test
    void outdatedNestedLibraryIsFlagged(@TempDir Path tmp) throws IOException {
        Path war = buildWar(tmp, "legacyapp.war", "WEB-INF/lib/spring-core-5.3.20.jar");
        List<LibraryVersionAnalyzer.VersionFinding> findings = scan(war, RULES);

        LibraryVersionAnalyzer.VersionFinding f = findByName(findings, "spring-core");
        assertNotNull(f, "spring-core must be detected via file name");
        assertEquals("5.3.20", f.detectedVersion());
        assertEquals("6.2.11", f.targetVersion());
        assertEquals("OUTDATED", f.status());
        assertEquals("CRITICAL", f.severity());
    }

    @Test
    void currentLibraryIsRecordedAsOk(@TempDir Path tmp) throws IOException {
        Path war = buildWar(tmp, "modernapp.war", "WEB-INF/lib/spring-core-6.2.11.jar");
        LibraryVersionAnalyzer.VersionFinding f = findByName(scan(war, RULES), "spring-core");
        assertNotNull(f);
        assertEquals("OK", f.status());
        assertEquals("", f.severity());
    }

    @Test
    void prefixRuleCoversWholeNettyFamily(@TempDir Path tmp) throws IOException {
        Path war = buildWar(tmp, "nettyapp.war",
                "WEB-INF/lib/netty-codec-http-4.1.100.Final.jar",   // outdated
                "WEB-INF/lib/netty-buffer-4.1.135.Final.jar");      // ok
        List<LibraryVersionAnalyzer.VersionFinding> findings = scan(war, RULES);

        LibraryVersionAnalyzer.VersionFinding old = findByName(findings, "netty-codec-http");
        LibraryVersionAnalyzer.VersionFinding cur = findByName(findings, "netty-buffer");
        assertNotNull(old, "netty-codec-http must match the netty- prefix rule");
        assertNotNull(cur, "netty-buffer must match the netty- prefix rule");
        assertEquals("OUTDATED", old.status());
        assertEquals("OK", cur.status());
    }

    // ── Prefix-collision guards ───────────────────────────────────────────────

    @Test
    void commonsVfsIsNotMatchedByCommonsVfs2Rule(@TempDir Path tmp) throws IOException {
        Path war = buildWar(tmp, "vfsapp.war",
                "WEB-INF/lib/commons-vfs-1.0.jar",     // old 1.x artifact — must NOT match
                "WEB-INF/lib/commons-vfs2-2.1.jar");   // matches the commons-vfs2 rule
        List<LibraryVersionAnalyzer.VersionFinding> findings = scan(war, RULES);

        assertNull(findByName(findings, "commons-vfs"),
                "commons-vfs must not be matched by the commons-vfs2 rule");
        assertNotNull(findByName(findings, "commons-vfs2"),
                "commons-vfs2-2.1.jar must be detected as commons-vfs2 v2.1");
    }

    @Test
    void embeddedVersionDigitsStayInTheArtifactName(@TempDir Path tmp) throws IOException {
        // log4j-1.2-api-2.17.1.jar → artifact log4j-1.2-api, version 2.17.1
        Path war = buildWar(tmp, "logapp.war", "WEB-INF/lib/log4j-1.2-api-2.17.1.jar");
        LibraryVersionAnalyzer.VersionFinding f = findByName(scan(war, RULES), "log4j-1.2-api");
        assertNotNull(f, "log4j-1.2-api must be parsed despite digits inside the artifact name");
        assertEquals("2.17.1", f.detectedVersion());
        assertEquals("OUTDATED", f.status());
        assertEquals("HIGH", f.severity());
    }

    // ── pom.properties detection ──────────────────────────────────────────────

    @Test
    void pomPropertiesProvidesExactCoordinates(@TempDir Path tmp) throws IOException {
        Path war = tmp.resolve("myapp.war");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(war))) {
            jos.putNextEntry(new JarEntry("META-INF/maven/org.springframework/spring-core/pom.properties"));
            jos.write(("groupId=org.springframework\nartifactId=spring-core\nversion=5.0.0.RELEASE\n")
                    .getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        LibraryVersionAnalyzer.VersionFinding f = findByName(scan(war, RULES), "spring-core");
        assertNotNull(f, "spring-core must be detected from pom.properties");
        assertEquals("5.0.0.RELEASE", f.detectedVersion());
        assertEquals("OUTDATED", f.status());
        assertTrue(f.location().contains("pom.properties"),
                "location should point at the pom.properties entry");
    }

    @Test
    void pomAndFileNameDescribeSameArtifactOnce(@TempDir Path tmp) throws IOException {
        // jar named spring-core-6.2.11.jar containing a matching pom.properties → one OK finding
        Path jar = tmp.resolve("spring-core-6.2.11.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("META-INF/maven/org.springframework/spring-core/pom.properties"));
            jos.write("artifactId=spring-core\nversion=6.2.11\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        List<LibraryVersionAnalyzer.VersionFinding> findings = scan(jar, RULES);
        long count = findings.stream().filter(f -> f.artifact().equals("spring-core")).count();
        assertEquals(1, count, "deduplication must yield exactly one spring-core finding, got: " + findings);
        assertEquals("OK", findings.get(0).status());
    }

    @Test
    void unknownArtifactsAreIgnored(@TempDir Path tmp) throws IOException {
        Path war = buildWar(tmp, "cleanapp.war",
                "WEB-INF/lib/our-internals-1.0.jar", "WEB-INF/lib/acme-tools-3.2.jar");
        assertTrue(scan(war, RULES).isEmpty(),
                "artifacts without a matching rule must produce no findings");
    }

    // ── Built-in table smoke test ─────────────────────────────────────────────

    @Test
    void builtinTableFlagsCommonLegacyStack(@TempDir Path tmp) throws IOException {
        Path war = buildWar(tmp, "bigsweep.war",
                "WEB-INF/lib/spring-core-5.3.39.jar",
                "WEB-INF/lib/spring-webflux-5.3.39.jar",
                "WEB-INF/lib/spring-security-web-5.8.16.jar",
                "WEB-INF/lib/jackson-databind-2.13.4.jar",
                "WEB-INF/lib/netty-codec-4.1.118.Final.jar",
                "WEB-INF/lib/jetty-http-9.4.57.v20241219.jar",
                "WEB-INF/lib/log4j-core-2.17.1.jar",
                "WEB-INF/lib/bcprov-jdk18on-1.80.jar",
                "WEB-INF/lib/commons-vfs2-2.1.jar",
                "WEB-INF/lib/sshd-core-2.19.0.jar",                 // compliant
                "WEB-INF/lib/spring-security-config-5.5.0.jar",     // no upgrade-list row → no finding
                "WEB-INF/lib/netty-codec-http-4.1.100.Final.jar");  // exact netty-codec rule → sibling ignored
        LibraryVersionAnalyzer analyzer =
                new LibraryVersionAnalyzer(LibraryVersionRules.builtIn());
        analyzer.analyze(war.toString());

        assertEquals(10, analyzer.getFindings().size(),
                "all 10 upgrade-list artifacts match built-in rules, the two extras do not");
        assertEquals(9, analyzer.countOutdated(), "9 of 10 are below the planned upgrade versions");
        assertEquals("OK", findByName(analyzer.getFindings(), "sshd-core").status());
        assertEquals("CRITICAL", findByName(analyzer.getFindings(), "jackson-databind").severity());
        assertNull(findByName(analyzer.getFindings(), "netty-codec-http"),
                "exact netty-codec rule must not match the http sibling");
        assertNull(findByName(analyzer.getFindings(), "spring-security-config"),
                "artifacts without a planned upgrade version produce no findings");
    }

    // ── splitNameVersion unit checks ──────────────────────────────────────────

    @Test
    void splitNameVersionHandlesRealWorldNames() {
        assertArrayEquals(new String[]{"spring-core", "5.3.20"},
                LibraryVersionAnalyzer.splitNameVersion("spring-core-5.3.20.jar"));
        assertArrayEquals(new String[]{"commons-vfs2", "2.9.0"},
                LibraryVersionAnalyzer.splitNameVersion("commons-vfs2-2.9.0.jar"));
        assertArrayEquals(new String[]{"log4j-1.2-api", "2.17.1"},
                LibraryVersionAnalyzer.splitNameVersion("log4j-1.2-api-2.17.1.jar"));
        assertArrayEquals(new String[]{"netty-codec-http", "4.1.135.Final"},
                LibraryVersionAnalyzer.splitNameVersion("netty-codec-http-4.1.135.Final.jar"));
        assertArrayEquals(new String[]{"nimbus-jose-jwt", "9.37.3"},
                LibraryVersionAnalyzer.splitNameVersion("nimbus-jose-jwt-9.37.3.jar"));
        assertNull(LibraryVersionAnalyzer.splitNameVersion("ourapp.war"),
                "no version segment → no split");
        assertNull(LibraryVersionAnalyzer.splitNameVersion("spring-core.jar"),
                "no version segment → no split");
    }
}
