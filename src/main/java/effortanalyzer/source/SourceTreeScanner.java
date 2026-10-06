package effortanalyzer.source;

import effortanalyzer.library.DeprecatedApi;
import effortanalyzer.wljboss.WlJBossRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Direct source-tree scanner for checked-out components. */
public class SourceTreeScanner {

    private final List<DeprecatedApi> libraryRules;
    private final WlJBossRules wlRules;

    public SourceTreeScanner(SourceScanProfile profile) {
        this.libraryRules = profile.libraryRules() == null ? List.of() : profile.libraryRules();
        this.wlRules = profile.wlJBossRules();
    }

    public SourceScanResult scan(CheckoutResult checkout) throws IOException {
        if (!checkout.success() || checkout.checkoutPath() == null || !Files.exists(checkout.checkoutPath())) {
            return new SourceScanResult(checkout.component(), checkout, 0, List.of());
        }

        List<SourceFinding> findings = new ArrayList<>();
        int filesScanned = 0;
        try (Stream<Path> stream = Files.walk(checkout.checkoutPath())) {
            for (Path file : stream.filter(Files::isRegularFile).filter(this::isScannable).toList()) {
                filesScanned++;
                scanFile(checkout.component(), checkout.checkoutPath(), file, findings);
            }
        }
        return new SourceScanResult(checkout.component(), checkout, filesScanned, findings);
    }

    private void scanFile(SourceComponent component, Path root, Path file, List<SourceFinding> findings) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
            } catch (IOException ignored) {
                return;
            }
        }

        Map<String, String> imports = collectImports(lines);
        boolean java = relative.toLowerCase(Locale.ROOT).endsWith(".java");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNumber = i + 1;
            if (java && !libraryRules.isEmpty()) scanLibraryRules(component, relative, line, lineNumber, imports, findings);
            if (wlRules != null) scanWlRules(component, relative, line, lineNumber, findings);
        }
    }

    private void scanLibraryRules(SourceComponent component, String file, String line, int lineNumber,
                                  Map<String, String> imports, List<SourceFinding> findings) {
        String trimmed = line.trim();
        boolean isImport = trimmed.startsWith("import ");
        for (DeprecatedApi rule : libraryRules) {
            String cls = rule.className();
            boolean packageRule = cls.endsWith(".*") || Character.isLowerCase(cls.substring(cls.lastIndexOf('.') + 1).charAt(0));
            boolean matched = false;
            String contextPrefix = "Usage: ";

            if (packageRule) {
                String pkg = cls.replace(".*", "");
                matched = isImport && trimmed.contains(pkg);
                contextPrefix = "Package import: ";
            } else if (isImport) {
                matched = trimmed.contains(cls);
                contextPrefix = "Import: ";
            } else {
                String simpleName = cls.substring(cls.lastIndexOf('.') + 1);
                String mapped = imports.get(simpleName);
                if (mapped == null || mapped.equals(cls)) {
                    matched = Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(trimmed).find();
                }
                if (matched && rule.methodName() != null && !rule.methodName().isBlank()) {
                    matched = Pattern.compile("\\b" + Pattern.quote(rule.methodName()) + "\\s*\\(").matcher(trimmed).find();
                    contextPrefix = "Method call: ";
                }
            }

            if (matched) {
                findings.add(new SourceFinding(component.component(), component.repository(), component.type(),
                        "Library", rule.library(), normalizeSeverity(rule.severity()), file, lineNumber,
                        cls + (rule.methodName() == null ? "" : "#" + rule.methodName()),
                        rule.description(), rule.replacement(), contextPrefix + trimmed));
            }
        }
    }

    private void scanWlRules(SourceComponent component, String file, String line, int lineNumber, List<SourceFinding> findings) {
        String trimmed = line.trim();
        if (trimmed.isBlank()) return;
        for (WlJBossRules.Rule rule : wlRules.getRules()) {
            if (rule.scanMode() == WlJBossRules.ScanMode.BYTECODE) continue;
            if (trimmed.contains(rule.apiPattern())) {
                findings.add(new SourceFinding(component.component(), component.repository(), component.type(),
                        "WL-JBoss", rule.category(), rule.severity(), file, lineNumber, rule.apiPattern(),
                        rule.description(), rule.remediation(), trimmed));
            }
        }
    }

    private static Map<String, String> collectImports(List<String> lines) {
        Map<String, String> imports = new HashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ") || trimmed.startsWith("import static ")) continue;
            String fqn = trimmed.substring("import ".length()).replace(";", "").trim();
            int dot = fqn.lastIndexOf('.');
            if (dot > 0) imports.put(fqn.substring(dot + 1), fqn);
        }
        return imports;
    }

    private boolean isScannable(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".java") || n.endsWith(".xml") || n.endsWith(".properties") || n.endsWith(".yaml")
                || n.endsWith(".yml") || n.equals("pom.xml") || n.equals("build.gradle") || n.equals("build.gradle.kts")
                || n.equals("ivy.xml") || n.equals("web.xml") || n.equals("weblogic.xml") || n.equals("application.xml");
    }

    private static String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) return "INFO";
        return severity.equalsIgnoreCase("WARNING") ? "MEDIUM" : severity.toUpperCase(Locale.ROOT);
    }
}

