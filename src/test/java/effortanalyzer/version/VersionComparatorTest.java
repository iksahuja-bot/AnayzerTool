package effortanalyzer.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VersionComparator} — the lenient version comparison
 * used by the library version checks.
 */
class VersionComparatorTest {

    // ── Numeric core ──────────────────────────────────────────────────────────

    @Test
    void numericCoresCompareNumericallyNotLexically() {
        assertTrue(VersionComparator.below("1.9.0", "1.10.0"),
                "1.9.0 must be below 1.10.0 (numeric, not string, compare)");
        assertTrue(VersionComparator.below("2.17.1", "2.18.9"));
        assertFalse(VersionComparator.below("2.18.9", "2.18.9"), "equal versions are not below");
        assertFalse(VersionComparator.below("2.19.0", "2.18.9"));
    }

    @Test
    void missingTrailingSegmentsAreEqualWhenRemainingAreReleaseQualifiers() {
        assertFalse(VersionComparator.below("4.1.135", "4.1.135.Final"),
                "4.1.135 and 4.1.135.Final must be considered equal");
        assertFalse(VersionComparator.below("4.1.135.Final", "4.1.135"));
        assertTrue(VersionComparator.below("6.2", "6.2.0"),
                "a missing numeric segment ranks below a concrete one (Maven semantics)");
    }

    @Test
    void longerNumericCoreCountsAsNewer() {
        assertTrue(VersionComparator.below("6.2", "6.2.11"), "6.2 must be below 6.2.11");
        assertFalse(VersionComparator.below("6.2.11", "6.2"));
    }

    // ── Qualifiers ────────────────────────────────────────────────────────────

    @Test
    void snapshotIsBelowRelease() {
        assertTrue(VersionComparator.below("6.0.0-SNAPSHOT", "6.2.11"));
        assertTrue(VersionComparator.below("6.2.11-SNAPSHOT", "6.2.11"),
                "a SNAPSHOT of the target version itself is not a release yet");
    }

    @Test
    void releaseQualifiersAreEquivalent() {
        assertFalse(VersionComparator.below("6.2.11.GA", "6.2.11"));
        assertFalse(VersionComparator.below("6.2.11-final", "6.2.11"));
        assertTrue(VersionComparator.below("6.2.11-rc1", "6.2.11"),
                "release candidates rank below the release");
        assertTrue(VersionComparator.below("6.2.11-beta2", "6.2.11"));
    }

    @Test
    void unknownVendorSuffixesTreatedAsNewer() {
        assertFalse(VersionComparator.below("1.8.0-jre", "1.8.0"),
                "-jre suffix must not be flagged as outdated");
        assertFalse(VersionComparator.below("2.17.1-redhat-00002", "2.17.1"),
                "vendor patch suffixes must not be flagged");
        assertFalse(VersionComparator.below("1.2.3-nc", "1.2.3"));
    }

    // ── Messy formats ─────────────────────────────────────────────────────────

    @Test
    void vPrefixedDateVersionsParse() {
        assertFalse(VersionComparator.below("v20241219", "20211018.2"),
                "v-prefixed date versions must compare numerically");
        assertTrue(VersionComparator.below("v20140401", "20280101.1"));
    }

    @Test
    void underscoreAndPlusSeparatorsAreEquivalent() {
        assertEquals(0, VersionComparator.compare("1_2_3", "1.2.3"));
        assertEquals(0, VersionComparator.compare("1.2.3+build5", "1.2.3.build5"));
    }

    @Test
    void leadingZerosDoNotMatter() {
        assertEquals(0, VersionComparator.compare("1.02.3", "1.2.3"));
        assertTrue(VersionComparator.below("1.09.9", "1.10.0"));
    }

    @Test
    void rSuffixVersionsCompare() {
        // e.g. commons-jexl-1.1-r136 style builds — unknown token ranks above release
        assertFalse(VersionComparator.below("1.1-r136", "1.1"));
        assertTrue(VersionComparator.below("1.0-r136", "1.1"));
    }

    @Test
    void nullInputsAreSafe() {
        assertEquals(0, VersionComparator.compare(null, null));
        assertEquals(0, VersionComparator.compare("", ""));
        assertTrue(VersionComparator.compare(null, "1.0") < 0);
        assertTrue(VersionComparator.compare("1.0", null) > 0);
    }

    @Test
    void upgradeListEdgeCases() {
        // hibernate-validator target 6.2.0.CR1: CR ranks like RC (pre-release)
        assertTrue(VersionComparator.below("5.2.5.Final", "6.2.0.CR1"));
        assertFalse(VersionComparator.below("6.2.0.Final", "6.2.0.CR1"));
        assertFalse(VersionComparator.below("6.2.0", "6.2.0.CR1"));
        // owasp current build "v.r136" vs the yearly target scheme
        assertTrue(VersionComparator.below("v.r136", "20260101.1"));
        // jetty bundle suffix style from the upgrade list
        assertTrue(VersionComparator.below("9.4.57.v20241219", "12.0.33"));
        // vendor-patched ehcache (…-0.3.nc) is below the 3.x target
        assertTrue(VersionComparator.below("2.8.3-0.3.nc", "3.11.1"));
        // commons-vfs patched snapshot bundle vs 2.10.0
        assertTrue(VersionComparator.below("2.0-SNAPSHOT-20091012-PATCHED-bundled", "2.10.0"));
        // plain numeric bumps (sshd-core / Bouncy Castle)
        assertTrue(VersionComparator.below("2.14.0", "2.19.0"));
        assertFalse(VersionComparator.below("1.85", "1.85"));
    }

    @Test
    void symmetricConsistency() {
        String[][] pairs = {
                {"1.9.0", "1.10.0"}, {"4.1.135", "4.1.135.Final"},
                {"6.2.11-SNAPSHOT", "6.2.11"}, {"2.17.1-redhat-00002", "2.17.1"},
                {"1.02.3", "1.2.3"}, {"v20241219", "20211018.2"},
        };
        for (String[] p : pairs) {
            assertEquals(-Integer.signum(VersionComparator.compare(p[1], p[0])),
                    Integer.signum(VersionComparator.compare(p[0], p[1])),
                    "compare must be antisymmetric for " + p[0] + " vs " + p[1]);
        }
    }
}