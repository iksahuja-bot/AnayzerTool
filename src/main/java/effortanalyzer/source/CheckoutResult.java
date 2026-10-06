package effortanalyzer.source;

import java.nio.file.Path;

/** Result of preparing a local working tree for a source component. */
public record CheckoutResult(
        SourceComponent component,
        Path checkoutPath,
        boolean success,
        String status,
        String message
) {
    public static CheckoutResult success(SourceComponent component, Path checkoutPath, String status, String message) {
        return new CheckoutResult(component, checkoutPath, true, status, message);
    }

    public static CheckoutResult failure(SourceComponent component, Path checkoutPath, String status, String message) {
        return new CheckoutResult(component, checkoutPath, false, status, message);
    }
}
