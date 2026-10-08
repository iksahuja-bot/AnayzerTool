package effortanalyzer.source;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owner-resolved JDK 21 compatibility evidence for generated artifacts.
 *
 * <p>Combines three sources: a built-in constant-pool check of every {@code java.*} class/member
 * reference against the running JDK, {@code jdeprscan --release 21}, and {@code jdeps --jdk-internals}.
 * Results are exact (resolved by owner class and descriptor), unlike text pattern rules.</p>
 */
public class JdkToolScanner {

    private static final Logger logger = LogManager.getLogger(JdkToolScanner.class);

    public static final String SCANNER = "JDK Tools";
    public static final String JDK_REMOVED_API = "JDK_REMOVED_API";
    public static final String JDK_REMOVED_INTERNAL_API = "JDK_REMOVED_INTERNAL_API";
    public static final String JDK_INTERNAL_API = "JDK_INTERNAL_API";
    public static final String JDK_UNSUPPORTED_API = "JDK_UNSUPPORTED_API";
    public static final String JDK_UNSUPPORTED_AT_RUNTIME = "JDK_UNSUPPORTED_AT_RUNTIME";
    public static final String JDK_DEPRECATED_FOR_REMOVAL = "JDK_DEPRECATED_FOR_REMOVAL";
    public static final String JDK_DEPRECATED = "JDK_DEPRECATED";

    private static final String RELEASE = "21";
    private static final long TOOL_TIMEOUT_MINUTES = 10;

    private static final Pattern JDEPRSCAN_LINE = Pattern.compile(
            "^(\\S+) (\\S+) (.*?) ?deprecated (class|interface|method|field|parameter type|return type|type) (\\S+)\\s*(\\(forRemoval=true\\))?\\s*$");
    private static final Pattern JDEPS_DEPENDENCY = Pattern.compile(
            "^\\s+(\\S+)\\s+->\\s+(\\S+)\\s+(JDK removed internal API|JDK internal API \\(([^)]+)\\))\\s*$");
    private static final Pattern JDEPS_SUGGESTION = Pattern.compile("^(\\S+)\\s{2,}(\\S.*)$");

    /** Deprecated-for-removal APIs that already throw UnsupportedOperationException on Java 21. */
    private static final Map<String, String> UNSUPPORTED_AT_RUNTIME = Map.of(
            "java.lang.Thread.stop", "Thread.stop() throws UnsupportedOperationException since JDK 20. Use interruption and cooperative cancellation.",
            "java.lang.Thread.suspend", "Thread.suspend() throws UnsupportedOperationException since JDK 20. Use java.util.concurrent coordination.",
            "java.lang.Thread.resume", "Thread.resume() throws UnsupportedOperationException since JDK 20. Use java.util.concurrent coordination.",
            "java.lang.ThreadGroup.stop", "ThreadGroup.stop() throws UnsupportedOperationException since JDK 20. Interrupt and join the threads instead.",
            "java.lang.ThreadGroup.suspend", "ThreadGroup.suspend() throws UnsupportedOperationException since JDK 20.",
            "java.lang.ThreadGroup.resume", "ThreadGroup.resume() throws UnsupportedOperationException since JDK 20.",
            "java.lang.System.setSecurityManager", "System.setSecurityManager() throws UnsupportedOperationException since JDK 18 unless -Djava.security.manager=allow. Remove the Security Manager dependency."
    );

    /** Deprecations jdeprscan does not report but which are resolvable exactly from the constant pool. */
    private static final Map<String, String> SUPPLEMENTAL_DEPRECATIONS = Map.of(
            "java/lang/Class.newInstance()Ljava/lang/Object;",
            "Use getDeclaredConstructor().newInstance(); deprecated since 9, not for removal, still works on Java 21."
    );

    private static final Map<String, String> KNOWN_REPLACEMENTS = Map.of(
            "java.lang.Class.newInstance()", "getDeclaredConstructor().newInstance()",
            "java.lang.Runtime.runFinalization()", "explicit resource management (AutoCloseable / java.lang.ref.Cleaner)",
            "java.lang.Object.finalize()", "AutoCloseable / try-with-resources / java.lang.ref.Cleaner",
            "java.util.Observable", "java.beans.PropertyChangeSupport or java.util.concurrent.Flow",
            "java.util.Observer", "java.beans.PropertyChangeListener or java.util.concurrent.Flow",
            "java.lang.SecurityManager", "remove Security Manager usage (JEP 411)"
    );

    /** Runs an external command and returns its merged stdout/stderr lines. */
    @FunctionalInterface
    public interface ToolRunner {
        List<String> run(List<String> command) throws IOException, InterruptedException;
    }

    private final Optional<Path> jdeprscan;
    private final Optional<Path> jdeps;
    private final ToolRunner runner;
    private final JdkApiIndex apiIndex = new JdkApiIndex();

    public JdkToolScanner(Optional<Path> jdeprscan, Optional<Path> jdeps, ToolRunner runner) {
        this.jdeprscan = jdeprscan == null ? Optional.empty() : jdeprscan;
        this.jdeps = jdeps == null ? Optional.empty() : jdeps;
        this.runner = runner == null ? JdkToolScanner::runProcess : runner;
    }

    /** Locates jdeprscan/jdeps next to the running JVM; missing tools are skipped, the built-in check always runs. */
    public static JdkToolScanner detect() {
        Path bin = Path.of(System.getProperty("java.home", ""), "bin");
        Optional<Path> deprscan = tool(bin, "jdeprscan");
        Optional<Path> deps = tool(bin, "jdeps");
        if (deprscan.isEmpty() || deps.isEmpty()) {
            logger.warn("jdeprscan/jdeps not found under {} (running on a JRE?). Only the built-in JDK API check will run.", bin);
        }
        if (Runtime.version().feature() != Integer.parseInt(RELEASE)) {
            logger.warn("Running on JDK {}; removed-API and jdeps checks reflect that JDK rather than JDK {}.",
                    Runtime.version().feature(), RELEASE);
        }
        return new JdkToolScanner(deprscan, deps, JdkToolScanner::runProcess);
    }

    public boolean hasExternalTools() {
        return jdeprscan.isPresent() || jdeps.isPresent();
    }

    public List<GeneratedArtifactScanner.BytecodeFinding> scan(SourceComponent component, List<Path> artifacts) {
        if (component == null || artifacts == null || artifacts.isEmpty()) return List.of();
        Map<String, GeneratedArtifactScanner.BytecodeFinding> unique = new LinkedHashMap<>();
        for (Path artifact : artifacts) {
            List<GeneratedArtifactScanner.BytecodeFinding> found = new ArrayList<>(scanBuiltIn(component, artifact));
            found.addAll(scanWithTools(component, artifact));
            for (GeneratedArtifactScanner.BytecodeFinding f : found) {
                unique.putIfAbsent(f.jarName() + "|" + f.className() + "|" + f.category() + "|" + f.rule(), f);
            }
        }
        logger.info("JDK tool scan for {}: {} finding(s) across {} artifact(s)", component.displayName(), unique.size(), artifacts.size());
        return List.copyOf(unique.values());
    }

    /**
     * Drops JDK-tool findings already reported by a pattern rule for the same class, so the
     * rule finding (which correlates with source) stays the single action item.
     */
    public static List<GeneratedArtifactScanner.BytecodeFinding> mergeWithRuleFindings(
            List<GeneratedArtifactScanner.BytecodeFinding> ruleFindings,
            List<GeneratedArtifactScanner.BytecodeFinding> jdkFindings) {
        List<GeneratedArtifactScanner.BytecodeFinding> merged = new ArrayList<>(ruleFindings == null ? List.of() : ruleFindings);
        if (jdkFindings == null) return merged;
        for (GeneratedArtifactScanner.BytecodeFinding jdk : jdkFindings) {
            boolean covered = merged.stream().anyMatch(rule -> !SCANNER.equals(rule.scanner())
                    && rule.className().equals(jdk.className())
                    && !rule.rule().isBlank()
                    && jdk.rule().startsWith(rule.rule()));
            if (!covered) merged.add(jdk);
        }
        return merged;
    }

    // ── Built-in constant-pool check ─────────────────────────────────────────

    List<GeneratedArtifactScanner.BytecodeFinding> scanBuiltIn(SourceComponent component, Path artifact) {
        if (!apiIndex.usable()) return List.of();
        List<GeneratedArtifactScanner.BytecodeFinding> findings = new ArrayList<>();
        String jarName = artifact.getFileName().toString();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
                ClassFileReferences refs;
                try (InputStream is = jar.getInputStream(entry)) {
                    refs = ClassFileReferences.parse(is.readAllBytes());
                }
                checkReferences(component, jarName, entry.getName(), refs, findings);
            }
        } catch (IOException e) {
            logger.warn("Could not run built-in JDK API check on {}: {}", artifact, e.getMessage());
        }
        return findings;
    }

    void checkReferences(SourceComponent component, String jarName, String classEntry, ClassFileReferences refs,
                         List<GeneratedArtifactScanner.BytecodeFinding> findings) {
        String className = refs.thisClass().isBlank() ? toClassName(classEntry) : refs.thisClass().replace('/', '.');
        if (className.startsWith("java.")) return;
        Set<String> missingClasses = new LinkedHashSet<>();
        for (String ref : refs.classRefs()) {
            if (ref.startsWith("java/") && !apiIndex.classExists(ref)) missingClasses.add(ref);
        }
        for (String missing : missingClasses) {
            String api = missing.replace('/', '.');
            findings.add(finding(component, jarName, classEntry, className, JDK_REMOVED_API, "CRITICAL", api,
                    api + " does not exist in JDK " + Runtime.version().feature() + " (class removed from Java SE).",
                    removedClassRemediation(api)));
        }
        for (ClassFileReferences.MemberRef ref : refs.memberRefs()) {
            String key = ref.owner() + "." + ref.name() + ref.descriptor();
            String supplemental = SUPPLEMENTAL_DEPRECATIONS.get(key);
            if (supplemental != null) {
                findings.add(finding(component, jarName, classEntry, className, JDK_DEPRECATED, "INFO", ref.displayName(),
                        ref.displayName() + " is deprecated (not for removal) and still works on Java 21.", supplemental));
                continue;
            }
            if (!ref.owner().startsWith("java/") || missingClasses.contains(ref.owner())) continue;
            if (apiIndex.memberExists(ref) == Boolean.FALSE) {
                findings.add(finding(component, jarName, classEntry, className, JDK_REMOVED_API, "CRITICAL", ref.displayName(),
                        ref.displayName() + " (" + ref.descriptor() + ") does not exist in JDK " + Runtime.version().feature()
                                + "; the call fails with NoSuchMethodError/NoSuchFieldError.",
                        "Remove or replace the call; see the JDK 21 Javadoc and release notes for the removed member."));
            }
        }
    }

    /** Remediation for an API that throws UnsupportedOperationException on Java 21, or empty when not in that list. */
    static String unsupportedAtRuntime(String api) {
        return UNSUPPORTED_AT_RUNTIME.getOrDefault(memberKey(api), "");
    }

    static String removedClassRemediation(String api) {
        if (api.startsWith("java.security.acl.")) {
            return "java.security.acl was removed in JDK 14. Use java.security.Policy/Principal-based checks or the application's own ACL model.";
        }
        if (api.startsWith("java.applet.")) return "Applet APIs were removed in JDK 17. Migrate to a web or desktop UI.";
        if (api.startsWith("java.rmi.activation.")) return "RMI Activation was removed in JDK 17. Manage service lifecycle explicitly.";
        if (api.equals("java.lang.Compiler")) return "java.lang.Compiler was removed in JDK 21. Remove the call; it was a no-op.";
        if (api.startsWith("java.util.jar.Pack200")) return "Pack200 was removed in JDK 14. Use standard JAR/ZIP compression.";
        return "Replace the removed class with its documented successor, or supply a maintained library that provides it.";
    }

    // ── jdeprscan / jdeps ────────────────────────────────────────────────────

    private List<GeneratedArtifactScanner.BytecodeFinding> scanWithTools(SourceComponent component, Path artifact) {
        if (!hasExternalTools()) return List.of();
        Path toolInput = artifact;
        boolean temporary = false;
        try {
            if (!artifact.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                toolInput = Files.createTempFile("ea-jdk-tools-", ".jar");
                Files.copy(artifact, toolInput, StandardCopyOption.REPLACE_EXISTING);
                temporary = true;
            }
            String jarName = artifact.getFileName().toString();
            List<GeneratedArtifactScanner.BytecodeFinding> findings = new ArrayList<>();
            if (jdeprscan.isPresent()) {
                List<String> out = runner.run(List.of(jdeprscan.get().toString(), "--release", RELEASE, toolInput.toString()));
                findings.addAll(parseJdeprscan(component, jarName, out));
            }
            if (jdeps.isPresent()) {
                List<String> out = runner.run(List.of(jdeps.get().toString(), "--jdk-internals", "--multi-release", RELEASE,
                        "--ignore-missing-deps", toolInput.toString()));
                findings.addAll(parseJdeps(component, jarName, out));
            }
            return findings;
        } catch (IOException e) {
            logger.warn("JDK tool scan failed for {}: {}", artifact, e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            if (temporary) {
                try {
                    Files.deleteIfExists(toolInput);
                } catch (IOException ignored) {
                    // temp file cleanup is best effort
                }
            }
        }
    }

    static List<GeneratedArtifactScanner.BytecodeFinding> parseJdeprscan(SourceComponent component, String jarName, List<String> lines) {
        List<GeneratedArtifactScanner.BytecodeFinding> findings = new ArrayList<>();
        for (String line : lines) {
            Matcher m = JDEPRSCAN_LINE.matcher(line);
            if (!m.matches()) continue;
            String internalClass = m.group(2);
            String relation = (m.group(3) + " deprecated " + m.group(4)).trim();
            String api = displayApi(m.group(5));
            boolean forRemoval = m.group(6) != null;
            String memberKey = memberKey(api);
            String className = internalClass.replace('/', '.');
            String classEntry = internalClass + ".class";

            if (forRemoval && UNSUPPORTED_AT_RUNTIME.containsKey(memberKey)) {
                findings.add(finding(component, jarName, classEntry, className, JDK_UNSUPPORTED_AT_RUNTIME, "CRITICAL", api,
                        "jdeprscan --release 21: " + className + " " + relation + " " + api + " (forRemoval=true); it fails at runtime on Java 21.",
                        UNSUPPORTED_AT_RUNTIME.get(memberKey)));
            } else if (forRemoval) {
                findings.add(finding(component, jarName, classEntry, className, JDK_DEPRECATED_FOR_REMOVAL, "MEDIUM", api,
                        "jdeprscan --release 21: " + className + " " + relation + " " + api + " (forRemoval=true). Works on Java 21 but will be removed in a later JDK.",
                        deprecationRemediation(api, true)));
            } else {
                findings.add(finding(component, jarName, classEntry, className, JDK_DEPRECATED, "INFO", api,
                        "jdeprscan --release 21: " + className + " " + relation + " " + api + ". Deprecated, not for removal; works on Java 21.",
                        deprecationRemediation(api, false)));
            }
        }
        return findings;
    }

    static List<GeneratedArtifactScanner.BytecodeFinding> parseJdeps(SourceComponent component, String jarName, List<String> lines) {
        Map<String, String> suggestions = new HashMap<>();
        boolean inTable = false;
        for (String line : lines) {
            if (line.startsWith("JDK Internal API")) {
                inTable = true;
                continue;
            }
            if (!inTable || line.startsWith("---")) continue;
            Matcher m = JDEPS_SUGGESTION.matcher(line);
            if (m.matches()) suggestions.put(m.group(1), m.group(2).trim());
        }

        List<GeneratedArtifactScanner.BytecodeFinding> findings = new ArrayList<>();
        for (String line : lines) {
            Matcher m = JDEPS_DEPENDENCY.matcher(line);
            if (!m.matches()) continue;
            String className = m.group(1);
            String api = m.group(2);
            String module = m.group(4);
            String suggestion = suggestions.getOrDefault(api, "");
            String classEntry = className.replace('.', '/') + ".class";
            String category;
            String severity;
            String description;
            if (module == null) {
                category = JDK_REMOVED_INTERNAL_API;
                severity = "CRITICAL";
                description = "jdeps: " + className + " -> " + api + " is a JDK removed internal API; the class is absent on Java 21.";
            } else if ("jdk.unsupported".equals(module)) {
                category = JDK_UNSUPPORTED_API;
                severity = "MEDIUM";
                description = "jdeps: " + className + " -> " + api + " is a critical internal API exported by jdk.unsupported; it still works on Java 21.";
            } else {
                category = JDK_INTERNAL_API;
                severity = "HIGH";
                description = "jdeps: " + className + " -> " + api + " is a JDK internal API in " + module
                        + "; strongly encapsulated on Java 21 (needs --add-exports/--add-opens).";
            }
            String remediation = suggestion.isBlank()
                    ? "Replace with a supported public API or upgrade the owning library."
                    : "jdeps suggested replacement: " + suggestion;
            findings.add(finding(component, jarName, classEntry, className, category, severity, api, description, remediation));
        }
        return findings;
    }

    /** Known exact replacement for an API reported by this scanner, or empty when none is curated. */
    public static String knownReplacement(String api) {
        if (api == null) return "";
        String direct = KNOWN_REPLACEMENTS.get(api);
        if (direct != null) return direct;
        if (api.startsWith("new java.lang.") && api.endsWith("(...)")) {
            String type = api.substring("new ".length(), api.length() - "(...)".length());
            if (Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double",
                    "java.lang.Float", "java.lang.Boolean", "java.lang.Character").contains(type)) {
                return type.substring("java.lang.".length()) + ".valueOf(...)";
            }
        }
        return "";
    }

    static String deprecationRemediation(String api, boolean forRemoval) {
        String replacement = knownReplacement(api);
        String base = replacement.isBlank()
                ? "Use the replacement documented in the JDK 21 @Deprecated Javadoc for " + api + "."
                : "Replace with " + replacement + ".";
        return forRemoval ? base + " Plan before the next JDK upgrade." : base + " Optional cleanup for Java 21.";
    }

    private static String displayApi(String token) {
        int sep = token.indexOf("::");
        if (sep < 0) return token.replace('/', '.');
        String owner = token.substring(0, sep).replace('/', '.');
        String member = token.substring(sep + 2);
        int paren = member.indexOf('(');
        if (paren < 0) return owner + "." + member;
        String name = member.substring(0, paren);
        return "<init>".equals(name) ? "new " + owner + "(...)" : owner + "." + name + "()";
    }

    private static String memberKey(String api) {
        return api.endsWith("()") ? api.substring(0, api.length() - 2) : api;
    }

    private static GeneratedArtifactScanner.BytecodeFinding finding(SourceComponent component, String jarName, String classEntry,
                                                                     String className, String category, String severity,
                                                                     String api, String description, String remediation) {
        return new GeneratedArtifactScanner.BytecodeFinding(component.component(), component.repository(), component.type(),
                jarName, classEntry, className, SCANNER, category, severity, api, description, remediation);
    }

    private static String toClassName(String entryName) {
        String name = entryName == null ? "" : entryName;
        if (name.endsWith(".class")) name = name.substring(0, name.length() - ".class".length());
        return name.replace('/', '.');
    }

    private static Optional<Path> tool(Path bin, String name) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path path = bin.resolve(windows ? name + ".exe" : name);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    private static List<String> runProcess(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        if (!process.waitFor(TOOL_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("Timed out after " + TOOL_TIMEOUT_MINUTES + " minutes: " + String.join(" ", command));
        }
        return lines;
    }

    /** Answers whether java.* classes and members referenced by bytecode exist in the running JDK. */
    static final class JdkApiIndex {
        private static final Set<String> SIGNATURE_POLYMORPHIC_OWNERS = Set.of("java/lang/invoke/MethodHandle", "java/lang/invoke/VarHandle");

        private final Map<String, Boolean> classes = new ConcurrentHashMap<>();
        private final Map<String, Set<String>> members = new ConcurrentHashMap<>();
        private final boolean usable = ClassLoader.getSystemResource("java/lang/Object.class") != null;

        boolean usable() {
            return usable;
        }

        boolean classExists(String internalName) {
            return classes.computeIfAbsent(internalName, n -> ClassLoader.getSystemResource(n + ".class") != null);
        }

        /** TRUE/FALSE when resolvable; null when the owner cannot be loaded or the member is signature-polymorphic. */
        Boolean memberExists(ClassFileReferences.MemberRef ref) {
            if (ref.kind() == ClassFileReferences.MemberKind.METHOD && SIGNATURE_POLYMORPHIC_OWNERS.contains(ref.owner())) return null;
            Set<String> known = members.computeIfAbsent(ref.owner(), JdkApiIndex::loadMembers);
            if (known.isEmpty()) return null;
            String prefix = ref.kind() == ClassFileReferences.MemberKind.FIELD ? "F:" : "M:";
            return known.contains(prefix + ref.name() + ref.descriptor());
        }

        private static Set<String> loadMembers(String owner) {
            Class<?> type;
            try {
                type = Class.forName(owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                return Set.of();
            }
            Set<String> out = new LinkedHashSet<>();
            Deque<Class<?>> queue = new ArrayDeque<>();
            Set<Class<?>> seen = new LinkedHashSet<>();
            queue.add(type);
            queue.add(Object.class);
            while (!queue.isEmpty()) {
                Class<?> c = queue.poll();
                if (!seen.add(c)) continue;
                try {
                    for (Method m : c.getDeclaredMethods()) {
                        out.add("M:" + m.getName() + MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString());
                    }
                    if (c == type) {
                        for (Constructor<?> k : c.getDeclaredConstructors()) {
                            out.add("M:<init>" + MethodType.methodType(void.class, k.getParameterTypes()).toMethodDescriptorString());
                        }
                        out.add("M:<clinit>()V");
                    }
                    for (Field f : c.getDeclaredFields()) {
                        out.add("F:" + f.getName() + f.getType().descriptorString());
                    }
                } catch (LinkageError | SecurityException e) {
                    return Set.of();
                }
                if (c.getSuperclass() != null) queue.add(c.getSuperclass());
                queue.addAll(List.of(c.getInterfaces()));
            }
            return out;
        }
    }
}
