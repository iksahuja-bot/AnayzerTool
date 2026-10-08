package effortanalyzer.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JdkToolScannerTest {

    private static final SourceComponent COMPONENT = new SourceComponent("PAL", "file:///repo/pal", RepositoryType.LOCAL,
            "", "", "", true, 2, List.of("target/pal.jar"), List.of("com.example"), "application code");

    @Test
    void builtInCheckFlagsRemovedJavaClassesAndMembersExactly(@TempDir Path tmpDir) throws Exception {
        byte[] app = new ClassBytes("com/example/App")
                .classRef("java/security/acl/Acl")
                .classRef("java/util/ArrayList")
                .methodRef("java/lang/Thread", "destroy", "()V")
                .methodRef("java/lang/String", "length", "()I")
                .methodRef("java/lang/Class", "newInstance", "()Ljava/lang/Object;")
                .methodRef("java/lang/reflect/Constructor", "newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;")
                .methodRef("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                .methodRef("java/util/List", "hashCode", "()I")
                .methodRef("java/lang/invoke/MethodHandle", "invokeExact", "(Ljava/lang/String;)V")
                .fieldRef("java/lang/System", "out", "Ljava/io/PrintStream;")
                .bytes();
        Path jar = jar(tmpDir.resolve("pal.jar"), Map.of("com/example/App.class", app));

        List<GeneratedArtifactScanner.BytecodeFinding> findings = noTools().scan(COMPONENT, List.of(jar));

        assertAll(
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("java.security.acl.Acl")
                        && JdkToolScanner.JDK_REMOVED_API.equals(f.category()) && "CRITICAL".equals(f.severity())
                        && f.className().equals("com.example.App"))),
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("java.lang.Thread.destroy()")
                        && JdkToolScanner.JDK_REMOVED_API.equals(f.category()))),
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("java.lang.Class.newInstance()")
                        && JdkToolScanner.JDK_DEPRECATED.equals(f.category()) && "INFO".equals(f.severity()))),
                () -> assertEquals(3, findings.size(), "existing members, Constructor.newInstance and signature-polymorphic calls must not be reported: " + findings),
                () -> assertTrue(findings.stream().allMatch(f -> JdkToolScanner.SCANNER.equals(f.scanner())))
        );
    }

    @Test
    void emptyInputProducesNoFindings(@TempDir Path tmpDir) throws Exception {
        Path jar = jar(tmpDir.resolve("empty.jar"), Map.of());
        assertTrue(noTools().scan(COMPONENT, List.of()).isEmpty());
        assertTrue(noTools().scan(COMPONENT, List.of(jar)).isEmpty());
        assertTrue(JdkToolScanner.parseJdeprscan(COMPONENT, "x.jar", List.of()).isEmpty());
        assertTrue(JdkToolScanner.parseJdeps(COMPONENT, "x.jar", List.of()).isEmpty());
    }

    @Test
    void parsesJdeprscanOutputIntoSeverityByKind() {
        List<String> output = List.of(
                "Jar file pal.jar:",
                "class com/example/App uses deprecated class java/util/Observable ",
                "class com/example/App uses deprecated class java/lang/SecurityManager (forRemoval=true)",
                "class com/example/M uses deprecated method java/lang/Integer::<init>(I)V (forRemoval=true)",
                "class com/example/M uses deprecated method java/lang/Thread::stop()V (forRemoval=true)",
                "error: cannot find class com/example/Missing");

        List<GeneratedArtifactScanner.BytecodeFinding> findings = JdkToolScanner.parseJdeprscan(COMPONENT, "pal.jar", output);

        assertAll(
                () -> assertEquals(4, findings.size()),
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("java.util.Observable")
                        && JdkToolScanner.JDK_DEPRECATED.equals(f.category()) && "INFO".equals(f.severity()))),
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("java.lang.SecurityManager")
                        && JdkToolScanner.JDK_DEPRECATED_FOR_REMOVAL.equals(f.category()) && "MEDIUM".equals(f.severity()))),
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("new java.lang.Integer(...)")
                        && f.remediation().contains("Integer.valueOf(...)"))),
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("java.lang.Thread.stop()")
                        && JdkToolScanner.JDK_UNSUPPORTED_AT_RUNTIME.equals(f.category()) && "CRITICAL".equals(f.severity())
                        && f.className().equals("com.example.M") && f.classEntry().equals("com/example/M.class")))
        );
    }

    @Test
    void parsesJdepsOutputWithSuggestedReplacements() {
        List<String> output = List.of(
                "pal.jar -> JDK removed internal API",
                "pal.jar -> java.base",
                "pal.jar -> jdk.unsupported",
                "   com.example.App                                    -> sun.misc.Service                                   JDK removed internal API",
                "   com.example.App                                    -> sun.misc.Unsafe                                    JDK internal API (jdk.unsupported)",
                "   com.example.App                                    -> sun.security.x509.X500Name                         JDK internal API (java.base)",
                "",
                "Warning: JDK internal APIs are unsupported and private to JDK implementation that are",
                "",
                "JDK Internal API                         Suggested Replacement",
                "----------------                         ---------------------",
                "sun.misc.Service                         Use java.util.ServiceLoader @since 1.6",
                "sun.misc.Unsafe                          See https://openjdk.org/jeps/260",
                "sun.security.x509.X500Name               Use javax.security.auth.x500.X500Principal @since 1.4");

        List<GeneratedArtifactScanner.BytecodeFinding> findings = JdkToolScanner.parseJdeps(COMPONENT, "pal.jar", output);

        assertAll(
                () -> assertEquals(3, findings.size()),
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("sun.misc.Service")
                        && JdkToolScanner.JDK_REMOVED_INTERNAL_API.equals(f.category()) && "CRITICAL".equals(f.severity())
                        && f.remediation().contains("java.util.ServiceLoader"))),
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("sun.misc.Unsafe")
                        && JdkToolScanner.JDK_UNSUPPORTED_API.equals(f.category()) && "MEDIUM".equals(f.severity()))),
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("sun.security.x509.X500Name")
                        && JdkToolScanner.JDK_INTERNAL_API.equals(f.category()) && "HIGH".equals(f.severity())))
        );
    }

    @Test
    void ruleFindingForSameClassSuppressesDuplicateJdkFinding() {
        var rule = new GeneratedArtifactScanner.BytecodeFinding("PAL", "r", RepositoryType.LOCAL, "pal.jar", "com/example/App.class",
                "com.example.App", "Java 21", "JAVA_REMOVED", "CRITICAL", "sun.misc.Service", "d", "r");
        var duplicate = new GeneratedArtifactScanner.BytecodeFinding("PAL", "r", RepositoryType.LOCAL, "pal.jar", "com/example/App.class",
                "com.example.App", JdkToolScanner.SCANNER, JdkToolScanner.JDK_REMOVED_INTERNAL_API, "CRITICAL", "sun.misc.Service", "d", "r");
        var otherClass = new GeneratedArtifactScanner.BytecodeFinding("PAL", "r", RepositoryType.LOCAL, "pal.jar", "com/example/Other.class",
                "com.example.Other", JdkToolScanner.SCANNER, JdkToolScanner.JDK_REMOVED_INTERNAL_API, "CRITICAL", "sun.misc.Service", "d", "r");

        var merged = JdkToolScanner.mergeWithRuleFindings(List.of(rule), List.of(duplicate, otherClass));

        assertEquals(List.of(rule, otherClass), merged);
    }

    @Test
    void runsInjectedToolsWithRelease21Arguments(@TempDir Path tmpDir) throws Exception {
        Path jar = jar(tmpDir.resolve("pal.jar"), Map.of());
        List<List<String>> commands = new ArrayList<>();
        JdkToolScanner scanner = new JdkToolScanner(Optional.of(Path.of("jdeprscan")), Optional.of(Path.of("jdeps")), command -> {
            commands.add(command);
            return command.get(0).endsWith("jdeprscan")
                    ? List.of("class com/example/App uses deprecated class java/util/Observable ")
                    : List.of("   com.example.App -> sun.misc.Service   JDK removed internal API");
        });

        List<GeneratedArtifactScanner.BytecodeFinding> findings = scanner.scan(COMPONENT, List.of(jar));

        assertAll(
                () -> assertEquals(List.of("jdeprscan", "--release", "21", jar.toString()), commands.get(0)),
                () -> assertTrue(commands.get(1).containsAll(List.of("--jdk-internals", "--multi-release", "21", "--ignore-missing-deps"))),
                () -> assertEquals(2, findings.size())
        );
    }

    @Test
    void realJdkToolsReportOwnerResolvedFindings(@TempDir Path tmpDir) throws Exception {
        JdkToolScanner scanner = JdkToolScanner.detect();
        assumeTrue(scanner.hasExternalTools(), "jdeprscan/jdeps not available on this JVM");
        byte[] app = new ClassBytes("com/example/App")
                .classRef("sun/misc/Service")
                .classRef("java/util/Observable")
                .methodRef("java/lang/Thread", "stop", "()V")
                .bytes();
        Path jar = jar(tmpDir.resolve("pal.jar"), Map.of("com/example/App.class", app));

        List<GeneratedArtifactScanner.BytecodeFinding> findings = scanner.scan(COMPONENT, List.of(jar));

        assertAll(
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("sun.misc.Service")
                        && JdkToolScanner.JDK_REMOVED_INTERNAL_API.equals(f.category())), findings::toString),
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("java.util.Observable")
                        && JdkToolScanner.JDK_DEPRECATED.equals(f.category())), findings::toString),
                () -> assertTrue(findings.stream().anyMatch(f -> f.rule().equals("java.lang.Thread.stop()")
                        && JdkToolScanner.JDK_UNSUPPORTED_AT_RUNTIME.equals(f.category())), findings::toString)
        );
    }

    private static JdkToolScanner noTools() {
        return new JdkToolScanner(Optional.empty(), Optional.empty(), command -> List.of());
    }

    private static Path jar(Path jar, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    /** Minimal valid class file whose constant pool holds exactly the requested references. */
    static final class ClassBytes {
        private final ByteArrayOutputStream pool = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(pool);
        private final Map<String, Integer> utf8 = new HashMap<>();
        private final Map<String, Integer> classes = new HashMap<>();
        private int next = 1;
        private final int thisClass;
        private final int superClass;

        ClassBytes(String name) throws IOException {
            thisClass = classIndex(name);
            superClass = classIndex("java/lang/Object");
        }

        ClassBytes classRef(String name) throws IOException {
            classIndex(name);
            return this;
        }

        ClassBytes methodRef(String owner, String name, String descriptor) throws IOException {
            return memberRef(10, owner, name, descriptor);
        }

        ClassBytes fieldRef(String owner, String name, String descriptor) throws IOException {
            return memberRef(9, owner, name, descriptor);
        }

        byte[] bytes() throws IOException {
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(file);
            d.writeInt(0xCAFEBABE);
            d.writeShort(0);
            d.writeShort(65);
            d.writeShort(next);
            d.write(pool.toByteArray());
            d.writeShort(0x0021);
            d.writeShort(thisClass);
            d.writeShort(superClass);
            d.writeShort(0);
            d.writeShort(0);
            d.writeShort(0);
            d.writeShort(0);
            return file.toByteArray();
        }

        private ClassBytes memberRef(int tag, String owner, String name, String descriptor) throws IOException {
            int ownerIndex = classIndex(owner);
            int nameIndex = utf8Index(name);
            int descIndex = utf8Index(descriptor);
            out.writeByte(12);
            out.writeShort(nameIndex);
            out.writeShort(descIndex);
            int nat = next++;
            out.writeByte(tag);
            out.writeShort(ownerIndex);
            out.writeShort(nat);
            next++;
            return this;
        }

        private int classIndex(String name) throws IOException {
            Integer existing = classes.get(name);
            if (existing != null) return existing;
            int nameIndex = utf8Index(name);
            out.writeByte(7);
            out.writeShort(nameIndex);
            classes.put(name, next);
            return next++;
        }

        private int utf8Index(String value) throws IOException {
            Integer existing = utf8.get(value);
            if (existing != null) return existing;
            out.writeByte(1);
            out.writeUTF(value);
            utf8.put(value, next);
            return next++;
        }
    }
}
