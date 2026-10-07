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

    @Test
    void libraryRulesDoNotFlagSimpleClassNameInCommentsWithoutImport(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("App.java"), """
                package com.example;

                class App {
                    // Client authentication should not match Jersey Client.
                    String message = "Client properties are specified";
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().noneMatch(f -> f.rule().contains("com.sun.jersey.api.client.Client")),
                "Plain word/comment/string 'Client' without Jersey import or FQN must not be flagged");
    }

    @Test
    void libraryRulesFlagSimpleClassNameWhenImportMatchesRule(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("App.java"), """
                package com.example;

                import com.sun.jersey.api.client.Client;

                class App {
                    Client client = Client.create();
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().anyMatch(f -> f.rule().contains("com.sun.jersey.api.client.Client")));
    }

    @Test
    void libraryRulesFlagFullyQualifiedClassNameWithoutImport(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("App.java"), """
                package com.example;

                class App {
                    com.sun.jersey.api.client.Client client = com.sun.jersey.api.client.Client.create();
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().anyMatch(f -> f.rule().contains("com.sun.jersey.api.client.Client")));
    }

    @Test
    void java21RulesFlagRemovedJavaApis(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("App.java"), """
                package com.example;

                import javax.xml.bind.JAXBContext;

                class App {
                    JAXBContext context;
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().anyMatch(f -> "Java 21".equals(f.scanner())
                && f.rule().equals("javax.xml.bind")));
    }

    @Test
    void java21RulesDoNotFlagJavaTransactionXaPackage(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("XaParticipant.java"), """
                import javax.transaction.xa.XAResource;
                import javax.transaction.xa.Xid;

                class XaParticipant {
                    XAResource resource;
                    Xid xid;
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().noneMatch(f -> "Java 21".equals(f.scanner())
                && f.rule().startsWith("javax.transaction")),
                "javax.transaction.xa is still present in Java SE 21 and must not be flagged as removed");
    }

    @Test
    void java21RulesStillFlagNonXaJavaTransactionApis(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("TransactionalBean.java"), """
                import javax.transaction.UserTransaction;

                class TransactionalBean {
                    UserTransaction tx;
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().anyMatch(f -> "Java 21".equals(f.scanner())
                && f.rule().equals("javax.transaction.UserTransaction")));
    }

    @Test
    void java21RulesDoNotMatchTransactionRuleAsPrefixOfTransactional(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("AnnotatedBean.java"), """
                import javax.transaction.Transactional;

                @Transactional
                class AnnotatedBean {
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().noneMatch(f -> "Java 21".equals(f.scanner())
                && f.rule().equals("javax.transaction.Transaction")));
        assertTrue(result.findings().stream().anyMatch(f -> "Java 21".equals(f.scanner())
                && f.rule().equals("javax.transaction.Transactional")));
    }

    @Test
    void java21RulesDoNotFlagJavaAnnotationProcessingPackage(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("DemoProcessor.java"), """
                import javax.annotation.processing.AbstractProcessor;
                import javax.annotation.processing.Processor;
                import javax.annotation.processing.SupportedAnnotationTypes;
                import javax.annotation.processing.RoundEnvironment;

                @SupportedAnnotationTypes("*")
                class DemoProcessor extends AbstractProcessor implements Processor {
                    RoundEnvironment roundEnvironment;
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().noneMatch(f -> "Java 21".equals(f.scanner())
                && f.rule().startsWith("javax.annotation")),
                "javax.annotation.processing is provided by java.compiler in Java SE 21 and must not be flagged as removed");
    }

    @Test
    void java21RulesStillFlagRemovedCommonAnnotations(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("LifecycleBean.java"), """
                import javax.annotation.PostConstruct;

                class LifecycleBean {
                    @PostConstruct
                    void init() {
                    }
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().anyMatch(f -> "Java 21".equals(f.scanner())
                && f.rule().equals("javax.annotation.PostConstruct")));
    }

    @Test
    void java21RulesDoNotMatchResourceRuleAsPrefixOfResources(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("ResourceHolder.java"), """
                import javax.annotation.Resources;

                @Resources({})
                class ResourceHolder {
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().noneMatch(f -> "Java 21".equals(f.scanner())
                && f.rule().equals("javax.annotation.Resource")));
        assertTrue(result.findings().stream().anyMatch(f -> "Java 21".equals(f.scanner())
                && f.rule().equals("javax.annotation.Resources")));
    }

    @Test
    void java21RulesDoNotMatchExplicitClassRuleInsideLongerIdentifier(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("RegistryHolder.java"), """
                import javax.transaction.TransactionSynchronizationRegistry;

                class RegistryHolder {
                    TransactionSynchronizationRegistry registry;
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().noneMatch(f -> "Java 21".equals(f.scanner())
                && (f.rule().equals("javax.transaction.Transaction")
                || f.rule().equals("javax.transaction.Synchronization"))),
                "Explicit class rules must not match inside longer class identifiers");
    }

    @Test
    void java21RulesDoNotFlagJjsTokenInJavaSource(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("Naming.java"), """
                class Naming {
                    Object jjs;
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().noneMatch(f -> "Java 21".equals(f.scanner())
                && f.rule().equals("jjs")),
                "The removed jjs tool rule should apply to scripts/config text, not Java identifiers");
    }

    @Test
    void java21RulesIgnoreCommentsAndStringsInJavaSource(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("App.java"), """
                package com.example;

                class App {
                    // import javax.xml.bind.JAXBContext;
                    String text = "javax.xml.bind.JAXBContext";
                }
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().noneMatch(f -> "Java 21".equals(f.scanner())
                && f.rule().equals("javax.xml.bind")));
    }

    @Test
    void java21RulesFlagMavenJavaLevelBelow21(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("pom.xml"), """
                <project>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                  </properties>
                </project>
                """);

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().anyMatch(f -> "Java 21".equals(f.scanner())
                && "BUILD_JAVA_LEVEL".equals(f.category())));
    }

    @Test
    void java21RulesFlagObsoleteJvmArgs(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("gradle.properties"), "org.gradle.jvmargs=-XX:+UseConcMarkSweepGC -Xmx2g");

        SourceScanResult result = scanWithWl14Profile(source);

        assertTrue(result.findings().stream().anyMatch(f -> "Java 21".equals(f.scanner())
                && f.rule().equals("UseConcMarkSweepGC")));
    }

    @Test
    void java21RulesAreDisabledForJava8WlJbossProfile(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source);
        Files.writeString(source.resolve("App.java"), """
                import javax.xml.bind.JAXBContext;
                class App { JAXBContext context; }
                """);

        SourceComponent component = new SourceComponent("Comp", source.toString(), RepositoryType.LOCAL,
                "", "", "", true, 2);
        CheckoutResult checkout = CheckoutResult.success(component, source, "LOCAL", "Using local source directory");
        SourceTreeScanner scanner = new SourceTreeScanner(SourceScanProfile.forModule("wl-jboss26",
                WlJBossRules.TargetProfile.WILDFLY26_JAVA8));
        SourceScanResult result = scanner.scan(checkout);

        assertTrue(result.findings().stream().noneMatch(f -> "Java 21".equals(f.scanner())));
    }

    private static SourceScanResult scanWithWl14Profile(Path source) throws Exception {
        SourceComponent component = new SourceComponent("Comp", source.toString(), RepositoryType.LOCAL,
                "", "", "", true, 2);
        CheckoutResult checkout = CheckoutResult.success(component, source, "LOCAL", "Using local source directory");
        SourceTreeScanner scanner = new SourceTreeScanner(SourceScanProfile.forModule("wl14",
                WlJBossRules.TargetProfile.WILDFLY27_JAVA21));
        return scanner.scan(checkout);
    }
}
