package effortanalyzer.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lenient, Maven-inspired version-string comparator used by the library
 * version checks.
 *
 * <p>Handles the messy version formats found in real-world archives:
 * <ul>
 *   <li>numeric cores of any length: {@code 4.1.135}, {@code 20280101.1}</li>
 *   <li>release qualifiers treated as equal to no qualifier:
 *       {@code 4.1.135.Final}, {@code 1.2.3.GA}, {@code 3.1.1.RELEASE}</li>
 *   <li>pre-release qualifiers ranked below the plain release:
 *       {@code SNAPSHOT}, {@code alpha}/{@code a}, {@code beta}/{@code b},
 *       {@code milestone}, {@code rc}/{@code cr}</li>
 *   <li>service-pack qualifier ranked above the plain release: {@code sp}</li>
 *   <li>unknown trailing tokens ({@code jre}, {@code nc}, {@code redhat-00002},
 *       {@code r136}, {@code tomcat7}) are treated as <em>newer</em> than the
 *       plain release — a conservative choice that avoids false "outdated"
 *       alarms on vendor-patched builds</li>
 *   <li>leading {@code v} prefixes ({@code v20241219}) and {@code +}/{@code _}
 *       separators</li>
 * </ul>
 *
 * <p>Only {@link #below} (strictly less-than) drives the "outdated" verdict.
 */
public final class VersionComparator {

    /** Qualifier ranking. Unknown qualifiers get rank 2 (treated as newer). */
    private static final Map<String, Integer> QUALIFIER_RANK = Map.ofEntries(
            Map.entry("snapshot",  -3),
            Map.entry("alpha",     -2), Map.entry("a", -2),
            Map.entry("beta",      -2), Map.entry("b", -2),
            Map.entry("milestone", -1),
            Map.entry("rc",        -1), Map.entry("cr", -1),
            Map.entry("",           0),
            Map.entry("final",      0), Map.entry("ga", 0),
            Map.entry("release",    0), Map.entry("prod", 0),
            Map.entry("sp",         1));

    /** Rank assumed by a missing token (e.g. "1.2" vs "1.2-Final"). */
    private static final int MISSING_RANK = 0;

    /** Rank of any qualifier not present in {@link #QUALIFIER_RANK}. */
    private static final int UNKNOWN_RANK = 2;

    private VersionComparator() {}

    /** Returns true when {@code version} is strictly below {@code target}. */
    public static boolean below(String version, String target) {
        return compare(version, target) < 0;
    }

    /** Lenient three-way comparison of two version strings. */
    public static int compare(String a, String b) {
        String[] ta = tokenize(a);
        String[] tb = tokenize(b);
        int n = Math.max(ta.length, tb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < ta.length ? ta[i] : null;
            String sb = i < tb.length ? tb[i] : null;
            int c = compareToken(sa, sb);
            if (c != 0) return c;
        }
        return 0;
    }

    // ── Tokenization ──────────────────────────────────────────────────────────

    /**
     * Splits a version string into ordered digit/letter tokens.
     * Separators {@code . - _ +} are equivalent; digit and letter runs inside
     * a segment are also split ({@code "135final"} → {@code 135}, {@code final}).
     */
    static String[] tokenize(String version) {
        if (version == null) return new String[0];
        String s = version.trim().toLowerCase(Locale.ROOT);
        if (s.length() > 1 && s.charAt(0) == 'v' && Character.isDigit(s.charAt(1))) {
            s = s.substring(1);
        }

        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean curDigits = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean isSep = ch == '.' || ch == '-' || ch == '_' || ch == '+';
            if (isSep) {
                if (cur.length() > 0) { tokens.add(cur.toString()); cur.setLength(0); }
            } else if (Character.isDigit(ch)) {
                if (cur.length() > 0 && !curDigits) { tokens.add(cur.toString()); cur.setLength(0); }
                cur.append(ch);
                curDigits = true;
            } else {
                if (cur.length() > 0 && curDigits) { tokens.add(cur.toString()); cur.setLength(0); }
                cur.append(ch);
                curDigits = false;
            }
        }
        if (cur.length() > 0) tokens.add(cur.toString());
        return tokens.toArray(new String[0]);
    }

    // ── Token-level comparison ────────────────────────────────────────────────

    private static int compareToken(String a, String b) {
        if (a == null && b == null) return 0;
        // A missing token behaves like a release (rank 0):
        //   1.2 < 1.2.3        (missing < numeric)
        //   1.2 > 1.2-rc1      (missing > pre-release qualifier)
        //   1.2 = 1.2.Final    (missing = release qualifier)
        if (a == null) return Integer.compare(MISSING_RANK, tokenRank(b));
        if (b == null) return Integer.compare(tokenRank(a), MISSING_RANK);

        boolean aNum = isNumeric(a);
        boolean bNum = isNumeric(b);
        if (aNum && bNum) return compareNumeric(a, b);
        if (aNum) return 1;   // a numeric token at this position outranks a qualifier
        if (bNum) return -1;

        int ra = tokenRank(a);
        int rb = tokenRank(b);
        if (ra != rb) return Integer.compare(ra, rb);
        // Same (unknown) rank — deterministic fallback, no false ordering claim.
        return a.compareTo(b);
    }

    /** Numeric tokens outrank any qualifier; string tokens use the rank table. */
    private static int tokenRank(String token) {
        if (isNumeric(token)) return Integer.MAX_VALUE / 2;
        return QUALIFIER_RANK.getOrDefault(token, UNKNOWN_RANK);
    }

    private static boolean isNumeric(String t) {
        return !t.isEmpty() && Character.isDigit(t.charAt(0));
    }

    /** Overflow-safe unsigned numeric compare (versions never have signs). */
    private static int compareNumeric(String a, String b) {
        String na = stripLeadingZeros(a);
        String nb = stripLeadingZeros(b);
        if (na.length() != nb.length()) return Integer.compare(na.length(), nb.length());
        return na.compareTo(nb);
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') i++;
        return s.substring(i);
    }
}
