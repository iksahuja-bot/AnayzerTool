package effortanalyzer.source;

/** A line-level source or generated-artifact finding with component/repository context. */
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
        String context,
        String detectionSource,
        String validationStatus,
        String confidence,
        boolean matchedInSource,
        boolean matchedInBytecode,
        String matchedJars,
        String reasonCode,
        String sourceClass,
        String bytecodeClass,
        String ownership,
        String trunkStatus,
        String trunkEvidence
) {
    public SourceFinding(String component, String repository, RepositoryType repositoryType, String scanner,
                         String category, String severity, String file, int line, String rule,
                         String description, String remediation, String context) {
        this(component, repository, repositoryType, scanner, category, severity, file, line, rule,
                description, remediation, context, "SOURCE", "CANDIDATE_SOURCE_ONLY", "LOW",
                true, false, "", "SOURCE_ONLY_NOT_IN_COMPILED_JARS", sourceClassFromFile(file), "", "", "", "");
    }

    public SourceFinding(String component, String repository, RepositoryType repositoryType, String scanner,
                         String category, String severity, String file, int line, String rule,
                         String description, String remediation, String context, String detectionSource,
                         String validationStatus, String confidence, boolean matchedInSource, boolean matchedInBytecode,
                         String matchedJars, String reasonCode, String sourceClass, String bytecodeClass, String ownership) {
        this(component, repository, repositoryType, scanner, category, severity, file, line, rule,
                description, remediation, context, detectionSource, validationStatus, confidence,
                matchedInSource, matchedInBytecode, matchedJars, reasonCode, sourceClass, bytecodeClass, ownership, "", "");
    }

    public SourceFinding withValidation(String detectionSource, String validationStatus, String confidence,
                                        boolean matchedInSource, boolean matchedInBytecode, String matchedJars,
                                        String reasonCode, String bytecodeClass, String ownership) {
        return new SourceFinding(component, repository, repositoryType, scanner, category, severity, file, line, rule,
                description, remediation, context, detectionSource, validationStatus, confidence,
                matchedInSource, matchedInBytecode, matchedJars, reasonCode, sourceClass,
                bytecodeClass == null ? "" : bytecodeClass, ownership == null ? "" : ownership, trunkStatus, trunkEvidence);
    }

    public SourceFinding withOwnership(String ownership) {
        return new SourceFinding(component, repository, repositoryType, scanner, category, severity, file, line, rule,
                description, remediation, context, detectionSource, validationStatus, confidence,
                matchedInSource, matchedInBytecode, matchedJars, reasonCode, sourceClass, bytecodeClass,
                ownership == null ? "" : ownership, trunkStatus, trunkEvidence);
    }

    public SourceFinding withTrunk(String trunkStatus, String trunkEvidence) {
        return new SourceFinding(component, repository, repositoryType, scanner, category, severity, file, line, rule,
                description, remediation, context, detectionSource, validationStatus, confidence,
                matchedInSource, matchedInBytecode, matchedJars, reasonCode, sourceClass, bytecodeClass, ownership,
                trunkStatus, trunkEvidence);
    }

    public SourceFinding {
        detectionSource = blankDefault(detectionSource, "SOURCE");
        validationStatus = blankDefault(validationStatus, "CANDIDATE_SOURCE_ONLY");
        confidence = blankDefault(confidence, "LOW");
        matchedJars = matchedJars == null ? "" : matchedJars;
        reasonCode = reasonCode == null ? "" : reasonCode;
        sourceClass = sourceClass == null ? sourceClassFromFile(file) : sourceClass;
        bytecodeClass = bytecodeClass == null ? "" : bytecodeClass;
        ownership = ownership == null ? "" : ownership;
        trunkStatus = trunkStatus == null ? "" : trunkStatus;
        trunkEvidence = trunkEvidence == null ? "" : trunkEvidence;
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sourceClassFromFile(String file) {
        if (file == null || file.isBlank()) return "";
        String normalized = file.replace('\\', '/');
        int javaIdx = normalized.indexOf("/java/");
        if (javaIdx >= 0) normalized = normalized.substring(javaIdx + "/java/".length());
        else if (normalized.startsWith("java/")) normalized = normalized.substring("java/".length());
        if (!normalized.endsWith(".java")) return "";
        normalized = normalized.substring(0, normalized.length() - ".java".length());
        return normalized.replace('/', '.');
    }
}
