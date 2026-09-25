package effortanalyzer.version;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the built-in {@link LibraryVersionRules} table and the external
 * library-versions.properties override mechanism.
 */
class LibraryVersionRulesTest {

    // ── Built-in table ────────────────────────────────────────────────────────

    @Test
    void builtInTableMatchesLibraryUpgradeList() {
        List<LibraryVersionRule> rules = LibraryVersionRules.builtIn();
        assertEquals(28, rules.size(),
                "LibraryUpgradeList defines exactly 28 planned-version checks");

        assertEquals("6.2.11", find(rules, "spring-core").targetVersion());
        assertEquals("6.1.14", find(rules, "spring-webflux").targetVersion());
        assertEquals("6.5.9", find(rules, "spring-security-web").targetVersion());
        assertEquals("4.1.135.Final", find(rules, "netty-codec").targetVersion());
        assertEquals("12.0.33", find(rules, "jetty-http").targetVersion());
        assertEquals("2.25.4", find(rules, "log4j-core").targetVersion());
        assertEquals("2.18.9", find(rules, "jackson-").targetVersion());
        assertEquals("20260101.1", find(rules, "owasp-java-html-sanitizer").targetVersion());
        assertEquals("1.85", find(rules, "bcprov-jdk18on").targetVersion());
        assertEquals("1.13.0", find(rules, "commons-text").targetVersion());
        assertEquals("2.10.0", find(rules, "commons-vfs").targetVersion());
        assertEquals("2.10.0", find(rules, "commons-vfs2").targetVersion());
        assertEquals("6.2.0.CR1", find(rules, "hibernate-validator").targetVersion());
        assertEquals("1.5.36", find(rules, "logback-core").targetVersion());
        assertEquals("2.19.0", find(rules, "sshd-core").targetVersion());
    }

    @Test
    void librariesWithoutPlannedVersionAreNotChecked() {
        List<LibraryVersionRule> rules = LibraryVersionRules.builtIn();
        // upgrade-list rows with an empty "Planned upgrade version" get no check
        assertNull(find(rules, "spring-web"));
        assertNull(find(rules, "spring-webmvc"));
        assertNull(find(rules, "spring-context"));
        assertNull(find(rules, "spring-security-crypto"));
        assertNull(find(rules, "spring-xml"));
        assertNull(find(rules, "spring-ws-core"));
        assertNull(find(rules, "angular"));
        assertNull(find(rules, "canvg"));
        // family-wide rules from the old draft table are gone: netty/jetty/log4j are exact rows now
        assertNull(find(rules, "netty-"));
        assertNull(find(rules, "jetty-"));
        assertNull(find(rules, "log4j-api"));
        assertNull(find(rules, "assertj-core"));
    }

    @Test
    void builtInTableHasUniqueLowercaseArtifacts() {
        List<LibraryVersionRule> rules = LibraryVersionRules.builtIn();
        long distinct = rules.stream().map(LibraryVersionRule::artifact).distinct().count();
        assertEquals(rules.size(), distinct, "duplicate artifact rows would shadow each other");
        assertTrue(rules.stream().allMatch(r -> r.artifact().equals(r.artifact().toLowerCase())),
                "artifact keys must be lowercase for matching");
        assertTrue(rules.stream().allMatch(r -> !r.targetVersion().isBlank()),
                "every rule needs a target version");
    }

    // ── Rule matching semantics ───────────────────────────────────────────────

    @Test
    void exactRulesMatchOnlyExactly() {
        LibraryVersionRule r = new LibraryVersionRule("commons-vfs2", "Commons VFS2", "2.9.0", "WARNING");
        assertTrue(r.matches("commons-vfs2"));
        assertTrue(r.matches("COMMONS-VFS2"), "matching must be case-insensitive");
        assertFalse(r.matches("commons-vfs"), "must not match the 1.x sibling");
        assertFalse(r.matches("commons-vfs2-extras"), "exact rules must not act as prefixes");
    }

    @Test
    void prefixRulesMatchTheWholeFamily() {
        LibraryVersionRule r = new LibraryVersionRule("netty-", "Netty", "4.1.135.Final", "CRITICAL");
        assertTrue(r.isPrefix());
        assertTrue(r.matches("netty-buffer"));
        assertTrue(r.matches("netty-codec-http"));
        assertFalse(r.matches("netty"), "prefix rule requires at least one trailing char");
        assertFalse(r.matches("jetty-buffer"));
    }

    // ── External override file ────────────────────────────────────────────────

    @Test
    void overrideFileCanAdjustAddAndDisableRules(@TempDir Path tmp) throws IOException {
        Path props = tmp.resolve("library-versions.properties");
        Files.writeString(props, String.join("\n",
                "# move the spring target",
                "spring-core=6.2.12",
                "# add a private check with severity and display name",
                "acme-shared=3.4.1:HIGH:Acme Shared Libs",
                "# drop a test-only built-in row",
                "disable.moment=true"));

        List<LibraryVersionRule> rules = LibraryVersionRules.load(props);

        assertEquals("6.2.12", find(rules, "spring-core").targetVersion(),
                "target version must be overridden");
        assertEquals("CRITICAL", find(rules, "spring-core").severity(),
                "omitted severity must keep the built-in value");

        LibraryVersionRule acme = find(rules, "acme-shared");
        assertNotNull(acme, "new artifact must be added");
        assertEquals("HIGH", acme.severity());
        assertEquals("Acme Shared Libs", acme.library());

        assertNull(find(rules, "moment"), "disabled row must be removed");
        assertNotNull(find(rules, "log4j-core"), "unrelated rows must survive");
    }

    @Test
    void missingOverrideFileYieldsBuiltIns() {
        List<LibraryVersionRule> viaNull = LibraryVersionRules.load((Path) null);
        List<LibraryVersionRule> viaMissing = LibraryVersionRules.load(Path.of("definitely-missing-9f3a.properties"));
        assertEquals(LibraryVersionRules.builtIn().size(), viaNull.size());
        assertEquals(LibraryVersionRules.builtIn().size(), viaMissing.size());
    }

    @Test
    void resolveOverrideKeepsExplicitExistingFileAndIgnoresMissingOne(@TempDir Path tmp) throws IOException {
        Path real = tmp.resolve("lv.properties");
        Files.writeString(real, "spring-core=6.2.12\n");

        assertEquals(real, LibraryVersionRules.resolveOverride(real.toString()));
        assertNull(LibraryVersionRules.resolveOverride(tmp.resolve("nope.properties").toString()),
                "missing explicit file falls back to defaults (null → built-ins)");
    }

    private static LibraryVersionRule find(List<LibraryVersionRule> rules, String artifact) {
        return rules.stream().filter(r -> r.artifact().equals(artifact)).findFirst().orElse(null);
    }
}