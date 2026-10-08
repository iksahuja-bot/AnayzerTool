package effortanalyzer.source;

import effortanalyzer.library.DeprecatedApi;
import effortanalyzer.wljboss.WlJBossRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Direct source-tree scanner for checked-out components. */
public class SourceTreeScanner {

    private static final String NEW_INSTANCE_PATTERN = "newInstance()";
    private static final Pattern NEW_INSTANCE_CALL = Pattern.compile("\\.\\s*newInstance\\s*\\(\\s*\\)");
    private static final Pattern CONSTRUCTOR_VARIABLE = Pattern.compile("\\bConstructor\\s*(?:<[^;=()]*>)?\\s+([A-Za-z_$][\\w$]*)\\s*[=;,)]");

    private final List<DeprecatedApi> libraryRules;
    private final WlJBossRules wlRules;
    private final List<Java21SourceRule> java21Rules;

    public SourceTreeScanner(SourceScanProfile profile) {
        this.libraryRules = profile.libraryRules() == null ? List.of() : profile.libraryRules();
        this.wlRules = profile.wlJBossRules();
        this.java21Rules = profile.java21Rules() == null ? List.of() : profile.java21Rules();
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

        List<String> codeOnlyLines = stripJavaCommentsAndLiterals(lines);
        Map<String, String> imports = collectImports(codeOnlyLines);
        boolean java = relative.toLowerCase(Locale.ROOT).endsWith(".java");
        Set<String> constructorVars = java ? collectConstructorVariables(codeOnlyLines) : Set.of();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String codeOnly = i < codeOnlyLines.size() ? codeOnlyLines.get(i) : "";
            int lineNumber = i + 1;
            if (java && !libraryRules.isEmpty()) scanLibraryRules(component, relative, line, codeOnly, lineNumber, imports, findings);
            if (wlRules != null) scanWlRules(component, relative, line, lineNumber, findings);
            if (!java21Rules.isEmpty()) scanJava21Rules(component, relative, java, line, codeOnly, lineNumber, constructorVars, findings);
        }
    }

    private static Set<String> collectConstructorVariables(List<String> codeOnlyLines) {
        Set<String> vars = new HashSet<>();
        for (String line : codeOnlyLines) {
            Matcher m = CONSTRUCTOR_VARIABLE.matcher(line);
            while (m.find()) vars.add(m.group(1));
        }
        return vars;
    }

    /**
     * True when a {@code newInstance()} call is plausibly the deprecated {@code Class.newInstance()}:
     * excludes {@code getDeclaredConstructor(..).newInstance()}, {@code Constructor}-typed receivers,
     * and static factories such as {@code DocumentBuilderFactory.newInstance()}.
     */
    static boolean isClassNewInstanceCall(String codeOnly, Set<String> constructorVars) {
        Matcher m = NEW_INSTANCE_CALL.matcher(codeOnly);
        while (m.find()) {
            int i = m.start() - 1;
            while (i >= 0 && Character.isWhitespace(codeOnly.charAt(i))) i--;
            if (i < 0) continue;
            if (codeOnly.charAt(i) == ')') {
                int depth = 0;
                for (; i >= 0; i--) {
                    char c = codeOnly.charAt(i);
                    if (c == ')') depth++;
                    else if (c == '(' && --depth == 0) break;
                }
                i--;
                while (i >= 0 && Character.isWhitespace(codeOnly.charAt(i))) i--;
                String method = identifierEndingAt(codeOnly, i);
                if (method.equals("getDeclaredConstructor") || method.equals("getConstructor")) continue;
                return true;
            }
            String receiver = identifierEndingAt(codeOnly, i);
            if (receiver.isEmpty() || constructorVars.contains(receiver)) continue;
            if (receiver.equals("class") || Character.isLowerCase(receiver.charAt(0)) || receiver.charAt(0) == '_') return true;
        }
        return false;
    }

    private static String identifierEndingAt(String text, int end) {
        int start = end;
        while (start >= 0 && Character.isJavaIdentifierPart(text.charAt(start))) start--;
        return end < 0 ? "" : text.substring(start + 1, end + 1);
    }

    private void scanLibraryRules(SourceComponent component, String file, String line, String codeOnlyLine, int lineNumber,
                                  Map<String, String> imports, List<SourceFinding> findings) {
        String trimmed = line.trim();
        String codeOnly = codeOnlyLine.trim();
        boolean isImport = codeOnly.startsWith("import ");
        if (!isImport && codeOnly.isBlank()) return;

        for (DeprecatedApi rule : libraryRules) {
            String cls = rule.className();
            boolean packageRule = cls.endsWith(".*") || Character.isLowerCase(cls.substring(cls.lastIndexOf('.') + 1).charAt(0));
            boolean matched = false;
            String contextPrefix = "Usage: ";

            if (packageRule) {
                String pkg = cls.replace(".*", "");
                matched = isImport && codeOnly.contains(pkg);
                contextPrefix = "Package import: ";
            } else if (isImport) {
                matched = codeOnly.contains(cls);
                contextPrefix = "Import: ";
            } else {
                String simpleName = cls.substring(cls.lastIndexOf('.') + 1);
                String mapped = imports.get(simpleName);
                matched = containsFullyQualifiedName(codeOnly, cls)
                        || (cls.equals(mapped) && containsSimpleName(codeOnly, simpleName));
                if (matched && rule.methodName() != null && !rule.methodName().isBlank()) {
                    matched = Pattern.compile("\\b" + Pattern.quote(rule.methodName()) + "\\s*\\(").matcher(codeOnly).find();
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

    private static boolean containsFullyQualifiedName(String code, String className) {
        return Pattern.compile("(?<![\\w$])" + Pattern.quote(className) + "(?![\\w$])").matcher(code).find();
    }

    private static boolean containsSimpleName(String code, String simpleName) {
        return Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(code).find();
    }

    private static List<String> stripJavaCommentsAndLiterals(List<String> lines) {
        List<String> stripped = new ArrayList<>(lines.size());
        boolean inBlockComment = false;

        for (String line : lines) {
            StringBuilder code = new StringBuilder(line.length());
            boolean inString = false;
            boolean inChar = false;
            boolean escaped = false;

            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';

                if (inBlockComment) {
                    if (c == '*' && next == '/') {
                        inBlockComment = false;
                        i++;
                    }
                    continue;
                }

                if (!inString && !inChar && c == '/' && next == '/') {
                    break;
                }
                if (!inString && !inChar && c == '/' && next == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }

                if (escaped) {
                    code.append(' ');
                    escaped = false;
                    continue;
                }
                if (c == '\\' && (inString || inChar)) {
                    code.append(' ');
                    escaped = true;
                    continue;
                }
                if (!inChar && c == '"') {
                    code.append(c);
                    inString = !inString;
                } else if (!inString && c == '\'') {
                    code.append(c);
                    inChar = !inChar;
                } else if (inString || inChar) {
                    code.append(' ');
                } else {
                    code.append(c);
                }
            }

            stripped.add(code.toString());
        }

        return stripped;
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

    private void scanJava21Rules(SourceComponent component, String file, boolean java, String line, String codeOnlyLine,
                                 int lineNumber, Set<String> constructorVars, List<SourceFinding> findings) {
        String trimmed = line.trim();
        String codeOnly = codeOnlyLine.trim();
        if (trimmed.isBlank() && codeOnly.isBlank()) return;

        for (Java21SourceRule rule : java21Rules) {
            boolean matched = false;
            String context = trimmed;

            if (java && rule.scansJava()) {
                matched = !codeOnly.isBlank() && containsPattern(codeOnly, rule.apiPattern());
                if (matched && NEW_INSTANCE_PATTERN.equals(rule.apiPattern())) {
                    matched = isClassNewInstanceCall(codeOnly, constructorVars);
                }
                context = codeOnly;
            }

            if (!matched && !java && rule.scansText()) {
                matched = containsPattern(trimmed, rule.apiPattern());
                context = trimmed;
            }

            if (!matched && rule.scanTarget() == Java21SourceRule.ScanTarget.BUILD_JAVA_LEVEL && isBuildFile(file)) {
                matched = declaresJavaLevelBelow21(trimmed);
                context = trimmed;
            }

            if (matched) {
                findings.add(new SourceFinding(component.component(), component.repository(), component.type(),
                        "Java 21", rule.category(), normalizeSeverity(rule.severity()), file, lineNumber,
                        rule.apiPattern(), rule.description(), rule.remediation(), context));
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
                || n.equals("ivy.xml") || n.equals("web.xml") || n.equals("weblogic.xml") || n.equals("application.xml")
                || n.equals("gradle.properties") || n.equals("maven.config") || n.endsWith(".sh") || n.endsWith(".bat")
                || n.endsWith(".cmd") || n.endsWith(".conf") || n.endsWith(".config") || n.endsWith(".ini")
                || n.endsWith(".env") || n.endsWith(".vmoptions");
    }

    private static boolean containsPattern(String text, String pattern) {
        if (text == null || pattern == null || pattern.isBlank()) return false;
        if (text.contains(pattern) && hasPatternBoundary(text, pattern)) return true;

        StringBuilder normalizedPattern = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '.') {
                normalizedPattern.append("\\s*\\.\\s*");
            } else {
                normalizedPattern.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (!pattern.endsWith(".")) {
            normalizedPattern.insert(0, "(?<![A-Za-z0-9_$])");
            normalizedPattern.append("(?![A-Za-z0-9_$])");
        }
        return Pattern.compile(normalizedPattern.toString()).matcher(text).find();
    }

    private static boolean hasPatternBoundary(String text, String pattern) {
        if (pattern.endsWith(".")) return true;
        int start = text.indexOf(pattern);
        while (start >= 0) {
            int end = start + pattern.length();
            boolean startBoundary = start == 0 || !isJavaIdentifierPart(text.charAt(start - 1));
            boolean endBoundary = end >= text.length() || !isJavaIdentifierPart(text.charAt(end));
            if (startBoundary && endBoundary) return true;
            start = text.indexOf(pattern, start + 1);
        }
        return false;
    }

    private static boolean isJavaIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean isBuildFile(String file) {
        String f = file.toLowerCase(Locale.ROOT);
        return f.endsWith("pom.xml") || f.endsWith("build.gradle") || f.endsWith("build.gradle.kts")
                || f.endsWith("gradle.properties") || f.endsWith("maven.config");
    }

    private static boolean declaresJavaLevelBelow21(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        if (l.contains("maven.compiler.release") || l.contains("maven.compiler.source")
                || l.contains("maven.compiler.target") || l.contains("<release>") || l.contains("<source>")
                || l.contains("<target>") || l.contains("sourcecompatibility") || l.contains("targetcompatibility")
                || l.contains("javalanguageversion") || l.contains("languageversion") || l.contains("options.release")) {
            return extractJavaVersions(line).stream().anyMatch(v -> v > 0 && v < 21);
        }
        return false;
    }

    private static List<Integer> extractJavaVersions(String line) {
        List<Integer> versions = new ArrayList<>();
        var matcher = Pattern.compile("(?<![\\d.])(?:1\\.)?([0-9]{1,2})(?![\\d.])").matcher(line);
        while (matcher.find()) {
            try {
                int v = Integer.parseInt(matcher.group(1));
                if (v >= 5 && v <= 25) versions.add(v);
            } catch (NumberFormatException ignored) {
                // ignore non-version numeric fragments
            }
        }
        return versions;
    }

    private static String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) return "INFO";
        return severity.equalsIgnoreCase("WARNING") ? "MEDIUM" : severity.toUpperCase(Locale.ROOT);
    }
}

