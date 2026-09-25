package effortanalyzer.version;

/**
 * One library-version upgrade check.
 *
 * <p>An artifact is identified by its Maven-style artifactId (lower-cased).
 * Two matching modes are supported:
 * <ul>
 *   <li><b>exact</b> — {@code spring-core} matches only {@code spring-core}</li>
 *   <li><b>prefix</b> — a trailing dash, e.g. {@code netty-}, matches every
 *       artifactId that starts with that prefix ({@code netty-codec-http},
 *       {@code netty-buffer}, …)</li>
 * </ul>
 * Exact rules always beat prefix rules for the same artifact, which prevents
 * collisions such as {@code commons-vfs} being matched by {@code commons-vfs2}
 * or {@code netty-codec} by {@code netty-codec-http}.
 *
 * @param artifact      artifactId or artifact prefix (lower-case; prefix ends with '-')
 * @param library       human-readable library name, e.g. "Spring Framework"
 * @param targetVersion minimum required version for the target platform
 * @param severity      CRITICAL | HIGH | WARNING | INFO
 */
public record LibraryVersionRule(
        String artifact,
        String library,
        String targetVersion,
        String severity) {

    public boolean isPrefix() {
        return artifact.endsWith("-");
    }

    /** True when this rule applies to the given artifactId (never crosses a '-' boundary wrongly). */
    public boolean matches(String artifactId) {
        String a = artifactId.toLowerCase();
        String r = artifact.toLowerCase();
        if (isPrefix()) return a.startsWith(r);
        return a.equals(r);
    }

    /** Specificity used when several rules match the same artifact: longer wins. */
    public int specificity() {
        return artifact.length();
    }
}
