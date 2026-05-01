package effortanalyzer.java21;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Rule database for Java 8 → Java 21 upgrade compatibility analysis.
 *
 * Inspired by the IBM Migration Toolkit for Application Binaries (WAMT)
 * approach to scanning binaries for Java version incompatibilities.
 *
 * Categories (aligned with IBM WAMT taxonomy):
 *
 *   JAVA_REMOVED      – APIs removed in Java 9–21 (JEP 320, JEP 407, JEP 398, etc.)
 *   JAVA_INTERNAL     – JDK internal APIs (sun.*, com.sun.*, jdk.internal.*)
 *                       inaccessible without --add-opens in Java 11+/17+
 *   JAVA_DEPRECATED   – APIs deprecated-for-removal in Java 9–21
 *   JAVA_BEHAVIOR     – APIs with changed behaviour between Java 8 and 21
 *   JVM_ARGS          – JVM flags removed or renamed (detected in .properties / .sh / .bat)
 *   JDK_MODULES       – Module system access violations (unnamed module access)
 *
 * Severity:
 *   CRITICAL – will fail at startup or class-load time without remediation
 *   HIGH     – likely runtime failure under the relevant code path
 *   MEDIUM   – potential behaviour change requiring testing
 *   INFO     – best practice or future-proofing recommendation
 *
 * Scan modes:
 *   BOTH     – search source files (.) and compiled bytecode (/ separator)
 *   SOURCE   – source / XML / properties files only
 *   BYTECODE – compiled class files only
 */
public class Java21Rules {

    public enum ScanMode { BOTH, SOURCE, BYTECODE }

    public record Rule(
            String id,
            String category,
            String apiPattern,
            String severity,
            String description,
            String remediation,
            String javaVersion,   // first Java version where this became a problem
            ScanMode scanMode,
            /**
             * IBM Transformation Advisor (WAMT) equivalent rule name for cross-referencing.
             * Allows the upgrade-excluded-rules.txt to use either our rule ID or the IBM TA name.
             * Null if there is no direct IBM TA equivalent.
             */
            String ibmAlias
    ) {
        /** Pattern as it appears in bytecode constant pool (/ instead of .) */
        public String bytecodePattern() {
            return apiPattern.replace('.', '/');
        }

        /** Short display label for the "Introduced in" column */
        public String versionLabel() {
            return javaVersion == null ? "" : "Java " + javaVersion;
        }

        /**
         * Returns true if this rule matches any entry in the exclusion set.
         * Matches are tried against: our rule ID, the IBM TA alias, and the api pattern.
         */
        public boolean isExcluded(Set<String> excluded) {
            if (excluded.isEmpty()) return false;
            return excluded.contains(id)
                    || (ibmAlias != null && excluded.contains(ibmAlias))
                    || excluded.contains(apiPattern);
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    private Java21Rules() {
        loadRemovedApiRules();
        loadInternalApiRules();
        loadDeprecatedForRemovalRules();
        loadBehaviorChangeRules();
        loadJvmArgRules();
        loadModuleSystemRules();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Factory
    // ──────────────────────────────────────────────────────────────────────────

    /** Load all rules with no exclusions. */
    public static Java21Rules load() {
        return load(Set.of());
    }

    /**
     * Load rules, filtering out any whose ID, IBM TA alias, or api pattern
     * appears in {@code excluded}.
     *
     * @param excluded set of rule IDs / IBM TA names / api patterns to skip.
     *                 Populate from {@link #loadExcludedRules()} to read the standard file.
     */
    public static Java21Rules load(Set<String> excluded) {
        Java21Rules db = new Java21Rules();
        if (!excluded.isEmpty()) {
            db.rules.removeIf(r -> r.isExcluded(excluded));
        }
        db.loadCustomRules();
        return db;
    }

    /**
     * Reads upgrade-excluded-rules.txt from the working directory or classpath,
     * returning all non-comment, non-blank entries as a Set.
     * Each line may have an inline # comment which is stripped.
     */
    public static Set<String> loadExcludedRules() {
        Set<String> excluded = new LinkedHashSet<>();
        Path externalFile = Path.of("upgrade-excluded-rules.txt");
        if (Files.exists(externalFile)) {
            try (var reader = Files.newBufferedReader(externalFile)) {
                readExcludedRules(reader, excluded);
            } catch (IOException e) {
                System.err.println("Warning: could not read upgrade-excluded-rules.txt: " + e.getMessage());
            }
            return excluded;
        }
        try (InputStream is = Java21Rules.class.getClassLoader()
                .getResourceAsStream("upgrade-excluded-rules.txt")) {
            if (is != null) {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                    readExcludedRules(reader, excluded);
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read upgrade-excluded-rules.txt from classpath: " + e.getMessage());
        }
        return excluded;
    }

    private static void readExcludedRules(java.io.BufferedReader reader, Set<String> out) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            int commentIdx = line.indexOf('#');
            if (commentIdx >= 0) line = line.substring(0, commentIdx);
            line = line.trim();
            if (!line.isEmpty()) out.add(line);
        }
    }

    public List<Rule> getRules() { return Collections.unmodifiableList(rules); }

    // ──────────────────────────────────────────────────────────────────────────
    // Category 1 – JAVA_REMOVED (JEP 320 / JEP 407 / JEP 398 / JEP 372)
    // ──────────────────────────────────────────────────────────────────────────

    private void loadRemovedApiRules() {

        // ── JEP 320: Remove the Java EE and CORBA modules (Java 11) ──────────
        add("JR-001", "JAVA_REMOVED", "javax.xml.bind", "CRITICAL",
                "JAXB (javax.xml.bind.*) was removed from the JDK in Java 11 (JEP 320)",
                "Add an explicit JAXB dependency:\n"
                + "  <dependency>\n"
                + "    <groupId>jakarta.xml.bind</groupId>\n"
                + "    <artifactId>jakarta.xml.bind-api</artifactId>\n"
                + "    <version>4.0.0</version>\n"
                + "  </dependency>\n"
                + "  <dependency>\n"
                + "    <groupId>com.sun.xml.bind</groupId>\n"
                + "    <artifactId>jaxb-impl</artifactId>\n"
                + "    <version>4.0.4</version>\n"
                + "    <scope>runtime</scope>\n"
                + "  </dependency>",
                "11", ScanMode.BOTH, "RemovedJaxBModuleNotProvided");

        add("JR-002", "JAVA_REMOVED", "javax.xml.ws", "CRITICAL",
                "JAX-WS (javax.xml.ws.*) was removed from the JDK in Java 11 (JEP 320)",
                "Add an explicit JAX-WS dependency:\n"
                + "  <dependency>\n"
                + "    <groupId>jakarta.xml.ws</groupId>\n"
                + "    <artifactId>jakarta.xml.ws-api</artifactId>\n"
                + "    <version>4.0.0</version>\n"
                + "  </dependency>\n"
                + "  <dependency>\n"
                + "    <groupId>com.sun.xml.ws</groupId>\n"
                + "    <artifactId>jaxws-ri</artifactId>\n"
                + "    <version>4.0.2</version>\n"
                + "    <type>pom</type>\n"
                + "  </dependency>",
                "11", ScanMode.BOTH, "RemovedJavaXMLWSModuleNotProvided");

        add("JR-003", "JAVA_REMOVED", "javax.xml.soap", "CRITICAL",
                "SAAJ (javax.xml.soap.*) was removed from the JDK in Java 11 (JEP 320)",
                "Add explicit SAAJ dependency: jakarta.xml.soap:jakarta.xml.soap-api:3.0.0 "
                + "and implementation com.sun.xml.messaging.saaj:saaj-impl.",
                "11", ScanMode.BOTH, "RemovedSAAJModuleNotProvided");

        add("JR-004", "JAVA_REMOVED", "javax.activation", "CRITICAL",
                "JavaBeans Activation Framework (javax.activation.*) removed from JDK in Java 11 (JEP 320)",
                "Add explicit dependency:\n"
                + "  <dependency>\n"
                + "    <groupId>jakarta.activation</groupId>\n"
                + "    <artifactId>jakarta.activation-api</artifactId>\n"
                + "    <version>2.1.2</version>\n"
                + "  </dependency>",
                "11", ScanMode.BOTH, "RemovedActivationModuleNotProvided");

        add("JR-005", "JAVA_REMOVED", "javax.jws", "HIGH",
                "JAX-WS annotations (javax.jws.*) removed from JDK in Java 11 (JEP 320)",
                "Add jakarta.jws:jakarta.jws-api dependency or include via the JAX-WS API umbrella artifact.",
                "11", ScanMode.BOTH, "RemovedJWSAnnotationModuleNotProvided");

        add("JR-006", "JAVA_REMOVED", "org.omg", "CRITICAL",
                "CORBA (org.omg.*) was removed from the JDK in Java 11 (JEP 320). "
                + "javax.rmi.PortableRemoteObject (which depends on CORBA) no longer works.",
                "Remove all CORBA usage. Replace remote EJB calls with EJB 3.x @Remote, REST (JAX-RS), "
                + "or messaging (JMS). Replace PortableRemoteObject.narrow() with plain Java casts.",
                "11", ScanMode.BOTH, "DetectCorbaJava");

        add("JR-007", "JAVA_REMOVED", "com.sun.corba", "HIGH",
                "Sun CORBA implementation classes (com.sun.corba.*) removed in Java 11 (JEP 320)",
                "Remove all CORBA usage. Migrate remote communication to REST or EJB 3.x @Remote.",
                "11", ScanMode.BOTH, "DetectCorbaJava");

        // ── JEP 372: Remove Nashorn JS Engine (Java 15) ──────────────────────
        add("JR-008", "JAVA_REMOVED", "jdk.nashorn", "CRITICAL",
                "Nashorn JavaScript engine (jdk.nashorn.*) was removed in Java 15 (JEP 372)",
                "Replace with GraalVM JavaScript (org.graalvm.js:js:23.x) or Rhino (org.mozilla:rhino). "
                + "GraalVM example:\n"
                + "  Context ctx = Context.create(\"js\");\n"
                + "  ctx.eval(\"js\", \"console.log('hello')\");",
                "15", ScanMode.BOTH, "RemovedNashornJSEngine");

        add("JR-009", "JAVA_REMOVED", "javax.script.ScriptEngineManager", "MEDIUM",
                "ScriptEngineManager is still present but Nashorn (the default JS engine) is removed in Java 15. "
                + "Code that creates a ScriptEngine named 'JavaScript' or 'nashorn' will get null at runtime.",
                "Replace Nashorn with GraalVM JavaScript. Check the engine name: use 'js' for GraalVM.",
                "15", ScanMode.BOTH, "RemovedNashornJSEngine");

        // ── JEP 407: Remove RMI Activation (Java 17) ─────────────────────────
        add("JR-010", "JAVA_REMOVED", "java.rmi.activation", "CRITICAL",
                "RMI Activation (java.rmi.activation.*) was removed in Java 17 (JEP 407). "
                + "ActivationGroup, ActivationDesc, Activatable are all gone.",
                "Remove all RMI Activation usage. Replace with EJB remote, REST services, "
                + "or message-driven beans depending on the use case.",
                "17", ScanMode.BOTH, "RemovedRMIActivation");

        add("JR-011", "JAVA_REMOVED", "java.rmi.server.UnicastRemoteObject", "MEDIUM",
                "UnicastRemoteObject remains in Java 21 but the broader RMI Activation system "
                + "it relied on is gone. Existing IIOP-based patterns will not work.",
                "Migrate remote communication to EJB 3.x @Remote, REST (JAX-RS), or JMS.",
                "17", ScanMode.BOTH, "RemovedRMIActivation");

        // ── JEP 398: Deprecate/Remove Applet API (Java 17) ───────────────────
        add("JR-012", "JAVA_REMOVED", "java.applet", "CRITICAL",
                "Applet API (java.applet.*) was deprecated in Java 9 and removed in Java 17 (JEP 398). "
                + "JApplet, Applet, AppletContext, AppletStub are all gone.",
                "Migrate applet code to a standard web application (HTML5 + JavaScript, "
                + "or a Java servlet-based webapp). Applets are no longer supported in any modern browser.",
                "17", ScanMode.BOTH, "DeprecatedAppletAPI");

        add("JR-013", "JAVA_REMOVED", "javax.swing.JApplet", "CRITICAL",
                "javax.swing.JApplet was deprecated in Java 9 and removed in Java 17 (JEP 398).",
                "Migrate to a Swing desktop application or a web application.",
                "17", ScanMode.BOTH, "DeprecatedAppletAPI");

        // ── Java 15: Removed -XX:+UseStringDeduplication default + other GC removals ─

        // ── Java 16: Removed deprecated primitive wrapper constructors ────────
        add("JR-014", "JAVA_REMOVED", "new Integer(", "HIGH",
                "Primitive wrapper constructors (new Integer(), new Long(), new Boolean(), etc.) "
                + "were deprecated in Java 9 and removed in Java 16.",
                "Use valueOf() instead:\n"
                + "  new Integer(42)  →  Integer.valueOf(42)\n"
                + "  new Long(100L)   →  Long.valueOf(100L)\n"
                + "  new Boolean(b)   →  Boolean.valueOf(b)",
                "16", ScanMode.SOURCE, "DeprecatedPrimitiveClassConstructors");

        add("JR-015", "JAVA_REMOVED", "new Long(", "HIGH",
                "Primitive wrapper constructors (new Long()) deprecated in Java 9, removed in Java 16.",
                "Use Long.valueOf() instead.",
                "16", ScanMode.SOURCE, "DeprecatedPrimitiveClassConstructors");

        add("JR-016", "JAVA_REMOVED", "new Double(", "HIGH",
                "Primitive wrapper constructors (new Double()) deprecated in Java 9, removed in Java 16.",
                "Use Double.valueOf() instead.",
                "16", ScanMode.SOURCE, "DeprecatedPrimitiveClassConstructors");

        add("JR-017", "JAVA_REMOVED", "new Boolean(", "HIGH",
                "Primitive wrapper constructors (new Boolean()) deprecated in Java 9, removed in Java 16.",
                "Use Boolean.valueOf() instead.",
                "16", ScanMode.SOURCE, "DeprecatedPrimitiveClassConstructors");

        add("JR-018", "JAVA_REMOVED", "new Float(", "HIGH",
                "Primitive wrapper constructors (new Float()) deprecated in Java 9, removed in Java 16.",
                "Use Float.valueOf() instead.",
                "16", ScanMode.SOURCE, "DeprecatedPrimitiveClassConstructors");

        // ── Java 9: java.util.logging.LogManager.addLogger removed from some JVMs ─
        add("JR-019", "JAVA_REMOVED", "com.sun.tools.javac", "HIGH",
                "com.sun.tools.javac.* (compiler internals) are inaccessible in Java 9+ by default",
                "Remove dependency on javac internals. Use javax.annotation.processing.* "
                + "or the javax.tools.* API for compiler interaction.",
                "9", ScanMode.BOTH, "DetectJdkCompilerInternalApi");

        // ── JEP 261 / JDK 11: JavaFX removed from OpenJDK ───────────────────
        add("JR-021", "JAVA_REMOVED", "javafx.", "CRITICAL",
                "The JavaFX modules were removed from the OpenJDK distribution in Java 11. "
                + "Any code importing javafx.* will fail to compile/run unless JavaFX is added as an explicit dependency.",
                "Add OpenJFX as an explicit Maven dependency:\n"
                + "  <dependency>\n"
                + "    <groupId>org.openjfx</groupId>\n"
                + "    <artifactId>javafx-controls</artifactId>\n"
                + "    <version>21</version>\n"
                + "  </dependency>\n"
                + "And configure the javafx-maven-plugin or javafx-gradle-plugin.",
                "11", ScanMode.BOTH, "RemovedJavaFX");

        // ── JEP 320: Remove java.transaction module (Java 11) ────────────────
        add("JR-020", "JAVA_REMOVED", "javax.transaction", "HIGH",
                "javax.transaction.* (JTA) was bundled in the JDK's java.transaction module which "
                + "was removed in Java 11 (JEP 320). Code that relies on the JDK-provided JTA classes "
                + "will throw NoClassDefFoundError at runtime.",
                "Add an explicit JTA dependency:\n"
                + "  <dependency>\n"
                + "    <groupId>jakarta.transaction</groupId>\n"
                + "    <artifactId>jakarta.transaction-api</artifactId>\n"
                + "    <version>2.0.1</version>\n"
                + "  </dependency>\n"
                + "For Java EE 8 / WildFly 26 environments, use:\n"
                + "  javax.transaction:javax.transaction-api:1.3 (scope provided)",
                "11", ScanMode.BOTH, "RemovedTransactionModule");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Category 2 – JAVA_INTERNAL (sun.*, com.sun.*, jdk.internal.*)
    // ──────────────────────────────────────────────────────────────────────────

    private void loadInternalApiRules() {

        add("JI-001", "JAVA_INTERNAL", "sun.misc.Unsafe", "HIGH",
                "sun.misc.Unsafe is a JDK internal API restricted in Java 9+ via strong encapsulation. "
                + "Requires --add-opens java.base/sun.misc=ALL-UNNAMED in Java 11+. "
                + "May be removed in a future JDK version.",
                "Use java.lang.invoke.VarHandle (Java 9+) for low-level memory access, "
                + "or java.lang.invoke.MethodHandles for reflection access.",
                "9", ScanMode.BOTH, "RemovedSunAPIs");

        add("JI-002", "JAVA_INTERNAL", "sun.misc.BASE64Encoder", "CRITICAL",
                "sun.misc.BASE64Encoder/Decoder are removed in Java 11. "
                + "They were internal APIs not available from Java 8 standard library.",
                "Replace with java.util.Base64 (available since Java 8):\n"
                + "  // Encode:\n"
                + "  String encoded = Base64.getEncoder().encodeToString(bytes);\n"
                + "  // Decode:\n"
                + "  byte[] decoded = Base64.getDecoder().decode(encodedStr);",
                "11", ScanMode.BOTH, "RemovedSunBASE64EncoderDecoder");

        add("JI-003", "JAVA_INTERNAL", "sun.misc.BASE64Decoder", "CRITICAL",
                "sun.misc.BASE64Decoder is removed in Java 11.",
                "Replace with java.util.Base64.getDecoder().",
                "11", ScanMode.BOTH, "RemovedSunBASE64EncoderDecoder");

        add("JI-004", "JAVA_INTERNAL", "sun.reflect", "HIGH",
                "sun.reflect.* is a JDK internal package inaccessible without --add-opens in Java 11+. "
                + "Replaced by standard java.lang.reflect API and MethodHandles.privateLookupIn().",
                "Use standard java.lang.reflect.* API. "
                + "For private access: MethodHandles.privateLookupIn(target, MethodHandles.lookup()). "
                + "If a library requires sun.reflect, add to JVM args: "
                + "--add-opens java.base/sun.reflect=ALL-UNNAMED",
                "9", ScanMode.BOTH, "RemovedSunAPIs");

        add("JI-005", "JAVA_INTERNAL", "sun.security", "HIGH",
                "sun.security.* internal security classes are inaccessible in Java 11+ "
                + "without explicit --add-opens flags.",
                "Use standard java.security.* API. "
                + "For certificate/key handling use java.security.KeyStore, KeyFactory, etc.",
                "11", ScanMode.BOTH, "RemovedSunAPIs");

        add("JI-006", "JAVA_INTERNAL", "com.sun.xml.internal", "CRITICAL",
                "com.sun.xml.internal.* is the JDK's bundled JAXB implementation, "
                + "removed in Java 11 along with the JAXB module (JEP 320).",
                "Add explicit JAXB dependency (jakarta.xml.bind:jakarta.xml.bind-api + jaxb-impl). "
                + "Replace all com.sun.xml.internal.* imports with the standard API.",
                "11", ScanMode.BOTH, "RemovedSunAPIs");

        add("JI-007", "JAVA_INTERNAL", "com.sun.net.httpserver", "MEDIUM",
                "com.sun.net.httpserver.* is an internal JDK HTTP server implementation. "
                + "While still accessible in Java 21, it is not a public API and may change.",
                "For test/embedded HTTP servers, use a portable library such as "
                + "com.sun.net.httpserver.HttpServer remains available in Java 21 but consider "
                + "migrating to a stable alternative (Javalin, Undertow embedded, or WireMock for tests).",
                "9", ScanMode.BOTH, "RemovedSunAPIs");

        add("JI-008", "JAVA_INTERNAL", "jdk.internal", "CRITICAL",
                "jdk.internal.* packages are strictly internal JDK APIs inaccessible in Java 9+ "
                + "without dangerous --add-exports flags.",
                "Remove all jdk.internal.* usage. Use the public java.* / javax.* equivalents. "
                + "If a third-party library pulls this in, upgrade the library.",
                "9", ScanMode.BOTH, "RemovedSunAPIs");

        add("JI-009", "JAVA_INTERNAL", "sun.nio", "MEDIUM",
                "sun.nio.* internal NIO classes are inaccessible in Java 11+ by default.",
                "Use standard java.nio.* public API. All sun.nio functionality has stable equivalents "
                + "in java.nio.channels, java.nio.file, and java.nio.ByteBuffer.",
                "11", ScanMode.BOTH, "RemovedSunAPIs");

        add("JI-010", "JAVA_INTERNAL", "sun.awt", "MEDIUM",
                "sun.awt.* internal AWT classes restricted in Java 9+ (strong encapsulation).",
                "Use public java.awt.* API. Avoid sun.awt.AppContext and related internal classes.",
                "9", ScanMode.BOTH, "RemovedSunAPIs");

        add("JI-011", "JAVA_INTERNAL", "sun.font", "MEDIUM",
                "sun.font.* internal font classes restricted in Java 9+.",
                "Use java.awt.Font and public AWT font APIs.",
                "9", ScanMode.BOTH, "RemovedSunAPIs");

        add("JI-012", "JAVA_INTERNAL", "sun.util.calendar", "MEDIUM",
                "sun.util.calendar.* internal calendar classes restricted in Java 9+.",
                "Use java.util.Calendar or java.time.* (preferred in Java 8+).",
                "9", ScanMode.BOTH, "RemovedSunAPIs");

        add("JI-013", "JAVA_INTERNAL", "com.sun.image.codec", "CRITICAL",
                "com.sun.image.codec.jpeg.* removed in Java 11.",
                "Use javax.imageio.ImageIO for reading/writing JPEG images.",
                "11", ScanMode.BOTH, "RemovedJPEGPackage");

        add("JI-014", "JAVA_INTERNAL", "com.sun.jdi", "INFO",
                "com.sun.jdi.* (Java Debug Interface) is a supported JDK API but "
                + "delivered via tools.jar in Java 8 and the jdk.jdi module in Java 9+.",
                "Update the build to depend on the jdk.jdi module instead of tools.jar. "
                + "The API itself is unchanged.",
                "9", ScanMode.BOTH, "RemovedSunAPIs");

        add("JI-015", "JAVA_INTERNAL", "sun.invoke", "HIGH",
                "sun.invoke.* internal method-handle classes restricted in Java 11+.",
                "Use java.lang.invoke.MethodHandles and VarHandle which cover the same functionality.",
                "11", ScanMode.BOTH, "RemovedSunAPIs");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Category 3 – JAVA_DEPRECATED (deprecated-for-removal in Java 9–21)
    // ──────────────────────────────────────────────────────────────────────────

    private void loadDeprecatedForRemovalRules() {

        // ── Thread API – removed in Java 21 ──────────────────────────────────
        add("JD-001", "JAVA_DEPRECATED", "Thread.stop", "CRITICAL",
                "Thread.stop() was deprecated since Java 1.2 and REMOVED in Java 21. "
                + "It is inherently unsafe and causes unpredictable behaviour.",
                "Redesign thread lifecycle management:\n"
                + "  1. Use a volatile boolean flag: while (!stopped) { ... }\n"
                + "  2. Use Thread.interrupt() + InterruptedException handling\n"
                + "  3. Use java.util.concurrent primitives (Future.cancel(), "
                + "ExecutorService.shutdownNow())",
                "21", ScanMode.BOTH, "DetectThreadStop");

        add("JD-002", "JAVA_DEPRECATED", "Thread.suspend", "CRITICAL",
                "Thread.suspend() removed in Java 21. "
                + "Can cause deadlocks — was already unsafe in Java 8.",
                "Use Object.wait() / Object.notify() or java.util.concurrent.locks.LockSupport "
                + "instead of suspend/resume.",
                "21", ScanMode.BOTH, "DetectThreadStop");

        add("JD-003", "JAVA_DEPRECATED", "Thread.resume", "CRITICAL",
                "Thread.resume() removed in Java 21 (paired with removed Thread.suspend()).",
                "Use Object.wait() / Object.notify() or LockSupport.park/unpark.",
                "21", ScanMode.BOTH, "DetectThreadStop");

        add("JD-004", "JAVA_DEPRECATED", "Thread.countStackFrames", "CRITICAL",
                "Thread.countStackFrames() removed in Java 21 (depended on Thread.suspend()).",
                "Use java.lang.StackWalker (Java 9+) for stack frame inspection:\n"
                + "  long count = StackWalker.getInstance().walk(s -> s.count());",
                "21", ScanMode.BOTH, "DetectThreadStop");

        add("JD-005", "JAVA_DEPRECATED", "Thread.destroy", "CRITICAL",
                "Thread.destroy() was already removed before Java 21 (threw UnsupportedOperationException). "
                + "Any reference is dead code.",
                "Remove all calls to Thread.destroy(). Use Thread.interrupt() for cooperative cancellation.",
                "17", ScanMode.BOTH, "DetectThreadStop");

        // ── SecurityManager – deprecated in Java 17, planned for removal ──────
        add("JD-006", "JAVA_DEPRECATED", "SecurityManager", "HIGH",
                "java.lang.SecurityManager was deprecated for removal in Java 17 (JEP 411). "
                + "Setting a SecurityManager throws an UnsupportedOperationException in Java 21.",
                "Remove all SecurityManager.setSecurityManager() calls and policy files. "
                + "Modern application servers (WildFly, Liberty) do not require a SecurityManager. "
                + "Use Elytron security or equivalent container security instead.",
                "17", ScanMode.BOTH, "DeprecatedSecurityManager");

        add("JD-007", "JAVA_DEPRECATED", "System.setSecurityManager", "HIGH",
                "System.setSecurityManager() deprecated for removal in Java 17; "
                + "throws UnsupportedOperationException in Java 21.",
                "Remove the System.setSecurityManager() call and related SecurityManager installation.",
                "17", ScanMode.BOTH, "DeprecatedSecurityManager");

        add("JD-008", "JAVA_DEPRECATED", "System.getSecurityManager", "MEDIUM",
                "System.getSecurityManager() deprecated for removal in Java 17; "
                + "returns null in Java 21.",
                "Remove checks that rely on getSecurityManager(). "
                + "Code guarded by 'if (System.getSecurityManager() != null)' will silently be skipped.",
                "17", ScanMode.BOTH, "DeprecatedSecurityManager");

        // ── Object.finalize() – JEP 421 ─────────────────────────────────────
        add("JD-009", "JAVA_DEPRECATED", "finalize", "HIGH",
                "Object.finalize() is deprecated for removal (JEP 421, Java 18). "
                + "The JVM may not call finalizers at all in future JDK versions.",
                "Replace with try-with-resources for resource cleanup:\n"
                + "  public class MyResource implements AutoCloseable {\n"
                + "    @Override public void close() { /* cleanup */ }\n"
                + "  }\n"
                + "Or use java.lang.ref.Cleaner (Java 9+) for post-GC cleanup.",
                "18", ScanMode.BOTH, "FinalizationDeprecated");

        // ── java.util.zip finalize methods ────────────────────────────────────
        add("JD-010", "JAVA_DEPRECATED", "ZipFile.finalize", "MEDIUM",
                "java.util.zip.ZipFile.finalize() deprecated for removal. "
                + "Resource management should use try-with-resources.",
                "Wrap ZipFile in try-with-resources:\n"
                + "  try (ZipFile zf = new ZipFile(file)) { ... }",
                "18", ScanMode.SOURCE, "FinalizationDeprecated");

        // ── Deprecated encoding/decoding ─────────────────────────────────────
        add("JD-011", "JAVA_DEPRECATED", "java.net.URLEncoder.encode(String)", "MEDIUM",
                "URLEncoder.encode(String) (single-arg, using platform default encoding) deprecated in Java 1.1. "
                + "Behaviour is platform-specific and unreliable.",
                "Use URLEncoder.encode(String, StandardCharsets.UTF_8) explicitly.",
                "8", ScanMode.SOURCE, "DeprecatedURLEncoder");

        add("JD-012", "JAVA_DEPRECATED", "new Date(String)", "MEDIUM",
                "Date(String) constructor deprecated since Java 1.1. "
                + "Parsing is locale-sensitive and unreliable.",
                "Use java.time.LocalDate.parse() or java.time.ZonedDateTime.parse() "
                + "with an explicit DateTimeFormatter.",
                "8", ScanMode.SOURCE, "DeprecatedDateConstructor");

        add("JD-013", "JAVA_DEPRECATED", "Date.toLocaleString", "MEDIUM",
                "Date.toLocaleString() deprecated since Java 1.1 — locale-sensitive and non-deterministic.",
                "Use DateTimeFormatter with an explicit locale:\n"
                + "  DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.US)",
                "8", ScanMode.SOURCE, "DeprecatedDateMethods");

        // ── Parallel API removed ─────────────────────────────────────────────
        add("JD-014", "JAVA_DEPRECATED", "javax.management.loading.MLet", "INFO",
                "javax.management.loading.MLet deprecated for removal in Java 21.",
                "Avoid MLet-based dynamic classloading. Use OSGi or standard classloading mechanisms.",
                "21", ScanMode.BOTH, "DeprecatedMLetUsage");

        // ── ThreadGroup / Thread deprecated methods (Java 21) ────────────────
        add("JD-015", "JAVA_DEPRECATED", "ThreadGroup", "MEDIUM",
                "Several java.lang.ThreadGroup methods are deprecated for removal in Java 21 "
                + "(destroy(), isDestroyed(), setDaemon(), isDaemon(), resume(), stop(), suspend()). "
                + "ThreadGroup.enumerate() and activeCount() are also unreliable.",
                "Stop using ThreadGroup directly. Use java.util.concurrent (ExecutorService, "
                + "ThreadFactory) for thread lifecycle management. "
                + "Replace ThreadGroup.activeCount() with ExecutorService tracking.",
                "21", ScanMode.BOTH, "DeprecatedThreadGroupMethods");

        // ── javax.security.auth.Subject deprecated methods (Java 17) ─────────
        add("JD-016", "JAVA_DEPRECATED", "javax.security.auth.Subject", "MEDIUM",
                "Subject.getSubject() and Subject.doAs() are deprecated in Java 17 (JEP 411) "
                + "and may be removed in a future release. They depend on SecurityManager which is also deprecated.",
                "Use Subject.current() (Java 18+) and Subject.callAs() as replacements. "
                + "For compatibility, check if your application server provides an alternative "
                + "subject propagation mechanism (e.g., WildFly Elytron).",
                "17", ScanMode.BOTH, "DeprecatedJavaxSecurityAuth");

        // ── java.security.cert deprecated APIs (Java 21) ─────────────────────
        add("JD-017", "JAVA_DEPRECATED", "java.security.cert", "INFO",
                "Several java.security.cert APIs were deprecated in Java 9–21, including "
                + "Certificate.encode(), X509Certificate.getIssuerDN(), getSubjectDN() "
                + "(replaced by getIssuerX500Principal(), getSubjectX500Principal()).",
                "Replace deprecated X500Principal accessors:\n"
                + "  cert.getIssuerDN()    →  cert.getIssuerX500Principal()\n"
                + "  cert.getSubjectDN()   →  cert.getSubjectX500Principal()\n"
                + "Use CertificateFactory.getInstance(\"X.509\") for modern cert handling.",
                "21", ScanMode.BOTH, "DeprecatedSecurityCertAPIs");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Category 4 – JAVA_BEHAVIOR (changed semantics in Java 9–21)
    // ──────────────────────────────────────────────────────────────────────────

    private void loadBehaviorChangeRules() {

        // ── String changes ────────────────────────────────────────────────────
        add("JB-001", "JAVA_BEHAVIOR", "String.getBytes()", "MEDIUM",
                "String.getBytes() with no args uses the platform default charset. "
                + "The default charset changed from system-locale in Java 8 to UTF-8 in Java 17 (JEP 400). "
                + "Applications that relied on a non-UTF-8 default (e.g., latin-1 on Windows) will break.",
                "Always specify the charset explicitly:\n"
                + "  str.getBytes(StandardCharsets.UTF_8)  // preferred\n"
                + "  str.getBytes(\"UTF-8\")",
                "17", ScanMode.SOURCE, "CLDRLocaleDataByDefault");

        add("JB-002", "JAVA_BEHAVIOR", "new String(bytes)", "MEDIUM",
                "new String(byte[]) with no charset uses the platform default charset. "
                + "Default changed to UTF-8 in Java 17 (JEP 400).",
                "Always specify charset: new String(bytes, StandardCharsets.UTF_8)",
                "17", ScanMode.SOURCE, "DefaultCharsetPrintWriter");

        add("JB-003", "JAVA_BEHAVIOR", "InputStreamReader(stream)", "MEDIUM",
                "InputStreamReader(InputStream) with no charset uses platform default. "
                + "Default changed to UTF-8 in Java 17 (JEP 400).",
                "Use InputStreamReader(stream, StandardCharsets.UTF_8) explicitly.",
                "17", ScanMode.SOURCE, "DefaultCharsetPrintWriter");

        add("JB-004", "JAVA_BEHAVIOR", "OutputStreamWriter(stream)", "MEDIUM",
                "OutputStreamWriter(OutputStream) with no charset uses platform default. "
                + "Default changed to UTF-8 in Java 17 (JEP 400).",
                "Use OutputStreamWriter(stream, StandardCharsets.UTF_8) explicitly.",
                "17", ScanMode.SOURCE, "DefaultCharsetPrintWriter");

        add("JB-005", "JAVA_BEHAVIOR", "FileReader(", "MEDIUM",
                "FileReader(File) / FileReader(String) with no charset uses platform default charset. "
                + "Default changed to UTF-8 in Java 17.",
                "Use FileReader(file, StandardCharsets.UTF_8) (available since Java 11).",
                "17", ScanMode.SOURCE, "DefaultCharsetPrintWriter");

        add("JB-006", "JAVA_BEHAVIOR", "FileWriter(", "MEDIUM",
                "FileWriter(File) / FileWriter(String) with no charset uses platform default charset. "
                + "Default changed to UTF-8 in Java 17.",
                "Use FileWriter(file, StandardCharsets.UTF_8) (available since Java 11).",
                "17", ScanMode.SOURCE, "DefaultCharsetPrintWriter");

        // ── Reflection encapsulation ──────────────────────────────────────────
        add("JB-007", "JAVA_BEHAVIOR", "setAccessible(true)", "HIGH",
                "Field.setAccessible(true) / Method.setAccessible(true) may throw "
                + "InaccessibleObjectException in Java 9+ when accessing JDK internal modules "
                + "without the corresponding --add-opens flag.",
                "Prefer accessing public APIs. If private access is required, use a "
                + "MethodHandles.privateLookupIn() approach. Add --add-opens as a fallback:\n"
                + "  --add-opens java.base/java.lang=ALL-UNNAMED\n"
                + "Audit all setAccessible(true) calls and assess which are on JDK classes.",
                "9", ScanMode.SOURCE, "DisableAccessibilityChecks");

        // ── HashMap / Hashtable iteration order ───────────────────────────────
        add("JB-008", "JAVA_BEHAVIOR", "Hashtable", "MEDIUM",
                "java.util.Hashtable is synchronized but has no guaranteed iteration order. "
                + "Java 21 uses an improved HashMap implementation — iteration order may differ. "
                + "Code relying on Hashtable/HashMap ordering will be fragile.",
                "If ordering matters, use LinkedHashMap. For thread-safety, use ConcurrentHashMap.",
                "21", ScanMode.SOURCE, "HashtableSynchronized");

        // ── Serialization filter (JEP 290 / JEP 415) ─────────────────────────
        add("JB-009", "JAVA_BEHAVIOR", "ObjectInputStream", "INFO",
                "Java 9+ (JEP 290) added serialization filters. Java 21 configures a default "
                + "reject-all filter for RMI. Deserialization of arbitrary classes may be blocked.",
                "Configure an explicit serialization filter:\n"
                + "  ObjectInputFilter filter = ObjectInputFilter.Config.createFilter(\"mypackage.*;!*\");\n"
                + "  ois.setObjectInputFilter(filter);\n"
                + "Or set system property: jdk.serialFilter=pattern",
                "9", ScanMode.BOTH, "ObjectInputStreamGetField");

        // ── Process API ───────────────────────────────────────────────────────
        add("JB-010", "JAVA_BEHAVIOR", "Runtime.exec", "INFO",
                "Runtime.exec(String) tokenizes the command string in a platform-specific way. "
                + "In Java 9+ this tokenisation changed slightly — always use the String[] form.",
                "Replace Runtime.exec(String cmd) with Runtime.exec(String[] cmdArray) "
                + "or use ProcessBuilder for full control.",
                "9", ScanMode.SOURCE, "RuntimeExecAPI");

        // ── Try-with-resources + AutoCloseable ────────────────────────────────
        add("JB-011", "JAVA_BEHAVIOR", "Runtime.getRuntime().gc()", "INFO",
                "System.gc() / Runtime.gc() behaviour changed in Java 21 with ZGC/Shenandoah. "
                + "It is now merely advisory — the JVM may ignore it.",
                "Remove explicit gc() calls. GC tuning should be done via JVM flags, not programmatically.",
                "21", ScanMode.SOURCE, "RuntimeGCBehavior");

        // ── PermGen replaced by Metaspace ─────────────────────────────────────
        add("JB-012", "JAVA_BEHAVIOR", "PermGen", "INFO",
                "PermGen was replaced by Metaspace in Java 8. "
                + "If code comments or scripts reference PermGen sizing, those references are stale.",
                "Remove PermGen references from documentation and scripts. "
                + "Use -XX:MaxMetaspaceSize= instead of -XX:MaxPermSize=.",
                "8", ScanMode.SOURCE, "PerGenRemoved");

        // ── java.io.File.toURL() ──────────────────────────────────────────────
        add("JB-013", "JAVA_BEHAVIOR", "File.toURL", "MEDIUM",
                "java.io.File.toURL() was deprecated in Java 1.4 and does not correctly escape "
                + "special characters in file paths — results in malformed URLs on Java 11+.",
                "Replace with File.toURI().toURL() which correctly handles spaces and special characters.",
                "11", ScanMode.SOURCE, "FileToURLDeprecated");

        // ── javax.net.ssl changes (TLS 1.0/1.1 disabled by default) ──────────
        add("JB-014", "JAVA_BEHAVIOR", "SSLSocket", "MEDIUM",
                "TLS 1.0 and TLS 1.1 are disabled by default in Java 11+. "
                + "Connections to servers that only support TLS 1.0/1.1 will fail.",
                "Ensure all TLS connections use TLS 1.2 or TLS 1.3. "
                + "Update server/client configurations. "
                + "Temporary workaround (not recommended): edit java.security to re-enable TLS 1.0.",
                "11", ScanMode.BOTH, "TLSProtocolsChanged");

        // ── XML factory loading ───────────────────────────────────────────────
        add("JB-015", "JAVA_BEHAVIOR", "DocumentBuilderFactory.newInstance", "INFO",
                "XML factory loading order changed in Java 9+. "
                + "ServiceLoader is now used before the legacy system property. "
                + "Third-party XML parser JARs bundled in the application now take precedence.",
                "Specify the factory implementation explicitly if a specific parser is required:\n"
                + "  DocumentBuilderFactory.newInstance(\"com.example.MyFactory\", null)",
                "9", ScanMode.SOURCE, "XMLFactoryLoader");

        // ── Java 17: Unicode regex boundary behaviour change ──────────────────
        add("JB-016", "JAVA_BEHAVIOR", "Pattern.compile", "MEDIUM",
                "Java 17 changed Unicode character-class matching in java.util.regex. "
                + "The \\b (word boundary) anchor and some \\p{} Unicode categories may behave "
                + "differently. Regex patterns that worked correctly in Java 8 can produce different "
                + "match results when run on Java 17+.",
                "Test all regular expressions against a Java 17+ runtime. "
                + "For explicit, predictable Unicode-aware matching use the UNICODE_CHARACTER_CLASS flag:\n"
                + "  Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS)\n"
                + "Pay particular attention to patterns that use \\b, \\w, or \\d with non-ASCII text.",
                "17", ScanMode.SOURCE, "jre17RegexBehaviorChange");

        // ── Java 17: Weak named EC curves disabled ────────────────────────────
        add("JB-017", "JAVA_BEHAVIOR", "secp256k1", "HIGH",
                "Java 17 disabled the secp256k1 elliptic curve by default (considered cryptographically "
                + "weak per NIST). Code that uses ECGenParameterSpec(\"secp256k1\") or TLS ciphers "
                + "referencing this curve will throw an InvalidAlgorithmParameterException at runtime.",
                "Migrate to a NIST-approved elliptic curve:\n"
                + "  secp256k1 → secp256r1 (P-256) or secp384r1 (P-384)\n"
                + "  Example: new ECGenParameterSpec(\"secp256r1\")\n"
                + "If secp256k1 is strictly required (e.g., blockchain), re-enable via:\n"
                + "  jdk.tls.namedGroups=secp256k1,...  (in java.security or -Djdk.tls.namedGroups)",
                "17", ScanMode.BOTH, "DetectWeakNamedCurves");

        // ── Java 17: Stronger serialisation filters affect EJB remote calls ───
        add("JB-018", "JAVA_BEHAVIOR", "java.rmi.Remote", "MEDIUM",
                "Java 17 introduced context-specific deserialization filters (JEP 415). "
                + "EJB remote interfaces extending java.rmi.Remote may encounter serialisation "
                + "filter rejections when complex object graphs are passed across the remote boundary.",
                "Review all remote EJB interfaces (those extending java.rmi.Remote, EJBHome, or EJBObject). "
                + "Ensure all transferable objects implement java.io.Serializable. "
                + "Define serialisation allowlists via the jdk.serialFilter system property if needed. "
                + "Consider migrating legacy EJB 2.x remote interfaces to EJB 3.x @Remote + REST.",
                "17", ScanMode.BOTH, "jre17EJBRemotePotentialIssues");

        // ── java.awt.peer packages not accessible (Java 9+) ──────────────────
        add("JB-020", "JAVA_BEHAVIOR", "java.awt.peer", "MEDIUM",
                "The java.awt.peer and java.awt.dnd.peer packages are not accessible in Java 9+ "
                + "without --add-exports. These are internal AWT implementation interfaces.",
                "Remove references to java.awt.peer.*. Use only public java.awt.* API. "
                + "If a library accesses these packages, upgrade to a Java 9+ compatible version.",
                "9", ScanMode.BOTH, "AwtPeerPackageNotAccessible");

        // ── Default KeyStore type changed JKS → PKCS12 (Java 11) ─────────────
        add("JB-021", "JAVA_BEHAVIOR", "\"JKS\"", "INFO",
                "The default KeyStore type changed from JKS to PKCS12 in Java 11. "
                + "Code that hardcodes KeyStore.getInstance(\"JKS\") is fine, but code relying on the "
                + "default type (via security property keystore.type) may load a PKCS12 store differently.",
                "Explicitly specify the keystore type:\n"
                + "  KeyStore ks = KeyStore.getInstance(\"JKS\");  // explicit — safe\n"
                + "Or migrate to PKCS12 which is the recommended format:\n"
                + "  KeyStore ks = KeyStore.getInstance(\"PKCS12\");",
                "11", ScanMode.SOURCE, "DetectDefaultKeyStoreChange");

        // ── JCEKS KeyStore format deprecated (Java 17) ───────────────────────
        add("JB-022", "JAVA_BEHAVIOR", "\"JCEKS\"", "MEDIUM",
                "JCEKS (Java Cryptography Extension Key Store) is deprecated and may be disabled "
                + "in Java 17+ security configurations. Code using JCEKS keystores may fail to load keys.",
                "Migrate from JCEKS to PKCS12 keystores:\n"
                + "  keytool -importkeystore -srckeystore old.jceks -srcstoretype JCEKS \\\n"
                + "    -destkeystore new.p12 -deststoretype PKCS12\n"
                + "Update code: KeyStore.getInstance(\"PKCS12\")",
                "17", ScanMode.SOURCE, "DetectJCEKSKeyStoreFormat");

        // ── ForkJoinPool common pool class loader change (Java 21) ───────────
        add("JB-023", "JAVA_BEHAVIOR", "ForkJoinPool", "INFO",
                "In Java 21, the ForkJoinPool common pool uses the system class loader by default. "
                + "Previously, the context class loader of the caller was used. Code that relies on "
                + "the common pool to pick up application-specific classes may fail.",
                "Do not rely on ForkJoinPool.commonPool() for context-class-loader-dependent work. "
                + "Use a custom ForkJoinPool with an explicit thread factory that sets the correct class loader:\n"
                + "  ForkJoinPool pool = new ForkJoinPool(parallelism, factory, handler, asyncMode);",
                "21", ScanMode.BOTH, "ForkJoinCommonPoolThread");

        // ── LineNumberReader.skip() behavior change (Java 11) ────────────────
        add("JB-024", "JAVA_BEHAVIOR", "LineNumberReader", "INFO",
                "java.io.LineNumberReader.skip() behaviour changed in Java 11: "
                + "it now tracks line numbers correctly when skipping. "
                + "Code that relied on the old (broken) behaviour may see different line counts.",
                "Test LineNumberReader usage with skip() calls. "
                + "Ensure line number tracking is consistent with the new behaviour. "
                + "Consider using BufferedReader if line-number tracking is not needed.",
                "11", ScanMode.BOTH, "LineNumberReaderBehaviorChange");

        // ── Selector.select() / selectNow() behaviour change (Java 11) ───────
        add("JB-025", "JAVA_BEHAVIOR", "java.nio.channels.Selector", "INFO",
                "Selector.select() and selectNow() behaviour changed in Java 11: "
                + "spurious wakeups are now possible. Code that assumes select() only returns "
                + "when a channel is ready may loop unexpectedly.",
                "Always re-check channel readiness after select() returns:\n"
                + "  while (selector.select() == 0) continue;  // was correct, may loop more in Java 11+\n"
                + "Use an explicit selectedKeys() check:\n"
                + "  int ready = selector.select(); if (ready > 0) { process(selector.selectedKeys()); }",
                "11", ScanMode.BOTH, "SelectorReadinessInfo");

        // ── user.timezone system property behavior change (Java 17) ──────────
        add("JB-026", "JAVA_BEHAVIOR", "\"user.timezone\"", "INFO",
                "The user.timezone system property behaviour changed in Java 17. "
                + "Setting -Duser.timezone at startup is still honoured, but "
                + "programmatic updates via System.setProperty(\"user.timezone\", ...) may not take effect "
                + "after the JVM has initialised the default timezone.",
                "Set the timezone at JVM startup using -Duser.timezone=<tz>. "
                + "Do not rely on dynamic timezone changes via System.setProperty. "
                + "Use TimeZone.setDefault() or ZoneId for programmatic timezone control.",
                "17", ScanMode.SOURCE, "DetectUserTimezoneProperty");

        // ── ThreadGroup degraded in Java 21 ──────────────────────────────────
        add("JB-027", "JAVA_BEHAVIOR", "ThreadGroup.enumerate", "MEDIUM",
                "ThreadGroup.enumerate() and activeCount() are inherently unreliable and "
                + "their behaviour is further degraded in Java 21 (they may return 0 for virtual thread groups). "
                + "Several ThreadGroup methods are also deprecated for removal.",
                "Replace ThreadGroup tracking with structured concurrency or ExecutorService:\n"
                + "  Use ExecutorService and Future/CompletableFuture for task lifecycle management.\n"
                + "  Use Thread factories (Thread.ofVirtual() / Thread.ofPlatform()) instead of ThreadGroup.",
                "21", ScanMode.BOTH, "ThreadGroupDegraded");

        // ── JAX-RS / JAX-WS raw-type API usage ───────────────────────────────
        add("JB-019", "JAVA_BEHAVIOR", "javax.ws.rs", "INFO",
                "JAX-RS/JAX-WS API methods that use raw types (e.g., Response without a generic "
                + "parameter, List without a type argument) generate unchecked warnings in Java 17+ "
                + "and can cause ClassCastExceptions under strict type-checking at runtime.",
                "Parameterise all JAX-RS response types:\n"
                + "  GenericEntity<List<MyDto>> entity = new GenericEntity<>(list) {};\n"
                + "  return Response.ok(entity).build();\n"
                + "Replace javax.ws.rs:* with jakarta.ws.rs:jakarta.ws.rs-api dependency.",
                "11", ScanMode.BOTH, "DetectJaxApiRawTypeMethods");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Category 5 – JVM_ARGS (removed/renamed JVM flags, detected in scripts/properties)
    // ──────────────────────────────────────────────────────────────────────────

    private void loadJvmArgRules() {

        add("JA-001", "JVM_ARGS", "PermSize", "CRITICAL",
                "-XX:PermSize and -XX:MaxPermSize are removed in Java 8+ (PermGen → Metaspace). "
                + "JVM silently ignores them in Java 8 but prints a warning in Java 9+ and "
                + "may refuse to start in some distributions.",
                "Remove -XX:PermSize and -XX:MaxPermSize from all JVM startup scripts. "
                + "Use -XX:MetaspaceSize and -XX:MaxMetaspaceSize instead.",
                "8", ScanMode.SOURCE, "PerGenRemoved");

        add("JA-002", "JVM_ARGS", "MaxPermSize", "CRITICAL",
                "-XX:MaxPermSize removed in Java 8+ (PermGen removed). JVM warns in Java 9+.",
                "Replace with -XX:MaxMetaspaceSize=<size>.",
                "8", ScanMode.SOURCE, "PerGenRemoved");

        add("JA-003", "JVM_ARGS", "-XX:+UseParNewGC", "HIGH",
                "-XX:+UseParNewGC was removed in Java 10. JVM refuses to start with this flag.",
                "Remove -XX:+UseParNewGC. Use -XX:+UseG1GC (default since Java 9) or "
                + "-XX:+UseZGC (Java 15+) / -XX:+UseShenandoahGC instead.",
                "10", ScanMode.SOURCE, "ObsoleteGCArg");

        add("JA-004", "JVM_ARGS", "-XX:+CMSClassUnloadingEnabled", "HIGH",
                "-XX:+CMSClassUnloadingEnabled is removed in Java 14 (CMS GC removed).",
                "Remove this flag. Class unloading is handled automatically by G1 GC and ZGC.",
                "14", ScanMode.SOURCE, "ObsoleteGCArg");

        add("JA-005", "JVM_ARGS", "-XX:+UseConcMarkSweepGC", "CRITICAL",
                "CMS Garbage Collector was deprecated in Java 9 and REMOVED in Java 14.",
                "Migrate to G1 GC (-XX:+UseG1GC, default since Java 9), ZGC (-XX:+UseZGC), "
                + "or Shenandoah (-XX:+UseShenandoahGC) depending on latency requirements.",
                "14", ScanMode.SOURCE, "ObsoleteGCArg");

        add("JA-006", "JVM_ARGS", "-XX:+UseSerialGC", "INFO",
                "-XX:+UseSerialGC is still available but rarely appropriate. "
                + "G1 GC is the default since Java 9 and superior for most workloads.",
                "Review GC selection. Use -XX:+UseG1GC or -XX:+UseZGC for most server applications.",
                "9", ScanMode.SOURCE, "ObsoleteGCArg");

        add("JA-007", "JVM_ARGS", "-XX:+AggressiveOpts", "HIGH",
                "-XX:+AggressiveOpts was removed in Java 11. JVM refuses to start with this flag.",
                "Remove -XX:+AggressiveOpts. Profile and tune GC/JIT individually as needed.",
                "11", ScanMode.SOURCE, "AggressiveOptsRemoved");

        add("JA-008", "JVM_ARGS", "-Xrunhprof", "HIGH",
                "-Xrunhprof was the legacy profiling agent flag (pre-JVMTI). Removed in Java 9+.",
                "Use -agentlib:hprof (JVMTI-based) or a modern profiler (JFR, YourKit, async-profiler).",
                "9", ScanMode.SOURCE, "XrunHprofRemoved");

        add("JA-009", "JVM_ARGS", "bootclasspath", "HIGH",
                "-Xbootclasspath:/p is removed in Java 9+ (module system). "
                + "Prepending or replacing bootstrap classpath entries is no longer supported.",
                "Remove -Xbootclasspath usage. Use Java modules or --patch-module for testing. "
                + "Application JARs should use the regular classpath.",
                "9", ScanMode.SOURCE, "BootClasspathOption");

        add("JA-010", "JVM_ARGS", "-XX:+UnlockCommercialFeatures", "HIGH",
                "-XX:+UnlockCommercialFeatures was an Oracle JDK flag removed in OpenJDK / Java 17+. "
                + "JVM refuses to start with this flag on OpenJDK.",
                "Remove -XX:+UnlockCommercialFeatures. Java Flight Recorder (JFR) is free "
                + "and available without any flags since OpenJDK 11.",
                "17", ScanMode.SOURCE, "UnlockCommercialFeaturesRemoved");

        add("JA-011", "JVM_ARGS", "-XX:+PrintGCDetails", "INFO",
                "-XX:+PrintGCDetails was replaced by Unified Logging (-Xlog) in Java 9+. "
                + "Still accepted in Java 9-17 but deprecated and removed in some newer builds.",
                "Replace with: -Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=20m",
                "9", ScanMode.SOURCE, "GCLoggingChanged");

        add("JA-012", "JVM_ARGS", "-XX:+PrintGCDateStamps", "INFO",
                "-XX:+PrintGCDateStamps replaced by Unified Logging in Java 9+.",
                "Use -Xlog:gc*:file=gc.log:time,uptime instead.",
                "9", ScanMode.SOURCE, "GCLoggingChanged");

        add("JA-013", "JVM_ARGS", "-verbose:gc", "INFO",
                "-verbose:gc still works but is superseded by the more powerful -Xlog:gc in Java 9+.",
                "Consider migrating to: -Xlog:gc:file=gc.log:time",
                "9", ScanMode.SOURCE, "GCLoggingChanged");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Category 6 – JDK_MODULES (module system access violations)
    // ──────────────────────────────────────────────────────────────────────────

    private void loadModuleSystemRules() {

        add("JM-001", "JDK_MODULES", "add-opens", "INFO",
                "--add-opens in JVM startup args is a workaround for missing strong-encapsulation compliance. "
                + "Each --add-opens flag indicates code that uses JDK internals without permission.",
                "Audit each --add-opens. Eliminate the root cause: remove the internal API usage "
                + "and use the public API equivalent. Libraries requiring --add-opens should be upgraded.",
                "9", ScanMode.SOURCE, "AddOpensMayBeRequired");

        add("JM-002", "JDK_MODULES", "add-exports", "INFO",
                "--add-exports exposes JDK internal packages to code. "
                + "This is a compatibility workaround that will eventually stop working.",
                "Same as --add-opens: audit and eliminate root cause. Upgrade libraries as needed.",
                "9", ScanMode.SOURCE, "AddExportsMayBeRequired");

        add("JM-003", "JDK_MODULES", "ClassLoader.getSystemClassLoader", "MEDIUM",
                "In Java 9+ the system class loader is not a URLClassLoader. "
                + "Code that casts the result to URLClassLoader will throw ClassCastException.",
                "Remove the cast: ClassLoader loader = ClassLoader.getSystemClassLoader(). "
                + "If you need to inspect URLs, use ModuleLayer or iterate module paths instead.",
                "9", ScanMode.SOURCE, "URLClassLoaderArrayContainsNull");

        add("JM-004", "JDK_MODULES", "URLClassLoader", "MEDIUM",
                "URLClassLoader is no longer the system/extension class loader in Java 9+. "
                + "Code that assumes getSystemClassLoader() returns URLClassLoader will throw ClassCastException.",
                "Refactor to not cast ClassLoader to URLClassLoader. Use Java Modules or "
                + "ServiceLoader for plugin/extension patterns.",
                "9", ScanMode.SOURCE, "URLClassLoaderArrayContainsNull");

        add("JM-005", "JDK_MODULES", "Class.forName(\"sun.", "HIGH",
                "Class.forName() targeting sun.* classes will throw ClassNotFoundException in Java 9+ "
                + "as these internal classes are no longer accessible by default.",
                "Remove Class.forName() on sun.* classes. Use the appropriate public java.* equivalent. "
                + "If a library does this, upgrade to a version that is Java 9+ compatible.",
                "9", ScanMode.SOURCE, "DetectSunInternalClassForName");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Custom rules from file
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Loads additional rules from java21-custom-rules.txt placed next to the JAR
     * (working directory) or on the classpath.
     *
     * File format (pipe-delimited, one rule per line):
     *   RULE_ID|CATEGORY|apiPattern|SEVERITY|javaVersion|description|remediation
     *
     * The javaVersion field is the first Java version where the rule applies
     * (e.g. "11", "17", "21"). Leave blank if not applicable.
     *
     * Lines starting with # are treated as comments and ignored.
     */
    private void loadCustomRules() {
        Path externalFile = Path.of("java21-custom-rules.txt");
        if (Files.exists(externalFile)) {
            try (var reader = Files.newBufferedReader(externalFile)) {
                parseRuleFile(reader);
            } catch (IOException e) {
                System.err.println("Warning: could not read " + externalFile + ": " + e.getMessage());
            }
            return;
        }

        try (InputStream is = Java21Rules.class.getClassLoader()
                .getResourceAsStream("java21-custom-rules.txt")) {
            if (is != null) {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                    parseRuleFile(reader);
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read custom rules from classpath: " + e.getMessage());
        }
    }

    private void parseRuleFile(java.io.BufferedReader reader) throws IOException {
        String line;
        int lineNum = 0;
        while ((line = reader.readLine()) != null) {
            lineNum++;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\|", -1);
            if (p.length < 7) {
                System.err.println("Warning: skipping malformed custom rule at line " + lineNum
                        + " (expected 7 fields: RULE_ID|CATEGORY|pattern|SEVERITY|javaVersion|description|remediation)");
                continue;
            }
            add(p[0].trim(), p[1].trim(), p[2].trim(), p[3].trim(),
                    p[5].trim(), p[6].trim(), p[4].trim(), ScanMode.BOTH);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Builder helper
    // ──────────────────────────────────────────────────────────────────────────

    /** Add a rule with no IBM TA alias. */
    private void add(String id, String category, String apiPattern, String severity,
                     String description, String remediation,
                     String javaVersion, ScanMode scanMode) {
        rules.add(new Rule(id, category, apiPattern, severity, description,
                remediation, javaVersion, scanMode, null));
    }

    /** Add a rule with an IBM Transformation Advisor alias for cross-referencing. */
    private void add(String id, String category, String apiPattern, String severity,
                     String description, String remediation,
                     String javaVersion, ScanMode scanMode, String ibmAlias) {
        rules.add(new Rule(id, category, apiPattern, severity, description,
                remediation, javaVersion, scanMode, ibmAlias));
    }
}
