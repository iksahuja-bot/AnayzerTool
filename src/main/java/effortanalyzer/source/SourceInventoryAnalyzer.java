package effortanalyzer.source;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Orchestrates Excel inventory parsing, checkout/update, direct source scan, and reporting. */
public class SourceInventoryAnalyzer {

    private static final Logger logger = LogManager.getLogger(SourceInventoryAnalyzer.class);

    private final Path inventoryFile;
    private final Path workspace;
    private final boolean reuseWorkspace;
    private final boolean cleanWorkspace;
    private final boolean failOnCheckoutError;
    private final SourceScanProfile scanProfile;
    private final CheckoutCredentials credentials;
    private final List<Path> artifactRoots;
    private final JdkToolScanner jdkToolScanner;
    private final CompileChecker compileChecker;
    private final boolean trunkValidatedDefault;

    public SourceInventoryAnalyzer(Path inventoryFile, Path workspace, boolean reuseWorkspace,
                                   boolean cleanWorkspace, boolean failOnCheckoutError,
                                   SourceScanProfile scanProfile, CheckoutCredentials credentials) {
        this(inventoryFile, workspace, reuseWorkspace, cleanWorkspace, failOnCheckoutError,
                scanProfile, credentials, List.of());
    }

    public SourceInventoryAnalyzer(Path inventoryFile, Path workspace, boolean reuseWorkspace,
                                   boolean cleanWorkspace, boolean failOnCheckoutError,
                                   SourceScanProfile scanProfile, CheckoutCredentials credentials,
                                   List<Path> artifactRoots) {
        this(inventoryFile, workspace, reuseWorkspace, cleanWorkspace, failOnCheckoutError,
                scanProfile, credentials, artifactRoots, null);
    }

    /** @param jdkToolScanner JDK 21 tool evidence for generated artifacts; {@code null} disables it */
    public SourceInventoryAnalyzer(Path inventoryFile, Path workspace, boolean reuseWorkspace,
                                   boolean cleanWorkspace, boolean failOnCheckoutError,
                                   SourceScanProfile scanProfile, CheckoutCredentials credentials,
                                   List<Path> artifactRoots, JdkToolScanner jdkToolScanner) {
        this(inventoryFile, workspace, reuseWorkspace, cleanWorkspace, failOnCheckoutError,
                scanProfile, credentials, artifactRoots, jdkToolScanner, null, false);
    }

    /**
     * @param compileChecker        javac --release 21 check of each checkout; {@code null} disables it
     * @param trunkValidatedDefault Trunk Validated value for inventory rows that leave the column blank
     */
    public SourceInventoryAnalyzer(Path inventoryFile, Path workspace, boolean reuseWorkspace,
                                   boolean cleanWorkspace, boolean failOnCheckoutError,
                                   SourceScanProfile scanProfile, CheckoutCredentials credentials,
                                   List<Path> artifactRoots, JdkToolScanner jdkToolScanner,
                                   CompileChecker compileChecker, boolean trunkValidatedDefault) {
        this.inventoryFile = inventoryFile;
        this.workspace = workspace;
        this.reuseWorkspace = reuseWorkspace;
        this.cleanWorkspace = cleanWorkspace;
        this.failOnCheckoutError = failOnCheckoutError;
        this.scanProfile = scanProfile;
        this.credentials = credentials == null ? CheckoutCredentials.none() : credentials;
        this.artifactRoots = artifactRoots == null ? List.of() : List.copyOf(artifactRoots);
        this.jdkToolScanner = jdkToolScanner;
        this.compileChecker = compileChecker;
        this.trunkValidatedDefault = trunkValidatedDefault;
    }

    public void run(String outputFile) throws IOException {
        List<SourceScanResult> results = scanInventory();
        new SourceInventoryReportWriter().write(outputFile, scanProfile.module(), results);
        logger.info("Source inventory scan complete: {} components", results.size());
    }

    public void appendToReport(String outputFile) throws IOException {
        List<SourceScanResult> results = scanInventory();
        new SourceInventoryReportWriter().append(outputFile, scanProfile.module(), results);
        logger.info("Source inventory sheets appended: {} components", results.size());
    }

    public List<SourceScanResult> scanInventory() throws IOException {
        List<SourceComponent> inventory = new SourceInventoryReader().read(inventoryFile).stream()
                .map(c -> c.withTrunkValidatedDefault(trunkValidatedDefault))
                .toList();
        RepositoryCheckoutService checkoutService = new RepositoryCheckoutService(workspace, reuseWorkspace, cleanWorkspace, credentials);
        SourceTreeScanner scanner = new SourceTreeScanner(scanProfile);
        GeneratedArtifactScanner artifactScanner = new GeneratedArtifactScanner(scanProfile);
        List<SourceScanResult> results = new ArrayList<>();

        for (SourceComponent component : inventory) {
            if (!component.enabled()) {
                CheckoutResult skipped = CheckoutResult.failure(component, null, "SKIPPED", "Inventory row disabled");
                results.add(new SourceScanResult(component, skipped, trunkSkipped(component), 0, List.of(), List.of()));
                continue;
            }

            logger.info("Preparing source component: {}", component.displayName());
            CheckoutResult checkout = checkoutService.checkout(component);
            if (!checkout.success()) {
                results.add(new SourceScanResult(component, checkout, trunkSkipped(component), 0, List.of(), List.of()));
                if (failOnCheckoutError) {
                    throw new IOException("Checkout failed for " + component.displayName() + ": " + checkout.message());
                }
                continue;
            }
            CheckoutResult trunkCheckout = checkoutService.checkoutTrunk(component);
            SourceScanResult sourceResult = scanner.scan(checkout);
            List<SourceFinding> trunkFindings = scanner.scan(trunkCheckout).findings();
            List<GeneratedArtifactScanner.BytecodeFinding> bytecodeFindings = artifactScanner.scan(component, checkout.checkoutPath(), artifactRoots);
            bytecodeFindings = withJdkToolFindings(component, checkout, bytecodeFindings);

            List<SourceFinding> findings = sourceResult.findings();
            CompileCheckResult compile = CompileCheckResult.notRun();
            if (compileChecker != null && scanProfile.hasJava21Rules()) {
                compile = compileChecker.check(component, checkout.checkoutPath());
                findings = CompileChecker.mergeWithSourceFindings(findings, compile.findings());
                bytecodeFindings = withCompiledOutputToolFindings(component, compile, findings, bytecodeFindings);
                CompileChecker.deleteOutput(compile);
                compile = compile.withoutClassesDir();
            }
            results.add(new SourceScanResult(component, checkout, trunkCheckout, sourceResult.filesScanned(), findings,
                    bytecodeFindings, trunkFindings, compile));
        }
        return results;
    }

    private List<GeneratedArtifactScanner.BytecodeFinding> withJdkToolFindings(
            SourceComponent component, CheckoutResult checkout, List<GeneratedArtifactScanner.BytecodeFinding> ruleFindings) {
        if (jdkToolScanner == null || !scanProfile.hasJava21Rules() || component.generatedJars().isEmpty()) return ruleFindings;
        try {
            List<Path> artifacts = GeneratedArtifactScanner.resolveGeneratedArtifacts(component, checkout.checkoutPath(), artifactRoots);
            return JdkToolScanner.mergeWithRuleFindings(ruleFindings, jdkToolScanner.scan(component, artifacts));
        } catch (IOException e) {
            logger.warn("JDK tool scan skipped for {}: {}", component.displayName(), e.getMessage());
            return ruleFindings;
        }
    }

    /**
     * Runs the JDK tools on javac output when the component compiled cleanly, so components without
     * pre-built Generated JARs still get jdeprscan/jdeps evidence. APIs already reported by the compiler
     * or another scanner are dropped.
     */
    private List<GeneratedArtifactScanner.BytecodeFinding> withCompiledOutputToolFindings(
            SourceComponent component, CompileCheckResult compile, List<SourceFinding> sourceFindings,
            List<GeneratedArtifactScanner.BytecodeFinding> bytecodeFindings) {
        if (jdkToolScanner == null || compile.classesDir() == null) return bytecodeFindings;
        Path jar = null;
        try {
            jar = Files.createTempFile("ea-" + safeName(component.displayName()) + "-javac-release21-", ".jar");
            jarDirectory(compile.classesDir(), jar);
            Set<String> knownRules = Stream.concat(sourceFindings.stream().map(SourceFinding::rule),
                            bytecodeFindings.stream().map(GeneratedArtifactScanner.BytecodeFinding::rule))
                    .collect(Collectors.toSet());
            List<GeneratedArtifactScanner.BytecodeFinding> fromOutput = jdkToolScanner.scan(component, List.of(jar)).stream()
                    .filter(f -> !knownRules.contains(f.rule()))
                    .toList();
            return JdkToolScanner.mergeWithRuleFindings(bytecodeFindings, fromOutput);
        } catch (IOException e) {
            logger.warn("JDK tool scan of compiled output skipped for {}: {}", component.displayName(), e.getMessage());
            return bytecodeFindings;
        } finally {
            if (jar != null) {
                try {
                    Files.deleteIfExists(jar);
                } catch (IOException ignored) {
                    // temp file cleanup is best effort
                }
            }
        }
    }

    private static void jarDirectory(Path dir, Path jar) throws IOException {
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os);
             Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                jos.putNextEntry(new JarEntry(dir.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jos);
                jos.closeEntry();
            }
        }
    }

    private static String safeName(String name) {
        return name == null ? "component" : name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static CheckoutResult trunkSkipped(SourceComponent component) {
        if (component.trunk().isBlank()) {
            return CheckoutResult.failure(component, null, "TRUNK_NOT_PROVIDED", "No Trunk value was supplied");
        }
        return CheckoutResult.failure(component, null, "TRUNK_SKIPPED", "Current source checkout did not complete; trunk checkout was skipped");
    }
}
