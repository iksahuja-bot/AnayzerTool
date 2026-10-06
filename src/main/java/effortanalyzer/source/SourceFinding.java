package effortanalyzer.source;

/** A line-level source finding with component/repository context. */
public record SourceFinding(
        String component,
        String repository,
        RepositoryType repositoryType,
        String scanner,
        String category,
        String severity,
        String file,
        int line,
        String rule,
        String description,
        String remediation,
        String context
) {}
