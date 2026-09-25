package effortanalyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class EffortConfigTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Builds a Properties from alternating key, value pairs. */
    private static Properties props(String... pairs) {
        Properties p = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            p.setProperty(pairs[i], pairs[i + 1]);
        }
        return p;
    }

    // ── Singleton smoke tests ─────────────────────────────────────────────────

    @Test
    void instanceIsNotNull() {
        assertNotNull(EffortConfig.INSTANCE);
    }

    @Test
    void instanceFlatOverrideNeverThrows() {
        assertDoesNotThrow(() -> EffortConfig.INSTANCE.flatOverride("anything"));
        assertDoesNotThrow(() -> EffortConfig.INSTANCE.flatOverride(null));
    }

    @Test
    void instanceScaleOverridesNeverReturnsNullArray() {
        assertNotNull(EffortConfig.INSTANCE.scaleOverrides("HIGH"));
        assertNotNull(EffortConfig.INSTANCE.scaleOverrides(null));
        assertEquals(3, EffortConfig.INSTANCE.scaleOverrides("CRITICAL").length);
    }

    // ── flatOverride ──────────────────────────────────────────────────────────

    @Test
    void flatOverrideEmptyWhenNoOverridesConfigured() {
        EffortConfig cfg = EffortConfig.from(new Properties());
        assertFalse(cfg.flatOverride("java/lang/System#exit").isPresent());
    }

    @Test
    void flatOverrideReturnsPresentWhenKeyExists() {
        EffortConfig cfg = EffortConfig.from(props("override.java/lang/System#exit", "8.0"));
        assertTrue(cfg.flatOverride("java/lang/System#exit").isPresent());
        assertEquals(8.0, cfg.flatOverride("java/lang/System#exit").getAsDouble(), 1e-9);
    }

    @Test
    void flatOverrideMatchesCaseInsensitively() {
        EffortConfig cfg = EffortConfig.from(props("override.wljboss-001", "5.0"));
        assertTrue(cfg.flatOverride("WLJBOSS-001").isPresent(),  "upper-case lookup failed");
        assertTrue(cfg.flatOverride("wljboss-001").isPresent(),  "lower-case lookup failed");
        assertTrue(cfg.flatOverride("WlJBoss-001").isPresent(),  "mixed-case lookup failed");
    }

    @Test
    void flatOverrideReturnsEmptyForNullKey() {
        EffortConfig cfg = EffortConfig.from(props("override.some-rule", "3.0"));
        assertFalse(cfg.flatOverride(null).isPresent());
    }

    @Test
    void flatOverrideReturnsEmptyForUnknownKey() {
        EffortConfig cfg = EffortConfig.from(props("override.rule-a", "3.0"));
        assertFalse(cfg.flatOverride("rule-b").isPresent());
    }

    @Test
    void flatOverrideSupportsSlashAndHashInKey() {
        String key = "org/springframework/web/context/XmlWebApplicationContext#refresh";
        EffortConfig cfg = EffortConfig.from(props("override." + key, "4.0"));
        assertTrue(cfg.flatOverride(key).isPresent());
        assertEquals(4.0, cfg.flatOverride(key).getAsDouble(), 1e-9);
    }

    @Test
    void multipleRuleOverridesAllParsed() {
        EffortConfig cfg = EffortConfig.from(props(
                "override.rule-a", "2.0",
                "override.rule-b", "4.0",
                "override.rule-c", "1.5"
        ));
        assertEquals(2.0, cfg.flatOverride("rule-a").getAsDouble(), 1e-9);
        assertEquals(4.0, cfg.flatOverride("rule-b").getAsDouble(), 1e-9);
        assertEquals(1.5, cfg.flatOverride("rule-c").getAsDouble(), 1e-9);
    }

    // ── scaleOverrides ────────────────────────────────────────────────────────

    @Test
    void scaleOverridesAllNullWhenNoOverridesConfigured() {
        EffortConfig cfg = EffortConfig.from(new Properties());
        Double[] ov = cfg.scaleOverrides("HIGH");
        assertNull(ov[0], "base should be null");
        assertNull(ov[1], "perFile should be null");
        assertNull(ov[2], "cap should be null");
    }

    @Test
    void scaleOverridesBaseFieldSetCorrectly() {
        EffortConfig cfg = EffortConfig.from(props("effort.HIGH.base", "3.0"));
        Double[] ov = cfg.scaleOverrides("HIGH");
        assertEquals(3.0, ov[0], 1e-9);
        assertNull(ov[1], "perFile should remain null");
        assertNull(ov[2], "cap should remain null");
    }

    @Test
    void scaleOverridesPerFileFieldSetCorrectly() {
        EffortConfig cfg = EffortConfig.from(props("effort.CRITICAL.perFile", "0.75"));
        Double[] ov = cfg.scaleOverrides("CRITICAL");
        assertNull(ov[0], "base should remain null");
        assertEquals(0.75, ov[1], 1e-9);
        assertNull(ov[2], "cap should remain null");
    }

    @Test
    void scaleOverridesCapFieldSetCorrectly() {
        EffortConfig cfg = EffortConfig.from(props("effort.MEDIUM.cap", "10.0"));
        Double[] ov = cfg.scaleOverrides("MEDIUM");
        assertNull(ov[0], "base should remain null");
        assertNull(ov[1], "perFile should remain null");
        assertEquals(10.0, ov[2], 1e-9);
    }

    @Test
    void scaleOverridesAllThreeFieldsIndependent() {
        EffortConfig cfg = EffortConfig.from(props(
                "effort.MEDIUM.base",   "0.5",
                "effort.MEDIUM.perFile","0.1",
                "effort.MEDIUM.cap",    "8.0"
        ));
        Double[] ov = cfg.scaleOverrides("MEDIUM");
        assertEquals(0.5, ov[0], 1e-9);
        assertEquals(0.1, ov[1], 1e-9);
        assertEquals(8.0, ov[2], 1e-9);
    }

    @Test
    void scaleOverridesMatchesCaseInsensitively() {
        // property key lower-case, lookup upper-case
        EffortConfig cfg = EffortConfig.from(props("effort.high.base", "2.5"));
        assertEquals(2.5, cfg.scaleOverrides("HIGH")[0],   1e-9);
        assertEquals(2.5, cfg.scaleOverrides("high")[0],   1e-9);
        assertEquals(2.5, cfg.scaleOverrides("High")[0],   1e-9);
    }

    @Test
    void scaleOverridesAllNullForNullSeverity() {
        EffortConfig cfg = EffortConfig.from(props("effort.HIGH.base", "2.0"));
        Double[] ov = cfg.scaleOverrides(null);
        assertNull(ov[0]);
        assertNull(ov[1]);
        assertNull(ov[2]);
    }

    @Test
    void scaleOverridesAllNullForUnknownSeverity() {
        EffortConfig cfg = EffortConfig.from(props("effort.HIGH.base", "2.0"));
        Double[] ov = cfg.scaleOverrides("EXTREME");
        assertNull(ov[0]);
        assertNull(ov[1]);
        assertNull(ov[2]);
    }

    @Test
    void scaleOverridesForDifferentSeveritiesAreIndependent() {
        EffortConfig cfg = EffortConfig.from(props(
                "effort.HIGH.base",     "1.0",
                "effort.CRITICAL.base", "2.0"
        ));
        assertEquals(1.0, cfg.scaleOverrides("HIGH")[0],     1e-9);
        assertEquals(2.0, cfg.scaleOverrides("CRITICAL")[0], 1e-9);
        assertNull(cfg.scaleOverrides("MEDIUM")[0]);
    }

    // ── Parsing edge cases ────────────────────────────────────────────────────

    @Test
    void invalidNumericValueForScaleIsIgnored() {
        EffortConfig cfg = EffortConfig.from(props("effort.HIGH.base", "not-a-number"));
        // The bad entry is discarded; array slot stays null (entry not created)
        assertNull(cfg.scaleOverrides("HIGH")[0]);
    }

    @Test
    void invalidNumericValueForRuleOverrideIsIgnored() {
        EffortConfig cfg = EffortConfig.from(props("override.my-rule", "bad-value"));
        assertFalse(cfg.flatOverride("my-rule").isPresent());
    }

    @Test
    void effortKeyWithOnlyTwoSegmentsIsIgnored() {
        // "effort.HIGH" (missing field part) must not crash and must not set anything
        EffortConfig cfg = EffortConfig.from(props("effort.HIGH", "2.0"));
        assertNull(cfg.scaleOverrides("HIGH")[0]);
    }

    @Test
    void unrecognisedPropertyKeyIsIgnored() {
        EffortConfig cfg = EffortConfig.from(props("unknown.key.here", "5.0"));
        assertFalse(cfg.flatOverride("unknown.key.here").isPresent());
        assertNull(cfg.scaleOverrides("UNKNOWN")[0]);
    }

    @Test
    void emptyPropertiesProducesNoOverrides() {
        EffortConfig cfg = EffortConfig.from(new Properties());
        assertFalse(cfg.flatOverride("anything").isPresent());
        assertNull(cfg.scaleOverrides("HIGH")[0]);
    }

    // ── File loading (round-trip) ─────────────────────────────────────────────

    @Test
    void loadFromPropertiesFileRoundTrip(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("effort-overrides.properties");
        Files.writeString(file,
                "effort.HIGH.base=3.0\n"
                + "effort.HIGH.perFile=0.5\n"
                + "effort.HIGH.cap=15.0\n"
                + "effort.LOW.base=0.1\n"
                + "override.javax/ejb/EJBHome=6.0\n"
                + "override.WLJBOSS-042=2.5\n",
                StandardCharsets.UTF_8);

        Properties loaded = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            loaded.load(in);
        }

        EffortConfig cfg = EffortConfig.from(loaded);

        // Severity scale
        Double[] high = cfg.scaleOverrides("HIGH");
        assertEquals(3.0,  high[0], 1e-9, "base");
        assertEquals(0.5,  high[1], 1e-9, "perFile");
        assertEquals(15.0, high[2], 1e-9, "cap");

        assertEquals(0.1, cfg.scaleOverrides("LOW")[0], 1e-9, "LOW base");

        // Flat overrides
        assertEquals(6.0, cfg.flatOverride("javax/ejb/EJBHome").getAsDouble(),  1e-9);
        assertEquals(2.5, cfg.flatOverride("wljboss-042").getAsDouble(),          1e-9);
        assertEquals(2.5, cfg.flatOverride("WLJBOSS-042").getAsDouble(),          1e-9);
    }

    @Test
    void propertiesFileWithCommentsAndBlankLinesIsHandledGracefully(@TempDir Path tmpDir)
            throws IOException {
        Path file = tmpDir.resolve("effort-overrides.properties");
        Files.writeString(file,
                "# This is a comment\n"
                + "\n"
                + "  # Indented comment\n"
                + "effort.CRITICAL.base=2.0\n"
                + "\n"
                + "override.some-api=7.0\n",
                StandardCharsets.UTF_8);

        Properties loaded = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            loaded.load(in);
        }

        EffortConfig cfg = EffortConfig.from(loaded);
        assertEquals(2.0, cfg.scaleOverrides("CRITICAL")[0], 1e-9);
        assertEquals(7.0, cfg.flatOverride("some-api").getAsDouble(), 1e-9);
    }

    @Test
    void mixedValidAndInvalidEntriesParsedPartially(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("effort-overrides.properties");
        Files.writeString(file,
                "effort.HIGH.base=1.5\n"
                + "effort.MEDIUM.base=INVALID\n"
                + "override.good-rule=3.0\n"
                + "override.bad-rule=NOTANUMBER\n",
                StandardCharsets.UTF_8);

        Properties loaded = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            loaded.load(in);
        }

        EffortConfig cfg = EffortConfig.from(loaded);

        assertEquals(1.5, cfg.scaleOverrides("HIGH")[0],   1e-9, "valid HIGH.base");
        assertNull(cfg.scaleOverrides("MEDIUM")[0],                "invalid MEDIUM.base discarded");
        assertEquals(3.0, cfg.flatOverride("good-rule").getAsDouble(), 1e-9, "valid rule override");
        assertFalse(cfg.flatOverride("bad-rule").isPresent(),          "invalid rule override discarded");
    }
}