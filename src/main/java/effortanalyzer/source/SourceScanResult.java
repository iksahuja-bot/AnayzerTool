package effortanalyzer.source;

import java.util.List;

/** Aggregated results for one source component scan. */
public record SourceScanResult(
        SourceComponent component,
        CheckoutResult checkout,
        CheckoutResult trunkCheckout,
        int filesScanned,
        List<SourceFinding> findings,
        List<GeneratedArtifactScanner.BytecodeFinding> bytecodeFindings
) {
    public SourceScanResult(SourceComponent component, CheckoutResult checkout, int filesScanned,
                            List<SourceFinding> findings) {
        this(component, checkout, null, filesScanned, findings, List.of());
    }

    public SourceScanResult(SourceComponent component, CheckoutResult checkout, int filesScanned,
                            List<SourceFinding> findings,
                            List<GeneratedArtifactScanner.BytecodeFinding> bytecodeFindings) {
        this(component, checkout, null, filesScanned, findings, bytecodeFindings);
    }

    public SourceScanResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        bytecodeFindings = bytecodeFindings == null ? List.of() : List.copyOf(bytecodeFindings);
    }
}
