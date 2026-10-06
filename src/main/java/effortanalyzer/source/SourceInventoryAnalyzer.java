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

    public SourceInventoryAnalyzer(Path inventoryFile, Path workspace, boolean reuseWorkspace,
                                   boolean cleanWorkspace, boolean failOnCheckoutError,
                                   SourceScanProfile scanProfile, CheckoutCredentials credentials) {
        this.inventoryFile = inventoryFile;
        this.workspace = workspace;
        this.reuseWorkspace = reuseWorkspace;
        this.cleanWorkspace = cleanWorkspace;
        this.failOnCheckoutError = failOnCheckoutError;
        this.scanProfile = scanProfile;
        this.credentials = credentials == null ? CheckoutCredentials.none() : credentials;
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

    private List<SourceScanResult> scanInventory() throws IOException {
        List<SourceComponent> inventory = new SourceInventoryReader().read(inventoryFile);
        RepositoryCheckoutService checkoutService = new RepositoryCheckoutService(workspace, reuseWorkspace, cleanWorkspace, credentials);
        SourceTreeScanner scanner = new SourceTreeScanner(scanProfile);
        List<SourceScanResult> results = new ArrayList<>();

        for (SourceComponent component : inventory) {
            if (!component.enabled()) {
                CheckoutResult skipped = CheckoutResult.failure(component, null, "SKIPPED", "Inventory row disabled");
                results.add(new SourceScanResult(component, skipped, 0, List.of()));
                continue;
            }

            logger.info("Preparing source component: {}", component.displayName());
            CheckoutResult checkout = checkoutService.checkout(component);
            if (!checkout.success()) {
                results.add(new SourceScanResult(component, checkout, 0, List.of()));
                if (failOnCheckoutError) {
                    throw new IOException("Checkout failed for " + component.displayName() + ": " + checkout.message());
                }
                continue;
            }
            results.add(scanner.scan(checkout));
        }
        return results;
    }
}
