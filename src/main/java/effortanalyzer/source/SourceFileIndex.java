package effortanalyzer.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File-name index of a checkout that resolves classes to their {@code .java} file and finds the line
 * using an API. Used to map bytecode findings back to source and to compare findings with trunk.
 */
final class SourceFileIndex {

    static final Set<String> SKIPPED_DIRECTORIES = Set.of(".svn", ".git", ".hg", "target", "build", "bin", "out",
            "node_modules", ".gradle", ".idea", ".settings");

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE_DECLARATION = Pattern.compile("\\b(class|interface|enum|record)\\s+[A-Za-z_$]");
    private static final int PACKAGE_SCAN_LINES = 400;

    private final Path root;
    private final Map<String, List<Path>> byFileName;
    private final Map<Path, String> packages = new ConcurrentHashMap<>();

    private SourceFileIndex(Path root, Map<String, List<Path>> byFileName) {
        this.root = root;
        this.byFileName = byFileName;
    }

    static SourceFileIndex of(Path root) {
        Map<String, List<Path>> index = new HashMap<>();
        if (root == null || !Files.isDirectory(root)) return new SourceFileIndex(root, index);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIPPED_DIRECTORIES.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        index.computeIfAbsent(file.getFileName().toString().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // a partial index still maps what it could reach
        }
        return new SourceFileIndex(root, index);
    }

    Path root() {
        return root;
    }

    /** The top-level {@code .java} file declaring {@code className} (nested {@code $} parts are ignored). */
    Optional<Path> findClass(String className) {
        if (className == null || className.isBlank()) return Optional.empty();
        String topLevel = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        int dot = topLevel.lastIndexOf('.');
        String pkg = dot < 0 ? "" : topLevel.substring(0, dot);
        String simple = topLevel.substring(dot + 1);
        return filesNamed(simple + ".java").stream()
                .filter(p -> pkg.equals(packageOf(p)))
                .findFirst();
    }

    List<Path> filesNamed(String fileName) {
        if (fileName == null) return List.of();
        return byFileName.getOrDefault(fileName.toLowerCase(Locale.ROOT), List.of());
    }

    String relative(Path file) {
        if (root == null || file == null) return "";
        return root.relativize(file).toString().replace('\\', '/');
    }

    String packageOf(Path javaFile) {
        return packages.computeIfAbsent(javaFile, SourceFileIndex::readPackage);
    }

    /** Fully qualified top-level class name for a checkout-relative {@code .java} path, or empty. */
    String classNameOf(String relativeFile) {
        if (root == null || relativeFile == null || !relativeFile.toLowerCase(Locale.ROOT).endsWith(".java")) return "";
        Path file = root.resolve(relativeFile);
        if (!Files.isRegularFile(file)) return "";
        String simple = file.getFileName().toString();
        simple = simple.substring(0, simple.length() - ".java".length());
        String pkg = packageOf(file);
        return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    /** {@code WEB-INF/classes/com/x/A$B.class} becomes {@code com.x.A$B}. */
    static String classNameFromEntry(String entry) {
        if (entry == null) return "";
        String name = entry.replace('\\', '/');
        for (String prefix : List.of("WEB-INF/classes/", "BOOT-INF/classes/")) {
            if (name.startsWith(prefix)) name = name.substring(prefix.length());
        }
        name = name.replaceFirst("^META-INF/versions/\\d+/", "");
        if (name.endsWith(".class")) name = name.substring(0, name.length() - ".class".length());
        return name.replace('/', '.');
    }

    static String readPackage(Path javaFile) {
        List<String> lines = readLines(javaFile);
        for (int i = 0; i < Math.min(lines.size(), PACKAGE_SCAN_LINES); i++) {
            Matcher m = PACKAGE.matcher(lines.get(i));
            if (m.find()) return m.group(1);
            if (TYPE_DECLARATION.matcher(lines.get(i)).find() && !lines.get(i).trim().startsWith("*")
                    && !lines.get(i).trim().startsWith("//")) {
                return "";
            }
        }
        return "";
    }

    /**
     * First line (1-based) that uses the API named by {@code rule}; non-import lines win over the import.
     * Returns -1 when the API text is not present.
     */
    static int findLine(Path file, String rule) {
        List<String> tokens = apiTokens(rule);
        if (tokens.isEmpty()) return -1;
        boolean classNewInstance = isClassNewInstance(rule);
        int importLine = -1;
        List<String> lines = readLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
            boolean matched = classNewInstance
                    ? SourceTreeScanner.isClassNewInstanceCall(line, Set.of())
                    : tokens.stream().anyMatch(line::contains);
            if (!matched) continue;
            if (trimmed.startsWith("import ")) {
                if (importLine < 0) importLine = i + 1;
                continue;
            }
            return i + 1;
        }
        return importLine;
    }

    /** Text tokens that identify an API in source: {@code new Integer(}, {@code .stop(}, {@code sun.misc.Service}. */
    static List<String> apiTokens(String rule) {
        if (rule == null || rule.isBlank()) return List.of();
        String r = rule.trim();
        if (r.startsWith("new ") && r.endsWith("(...)")) {
            String type = r.substring("new ".length(), r.length() - "(...)".length());
            String simple = type.substring(type.lastIndexOf('.') + 1);
            return List.of("new " + simple + "(", "new " + type + "(");
        }
        int hash = r.indexOf('#');
        if (hash > 0 && hash < r.length() - 1) return List.of(r.substring(hash + 1) + "(");
        if (r.endsWith("()")) {
            String member = r.substring(0, r.length() - 2);
            return List.of("." + member.substring(member.lastIndexOf('.') + 1) + "(");
        }
        String last = r.substring(r.lastIndexOf('.') + 1);
        if (!last.isEmpty() && Character.isLowerCase(last.charAt(0)) && r.contains(".")) return List.of(r + ".");
        return List.of(r);
    }

    private static boolean isClassNewInstance(String rule) {
        return "java.lang.Class.newInstance()".equals(rule) || "newInstance()".equals(rule);
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            try {
                return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
            } catch (IOException ignored) {
                return List.of();
            }
        }
    }
}
