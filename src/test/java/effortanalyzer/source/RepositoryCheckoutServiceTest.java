package effortanalyzer.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryCheckoutServiceTest {

    @Test
    void checkoutTrunkPreparesSeparateLocalWorkspaceFromTrunkValue(@TempDir Path tmpDir) throws Exception {
        Path current = Files.createDirectories(tmpDir.resolve("current"));
        Path trunk = Files.createDirectories(tmpDir.resolve("trunk"));
        Path workspace = Files.createDirectories(tmpDir.resolve(".ea-workspace"));
        SourceComponent component = new SourceComponent("cluster", current.toString(), trunk.toString(),
                RepositoryType.LOCAL, "", "", "", true, 7, List.of(), List.of(), "application code");

        RepositoryCheckoutService checkoutService = new RepositoryCheckoutService(workspace, true, false);

        CheckoutResult currentCheckout = checkoutService.checkout(component);
        CheckoutResult trunkCheckout = checkoutService.checkoutTrunk(component);

        assertAll(
                () -> assertTrue(currentCheckout.success(), "current local checkout should succeed"),
                () -> assertTrue(trunkCheckout.success(), "trunk local checkout should succeed"),
                () -> assertEquals(current.toAbsolutePath().normalize(), currentCheckout.checkoutPath()),
                () -> assertEquals(trunk.toAbsolutePath().normalize(), trunkCheckout.checkoutPath()),
                () -> assertEquals(trunk.toString(), trunkCheckout.component().repository(), "trunk checkout should use the Trunk value as repository"),
                () -> assertEquals(RepositoryType.LOCAL, trunkCheckout.component().type(), "trunk type should be inferred from the Trunk value")
        );
    }
}