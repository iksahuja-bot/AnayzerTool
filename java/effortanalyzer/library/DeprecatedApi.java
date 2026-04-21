package effortanalyzer.library;

/**
 * Represents a deprecated or removed API that should be flagged during a library upgrade scan.
 *
 * @param library      Human-readable library label (e.g. "Spring 5.3.39", "Jersey 2.22.2")
 * @param className    Fully-qualified class name or package prefix to match
 * @param methodName   Optional method name to narrow the match (null = class-level rule)
 * @param severity     "CRITICAL", "HIGH", "WARNING", or "INFO"
 * @param replacement  What to use instead
 * @param description  Why this is flagged
 */
public record DeprecatedApi(
        String library,
        String className,
        String methodName,
        String severity,
        String replacement,
        String description
) {}
