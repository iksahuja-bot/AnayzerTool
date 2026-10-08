package effortanalyzer.source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Compares each finding of the fixable checkout with the trunk/reference checkout.
 *
 * <p>Java findings are matched by fully qualified class (from the {@code package} declaration, so
 * different module layouts still line up). Pattern-rule findings compare against the trunk scan;
 * compiler, JDK-tool and bytecode findings compare against the API text in the trunk class.</p>
 */
final class TrunkComparator {

    static final String SAME_VALIDATED = "TRUNK_SAME_VALIDATED";
    static final String SAME = "TRUNK_SAME";
    static final String FIXED = "TRUNK_FIXED";
    static final String REMOVED = "TRUNK_REMOVED";
    static final String NOT_COMPARED = "NOT_COMPARED";

    private final SourceComponent component;
    private final SourceFileIndex currentIndex;
    private final SourceFileIndex trunkIndex;
    private final Map<String, List<SourceFinding>> trunkByRule = new LinkedHashMap<>();

    private TrunkComparator(SourceScanResult result) {
        this.component = result.component();
        this.currentIndex = SourceFileIndex.of(result.checkout() == null ? null : result.checkout().checkoutPath());
        this.trunkIndex = SourceFileIndex.of(result.trunkCheckout().checkoutPath());
        for (SourceFinding t : result.trunkFindings()) {
            trunkByRule.computeIfAbsent(ruleKey(t), k -> new ArrayList<>()).add(t);
        }
    }

    static List<SourceFinding> annotate(SourceScanResult result, List<SourceFinding> findings) {
        if (findings.isEmpty()) return findings;
        if (result == null || !result.trunkAvailable()) {
            String reason = notComparedReason(result);
            return findings.stream().map(f -> f.withTrunk(NOT_COMPARED, reason)).toList();
        }
        TrunkComparator comparator = new TrunkComparator(result);
        return findings.stream().map(comparator::compare).toList();
    }

    SourceFinding compare(SourceFinding f) {
        String file = f.file() == null ? "" : f.file().replace('\\', '/');
        String lower = file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java") || lower.endsWith(".class") || !f.bytecodeClass().isBlank() && !f.matchedInSource()) {
            return compareJava(f, file);
        }
        return compareFile(f, file);
    }

    private SourceFinding compareJava(SourceFinding f, String file) {
        String className = className(f, file);
        if (className.isBlank()) return f.withTrunk(NOT_COMPARED, "Could not resolve the class for " + file);

        Optional<Path> trunkFile = trunkIndex.findClass(className);
        if (trunkFile.isEmpty()) {
            return f.withTrunk(REMOVED, "Class " + className + " is not present in trunk; trunk restructured or removed this code.");
        }
        String trunkRel = trunkIndex.relative(trunkFile.get());
        if (comparesAgainstTrunkScan(f)) {
            Optional<SourceFinding> same = trunkByRule.getOrDefault(ruleKey(f), List.of()).stream()
                    .filter(t -> trunkRel.equals(normalizePath(t.file())))
                    .findFirst();
            if (same.isPresent()) return f.withTrunk(same(), "Trunk " + location(trunkRel, same.get().line()) + " has the same finding.");
            return f.withTrunk(FIXED, "Trunk " + trunkRel + " no longer matches rule '" + f.rule() + "'. Port that change.");
        }
        int line = SourceFileIndex.findLine(trunkFile.get(), f.rule());
        if (line > 0) return f.withTrunk(same(), "Trunk " + location(trunkRel, line) + " still uses " + f.rule() + ".");
        return f.withTrunk(FIXED, "Trunk " + trunkRel + " no longer uses " + f.rule() + ". Port that change.");
    }

    private SourceFinding compareFile(SourceFinding f, String file) {
        String fileName = file.contains("/") ? file.substring(file.lastIndexOf('/') + 1) : file;
        if (fileName.isBlank()) return f.withTrunk(NOT_COMPARED, "Finding has no file to compare");
        Optional<SourceFinding> same = trunkByRule.getOrDefault(ruleKey(f), List.of()).stream()
                .filter(t -> normalizePath(t.file()).equals(file))
                .findFirst()
                .or(() -> trunkByRule.getOrDefault(ruleKey(f), List.of()).stream()
                        .filter(t -> normalizePath(t.file()).endsWith("/" + fileName) || normalizePath(t.file()).equals(fileName))
                        .findFirst());
        if (same.isPresent()) {
            return f.withTrunk(same(), "Trunk " + location(normalizePath(same.get().file()), same.get().line()) + " has the same finding.");
        }
        List<Path> candidates = trunkIndex.filesNamed(fileName);
        if (candidates.isEmpty()) return f.withTrunk(REMOVED, fileName + " is not present in trunk.");
        Path exact = trunkIndex.root().resolve(file);
        String trunkRel = candidates.contains(exact) ? file : trunkIndex.relative(candidates.get(0));
        return f.withTrunk(FIXED, "Trunk " + trunkRel + " no longer matches rule '" + f.rule() + "'. Port that change.");
    }

    private String className(SourceFinding f, String file) {
        String lower = file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            String resolved = currentIndex.classNameOf(file);
            return resolved.isBlank() ? f.sourceClass() : resolved;
        }
        if (!f.bytecodeClass().isBlank() && !f.bytecodeClass().contains(";")) return f.bytecodeClass();
        if (lower.endsWith(".class")) return SourceFileIndex.classNameFromEntry(file);
        return "";
    }

    private String same() {
        return component.isTrunkValidated() ? SAME_VALIDATED : SAME;
    }

    private static boolean comparesAgainstTrunkScan(SourceFinding f) {
        return f.matchedInSource()
                && !CompileChecker.SCANNER.equals(f.scanner())
                && !JdkToolScanner.SCANNER.equals(f.scanner());
    }

    private static String ruleKey(SourceFinding f) {
        return lower(f.scanner()) + "|" + lower(f.category()) + "|" + lower(f.rule());
    }

    private static String location(String file, int line) {
        return line > 0 ? file + ":" + line : file;
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String notComparedReason(SourceScanResult result) {
        if (result == null) return "No scan result";
        if (result.component().trunk().isBlank()) return "No Trunk value in the inventory";
        CheckoutResult trunk = result.trunkCheckout();
        if (trunk == null) return "Trunk was not checked out";
        if (trunk.success()) return "Trunk workspace not found: " + trunk.checkoutPath();
        return "Trunk checkout " + trunk.status() + (trunk.message() == null || trunk.message().isBlank() ? "" : ": " + trunk.message());
    }
}
