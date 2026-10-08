package effortanalyzer.source;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Java 21 source/build/config readiness rules for Source Inventory scans. */
public final class Java21SourceRules {

    private static final Logger logger = LogManager.getLogger(Java21SourceRules.class);

    private final List<Java21SourceRule> rules = new ArrayList<>();

    private Java21SourceRules() {
        loadBuiltIns();
    }

    public static Java21SourceRules load() {
        Java21SourceRules db = new Java21SourceRules();
        db.loadCustomRules();
        logger.info("Loaded {} Java 21 source readiness rules", db.rules.size());
        return db;
    }

    public List<Java21SourceRule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    private void loadBuiltIns() {
        removedApis();
        internalApis();
        deprecatedRisks();
        behaviorRisks();
        jvmArgsAndTools();
        moduleAndBuildRules();
    }

    private void removedApis() {
        both("J21-JR-001", "JAVA_REMOVED", "javax.xml.bind", "CRITICAL", "11",
                "JAXB (javax.xml.bind.*) was removed from the JDK in Java 11 (JEP 320).",
                "Add explicit JAXB dependencies and update javax/jakarta imports for the target runtime.");
        both("J21-JR-002", "JAVA_REMOVED", "javax.xml.ws", "CRITICAL", "11",
                "JAX-WS (javax.xml.ws.*) was removed from the JDK in Java 11 (JEP 320).",
                "Add explicit JAX-WS dependencies or migrate SOAP integrations to a supported stack.");
        both("J21-JR-003", "JAVA_REMOVED", "javax.xml.soap", "CRITICAL", "11",
                "SAAJ (javax.xml.soap.*) was removed from the JDK in Java 11.",
                "Add jakarta.xml.soap API/implementation dependencies or migrate SOAP handling.");
        both("J21-JR-004", "JAVA_REMOVED", "javax.activation", "CRITICAL", "11",
                "JavaBeans Activation Framework was removed from the JDK in Java 11.",
                "Add jakarta.activation-api or the legacy javax.activation API explicitly as appropriate.");
        both("J21-JR-005", "JAVA_REMOVED", "javax.jws", "HIGH", "11",
                "JAX-WS annotations were removed from the JDK in Java 11.",
                "Add an explicit JWS/JAX-WS API dependency or migrate to Jakarta annotations.");
        both("J21-JR-006", "JAVA_REMOVED", "javax.annotation.Generated", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006A", "JAVA_REMOVED", "javax.annotation.ManagedBean", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006B", "JAVA_REMOVED", "javax.annotation.PostConstruct", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006C", "JAVA_REMOVED", "javax.annotation.PreDestroy", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006D", "JAVA_REMOVED", "javax.annotation.Priority", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006E", "JAVA_REMOVED", "javax.annotation.Resource", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006F", "JAVA_REMOVED", "javax.annotation.Resources", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006G", "JAVA_REMOVED", "javax.annotation.security.DeclareRoles", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006H", "JAVA_REMOVED", "javax.annotation.security.DenyAll", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006I", "JAVA_REMOVED", "javax.annotation.security.PermitAll", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006J", "JAVA_REMOVED", "javax.annotation.security.RolesAllowed", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006K", "JAVA_REMOVED", "javax.annotation.security.RunAs", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006L", "JAVA_REMOVED", "javax.annotation.sql.DataSourceDefinition", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-006M", "JAVA_REMOVED", "javax.annotation.sql.DataSourceDefinitions", "HIGH", "11",
                "Common Annotations are no longer bundled with the JDK after Java 8; javax.annotation.processing remains in Java SE 21.",
                "Add an explicit annotation API dependency or migrate to jakarta.annotation.");
        both("J21-JR-007", "JAVA_REMOVED", "javax.transaction.UserTransaction", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007A", "JAVA_REMOVED", "javax.transaction.TransactionManager", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007B", "JAVA_REMOVED", "javax.transaction.Transactional", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007C", "JAVA_REMOVED", "javax.transaction.TransactionScoped", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007D", "JAVA_REMOVED", "javax.transaction.TransactionRequiredException", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007E", "JAVA_REMOVED", "javax.transaction.TransactionRolledbackException", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007F", "JAVA_REMOVED", "javax.transaction.Transaction", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007G", "JAVA_REMOVED", "javax.transaction.Synchronization", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007H", "JAVA_REMOVED", "javax.transaction.RollbackException", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007I", "JAVA_REMOVED", "javax.transaction.SystemException", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007J", "JAVA_REMOVED", "javax.transaction.NotSupportedException", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007K", "JAVA_REMOVED", "javax.transaction.HeuristicCommitException", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007L", "JAVA_REMOVED", "javax.transaction.HeuristicMixedException", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007M", "JAVA_REMOVED", "javax.transaction.HeuristicRollbackException", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007N", "JAVA_REMOVED", "javax.transaction.InvalidTransactionException", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-007O", "JAVA_REMOVED", "javax.transaction.Status", "HIGH", "11",
                "JTA APIs outside javax.transaction.xa are not provided by Java SE 21.",
                "Add jakarta.transaction-api or javax.transaction-api explicitly, depending on the target platform.");
        both("J21-JR-008", "JAVA_REMOVED", "org.omg", "CRITICAL", "11",
                "CORBA APIs were removed from the JDK in Java 11.",
                "Remove CORBA usage; migrate to REST, messaging, or supported remoting.");
        both("J21-JR-009", "JAVA_REMOVED", "com.sun.corba", "HIGH", "11",
                "Sun CORBA implementation classes were removed with Java 11 CORBA removal.",
                "Remove implementation dependency and migrate away from CORBA/IIOP.");
        both("J21-JR-010", "JAVA_REMOVED", "java.corba", "CRITICAL", "11",
                "The java.corba module was removed in Java 11.",
                "Remove module references and CORBA/IIOP usage.");
        both("J21-JR-011", "JAVA_REMOVED", "java.se.ee", "CRITICAL", "11",
                "The aggregate java.se.ee module was removed in Java 11.",
                "Remove --add-modules java.se.ee and add explicit dependencies for required APIs.");
        both("J21-JR-012", "JAVA_REMOVED", "javax.rmi.CORBA", "CRITICAL", "11",
                "RMI-IIOP/CORBA support was removed from the JDK in Java 11.",
                "Replace IIOP remoting with supported remoting, REST, or messaging.");
        both("J21-JR-013", "JAVA_REMOVED", "javax.activity", "HIGH", "11",
                "javax.activity was part of the removed CORBA/Java EE modules.",
                "Remove this dependency or add a maintained external API if still required.");
        both("J21-JR-014", "JAVA_REMOVED", "jdk.nashorn", "CRITICAL", "15",
                "Nashorn JavaScript engine was removed from the JDK in Java 15 (JEP 372).",
                "Use GraalVM JavaScript, another script engine, or remove embedded JavaScript execution.");
        both("J21-JR-015", "JAVA_REMOVED", "NashornScriptEngineFactory", "CRITICAL", "15",
                "NashornScriptEngineFactory is unavailable on Java 15+ unless an external Nashorn dependency is provided.",
                "Add org.openjdk.nashorn:nashorn-core explicitly or migrate to GraalVM JavaScript.");
        text("J21-JR-016", "JVM_TOOL_REMOVED", "jjs", "HIGH", "15",
                "The jjs Nashorn command-line tool was removed in Java 15.",
                "Replace jjs scripts with node, jshell, or another supported scripting runtime.");
        both("J21-JR-017", "JAVA_REMOVED", "java.pack200", "CRITICAL", "14",
                "Pack200 APIs/tools were removed in Java 14 (JEP 367).",
                "Stop producing/consuming Pack200 archives; use standard JAR/ZIP compression.");
        both("J21-JR-018", "JAVA_REMOVED", "Pack200", "CRITICAL", "14",
                "java.util.jar.Pack200 was removed in Java 14.",
                "Remove Pack200 usage and update packaging scripts.");
        both("J21-JR-019", "JAVA_REMOVED", "java.rmi.activation", "HIGH", "17",
                "RMI Activation was removed in Java 17 (JEP 407).",
                "Remove activation-based RMI and use explicit service lifecycle management.");
        both("J21-JR-020", "JAVA_REMOVED", "java.applet", "CRITICAL", "17",
                "Applet APIs were removed in Java 17 (JEP 398).",
                "Migrate applets to a web application or supported desktop UI.");
        both("J21-JR-021", "JAVA_REMOVED", "javax.management.remote.rmi.RMIIIOPServerImpl", "CRITICAL", "21",
                "RMIIIOPServerImpl was removed in Java 21.",
                "Remove IIOP-based JMX/RMI usage and use standard JMX RMI connector support without IIOP.");
        java("J21-JR-022", "JAVA_REMOVED", "ThreadGroup.allowThreadSuspension", "CRITICAL", "21",
                "ThreadGroup.allowThreadSuspension(boolean) was removed in Java 21.",
                "Remove the call and use cooperative thread coordination.");
        both("J21-JR-023", "JAVA_REMOVED", "com.sun.image.codec.jpeg", "CRITICAL", "9",
                "The nonstandard com.sun.image.codec.jpeg package was removed from modern JDKs.",
                "Use the standard javax.imageio Image I/O API.");
        both("J21-JR-024", "JAVA_REMOVED", "javafx.", "HIGH", "11",
                "JavaFX is no longer bundled with the JDK as of Java 11/OpenJDK distributions.",
                "Add OpenJFX dependencies/plugins explicitly or migrate UI technology.");
        both("J21-JR-025", "JAVA_REMOVED", "sun.misc.Service", "CRITICAL", "9",
                "sun.misc.Service was removed in JDK 9; code using it does not compile or link on Java 21.",
                "Use java.util.ServiceLoader.load(Type.class) and iterate the loaded providers.");
        both("J21-JR-026", "JAVA_REMOVED", "java.security.acl", "CRITICAL", "14",
                "The java.security.acl package was removed in JDK 14.",
                "Replace with java.security.Policy/Principal-based checks or the application's own ACL model.");
        java("J21-JR-027", "JAVA_REMOVED", "Thread.destroy", "CRITICAL", "11",
                "Thread.destroy() was removed from the JDK.",
                "Remove the call; use interruption and cooperative shutdown.");
    }

    private void internalApis() {
        both("J21-JI-001", "JAVA_INTERNAL", "sun.misc.Unsafe", "HIGH", "9", "sun.misc.Unsafe is an internal JDK API subject to strong encapsulation.", "Use supported APIs such as VarHandle, MethodHandles, ByteBuffer, or upgrade dependent libraries.");
        both("J21-JI-002", "JAVA_INTERNAL", "sun.misc.BASE64Encoder", "CRITICAL", "9", "sun.misc.BASE64Encoder is removed/internal.", "Use java.util.Base64.getEncoder().");
        both("J21-JI-003", "JAVA_INTERNAL", "sun.misc.BASE64Decoder", "CRITICAL", "9", "sun.misc.BASE64Decoder is removed/internal.", "Use java.util.Base64.getDecoder().");
        both("J21-JI-004", "JAVA_INTERNAL", "sun.reflect", "HIGH", "9", "sun.reflect.* is internal and strongly encapsulated.", "Use java.lang.reflect, MethodHandles.privateLookupIn(), VarHandle, or upgraded libraries.");
        both("J21-JI-005", "JAVA_INTERNAL", "jdk.internal", "HIGH", "9", "jdk.internal.* packages are unsupported and inaccessible without command-line exports/opens.", "Replace with supported public APIs; treat --add-opens/--add-exports as temporary.");
        both("J21-JI-006", "JAVA_INTERNAL", "com.sun.tools.javac", "HIGH", "9", "com.sun.tools.javac.* compiler internals are not stable APIs.", "Use javax.tools, annotation processing APIs, or supported compiler integration.");
        both("J21-JI-007", "JAVA_INTERNAL", "com.sun.net.ssl", "HIGH", "9", "com.sun.net.ssl.* is a legacy/internal SSL API.", "Migrate to javax.net.ssl APIs and current TLS configuration.");
        both("J21-JI-008", "JAVA_INTERNAL", "sun.security", "HIGH", "9", "sun.security.* internals are strongly encapsulated.", "Use supported java.security/javax.crypto APIs or upgrade libraries.");
        both("J21-JI-009", "JAVA_INTERNAL", "sun.nio", "HIGH", "9", "sun.nio.* internals are unsupported and strongly encapsulated.", "Use java.nio public APIs or upgrade dependent libraries.");
        both("J21-JI-010", "JAVA_INTERNAL", "sun.awt", "HIGH", "9", "sun.awt.* internals are unsupported.", "Use supported java.awt/javax.swing APIs.");
        both("J21-JI-011", "JAVA_INTERNAL", "sun.misc.Cleaner", "HIGH", "9", "sun.misc.Cleaner is an internal cleanup API.", "Use java.lang.ref.Cleaner or explicit AutoCloseable resource management.");
        both("J21-JI-012", "JAVA_INTERNAL", "com.sun.org.apache", "MEDIUM", "9", "Bundled com.sun.org.apache.* implementation classes are JDK internals.", "Use standard JAXP APIs or add explicit third-party dependencies.");
        both("J21-JI-013", "JAVA_INTERNAL", "com.sun.rowset", "MEDIUM", "9", "com.sun.rowset.* implementation classes are nonstandard JDK internals.", "Use standard javax.sql.rowset APIs and RowSetProvider where possible.");
        both("J21-JI-014", "JAVA_INTERNAL", "com.sun.xml.internal", "HIGH", "11", "JDK-internal XML/JAXB implementation packages are not available as stable APIs.", "Declare supported XML/JAXB dependencies explicitly and use public APIs.");
        both("J21-JI-015", "JAVA_INTERNAL", "sun.net", "MEDIUM", "9", "sun.net.* is an unsupported internal networking package.", "Use java.net/http APIs or supported libraries.");
    }

    private void deprecatedRisks() {
        java("J21-JD-001", "JAVA_DEPRECATED", "finalize", "HIGH", "18",
                "Finalization is deprecated for removal and can be disabled in modern Java (JEP 421).",
                "Replace finalize() cleanup with try-with-resources, AutoCloseable, or java.lang.ref.Cleaner.");
        java("J21-JD-002", "JAVA_DEPRECATED", "Thread.stop", "CRITICAL", "20",
                "Thread.stop() throws UnsupportedOperationException since JDK 20.",
                "Use interruption, cancellation tokens, executors, and cooperative shutdown.");
        java("J21-JD-003", "JAVA_DEPRECATED", "Thread.suspend", "CRITICAL", "20",
                "Thread.suspend() throws UnsupportedOperationException since JDK 20.",
                "Use java.util.concurrent primitives or cooperative blocking controls.");
        java("J21-JD-004", "JAVA_DEPRECATED", "Thread.resume", "CRITICAL", "20",
                "Thread.resume() throws UnsupportedOperationException since JDK 20.",
                "Use java.util.concurrent primitives or cooperative blocking controls.");
        java("J21-JD-007", "JAVA_DEPRECATED", "System.setSecurityManager", "CRITICAL", "18",
                "System.setSecurityManager() throws UnsupportedOperationException since JDK 18 unless -Djava.security.manager=allow (JEP 411).",
                "Remove the Security Manager dependency; enforce permissions in the application or container instead.");
        java("J21-JD-005", "JAVA_DEPRECATED", "System.runFinalizersOnExit", "CRITICAL", "11",
                "System.runFinalizersOnExit was removed after long deprecation.",
                "Remove the call; manage resources explicitly.");
        java("J21-JD-006", "JAVA_DEPRECATED", "Runtime.runFinalizersOnExit", "CRITICAL", "11",
                "Runtime.runFinalizersOnExit was removed after long deprecation.",
                "Remove the call; manage resources explicitly.");
    }

    private void behaviorRisks() {
        java("J21-JB-001", "JAVA_BEHAVIOR", "ClassLoader.getSystemClassLoader", "MEDIUM", "9",
                "The system class loader is not guaranteed to be a URLClassLoader in Java 9+.",
                "Avoid casting the system class loader to URLClassLoader; use modules, class path inspection, or ServiceLoader.");
        java("J21-JB-002", "JAVA_BEHAVIOR", "URLClassLoader", "MEDIUM", "9",
                "Code assuming URLClassLoader for the app/system class loader may fail on Java 9+.",
                "Remove ClassLoader-to-URLClassLoader casts and update plugin/classpath scanning logic.");
        java("J21-JB-003", "JAVA_BEHAVIOR", "Class.forName(\"sun.", "HIGH", "9",
                "Reflective access to sun.* internals is blocked or unavailable on modern Java.",
                "Remove reflective dependency on internal JDK classes or upgrade the owning library.");
        java("J21-JB-004", "JAVA_BEHAVIOR", "setAccessible(true)", "MEDIUM", "16",
                "Deep reflection into JDK/platform classes can fail due to strong encapsulation.",
                "Use public APIs/MethodHandles or upgrade libraries; avoid relying on --add-opens long term.");
        java("J21-JB-005", "JAVA_BEHAVIOR", "newInstance()", "INFO", "9",
                "Class.newInstance() is deprecated; it hides checked exceptions and has access issues.",
                "Use getDeclaredConstructor().newInstance() and handle reflective exceptions explicitly.");
    }

    private void jvmArgsAndTools() {
        text("J21-JVM-001", "JVM_ARG_OBSOLETE", "MaxPermSize", "CRITICAL", "8",
                "PermGen was removed in Java 8; MaxPermSize is ignored or rejected by modern JVMs.",
                "Remove -XX:MaxPermSize and tune Metaspace with -XX:MaxMetaspaceSize only if needed.");
        text("J21-JVM-002", "JVM_ARG_OBSOLETE", "PermSize", "HIGH", "8",
                "PermGen was removed in Java 8; PermSize options are obsolete.",
                "Remove -XX:PermSize and related options.");
        text("J21-JVM-003", "JVM_ARG_OBSOLETE", "UseConcMarkSweepGC", "CRITICAL", "14",
                "CMS GC was removed in Java 14.",
                "Use G1/ZGC/Shenandoah as appropriate and rebaseline GC tuning.");
        text("J21-JVM-004", "JVM_ARG_OBSOLETE", "UseParNewGC", "CRITICAL", "14",
                "ParNew/CMS-era GC options are obsolete in modern Java.",
                "Remove ParNew options and rebaseline GC tuning with supported collectors.");
        text("J21-JVM-005", "JVM_ARG_OBSOLETE", "UseBiasedLocking", "HIGH", "15",
                "Biased locking is disabled/obsolete and removed from modern JVM behavior.",
                "Remove biased-locking tuning flags and performance test on Java 21.");
        text("J21-JVM-006", "JVM_ARG_OBSOLETE", "UseAppCDS", "MEDIUM", "10",
                "AppCDS flags changed after Java 8/10 and old flags may fail startup.",
                "Review CDS/AppCDS flags against Java 21 documentation.");
        text("J21-JVM-007", "JVM_ARG_OBSOLETE", "-Xbootclasspath", "CRITICAL", "9",
                "Boot class path override is strongly restricted in the modular JDK.",
                "Remove bootclasspath overrides; use supported agents, modules, or dependencies.");
        text("J21-JVM-008", "JVM_ARG_OBSOLETE", "-XX:+TraceClassLoading", "HIGH", "9",
                "TraceClassLoading was replaced by unified logging.",
                "Use -Xlog:class+load=info or another Java 21 unified logging selector.");
        text("J21-JVM-009", "JVM_ARG_OBSOLETE", "-XX:+TraceClassUnloading", "HIGH", "9",
                "TraceClassUnloading was replaced by unified logging.",
                "Use -Xlog:class+unload=info.");
        text("J21-JVM-010", "JVM_ARG_MODULE", "--illegal-access", "HIGH", "17",
                "--illegal-access was removed/ignored after Java 16 and cannot relax encapsulation on Java 21.",
                "Fix illegal reflective access or add narrowly scoped --add-opens only as a temporary workaround.");
        text("J21-JVM-011", "JVM_ARG_MODULE", "--add-opens", "INFO", "9",
                "--add-opens indicates code relies on strong-encapsulation workarounds.",
                "Audit each opened package and eliminate internal/reflection dependency where possible.");
        text("J21-JVM-012", "JVM_ARG_MODULE", "--add-exports", "INFO", "9",
                "--add-exports exposes JDK internals and is a migration workaround.",
                "Upgrade libraries or replace internal APIs so this option can be removed.");
        text("J21-JVM-013", "JVM_TOOL_REMOVED", "rmic", "HIGH", "15",
                "The rmic tool was removed in Java 15.",
                "Remove rmic build steps; use dynamic stubs or migrate away from legacy RMI.");
        text("J21-JVM-014", "JVM_TOOL_REMOVED", "jaotc", "MEDIUM", "16",
                "The experimental jaotc/AOT tool was removed in Java 16.",
                "Remove AOT build steps or use supported deployment/runtime optimization.");
    }

    private void moduleAndBuildRules() {
        build("J21-BLD-001", "BUILD_JAVA_LEVEL", "java-version-below-21", "HIGH", "21",
                "Build configuration declares a Java source/target/release level below 21.",
                "Set Maven/Gradle compiler release/source/target/toolchain to 21 for Java 21 readiness validation.");
        text("J21-BLD-002", "BUILD_MODULE", "java.se.ee", "CRITICAL", "11",
                "Build/module configuration references the removed java.se.ee aggregate module.",
                "Remove java.se.ee and add explicit dependencies for JAXB/JAX-WS/Activation/etc. as needed.");
        text("J21-BLD-003", "BUILD_MODULE", "java.xml.bind", "CRITICAL", "11",
                "Build/module configuration references the removed java.xml.bind module.",
                "Add explicit JAXB dependencies instead of JDK modules.");
        text("J21-BLD-004", "BUILD_MODULE", "java.xml.ws", "CRITICAL", "11",
                "Build/module configuration references the removed java.xml.ws module.",
                "Add explicit JAX-WS dependencies or migrate SOAP integrations.");
        text("J21-BLD-005", "BUILD_MODULE", "java.corba", "CRITICAL", "11",
                "Build/module configuration references the removed java.corba module.",
                "Remove CORBA module usage and dependencies.");
    }

    private void loadCustomRules() {
        Path externalFile = Path.of("java21-custom-rules.txt");
        if (Files.exists(externalFile)) {
            try (BufferedReader reader = Files.newBufferedReader(externalFile, StandardCharsets.UTF_8)) {
                parseRuleFile(reader, externalFile.toString());
            } catch (IOException e) {
                logger.warn("Could not read {}: {}", externalFile, e.getMessage());
            }
            return;
        }

        try (InputStream is = Java21SourceRules.class.getClassLoader().getResourceAsStream("java21-custom-rules.txt")) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    parseRuleFile(reader, "classpath:java21-custom-rules.txt");
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read custom Java 21 source rules from classpath: {}", e.getMessage());
        }
    }

    private void parseRuleFile(BufferedReader reader, String source) throws IOException {
        String line;
        int lineNum = 0;
        while ((line = reader.readLine()) != null) {
            lineNum++;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] p = line.split("\\|", -1);
            if (p.length < 7) {
                logger.warn("Skipping malformed Java 21 source rule at {}:{} (expected at least 7 fields)", source, lineNum);
                continue;
            }

            Java21SourceRule.ScanTarget target = p.length >= 8 ? inferScanTarget(p[7]) : Java21SourceRule.ScanTarget.BOTH;
            add(p[0].trim(), p[1].trim(), p[2].trim(), p[3].trim(), p[4].trim(),
                    p[5].trim(), p[6].trim(), target);
        }
    }

    private static Java21SourceRule.ScanTarget inferScanTarget(String value) {
        if (value == null || value.isBlank()) return Java21SourceRule.ScanTarget.BOTH;
        return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "JAVA", "SOURCE" -> Java21SourceRule.ScanTarget.JAVA;
            case "TEXT", "CONFIG", "SCRIPT" -> Java21SourceRule.ScanTarget.TEXT;
            case "BUILD", "BUILD_JAVA_LEVEL", "JAVA_LEVEL" -> Java21SourceRule.ScanTarget.BUILD_JAVA_LEVEL;
            case "BOTH", "ALL" -> Java21SourceRule.ScanTarget.BOTH;
            default -> Java21SourceRule.ScanTarget.BOTH;
        };
    }

    private void both(String id, String category, String apiPattern, String severity, String javaVersion,
                      String description, String remediation) {
        add(id, category, apiPattern, severity, javaVersion, description, remediation, Java21SourceRule.ScanTarget.BOTH);
    }

    private void java(String id, String category, String apiPattern, String severity, String javaVersion,
                      String description, String remediation) {
        add(id, category, apiPattern, severity, javaVersion, description, remediation, Java21SourceRule.ScanTarget.JAVA);
    }

    private void text(String id, String category, String apiPattern, String severity, String javaVersion,
                      String description, String remediation) {
        add(id, category, apiPattern, severity, javaVersion, description, remediation, Java21SourceRule.ScanTarget.TEXT);
    }

    private void build(String id, String category, String apiPattern, String severity, String javaVersion,
                       String description, String remediation) {
        add(id, category, apiPattern, severity, javaVersion, description, remediation, Java21SourceRule.ScanTarget.BUILD_JAVA_LEVEL);
    }

    private void add(String id, String category, String apiPattern, String severity, String javaVersion,
                     String description, String remediation, Java21SourceRule.ScanTarget scanTarget) {
        if (id == null || id.isBlank() || apiPattern == null || apiPattern.isBlank()) return;
        rules.add(new Java21SourceRule(id.trim(), category == null ? "JAVA21" : category.trim(), apiPattern.trim(),
                normalizeSeverity(severity), javaVersion == null ? "" : javaVersion.trim(),
                description == null ? "" : description.trim(), remediation == null ? "" : remediation.trim(), scanTarget));
    }

    private static String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) return "INFO";
        String s = severity.trim().toUpperCase(Locale.ROOT);
        return "WARNING".equals(s) ? "MEDIUM" : s;
    }
}