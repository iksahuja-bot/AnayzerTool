package effortanalyzer.source;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        this.inventoryFile = inventoryFile;
        this.workspace = workspace;
        this.reuseWorkspace = reuseWorkspace;
        this.cleanWorkspace = cleanWorkspace;
        this.failOnCheckoutError = failOnCheckoutError;
        this.scanProfile = scanProfile;
        this.credentials = credentials == null ? CheckoutCredentials.none() : credentials;
        this.artifactRoots = artifactRoots == null ? List.of() : List.copyOf(artifactRoots);
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
        List<SourceComponent> inventory = new SourceInventoryReader().read(inventoryFile);
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
            List<GeneratedArtifactScanner.BytecodeFinding> bytecodeFindings = artifactScanner.scan(component, checkout.checkoutPath(), artifactRoots);
            results.add(new SourceScanResult(component, checkout, trunkCheckout, sourceResult.filesScanned(), sourceResult.findings(), bytecodeFindings));
        }
        return results;
    }

    private static CheckoutResult trunkSkipped(SourceComponent component) {
        if (component.trunk().isBlank()) {
            return CheckoutResult.failure(component, null, "TRUNK_NOT_PROVIDED", "No Trunk value was supplied");
        }
        return CheckoutResult.failure(component, null, "TRUNK_SKIPPED", "Current source checkout did not complete; trunk checkout was skipped");
    }
}
