package effortanalyzer.source;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compiles a component's main sources with {@code javac --release 21} and turns the diagnostics into
 * owner-resolved Java 21 findings (removed, internal, unsupported, deprecated APIs and language changes).
 *
 * <p>Errors for non-JDK types mean the compile classpath is incomplete; they are counted, never
 * reported as findings. The compiler is ground truth for what it reports, so these findings are
 * {@code CONFIRMED_COMPILER} with HIGH confidence.</p>
 */
public class CompileChecker {

    private static final Logger logger = LogManager.getLogger(CompileChecker.class);

    public static final String SCANNER = "Compile Check";
    public static final String JAVA21_LANGUAGE = "JAVA21_LANGUAGE";
    public static final String BUILD_CLASSPATH = "BUILD_CLASSPATH";

    private static final String RELEASE = "21";
    private static final long MAVEN_TIMEOUT_MINUTES = 20;
    private static final String MAVEN_CLASSPATH_FILE = "ea-compile-classpath.txt";
    private static final int SAMPLE_LIMIT = 10;

    private static final Set<String> JAR_SKIPPED_DIRECTORIES = Set.of(".svn", ".git", ".hg", "node_modules");
    private static final Set<String> TEST_ONLY_DIRECTORIES = Set.of("unit-test", "unittest", "testsrc", "test-src", "src-test");
    private static final Set<String> TEST_DIRECTORIES = Set.of("test", "tests", "it");

    private static final Pattern DOESNT_EXIST = Pattern.compile("package (\\S+) does not exist");
    private static final Pattern NOT_VISIBLE = Pattern.compile("package (\\S+) is not visible");
    private static final Pattern DECLARED_IN_MODULE = Pattern.compile("declared in module ([\\w.]+)");
    private static final Pattern SYMBOL = Pattern.compile("symbol:\\s+(?:(class|interface|enum|record|method|variable|constructor|static)\\s+)?(\\S+)");
    private static final Pattern LOCATION = Pattern.compile(
            "location:\\s+(?:(package|class|interface|enum|record)\\s+([\\w.$]+)|.*?\\bof type\\s+([\\w.$]+))");
    private static final Pattern DEPRECATED = Pattern.compile("^(.+?) in ([\\w.$]+) has been deprecated");
    private static final Pattern PROPRIETARY = Pattern.compile("^(\\S+) is internal proprietary API");

    /** Java EE / CORBA packages removed from Java SE in JDK 11. */
    private static final List<String> REMOVED_JAVA_EE_PACKAGES = List.of("javax.xml.bind", "javax.xml.ws", "javax.xml.soap",
            "javax.activation", "javax.jws", "javax.annotation", "javax.transaction", "javax.rmi.CORBA", "javax.activity", "org.omg");
    /** com.sun.* packages shipped by libraries rather than the JDK. */
    private static final List<String> THIRD_PARTY_COM_SUN = List.of("com.sun.xml.bind", "com.sun.xml.ws", "com.sun.xml.messaging",
            "com.sun.mail", "com.sun.jersey", "com.sun.faces", "com.sun.istack", "com.sun.codemodel", "com.sun.tools.xjc",
            "com.sun.activation", "com.sun.xml.fastinfoset", "com.sun.xml.stream.buffer", "com.sun.research");
    private static final Set<String> JDK_PACKAGES = ModuleLayer.boot().modules().stream()
            .flatMap(m -> m.getPackages().stream())
            .collect(Collectors.toUnmodifiableSet());

    /** Resolves extra compile classpath entries for a checkout (Maven by default). */
    @FunctionalInterface
    public interface ClasspathResolver {
        List<Path> resolve(Path checkoutRoot) throws IOException, InterruptedException;
    }

    private final String module;
    private final List<Path> extraClasspath;
    private final ClasspathResolver mavenResolver;

    /** @param mavenResolver resolves Maven dependencies when the checkout has a pom.xml; {@code null} skips Maven */
    public CompileChecker(String module, List<Path> extraClasspath, ClasspathResolver mavenResolver) {
        this.module = module == null ? "" : module;
        this.extraClasspath = extraClasspath == null ? List.of() : List.copyOf(extraClasspath);
        this.mavenResolver = mavenResolver;
    }

    public static CompileChecker create(String module, List<Path> extraClasspath, Path mavenSettings) {
        return new CompileChecker(module, extraClasspath, root -> resolveMavenClasspath(root, mavenSettings));
    }

    public CompileCheckResult check(SourceComponent component, Path checkoutRoot) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) return CompileCheckResult.skipped("javac is not available (running on a JRE)");
        if (checkoutRoot == null || !Files.isDirectory(checkoutRoot)) return CompileCheckResult.skipped("checkout not available");

        Path out = null;
        try {
            List<Path> sources = mainSources(checkoutRoot);
            if (sources.isEmpty()) return CompileCheckResult.skipped("no main Java sources");
            List<Path> classpath = classpath(checkoutRoot);
            Charset encoding = detectEncoding(sources);
            out = Files.createTempDirectory("ea-javac-");

            List<String> options = new ArrayList<>(List.of("--release", RELEASE, "-Xlint:deprecation,removal",
                    "-proc:none", "-implicit:none", "-encoding", encoding.name(),
                    "-Xmaxerrs", "100000", "-Xmaxwarns", "100000",
                    "-XDshould-stop.ifError=FLOW", "-d", out.toString()));
            if (!classpath.isEmpty()) {
                options.add("-classpath");
                options.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
            }

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            boolean success;
            try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, Locale.ROOT, encoding)) {
                Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);
                success = Boolean.TRUE.equals(javac.getTask(new StringWriter(), fm, diagnostics, options, null, units).call());
            }

            Classifier classifier = new Classifier(component, checkoutRoot);
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) classifier.accept(d);

            Path classesDir = success ? out : null;
            if (!success) deleteRecursively(out);
            CompileCheckResult result = classifier.result(sources.size(), classpath.size(), classesDir);
            logger.info("Compile check for {}: {}", component.displayName(), result.display());
            return result;
        } catch (IOException | RuntimeException e) {
            deleteRecursively(out);
            logger.warn("Compile check failed for {}: {}", component.displayName(), e.toString());
            return CompileCheckResult.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Deletes the temporary javac output directory of {@code result}, if any. */
    public static void deleteOutput(CompileCheckResult result) {
        if (result != null) deleteRecursively(result.classesDir());
    }

    /**
     * Confirms pattern findings that javac reports on the same line for the same API, and appends the
     * compiler-only findings. Each issue therefore appears once, with compiler confirmation when available.
     */
    public static List<SourceFinding> mergeWithSourceFindings(List<SourceFinding> patternFindings, List<SourceFinding> compileFindings) {
        List<SourceFinding> merged = new ArrayList<>();
        Set<SourceFinding> consumed = new LinkedHashSet<>();
        Map<String, List<SourceFinding>> byLine = new LinkedHashMap<>();
        for (SourceFinding c : compileFindings == null ? List.<SourceFinding>of() : compileFindings) {
            byLine.computeIfAbsent(lineKey(c), k -> new ArrayList<>()).add(c);
        }
        for (SourceFinding p : patternFindings == null ? List.<SourceFinding>of() : patternFindings) {
            List<SourceFinding> related = byLine.getOrDefault(lineKey(p), List.of()).stream()
                    .filter(c -> sameApi(p.rule(), c.rule()))
                    .toList();
            if (related.isEmpty()) {
                merged.add(p);
                continue;
            }
            consumed.addAll(related);
            merged.add(p.withValidation("SOURCE_AND_COMPILER", "CONFIRMED_SOURCE_AND_COMPILER", "HIGH",
                    true, false, "", "JAVAC_RELEASE_21_SAME_LINE", "", p.ownership()));
        }
        for (SourceFinding c : compileFindings == null ? List.<SourceFinding>of() : compileFindings) {
            if (!consumed.contains(c)) merged.add(c);
        }
        return merged;
    }

    static boolean isCompilerConfirmed(SourceFinding f) {
        return f != null && f.detectionSource() != null && f.detectionSource().contains("COMPILER");
    }

    private static String lineKey(SourceFinding f) {
        return (f.file() == null ? "" : f.file().replace('\\', '/')) + ":" + f.line();
    }

    private static boolean sameApi(String patternRule, String compileRule) {
        String p = normalizeApi(patternRule);
        String c = normalizeApi(compileRule);
        if (p.isEmpty() || c.isEmpty()) return false;
        String cLast = c.substring(c.lastIndexOf('.') + 1);
        return c.contains(p) || p.contains(c) || p.endsWith(cLast) && !cLast.isEmpty();
    }

    private static String normalizeApi(String rule) {
        if (rule == null) return "";
        String r = rule.trim();
        if (r.startsWith("new ")) r = r.substring("new ".length());
        if (r.endsWith("(...)")) r = r.substring(0, r.length() - "(...)".length());
        if (r.endsWith("()")) r = r.substring(0, r.length() - 2);
        if (r.contains("#")) r = r.replace('#', '.');
        while (r.endsWith(".") || r.endsWith("*")) r = r.substring(0, r.length() - 1);
        return r;
    }

    // ── Diagnostic classification ────────────────────────────────────────────

    private final class Classifier {
        private final SourceComponent component;
        private final Path root;
        private final Map<String, SourceFinding> findings = new LinkedHashMap<>();
        /** Missing JDK members; trusted only in files where every non-JDK type resolved. */
        private final Map<String, SourceFinding> memberFindings = new LinkedHashMap<>();
        private final Set<String> filesWithUnresolved = new LinkedHashSet<>();
        private final Set<String> unresolvedSamples = new LinkedHashSet<>();
        private int unresolved;
        private int otherErrors;

        Classifier(SourceComponent component, Path root) {
            this.component = component;
            this.root = root;
        }

        void accept(Diagnostic<? extends JavaFileObject> d) {
            String code = d.getCode() == null ? "" : d.getCode();
            String message = d.getMessage(Locale.ROOT);
            message = message == null ? "" : message;
            boolean error = d.getKind() == Diagnostic.Kind.ERROR;
            String file = relativeFile(d.getSource());
            int line = d.getLineNumber() > 0 ? (int) d.getLineNumber() : -1;

            if (isLanguageChange(code)) {
                add(file, line, JAVA21_LANGUAGE, "CRITICAL", code.replaceFirst("^compiler\\.(err|warn)\\.", ""),
                        "javac --release 21: " + firstLine(message),
                        "Rename the identifier or adjust the syntax; this is a Java language change since Java 8.", message);
                return;
            }
            switch (code) {
                case "compiler.err.doesnt.exist" -> missingPackage(file, line, message);
                case "compiler.err.package.not.visible" -> notVisible(file, line, message);
                case "compiler.warn.has.been.deprecated", "compiler.warn.has.been.deprecated.for.removal" ->
                        deprecated(file, line, message, code.endsWith("for.removal"));
                case "compiler.warn.sun.proprietary" -> proprietary(file, line, message);
                default -> {
                    if (code.startsWith("compiler.err.cant.resolve")) {
                        cannotResolve(file, line, message);
                    } else if (error) {
                        if (code.startsWith("compiler.err.cant.access") || code.equals("compiler.err.cant.apply.symbol")
                                || code.equals("compiler.err.cant.inherit.from.final") || code.contains("not.def.public")) {
                            unresolved(file, message);
                        } else {
                            otherErrors++;
                        }
                    }
                }
            }
        }

        private void missingPackage(String file, int line, String message) {
            Matcher m = DOESNT_EXIST.matcher(message);
            String pkg = m.find() ? m.group(1) : "";
            if ("wl14".equalsIgnoreCase(module) && TargetPlatformPolicy.isWl14Provided(pkg)) {
                String artifact = TargetPlatformPolicy.wl14ApiArtifact(pkg);
                add(file, line, BUILD_CLASSPATH, "INFO", pkg,
                        "javac --release 21: package " + pkg + " is not on the compile classpath. JDK 21 no longer ships it; WebLogic 14.1.2 provides the Java EE API at runtime.",
                        "No code change: keep javax.*. Declare " + artifact + " with scope 'provided' (or the library that supplies "
                                + pkg + ", e.g. jsr305 for javax.annotation.Nonnull) on the compile classpath.",
                        message);
            } else if (isJdkOwned(pkg)) {
                add(file, line, pkg.startsWith("sun.") || pkg.startsWith("com.sun.") ? JdkToolScanner.JDK_REMOVED_INTERNAL_API : JdkToolScanner.JDK_REMOVED_API,
                        "CRITICAL", pkg, "javac --release 21: package " + pkg + " does not exist in JDK 21.",
                        JdkToolScanner.removedClassRemediation(pkg + "."), message);
            } else {
                unresolved(file, message);
            }
        }

        private void notVisible(String file, int line, String message) {
            Matcher m = NOT_VISIBLE.matcher(message);
            String pkg = m.find() ? m.group(1) : "";
            Matcher mod = DECLARED_IN_MODULE.matcher(message);
            String owningModule = mod.find() ? mod.group(1) : "the JDK";
            add(file, line, JdkToolScanner.JDK_INTERNAL_API, "HIGH", pkg,
                    "javac --release 21: package " + pkg + " is declared in " + owningModule + ", which does not export it.",
                    "Replace with a supported public API. --add-exports " + owningModule + "/" + pkg + "=ALL-UNNAMED is only a stopgap.",
                    message);
        }

        private void cannotResolve(String file, int line, String message) {
            Matcher sym = SYMBOL.matcher(message);
            Matcher loc = LOCATION.matcher(message);
            if (!sym.find() || !loc.find()) {
                unresolved(file, message);
                return;
            }
            String symbolKind = sym.group(1) == null ? "" : sym.group(1);
            String symbol = sym.group(2);
            boolean packageLocation = "package".equals(loc.group(1));
            String owner = loc.group(2) != null ? loc.group(2) : loc.group(3);
            if (owner == null || !isJdkOwned(owner)) {
                unresolved(file, message);
                return;
            }
            String name = memberName(symbol);
            String api = switch (symbolKind) {
                case "method" -> owner + "." + name + "()";
                case "constructor" -> "new " + owner + "(...)";
                default -> owner + "." + name;
            };
            String category = packageLocation && (owner.startsWith("sun.") || owner.startsWith("com.sun."))
                    ? JdkToolScanner.JDK_REMOVED_INTERNAL_API : JdkToolScanner.JDK_REMOVED_API;
            if (packageLocation) {
                add(file, line, category, "CRITICAL", api, "javac --release 21: " + api + " does not exist in JDK 21.",
                        JdkToolScanner.removedClassRemediation(api), message);
                return;
            }
            // A missing member can also mean a project type failed to resolve and the name fell back to a JDK type
            // (e.g. java.sql.JDBCType via an on-demand import), so it is confirmed only after the whole file resolves.
            memberFindings.putIfAbsent(file + "|" + line + "|" + api, finding(file, line, category, "CRITICAL", api,
                    "javac --release 21: " + api + " does not exist in JDK 21.",
                    "Remove or replace the call; see the JDK 21 Javadoc and release notes for the removed member.", message));
        }

        private void deprecated(String file, int line, String message, boolean forRemoval) {
            Matcher m = DEPRECATED.matcher(message);
            if (!m.find()) return;
            String symbol = m.group(1).trim();
            String owner = m.group(2);
            if (!isJdkOwned(owner)) return;
            String api;
            if (symbol.contains("(")) {
                String name = memberName(symbol);
                String simpleOwner = owner.substring(owner.lastIndexOf('.') + 1);
                api = name.equals(simpleOwner) ? "new " + owner + "(...)" : owner + "." + name + "()";
            } else {
                api = symbol.contains(".") ? symbol : owner + "." + symbol;
            }
            String unsupported = forRemoval ? JdkToolScanner.unsupportedAtRuntime(api) : "";
            if (!unsupported.isEmpty()) {
                add(file, line, JdkToolScanner.JDK_UNSUPPORTED_AT_RUNTIME, "CRITICAL", api,
                        "javac --release 21: " + api + " is deprecated for removal and throws UnsupportedOperationException on Java 21.",
                        unsupported, message);
            } else if (forRemoval) {
                add(file, line, JdkToolScanner.JDK_DEPRECATED_FOR_REMOVAL, "MEDIUM", api,
                        "javac --release 21: " + api + " is deprecated for removal. Works on Java 21 but will be removed in a later JDK.",
                        JdkToolScanner.deprecationRemediation(api, true), message);
            } else {
                add(file, line, JdkToolScanner.JDK_DEPRECATED, "INFO", api,
                        "javac --release 21: " + api + " is deprecated, not for removal; works on Java 21.",
                        JdkToolScanner.deprecationRemediation(api, false), message);
            }
        }

        private void proprietary(String file, int line, String message) {
            Matcher m = PROPRIETARY.matcher(message);
            if (!m.find()) return;
            String api = m.group(1);
            add(file, line, JdkToolScanner.JDK_UNSUPPORTED_API, "MEDIUM", api,
                    "javac --release 21: " + api + " is a critical internal API exported by jdk.unsupported; it still works on Java 21.",
                    "Plan a supported replacement (for sun.misc.Unsafe: VarHandle / java.lang.foreign).", message);
        }

        private void unresolved(String file, String message) {
            unresolved++;
            filesWithUnresolved.add(file);
            if (unresolvedSamples.size() < SAMPLE_LIMIT) unresolvedSamples.add(firstLine(message) + secondLine(message));
        }

        private void add(String file, int line, String category, String severity, String rule, String description,
                         String remediation, String message) {
            findings.putIfAbsent(file + "|" + line + "|" + category + "|" + rule,
                    finding(file, line, category, severity, rule, description, remediation, message));
        }

        private SourceFinding finding(String file, int line, String category, String severity, String rule, String description,
                                      String remediation, String message) {
            return new SourceFinding(component.component(), component.repository(), component.type(),
                    SCANNER, category, severity, file, line, rule, description, remediation,
                    "javac: " + message.replace('\n', ' ').replaceAll("\\s+", " ").trim())
                    .withValidation("COMPILER", "CONFIRMED_COMPILER", "HIGH", true, false, "", "JAVAC_RELEASE_21", "", "");
        }

        private String relativeFile(JavaFileObject source) {
            if (source == null) return "";
            try {
                return root.relativize(Path.of(source.toUri())).toString().replace('\\', '/');
            } catch (IllegalArgumentException e) {
                return source.getName();
            }
        }

        CompileCheckResult result(int sourceFiles, int classpathEntries, Path classesDir) {
            for (Map.Entry<String, SourceFinding> e : memberFindings.entrySet()) {
                SourceFinding f = e.getValue();
                if (filesWithUnresolved.contains(f.file())) {
                    unresolved++;
                    if (unresolvedSamples.size() < SAMPLE_LIMIT) unresolvedSamples.add("ambiguous missing JDK member " + f.rule() + " in " + f.file());
                } else {
                    findings.putIfAbsent(e.getKey(), f);
                }
            }
            memberFindings.clear();
            String status;
            if (!findings.isEmpty()) status = CompileCheckResult.JAVA21_FINDINGS;
            else if (unresolved > 0 || otherErrors > 0) status = CompileCheckResult.INCOMPLETE_CLASSPATH;
            else status = CompileCheckResult.CLEAN;
            String summary = sourceFiles + " source file(s), " + classpathEntries + " classpath entr" + (classpathEntries == 1 ? "y" : "ies")
                    + ", " + findings.size() + " Java 21 finding(s), " + unresolved + " unresolved non-JDK symbol(s), "
                    + otherErrors + " other error(s)"
                    + (unresolved > 0 ? "; add --compile-classpath or --maven-settings for full coverage" : "");
            return new CompileCheckResult(status, summary, sourceFiles, classpathEntries, List.copyOf(findings.values()),
                    unresolved, otherErrors, List.copyOf(unresolvedSamples), classesDir);
        }
    }

    private static boolean isLanguageChange(String code) {
        return code.contains("underscore") || code.contains("restricted.type") || code.contains("invalid.yield")
                || code.endsWith(".as.identifier") || code.contains("var.not.allowed");
    }

    static boolean isJdkOwned(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.startsWith("java.") || name.startsWith("sun.") || name.startsWith("jdk.")) return true;
        String pkg = packageOf(name);
        if (JDK_PACKAGES.contains(pkg)) return true;
        if (pkg.startsWith("com.sun.")) return THIRD_PARTY_COM_SUN.stream().noneMatch(pkg::startsWith);
        return REMOVED_JAVA_EE_PACKAGES.stream().anyMatch(p -> pkg.equals(p) || pkg.startsWith(p + "."));
    }

    /** {@code <T>doAs(javax.security.auth.Subject,...)} becomes {@code doAs}. */
    private static String memberName(String symbol) {
        String s = symbol.trim();
        if (s.startsWith("<")) {
            int depth = 0;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '<') depth++;
                else if (s.charAt(i) == '>' && --depth == 0) {
                    s = s.substring(i + 1);
                    break;
                }
            }
        }
        return s.contains("(") ? s.substring(0, s.indexOf('(')) : s;
    }

    private static String packageOf(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("\\.")) {
            if (part.isEmpty() || Character.isUpperCase(part.charAt(0))) break;
            if (!sb.isEmpty()) sb.append('.');
            sb.append(part);
        }
        return sb.toString();
    }

    private static String firstLine(String message) {
        int nl = message.indexOf('\n');
        return (nl < 0 ? message : message.substring(0, nl)).trim();
    }

    private static String secondLine(String message) {
        String[] lines = message.split("\n");
        return lines.length > 1 ? " (" + lines[1].trim() + ")" : "";
    }

    // ── Sources and classpath ────────────────────────────────────────────────

    static List<Path> mainSources(Path root) throws IOException {
        List<Path> sources = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                if (SourceFileIndex.SKIPPED_DIRECTORIES.contains(name) || TEST_ONLY_DIRECTORIES.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (TEST_DIRECTORIES.contains(name) && isProjectLevel(dir.getParent())) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (attrs.isRegularFile() && name.endsWith(".java") && !name.equals("module-info.java")) sources.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        sources.sort(Comparator.naturalOrder());
        return sources;
    }

    private static boolean isProjectLevel(Path dir) {
        if (dir == null || dir.getFileName() == null) return false;
        return dir.getFileName().toString().equalsIgnoreCase("src")
                || Files.exists(dir.resolve("pom.xml")) || Files.exists(dir.resolve("build.xml"))
                || Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("ivy.xml"));
    }

    List<Path> classpath(Path root) {
        Set<Path> entries = new LinkedHashSet<>();
        if (mavenResolver != null && Files.isRegularFile(root.resolve("pom.xml"))) {
            try {
                entries.addAll(mavenResolver.resolve(root));
            } catch (IOException e) {
                logger.warn("Maven classpath resolution failed for {}: {}", root, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        entries.addAll(jarsUnder(root, JAR_SKIPPED_DIRECTORIES));
        for (Path extra : extraClasspath) {
            if (Files.isDirectory(extra)) {
                List<Path> jars = jarsUnder(extra, Set.of());
                if (jars.isEmpty()) entries.add(extra);
                else entries.addAll(jars);
            } else if (Files.exists(extra)) {
                entries.add(extra);
            }
        }
        return entries.stream().filter(Files::exists).map(Path::toAbsolutePath).distinct().toList();
    }

    private static List<Path> jarsUnder(Path dir, Set<String> skipped) {
        List<Path> jars = new ArrayList<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (!d.equals(dir) && skipped.contains(d.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) jars.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.debug("Could not list jars under {}: {}", dir, e.getMessage());
        }
        jars.sort(Comparator.naturalOrder());
        return jars;
    }

    private static Charset detectEncoding(List<Path> sources) {
        for (Path source : sources) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(Files.readAllBytes(source)));
            } catch (CharacterCodingException e) {
                return StandardCharsets.ISO_8859_1;
            } catch (IOException ignored) {
                // unreadable files surface as javac errors
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Runs {@code mvn dependency:build-classpath} for every module and returns the union. Target
     * directories that Maven creates are removed afterwards so the checkout stays clean.
     */
    static List<Path> resolveMavenClasspath(Path root, Path settings) throws IOException, InterruptedException {
        List<Path> moduleDirs;
        try (Stream<Path> poms = Files.walk(root)) {
            moduleDirs = poms.filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> root.relativize(p).toString().replace('\\', '/').split("/").length < 12)
                    .filter(p -> java.util.stream.StreamSupport.stream(root.relativize(p).spliterator(), false)
                            .noneMatch(seg -> SourceFileIndex.SKIPPED_DIRECTORIES.contains(seg.toString().toLowerCase(Locale.ROOT))))
                    .map(Path::getParent)
                    .toList();
        }
        List<Path> createdTargets = moduleDirs.stream().map(d -> d.resolve("target")).filter(t -> !Files.exists(t)).toList();

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> command = new ArrayList<>(List.of(windows ? "mvn.cmd" : "mvn", "-B", "-q", "-fae"));
        if (settings != null) command.addAll(List.of("-s", settings.toAbsolutePath().toString()));
        command.addAll(List.of("dependency:build-classpath", "-Dmdep.outputFile=target/" + MAVEN_CLASSPATH_FILE,
                "-Dmdep.includeScope=compile"));

        Set<Path> entries = new LinkedHashSet<>();
        try {
            Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
            List<String> output = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) output.add(line);
            }
            if (!process.waitFor(MAVEN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("mvn dependency:build-classpath timed out after " + MAVEN_TIMEOUT_MINUTES + " minutes");
            }
            if (process.exitValue() != 0) {
                logger.warn("mvn dependency:build-classpath exited {} in {}; using partial classpath. Last output: {}",
                        process.exitValue(), root, String.join(" | ", output.subList(Math.max(0, output.size() - 5), output.size())));
            }
            for (Path moduleDir : moduleDirs) {
                Path file = moduleDir.resolve("target").resolve(MAVEN_CLASSPATH_FILE);
                if (!Files.isRegularFile(file)) continue;
                for (String entry : Files.readString(file).trim().split(Pattern.quote(File.pathSeparator))) {
                    if (!entry.isBlank()) entries.add(Path.of(entry.trim()));
                }
                Files.deleteIfExists(file);
            }
        } finally {
            createdTargets.forEach(CompileChecker::deleteRecursively);
        }
        return List.copyOf(entries);
    }

    static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of temporary output
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of temporary output
        }
    }
}
