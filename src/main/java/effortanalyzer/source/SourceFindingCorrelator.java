package effortanalyzer.source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Correlates source scan findings with generated JAR bytecode evidence for reporting. */
public final class SourceFindingCorrelator {

    private SourceFindingCorrelator() {
    }

    public static List<SourceFinding> correlatedFindings(List<SourceScanResult> results) {
        List<SourceFinding> out = new ArrayList<>();
        for (SourceScanResult result : results) {
            out.addAll(correlate(result));
        }
        return out;
    }

    public static List<SourceFinding> correlate(SourceScanResult result) {
        if (result == null) return List.of();

        List<SourceFinding> sources = result.findings();
        List<GeneratedArtifactScanner.BytecodeFinding> bytecode = result.bytecodeFindings();
        Map<String, List<GeneratedArtifactScanner.BytecodeFinding>> bytecodeByRule = bytecode.stream()
                .collect(Collectors.groupingBy(SourceFindingCorrelator::key, LinkedHashMap::new, Collectors.toList()));

        List<SourceFinding> out = new ArrayList<>();
        Set<GeneratedArtifactScanner.BytecodeFinding> consumed = new LinkedHashSet<>();

        for (SourceFinding source : sources) {
            List<GeneratedArtifactScanner.BytecodeFinding> matches = bytecodeByRule.getOrDefault(key(source), List.of());
            List<GeneratedArtifactScanner.BytecodeFinding> classMatches = matches.stream()
                    .filter(b -> classMatches(source.sourceClass(), b.className()))
                    .toList();
            List<GeneratedArtifactScanner.BytecodeFinding> effective = !classMatches.isEmpty() ? classMatches : matches;

            if (!effective.isEmpty() && isBytecodeCorrelatable(source)) {
                consumed.addAll(effective);
                out.add(source.withValidation("SOURCE_AND_BYTECODE", "CONFIRMED_SOURCE_AND_BYTECODE", "HIGH",
                        true, true, jars(effective), reasonForConfirmed(source, classMatches),
                        classes(effective), ownership(result.component(), effective)));
            } else {
                out.add(sourceOnly(source, result.component()));
            }
        }

        for (GeneratedArtifactScanner.BytecodeFinding b : bytecode) {
            if (consumed.contains(b)) continue;
            out.add(bytecodeOnly(result.component(), b));
        }

        return out;
    }

    private static SourceFinding sourceOnly(SourceFinding f, SourceComponent component) {
        if (isBuildOnly(f)) {
            return f.withValidation("SOURCE", "BUILD_ONLY", "MEDIUM", true, false, "", "BUILD_CONFIGURATION_ONLY", "",
                    ownership(component, List.of()));
        }
        if (isConfigOnly(f)) {
            return f.withValidation("SOURCE", "CONFIG_ONLY", "MEDIUM", true, false, "", "CONFIGURATION_ONLY", "",
                    ownership(component, List.of()));
        }
        if (isTestOnly(f.file())) {
            return f.withValidation("SOURCE", "TEST_ONLY", "LOW", true, false, "", "TEST_SCOPE_ONLY", "",
                    ownership(component, List.of()));
        }
        return f.withValidation("SOURCE", "CANDIDATE_SOURCE_ONLY", "LOW", true, false, "",
                component.generatedJars().isEmpty() ? "NO_GENERATED_JARS_DECLARED" : "SOURCE_ONLY_NOT_IN_COMPILED_JARS",
                "", ownership(component, List.of()));
    }

    private static SourceFinding bytecodeOnly(SourceComponent component, GeneratedArtifactScanner.BytecodeFinding b) {
        return new SourceFinding(
                nonBlank(b.component(), component.component()),
                nonBlank(b.repository(), component.repository()),
                b.repositoryType() == null ? component.type() : b.repositoryType(),
                b.scanner(),
                b.category(),
                b.severity(),
                b.classEntry(),
                -1,
                b.rule(),
                b.description(),
                b.remediation(),
                "Bytecode reference in " + b.className(),
                "BYTECODE",
                "CONFIRMED_BYTECODE_ONLY",
                "HIGH",
                false,
                true,
                b.jarName(),
                "BYTECODE_ONLY_NOT_FOUND_IN_SOURCE_SCAN",
                "",
                b.className(),
                ownership(component, List.of(b)));
    }

    private static String key(SourceFinding f) {
        return normalize(f.component()) + "|" + normalize(f.scanner()) + "|" + normalize(f.category()) + "|" + normalize(f.rule());
    }

    private static String key(GeneratedArtifactScanner.BytecodeFinding f) {
        return normalize(f.component()) + "|" + normalize(f.scanner()) + "|" + normalize(f.category()) + "|" + normalize(f.rule());
    }

    private static boolean isBytecodeCorrelatable(SourceFinding f) {
        return !isBuildOnly(f) && !isConfigOnly(f) && !isTestOnly(f.file());
    }

    private static boolean isBuildOnly(SourceFinding f) {
        return "BUILD_JAVA_LEVEL".equalsIgnoreCase(f.category()) || isBuildFile(f.file());
    }

    private static boolean isConfigOnly(SourceFinding f) {
        String file = lower(f.file());
        return !file.endsWith(".java") && !isBuildFile(file);
    }

    private static boolean isTestOnly(String file) {
        String f = lower(file).replace('\\', '/');
        return f.contains("/src/test/") || f.contains("/test/") || f.contains("/tests/");
    }

    private static boolean isBuildFile(String file) {
        String f = lower(file);
        return f.endsWith("pom.xml") || f.endsWith("build.gradle") || f.endsWith("build.gradle.kts")
                || f.endsWith("gradle.properties") || f.endsWith("maven.config") || f.endsWith("ivy.xml");
    }

    private static boolean classMatches(String sourceClass, String bytecodeClass) {
        if (sourceClass == null || sourceClass.isBlank() || bytecodeClass == null || bytecodeClass.isBlank()) return false;
        return bytecodeClass.equals(sourceClass) || bytecodeClass.startsWith(sourceClass + "$");
    }

    private static String reasonForConfirmed(SourceFinding source, List<GeneratedArtifactScanner.BytecodeFinding> classMatches) {
        return classMatches.isEmpty() || source.sourceClass().isBlank()
                ? "BYTECODE_CONFIRMED_RULE_COMPONENT_API"
                : "BYTECODE_CONFIRMED_SAME_CLASS";
    }

    private static String jars(List<GeneratedArtifactScanner.BytecodeFinding> findings) {
        return findings.stream().map(GeneratedArtifactScanner.BytecodeFinding::jarName)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .collect(Collectors.joining(";"));
    }

    private static String classes(List<GeneratedArtifactScanner.BytecodeFinding> findings) {
        return findings.stream().map(GeneratedArtifactScanner.BytecodeFinding::className)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .limit(10)
                .collect(Collectors.joining(";"));
    }

    private static String ownership(SourceComponent component, List<GeneratedArtifactScanner.BytecodeFinding> findings) {
        String declared = component == null ? "" : component.ownership();
        if (declared != null && !declared.isBlank()) return normalizeOwnership(declared);
        if (component != null && !component.applicationPackages().isEmpty() && !findings.isEmpty()) {
            boolean owned = findings.stream().anyMatch(f -> component.applicationPackages().stream().anyMatch(f.className()::startsWith));
            if (!owned) return "THIRD_PARTY_DEPENDENCY";
        }
        return "APPLICATION_CODE";
    }

    private static String normalizeOwnership(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (v) {
            case "third_party", "thirdparty", "dependency", "third_party_dependency" -> "THIRD_PARTY_DEPENDENCY";
            case "generated", "generated_code" -> "GENERATED_CODE";
            case "test", "test_runtime", "runtime", "test/runtime" -> "TEST_RUNTIME_SCOPE";
            default -> "APPLICATION_CODE";
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String nonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? (fallback == null ? "" : fallback) : first;
    }
}
