package effortanalyzer.source;

import java.util.List;

/** Aggregated results for one source component scan. */
public record SourceScanResult(
        SourceComponent component,
        CheckoutResult checkout,
        int filesScanned,
        List<SourceFinding> findings
) {}
