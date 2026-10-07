package effortanalyzer.source;

/** Java 21 migration/readiness rule used by source-inventory scans. */
public record Java21SourceRule(
        String id,
        String category,
        String apiPattern,
        String severity,
        String javaVersion,
        String description,
        String remediation,
        ScanTarget scanTarget
) {
    public enum ScanTarget {
        /** Java source only, after comments and string/char literals are stripped. */
        JAVA,
        /** Build/config/script text, scanned as raw text. */
        TEXT,
        /** Java source and raw text files. */
        BOTH,
        /** Special rule: Java language/build level below 21. */
        BUILD_JAVA_LEVEL
    }

    public boolean scansJava() {
        return scanTarget == ScanTarget.JAVA || scanTarget == ScanTarget.BOTH;
    }

    public boolean scansText() {
        return scanTarget == ScanTarget.TEXT || scanTarget == ScanTarget.BOTH;
    }
}