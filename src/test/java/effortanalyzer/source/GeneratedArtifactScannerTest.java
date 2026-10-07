package effortanalyzer.source;

import effortanalyzer.wljboss.WlJBossRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedArtifactScannerTest {

    @Test
    void correlatesJava21SourceFindingWithGeneratedJarBytecode(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source.resolve("src/main/java/com/example"));
        Files.writeString(source.resolve("src/main/java/com/example/App.java"), """
                package com.example;
                import javax.xml.bind.JAXBContext;
                public class App {
                    JAXBContext context;
                }
                """);

        Path jar = createJarWithClassReferencingConstant(tmpDir, "com/example/App.class", "javax/xml/bind/JAXBContext");
        Path relativeJar = source.relativize(jar);
        SourceComponent component = new SourceComponent("Comp", source.toString(), RepositoryType.LOCAL,
                "", "", "", true, 2, List.of(relativeJar.toString()), List.of("com.example"), "application code");
        CheckoutResult checkout = CheckoutResult.success(component, source, "LOCAL", "Using local source directory");
        SourceScanProfile profile = SourceScanProfile.forModule("wl14", WlJBossRules.TargetProfile.WILDFLY27_JAVA21);

        SourceScanResult sourceResult = new SourceTreeScanner(profile).scan(checkout);
        List<GeneratedArtifactScanner.BytecodeFinding> bytecode = new GeneratedArtifactScanner(profile).scan(component, source);
        SourceScanResult combined = new SourceScanResult(component, checkout, sourceResult.filesScanned(), sourceResult.findings(), bytecode);

        List<SourceFinding> correlated = SourceFindingCorrelator.correlate(combined);

        assertTrue(correlated.stream().anyMatch(f -> "javax.xml.bind".equals(f.rule())
                && "CONFIRMED_SOURCE_AND_BYTECODE".equals(f.validationStatus())
                && "HIGH".equals(f.confidence())
                && f.matchedInSource()
                && f.matchedInBytecode()
                && f.matchedJars().contains(jar.getFileName().toString())));
    }

    @Test
    void sourceOnlyFindingIsCandidateNotSuppressed(@TempDir Path tmpDir) throws Exception {
        Path source = tmpDir.resolve("component");
        Files.createDirectories(source.resolve("src/main/java/com/example"));
        Files.writeString(source.resolve("src/main/java/com/example/App.java"), """
                package com.example;
                import javax.xml.bind.JAXBContext;
                public class App { JAXBContext context; }
                """);

        SourceComponent component = new SourceComponent("Comp", source.toString(), RepositoryType.LOCAL,
                "", "", "", true, 2, List.of("target/missing.jar"), List.of("com.example"), "");
        CheckoutResult checkout = CheckoutResult.success(component, source, "LOCAL", "Using local source directory");
        SourceScanProfile profile = SourceScanProfile.forModule("wl14", WlJBossRules.TargetProfile.WILDFLY27_JAVA21);
        SourceScanResult sourceResult = new SourceTreeScanner(profile).scan(checkout);
        SourceScanResult combined = new SourceScanResult(component, checkout, sourceResult.filesScanned(), sourceResult.findings(), List.of());

        List<SourceFinding> correlated = SourceFindingCorrelator.correlate(combined);

        assertTrue(correlated.stream().anyMatch(f -> "javax.xml.bind".equals(f.rule())
                && "CANDIDATE_SOURCE_ONLY".equals(f.validationStatus())
                && "SOURCE_ONLY_NOT_IN_COMPILED_JARS".equals(f.reasonCode())));
    }

    @Test
    void resolvesGeneratedArtifactsFromSuppliedCompiledInputDirectory(@TempDir Path tmpDir) throws Exception {
        Path checkout = tmpDir.resolve("checkout");
        Path compiledInput = tmpDir.resolve("compiled-input");
        Files.createDirectories(checkout);
        Files.createDirectories(compiledInput);

        Path jar = compiledInput.resolve("app-core.jar");
        Files.writeString(jar, "fake jar");
        SourceComponent component = new SourceComponent("Comp", checkout.toString(), RepositoryType.LOCAL,
                "", "", "", true, 2, List.of("target/app-core.jar"), List.of(), "");

        List<Path> resolved = GeneratedArtifactScanner.resolveGeneratedArtifacts(component, checkout, List.of(compiledInput));

        assertEquals(List.of(jar), resolved);
    }

    private static Path createJarWithClassReferencingConstant(Path tmpDir, String classEntry, String constant) throws Exception {
        Path srcDir = tmpDir.resolve("compile-src");
        Path classesDir = tmpDir.resolve("classes");
        Files.createDirectories(srcDir);
        Files.createDirectories(classesDir);
        Path sourceFile = srcDir.resolve("Holder.java");
        Files.writeString(sourceFile, "public class Holder { static final String VALUE = \"" + constant + "\"; }\n");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK, not a JRE");
        int exit = compiler.run(null, null, null, "-d", classesDir.toString(), sourceFile.toString());
        assertEquals(0, exit);

        Path compiled = classesDir.resolve("Holder.class");
        Path jar = tmpDir.resolve("component").resolve("target").resolve("app-core.jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            out.putNextEntry(new JarEntry(classEntry));
            out.write(Files.readAllBytes(compiled));
            out.closeEntry();
        }
        return jar;
    }
}
