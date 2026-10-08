package effortanalyzer.source;

import java.nio.file.Files;
import java.util.List;

/**
 * Aggregated results for one source component scan.
 *
 * @param trunkFindings source findings from the trunk/reference checkout, used only for comparison
 * @param compileCheck  javac --release 21 outcome; {@link CompileCheckResult#notRun()} when disabled
 */
public record SourceScanResult(
        SourceComponent component,
        CheckoutResult checkout,
        CheckoutResult trunkCheckout,
        int filesScanned,
        List<SourceFinding> findings,
        List<GeneratedArtifactScanner.BytecodeFinding> bytecodeFindings,
        List<SourceFinding> trunkFindings,
        CompileCheckResult compileCheck
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

    public SourceScanResult(SourceComponent component, CheckoutResult checkout, CheckoutResult trunkCheckout,
                            int filesScanned, List<SourceFinding> findings,
                            List<GeneratedArtifactScanner.BytecodeFinding> bytecodeFindings) {
        this(component, checkout, trunkCheckout, filesScanned, findings, bytecodeFindings, List.of(), null);
    }

    public SourceScanResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        bytecodeFindings = bytecodeFindings == null ? List.of() : List.copyOf(bytecodeFindings);
        trunkFindings = trunkFindings == null ? List.of() : List.copyOf(trunkFindings);
        compileCheck = compileCheck == null ? CompileCheckResult.notRun() : compileCheck;
    }

    public boolean trunkAvailable() {
        return trunkCheckout != null && trunkCheckout.success() && trunkCheckout.checkoutPath() != null
                && Files.isDirectory(trunkCheckout.checkoutPath());
    }
}
