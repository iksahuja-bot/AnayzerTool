package effortanalyzer.source;

import effortanalyzer.wljboss.WlJBossRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SourceTreeScannerTest {

    @Test
    void wl14ProfileScansLocalJavaSourceWithWl14Rules(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source.resolve("src/main/java/com/example"));
        Files.writeString(source.resolve("src/main/java/com/example/App.java"), """
                package com.example;

                import weblogic.common.T3StartupDef;

                public class App implements T3StartupDef {
                }
                """);

        SourceComponent component = new SourceComponent("Comp", source.toString(), RepositoryType.LOCAL,
                "", "", "", true, 2);
        CheckoutResult checkout = CheckoutResult.success(component, source, "LOCAL", "Using local source directory");

        SourceTreeScanner scanner = new SourceTreeScanner(SourceScanProfile.forModule("wl14",
                WlJBossRules.TargetProfile.WILDFLY27_JAVA21));
        SourceScanResult result = scanner.scan(checkout);

        assertEquals(1, result.filesScanned());
        assertFalse(result.findings().isEmpty());
        assertTrue(result.findings().stream().anyMatch(f -> f.rule().contains("weblogic.common.T3StartupDef")));
    }

    @Test
    void wlJbossProfileDoesNotRunLibraryRules(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("App.java"), """
                import org.springframework.web.servlet.mvc.SimpleFormController;
                class App { }
                """);

        SourceComponent component = new SourceComponent("Comp", source.toString(), RepositoryType.LOCAL,
                "", "", "", true, 2);
        CheckoutResult checkout = CheckoutResult.success(component, source, "LOCAL", "Using local source directory");

        SourceTreeScanner scanner = new SourceTreeScanner(SourceScanProfile.forModule("wl-jboss26",
                WlJBossRules.TargetProfile.WILDFLY26_JAVA8));
        SourceScanResult result = scanner.scan(checkout);

        assertTrue(result.findings().stream().noneMatch(f -> "Library".equals(f.scanner())));
    }
}
