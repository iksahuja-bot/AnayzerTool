package effortanalyzer.source;

import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of compiling one component with {@code javac --release 21}.
 *
 * @param status            NOT_RUN, SKIPPED, CLEAN, JAVA21_FINDINGS, INCOMPLETE_CLASSPATH or FAILED
 * @param unresolvedSymbols errors for non-JDK types/packages, i.e. a missing compile classpath entry
 * @param classesDir        compiled output when javac produced it (only on an error-free compile), else {@code null}
 */
public record CompileCheckResult(
        String status,
        String summary,
        int sourceFiles,
        int classpathEntries,
        List<SourceFinding> findings,
        int unresolvedSymbols,
        int otherErrors,
        List<String> sampleUnresolved,
        Path classesDir
) {
    public static final String NOT_RUN = "NOT_RUN";
    public static final String SKIPPED = "SKIPPED";
    public static final String CLEAN = "CLEAN";
    public static final String JAVA21_FINDINGS = "JAVA21_FINDINGS";
    public static final String INCOMPLETE_CLASSPATH = "INCOMPLETE_CLASSPATH";
    public static final String FAILED = "FAILED";

    public CompileCheckResult {
        status = status == null || status.isBlank() ? NOT_RUN : status;
        summary = summary == null ? "" : summary;
        findings = findings == null ? List.of() : List.copyOf(findings);
        sampleUnresolved = sampleUnresolved == null ? List.of() : List.copyOf(sampleUnresolved);
    }

    public static CompileCheckResult notRun() {
        return new CompileCheckResult(NOT_RUN, "", 0, 0, List.of(), 0, 0, List.of(), null);
    }

    public static CompileCheckResult skipped(String reason) {
        return new CompileCheckResult(SKIPPED, reason, 0, 0, List.of(), 0, 0, List.of(), null);
    }

    public static CompileCheckResult failed(String reason) {
        return new CompileCheckResult(FAILED, reason, 0, 0, List.of(), 0, 0, List.of(), null);
    }

    public boolean ran() {
        return !NOT_RUN.equals(status) && !SKIPPED.equals(status) && !FAILED.equals(status);
    }

    public CompileCheckResult withoutClassesDir() {
        return new CompileCheckResult(status, summary, sourceFiles, classpathEntries, findings, unresolvedSymbols,
                otherErrors, sampleUnresolved, null);
    }

    /** One-line text for the Source Inventory sheet. */
    public String display() {
        if (NOT_RUN.equals(status)) return "";
        return summary.isBlank() ? status : status + " — " + summary;
    }
}
