package effortanalyzer.version;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Scans JAR/WAR/EAR archives and reports bundled third-party libraries whose
 * version is below the target version table ({@link LibraryVersionRules}).
 *
 * <p>Artifacts are detected from two sources, in order of accuracy:
 * <ol>
 *   <li>{@code META-INF/maven/<group>/<artifact>/pom.properties} entries
 *       (exact artifactId + version)</li>
 *   <li>Archive file names — the archive's own name and every nested
 *       {@code *.jar / *.war / *.ear} entry (e.g. {@code WEB-INF/lib/} jars),
 *       split into artifact + version at the last hyphen-led numeric segment,
 *       so {@code commons-vfs2-2.9.0.jar} yields artifact
 *       {@code commons-vfs2} and not {@code commons}</li>
 * </ol>
 *
 * <p>When both an exact rule and a prefix rule match an artifact, the more
 * specific (longer) rule wins — {@code netty-codec-http} is never judged by a
 * hypothetical {@code netty-codec} rule and {@code commons-vfs} is never
 * matched by the {@code commons-vfs2} rule.
 */
public class LibraryVersionAnalyzer {

    private static final Logger logger = LogManager.getLogger(LibraryVersionAnalyzer.class);

    /** Result of matching one detected artifact against the version table. */
    public record VersionFinding(
            String jarName,          // top-level archive the library was found in
            String location,         // where it was detected (entry name or "archive name")
            String artifact,         // detected artifactId
            String detectedVersion,  // version found in the archive
            String targetVersion,    // required minimum version
            String status,           // OUTDATED | OK
            String severity,         // severity when OUTDATED, empty when OK
            String library,          // display name from the rule table
            String action) {         // remediation hint

        public boolean outdated() { return "OUTDATED".equals(status); }
    }

    private final List<LibraryVersionRule> rules;
    private final List<VersionFinding> findings = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    /** Uses the built-in rule table plus external overrides. */
    public LibraryVersionAnalyzer() {
        this(LibraryVersionRules.load());
    }

    /** Uses an explicit rule table (tests, custom configurations). */
    public LibraryVersionAnalyzer(List<LibraryVersionRule> rules) {
        this.rules = List.copyOf(rules);
    }

    // ── Analysis ──────────────────────────────────────────────────────────────

    public void analyze(String targetPath) throws IOException {
        Path path = Path.of(targetPath);
        if (!Files.exists(path)) throw new FileNotFoundException("Path not found: " + targetPath);

        List<Path> archives = findArchives(path);
        logger.info("Version check: found {} archive(s) under {}", archives.size(), targetPath);

        for (Path archive : archives) {
            scanArchive(archive);
        }
        logger.info("Version check complete. Findings: {} ({} outdated)",
                findings.size(), countOutdated());
    }

    public List<VersionFinding> getFindings()  { return List.copyOf(findings); }
    public List<VersionFinding> getOutdated()  { return findings.stream().filter(VersionFinding::outdated).toList(); }
    public int countOutdated()                 { return (int) findings.stream().filter(VersionFinding::outdated).count(); }
    public int getRuleCount()                  { return rules.size(); }

    // ── Archive discovery ─────────────────────────────────────────────────────

    private List<Path> findArchives(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return isArchive(path.getFileName().toString()) ? List.of(path) : List.of();
        }
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile)
                         .filter(p -> isArchive(p.getFileName().toString()))
                         .toList();
        }
    }

    private static boolean isArchive(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jar") || n.endsWith(".war") || n.endsWith(".ear");
    }

    // ── Single archive scan ───────────────────────────────────────────────────

    private void scanArchive(Path archive) {
        String jarName = archive.getFileName().toString();
        try (JarFile jar = new JarFile(archive.toFile())) {
            // The archive itself, detected from its own file name.
            detectFromName(jarName, jarName, "archive name");

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();

                if (name.endsWith("pom.properties") && name.contains("META-INF/maven/")) {
                    detectFromPomProperties(jar, entry, jarName);
                } else if (isArchive(name)) {
                    String entryFileName = name.substring(name.lastIndexOf('/') + 1);
                    detectFromName(jarName, entryFileName, name);
                    // Second level: nested war/ear entries may carry their own lib jars.
                    if (name.toLowerCase(Locale.ROOT).endsWith(".war")
                            || name.toLowerCase(Locale.ROOT).endsWith(".ear")) {
                        scanNestedArchive(jar, entry, jarName, name);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Version check: could not scan archive {}: {}", archive, e.getMessage());
        }
    }

    /** Scans library jar names inside a nested war/ear entry (one level deeper). */
    private void scanNestedArchive(JarFile outer, JarEntry nested, String jarName, String nestedPath) {
        Path tmp = null;
        try (InputStream is = outer.getInputStream(nested)) {
            tmp = Files.createTempFile("ea-nested-", ".jar");
            Files.copy(is, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (JarFile inner = new JarFile(tmp.toFile())) {
                Enumeration<JarEntry> entries = inner.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (!e.isDirectory() && isArchive(e.getName())) {
                        String entryFileName = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                        detectFromName(jarName, entryFileName, nestedPath + "!" + e.getName());
                    }
                }
            }
        } catch (IOException ex) {
            logger.debug("Version check: could not scan nested archive {}: {}", nestedPath, ex.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    // ── Detection sources ─────────────────────────────────────────────────────

    private void detectFromPomProperties(JarFile jar, JarEntry entry, String jarName) {
        try (InputStream is = jar.getInputStream(entry)) {
            Properties p = new Properties();
            p.load(is);
            String artifact = p.getProperty("artifactId", "").trim().toLowerCase(Locale.ROOT);
            String version  = p.getProperty("version",  "").trim();
            if (!artifact.isEmpty() && !version.isEmpty()) {
                record(jarName, entry.getName(), artifact, version);
            }
        } catch (IOException e) {
            logger.debug("Version check: unreadable pom.properties: {}", entry.getName());
        }
    }

    private void detectFromName(String jarName, String fileName, String location) {
        String[] av = splitNameVersion(fileName);
        if (av == null) return;
        record(jarName, location, av[0], av[1]);
    }

    /**
     * Splits {@code spring-core-5.3.20.jar} into {@code [spring-core, 5.3.20]}.
     * The version starts at the LAST hyphen-separated segment that begins with
     * a digit, so artifacts containing digits ({@code commons-vfs2}) and
     * versions embedded in the artifact name ({@code log4j-1.2-api}) both
     * parse correctly. Returns null when no version-looking segment exists.
     */
    static String[] splitNameVersion(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".jar") || lower.endsWith(".war") || lower.endsWith(".ear"))) return null;
        String base = fileName.substring(0, fileName.lastIndexOf('.'));

        String[] segs = base.split("-");
        for (int i = segs.length - 1; i > 0; i--) {
            if (!segs[i].isEmpty() && Character.isDigit(segs[i].charAt(0))) {
                String artifact = String.join("-", java.util.Arrays.copyOfRange(segs, 0, i))
                                        .toLowerCase(Locale.ROOT);
                String version  = String.join("-", java.util.Arrays.copyOfRange(segs, i, segs.length));
                return artifact.isEmpty() ? null : new String[]{artifact, version};
            }
        }
        return null;
    }

    // ── Rule matching ─────────────────────────────────────────────────────────

    /**
     * Matches the artifact against the rule table (most specific rule wins)
     * and records an OUTDATED or OK finding.
     */
    private void record(String jarName, String location, String artifact, String version) {
        LibraryVersionRule best = null;
        for (LibraryVersionRule rule : rules) {
            if (!rule.matches(artifact)) continue;
            if (best == null || rule.specificity() > best.specificity()) best = rule;
        }
        if (best == null) return;

        // One finding per (archive, artifact, version) — pom.properties and the
        // file name usually describe the same library; prefer the first seen.
        String key = jarName + "|" + artifact + "|" + version;
        if (!seen.add(key)) return;

        boolean outdated = VersionComparator.below(version, best.targetVersion());
        findings.add(new VersionFinding(
                jarName,
                location,
                artifact,
                version,
                best.targetVersion(),
                outdated ? "OUTDATED" : "OK",
                outdated ? best.severity() : "",
                best.library(),
                outdated ? "Upgrade to " + best.targetVersion() + " or later"
                         : "Target version satisfied"));
    }
}


