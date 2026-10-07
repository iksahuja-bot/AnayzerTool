package effortanalyzer.source;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Scans component-declared generated JAR/WAR/EAR artifacts for Java 21 bytecode evidence. */
public class GeneratedArtifactScanner {

    private static final Logger logger = LogManager.getLogger(GeneratedArtifactScanner.class);

    private final List<Java21SourceRule> rules;

    public GeneratedArtifactScanner(SourceScanProfile profile) {
        this.rules = profile.java21Rules() == null ? List.of() : profile.java21Rules().stream()
                .filter(r -> r.scansJava() || r.scanTarget() == Java21SourceRule.ScanTarget.BOTH)
                .toList();
    }

    public List<BytecodeFinding> scan(SourceComponent component, Path checkoutRoot) {
        try {
            return scanResolved(component, resolveGeneratedArtifacts(component, checkoutRoot));
        } catch (IOException e) {
            logger.warn("Could not resolve generated artifacts for {}: {}", component.displayName(), e.getMessage());
            return List.of();
        }
    }

    public List<BytecodeFinding> scan(SourceComponent component, Path checkoutRoot, List<Path> artifactRoots) {
        if (rules.isEmpty() || component.generatedJars().isEmpty()) return List.of();

        try {
            return scanResolved(component, resolveGeneratedArtifacts(component, checkoutRoot, artifactRoots));
        } catch (IOException e) {
            logger.warn("Could not resolve generated artifacts for {}: {}", component.displayName(), e.getMessage());
            return List.of();
        }
    }

    private List<BytecodeFinding> scanResolved(SourceComponent component, List<Path> artifacts) {
        if (rules.isEmpty() || artifacts.isEmpty()) return List.of();
        List<BytecodeFinding> findings = new ArrayList<>();
        for (Path artifact : artifacts) {
            scanArtifact(component, artifact, findings);
        }
        return findings;
    }

    public static List<Path> resolveGeneratedArtifacts(SourceComponent component, Path checkoutRoot) throws IOException {
        return resolveGeneratedArtifacts(component, checkoutRoot, List.of());
    }

    public static List<Path> resolveGeneratedArtifacts(SourceComponent component, Path checkoutRoot, List<Path> artifactRoots) throws IOException {
        if (component == null || component.generatedJars().isEmpty()) return List.of();

        Set<Path> artifacts = new LinkedHashSet<>();
        for (String declared : component.generatedJars()) {
            artifacts.addAll(resolveArtifacts(declared, checkoutRoot));
            if (artifactRoots != null) {
                for (Path root : artifactRoots) {
                    artifacts.addAll(resolveArtifacts(declared, root));
                }
            }
        }
        return List.copyOf(artifacts);
    }

    private void scanArtifact(SourceComponent component, Path artifact, List<BytecodeFinding> findings) {
        String jarName = artifact.getFileName().toString();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                try (InputStream is = jar.getInputStream(entry)) {
                    List<String> constants = constantPoolUtf8Strings(is.readAllBytes());
                    for (Java21SourceRule rule : rules) {
                        if (containsPattern(constants, rule.apiPattern())) {
                            findings.add(new BytecodeFinding(component.component(), component.repository(), component.type(),
                                    jarName, entry.getName(), toClassName(entry.getName()), "Java 21", rule.category(),
                                    normalizeSeverity(rule.severity()), rule.apiPattern(), rule.description(), rule.remediation()));
                        }
                    }
                } catch (IOException e) {
                    logger.debug("Could not read class {} from {}: {}", entry.getName(), artifact, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.warn("Could not scan generated artifact {} for {}: {}", artifact, component.displayName(), e.getMessage());
        }
    }

    private static Path resolveArtifact(String declared, Path checkoutRoot) {
        Path path = Path.of(declared);
        if (path.isAbsolute()) return path.normalize();
        if (checkoutRoot != null) return checkoutRoot.resolve(path).normalize();
        return path.toAbsolutePath().normalize();
    }

    private static List<Path> resolveArtifacts(String declared, Path checkoutRoot) throws IOException {
        if (declared == null || declared.isBlank()) return List.of();
        if (checkoutRoot != null && !Files.exists(checkoutRoot)) return List.of();
        if (checkoutRoot != null && Files.isRegularFile(checkoutRoot)) {
            return fileMatchesDeclaration(checkoutRoot, declared) ? List.of(checkoutRoot.normalize()) : List.of();
        }
        if (containsGlob(declared)) {
            Path base = checkoutRoot == null ? Path.of("").toAbsolutePath().normalize() : checkoutRoot.normalize();
            PathMatcher matcher = base.getFileSystem().getPathMatcher("glob:" + declared.replace('\\', '/'));
            try (var stream = Files.walk(base)) {
                return stream.filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(Path.of(base.relativize(p).toString().replace('\\', '/')))
                                || matcher.matches(Path.of(p.toString().replace('\\', '/'))))
                        .sorted()
                        .toList();
            }
        }

        Path artifact = resolveArtifact(declared, checkoutRoot);
        if (Files.isRegularFile(artifact)) return List.of(artifact);

        if (checkoutRoot != null && Files.isDirectory(checkoutRoot)) {
            Path declaredFileName = Path.of(declared).getFileName();
            if (declaredFileName != null) {
                String expected = declaredFileName.toString();
                try (var stream = Files.walk(checkoutRoot)) {
                    return stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equalsIgnoreCase(expected))
                            .sorted()
                            .toList();
                }
            }
        }

        return List.of();
    }

    private static boolean fileMatchesDeclaration(Path file, String declared) {
        if (file == null || declared == null || declared.isBlank()) return false;
        Path declaredPath = Path.of(declared);
        if (declaredPath.isAbsolute() && file.normalize().equals(declaredPath.normalize())) return true;
        Path declaredFileName = declaredPath.getFileName();
        return declaredFileName != null && file.getFileName().toString().equalsIgnoreCase(declaredFileName.toString());
    }

    private static boolean containsGlob(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0 || value.indexOf('[') >= 0 || value.indexOf('{') >= 0;
    }

    private static boolean containsPattern(List<String> constants, String apiPattern) {
        if (apiPattern == null || apiPattern.isBlank()) return false;
        String slash = apiPattern.replace('.', '/');
        for (String value : constants) {
            if (matchesPattern(value, apiPattern, isPackagePattern(apiPattern))
                    || matchesPattern(value, slash, isPackagePattern(slash))) return true;
        }
        return false;
    }

    private static boolean matchesPattern(String value, String pattern, boolean packagePattern) {
        if (value == null || pattern == null || pattern.isBlank()) return false;
        int index = value.indexOf(pattern);
        while (index >= 0) {
            int end = index + pattern.length();
            if (hasBoundary(value, end, pattern, packagePattern)) return true;
            index = value.indexOf(pattern, index + 1);
        }
        return false;
    }

    private static boolean hasBoundary(String value, int end, String pattern, boolean packagePattern) {
        if (end >= value.length()) return true;
        if (pattern.endsWith(".") || pattern.endsWith("/")) return true;
        char next = value.charAt(end);
        if (packagePattern && (next == '.' || next == '/')) return true;
        return next != '.' && next != '/' && next != '$'
                && !Character.isLetterOrDigit(next) && next != '_';
    }

    private static boolean isPackagePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) return false;
        if (pattern.endsWith(".") || pattern.endsWith("/")) return true;
        String normalized = pattern.replace('/', '.');
        int dot = normalized.lastIndexOf('.');
        String lastSegment = dot >= 0 ? normalized.substring(dot + 1) : normalized;
        return !lastSegment.isEmpty() && Character.isLowerCase(lastSegment.charAt(0));
    }

    private static List<String> constantPoolUtf8Strings(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 12) return List.of();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            if (in.readInt() != 0xCAFEBABE) return List.of();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int cpCount = in.readUnsignedShort();
            if (cpCount <= 1) return List.of();

            List<String> utf8 = new ArrayList<>();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8.add(in.readUTF());
                    case 3, 4 -> in.skipBytes(4);
                    case 5, 6 -> { in.skipBytes(8); i++; }
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                    case 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 15 -> in.skipBytes(3);
                    default -> { return List.of(); }
                }
            }
            return utf8;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static String toClassName(String entryName) {
        String name = entryName == null ? "" : entryName;
        if (name.endsWith(".class")) name = name.substring(0, name.length() - ".class".length());
        return name.replace('/', '.').replace('\\', '.');
    }

    private static String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) return "INFO";
        return severity.equalsIgnoreCase("WARNING") ? "MEDIUM" : severity.toUpperCase(Locale.ROOT);
    }

    public record BytecodeFinding(
            String component,
            String repository,
            RepositoryType repositoryType,
            String jarName,
            String classEntry,
            String className,
            String scanner,
            String category,
            String severity,
            String rule,
            String description,
            String remediation
    ) {}
}
