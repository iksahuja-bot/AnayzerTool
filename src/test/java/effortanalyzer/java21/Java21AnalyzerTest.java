package effortanalyzer.java21;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Java21AnalyzerTest {

    @Test
    void bytecodeRulesDoNotFlagJavaTransactionXaPackage(@TempDir Path tmpDir) throws Exception {
        Path jar = jarWithClassConstant(tmpDir, "javax/transaction/xa/XAResource");

        Java21Analyzer analyzer = new Java21Analyzer();
        analyzer.analyze(jar.toString());

        assertFalse(analyzer.getFindingsByJar().values().stream()
                .flatMap(java.util.Collection::stream)
                .anyMatch(finding -> finding.rule().apiPattern().startsWith("javax.transaction")),
                "javax.transaction.xa is still present in Java SE 21 and must not be flagged as removed");
    }

    @Test
    void bytecodeRulesStillFlagNonXaJavaTransactionApis(@TempDir Path tmpDir) throws Exception {
        Path jar = jarWithClassConstant(tmpDir, "javax/transaction/UserTransaction");

        Java21Analyzer analyzer = new Java21Analyzer();
        analyzer.analyze(jar.toString());

        assertTrue(analyzer.getFindingsByJar().values().stream()
                .flatMap(java.util.Collection::stream)
                .anyMatch(finding -> "javax.transaction.UserTransaction".equals(finding.rule().apiPattern())));
    }

    @Test
    void bytecodeRulesDoNotFlagApisStillPresentInJava21(@TempDir Path tmpDir) throws Exception {
        Path jar = jarWithClassConstants(tmpDir,
                "javax/script/ScriptEngineManager",
                "java/rmi/server/UnicastRemoteObject");

        Java21Analyzer analyzer = new Java21Analyzer();
        analyzer.analyze(jar.toString());

        assertTrue(analyzer.getFindingsByJar().isEmpty(),
                "ScriptEngineManager and UnicastRemoteObject are still present in Java 21");
    }

    @Test
    void bytecodeRulesDoNotMatchExplicitClassRulesAsPrefixes(@TempDir Path tmpDir) throws Exception {
        Path jar = jarWithClassConstant(tmpDir, "javax/transaction/TransactionSynchronizationRegistry");

        Java21Analyzer analyzer = new Java21Analyzer();
        analyzer.analyze(jar.toString());

        assertFalse(analyzer.getFindingsByJar().values().stream()
                .flatMap(java.util.Collection::stream)
                .anyMatch(finding -> "javax.transaction.Transaction".equals(finding.rule().apiPattern())
                        || "javax.transaction.Synchronization".equals(finding.rule().apiPattern())),
                "Explicit bytecode class rules must not match longer class names by prefix");
    }

    @Test
    void packagedTextRulesDoNotMatchExplicitClassRulesAsPrefixes(@TempDir Path tmpDir) throws Exception {
        Path jar = jarWithTextEntry(tmpDir, "META-INF/sample.properties",
                "type=javax.transaction.TransactionSynchronizationRegistry\n");

        Java21Analyzer analyzer = new Java21Analyzer();
        analyzer.analyze(jar.toString());

        assertFalse(analyzer.getFindingsByJar().values().stream()
                .flatMap(java.util.Collection::stream)
                .anyMatch(finding -> "javax.transaction.Transaction".equals(finding.rule().apiPattern())
                        || "javax.transaction.Synchronization".equals(finding.rule().apiPattern())),
                "Explicit packaged text rules must not match longer class names by prefix");
    }

    private static Path jarWithClassConstant(Path tmpDir, String constant) throws Exception {
        return jarWithClassConstants(tmpDir, constant);
    }

    private static Path jarWithClassConstants(Path tmpDir, String... constants) throws Exception {
        Path jar = tmpDir.resolve("sample.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("com/example/Dummy.class"));
            out.write(classBytes(constants));
            out.closeEntry();
        }
        return jar;
    }

    private static Path jarWithTextEntry(Path tmpDir, String entryName, String content) throws Exception {
        Path jar = tmpDir.resolve("sample.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(entryName));
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static byte[] classBytes(String... extraUtf8Constants) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(61);
            out.writeShort(5 + extraUtf8Constants.length);
            out.writeByte(1);
            out.writeUTF("com/example/Dummy");
            out.writeByte(7);
            out.writeShort(1);
            out.writeByte(1);
            out.writeUTF("java/lang/Object");
            out.writeByte(7);
            out.writeShort(3);
            for (String constant : extraUtf8Constants) {
                out.writeByte(1);
                out.writeUTF(constant);
            }
            out.writeShort(0x0021);
            out.writeShort(2);
            out.writeShort(4);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
        }
        return bytes.toByteArray();
    }
}