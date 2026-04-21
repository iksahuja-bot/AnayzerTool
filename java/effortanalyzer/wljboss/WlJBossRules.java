package effortanalyzer.wljboss;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Comprehensive rule database for WebLogic → JBoss/WildFly migration.
 *
 * Supports two migration target profiles:
 *
 *   WILDFLY27_JAVA21  –  WildFly 27+ / JBoss EAP 8, Java 21, Jakarta EE 10
 *                        javax.* → jakarta.* namespace migration required
 *
 *   WILDFLY26_JAVA8   –  WildFly 26 / JBoss EAP 7.4, Java 8, Jakarta EE 8
 *                        javax.* namespace is still used — NO namespace migration
 *                        EJB 2.x (CMP/BMP) entity beans still not supported
 *
 * Severity levels:
 *   CRITICAL – application will NOT start or will fail at runtime without this fix
 *   HIGH     – likely runtime failure under specific conditions
 *   MEDIUM   – behavioural difference; needs testing
 *   INFO     – best-practice recommendation or awareness item
 *
 * Scan modes:
 *   BOTH     – search source files (.) and bytecode (/ separator)
 *   SOURCE   – source / XML only
 *   BYTECODE – compiled class files only
 */
public class WlJBossRules {

    // ──────────────────────────────────────────────────────────────────────────
    // Target profile
    // ──────────────────────────────────────────────────────────────────────────

    public enum TargetProfile {
        /** WildFly 27+ / JBoss EAP 8, Java 21, Jakarta EE 10 (default) */
        WILDFLY27_JAVA21("WildFly 27+ / JBoss EAP 8", "Java 21", "Jakarta EE 10"),
        /** WildFly 26 / JBoss EAP 7.4, Java 8, Jakarta EE 8 */
        WILDFLY26_JAVA8 ("WildFly 26 / JBoss EAP 7.4", "Java 8",  "Jakarta EE 8");

        public final String serverLabel;
        public final String javaLabel;
        public final String eeLabel;

        TargetProfile(String serverLabel, String javaLabel, String eeLabel) {
            this.serverLabel = serverLabel;
            this.javaLabel   = javaLabel;
            this.eeLabel      = eeLabel;
        }

        public static TargetProfile from(String value) {
            if (value == null) return WILDFLY27_JAVA21;
            return switch (value.toLowerCase().trim()) {
                case "wildfly26-java8", "wildfly26_java8", "eap74", "java8" -> WILDFLY26_JAVA8;
                default -> WILDFLY27_JAVA21;
            };
        }

        public String displayLabel() {
            return serverLabel + " / " + javaLabel + " / " + eeLabel;
        }
    }

    public enum ScanMode { BOTH, SOURCE, BYTECODE }

    public record Rule(
            String category,
            String apiPattern,
            String severity,
            String description,
            String remediation,
            ScanMode scanMode
    ) {
        /** Pattern as it appears in bytecode constant pool (/ instead of .) */
        public String bytecodePattern() {
            return apiPattern.replace('.', '/');
        }
    }

    private final List<Rule>   rules;
    private final TargetProfile target;

    private WlJBossRules(TargetProfile target) {
        this.target = target;
        this.rules  = new ArrayList<>();
        loadBuiltInRules();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Factory
    // ──────────────────────────────────────────────────────────────────────────

    /** Load with default profile (WildFly 27 / Java 21). */
    public static WlJBossRules load() {
        return load(TargetProfile.WILDFLY27_JAVA21);
    }

    /** Load rules for the given target profile string (e.g. "wildfly26-java8"). */
    public static WlJBossRules load(String targetProfileValue) {
        return load(TargetProfile.from(targetProfileValue));
    }

    public static WlJBossRules load(TargetProfile profile) {
        WlJBossRules db = new WlJBossRules(profile);
        db.loadCustomRules();
        return db;
    }

    public List<Rule>    getRules()  { return Collections.unmodifiableList(rules); }
    public TargetProfile getTarget() { return target; }

    // ──────────────────────────────────────────────────────────────────────────
    // Built-in rules — common + target-specific
    // ──────────────────────────────────────────────────────────────────────────

    private void loadBuiltInRules() {
        loadWebLogicApiRules();
        loadDeploymentDescriptorRules();
        loadJndiNamingRules();
        loadClassloadingRules();
        loadJmsConfigRules();
        loadEjbLegacyRules();

        if (target == TargetProfile.WILDFLY27_JAVA21) {
            loadJavaxToJakartaRules();
            loadJava21IncompatibleRules();
            loadThirdPartyRulesJava21();
        } else {
            loadJava8CompatRules();
            loadThirdPartyRulesJava8();
        }
    }

    // ── 1. WEBLOGIC PROPRIETARY APIs (common to all targets) ─────────────────

    private void loadWebLogicApiRules() {

        String jmsApi  = target == TargetProfile.WILDFLY27_JAVA21 ? "jakarta.jms.*" : "javax.jms.*";
        String txApi   = target == TargetProfile.WILDFLY27_JAVA21 ? "jakarta.transaction.*" : "javax.transaction.*";
        String concApi = target == TargetProfile.WILDFLY27_JAVA21 ? "jakarta.enterprise.concurrent.*" : "javax.enterprise.concurrent.*";
        String wsApi   = target == TargetProfile.WILDFLY27_JAVA21 ? "jakarta.xml.ws.*" : "javax.xml.ws.*";

        add("WEBLOGIC_API", "weblogic.servlet", "CRITICAL",
                "WebLogic servlet extensions are not available on WildFly",
                "Replace with standard Servlet API. "
                + "Remove weblogic.servlet.http.* and weblogic.servlet.annotation.* usage.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.ejb", "CRITICAL",
                "WebLogic-specific EJB extensions not supported on WildFly",
                "Replace with standard EJB annotations. "
                + "Review any @WLExtension or WebLogic-specific deployment hints. "
                + "Note: WebLogic appc-generated stub classes (*_WLStub, *_WLSkel, *_EOImpl, *_HomeImpl) "
                + "are automatically excluded from this rule and reported separately as WL_GENERATED_STUBS.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.jms", "CRITICAL",
                "WebLogic JMS API is proprietary and unavailable on WildFly",
                "Use standard JMS API (" + jmsApi + "). "
                + "Reconfigure connection factories and destinations in the WildFly/Artemis subsystem.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.jndi", "CRITICAL",
                "WebLogic JNDI context factory (WLInitialContextFactory / t3 protocol) will not work on WildFly",
                "Use standard InitialContext with WildFly JNDI naming. "
                + "Replace t3:// URLs with remote+http:// or in-VM lookups with java:global/...",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.transaction", "CRITICAL",
                "WebLogic transaction manager API not available on WildFly",
                "Use standard Transaction API (" + txApi + "). WildFly uses Narayana.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.security", "CRITICAL",
                "WebLogic security framework is completely proprietary",
                "Migrate to Elytron security subsystem or standard JAAS. "
                + "Rewrite authentication/authorisation providers as Elytron components. "
                + "Direct usages such as weblogic.security.Security.getCurrentSubject() must be replaced "
                + "with WildFly Elytron: SecurityDomain.getCurrent().getCurrentSecurityIdentity(). "
                + "Note: appc-generated stub classes that reference weblogic.security are excluded automatically.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.management", "HIGH",
                "WebLogic management MBeans have no equivalent on WildFly",
                "Use WildFly Management API (DMR) or standard JMX MBeans. "
                + "CLI: /subsystem=<name>:read-resource",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.cluster", "HIGH",
                "WebLogic clustering API is not available on WildFly",
                "Use WildFly clustering (Infinispan-based). "
                + "Singleton services → WildFly HA Singleton.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.rmi", "HIGH",
                "WebLogic RMI extensions (T3, IIOP) not supported on WildFly",
                "Migrate remote calls to EJB remote, REST, or messaging. "
                + "WildFly uses JBoss Remoting, not T3. "
                + "Note: WebLogic appc-generated EJB stub/skeleton classes extensively reference weblogic.rmi — "
                + "those are excluded automatically by the WL_GENERATED_STUBS filter.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.jdbc", "HIGH",
                "WebLogic JDBC extension classes are not available on WildFly",
                "Use standard javax.sql / java.sql JDBC API. "
                + "Configure datasources via standalone.xml / WildFly CLI.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.work", "HIGH",
                "WebLogic Work Manager API is proprietary",
                "Use Managed Executor Service (" + concApi + ").",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.store", "HIGH",
                "WebLogic persistent store not available on WildFly",
                "Migrate to JPA, Infinispan, or file-based storage depending on use case.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.wsee", "HIGH",
                "WebLogic web-services extensions are proprietary",
                "Use standard JAX-WS API (" + wsApi + "). WildFly ships Apache CXF.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.xml", "MEDIUM",
                "WebLogic XML utility classes are proprietary",
                "Use standard JAXB / StAX / DOM APIs.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.logging", "MEDIUM",
                "WebLogic logging API not available on WildFly",
                "Use java.util.logging, SLF4J, or Log4j 2 (WildFly ships JBoss Logging as a bridge).",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.deploy", "MEDIUM",
                "WebLogic deployment API not available on WildFly",
                "Use WildFly Management API or the WildFly Maven plugin for deployment.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.application", "MEDIUM",
                "WebLogic application lifecycle hooks not available on WildFly",
                "Use CDI @Observes events or @Startup/@Singleton EJB for startup/shutdown.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.utils", "MEDIUM",
                "WebLogic internal utility classes are proprietary",
                "Replace with standard Java or Apache Commons utilities.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.kernel", "HIGH",
                "WebLogic kernel-level API is proprietary",
                "No direct equivalent on WildFly. Replace with standard Java concurrency "
                + "or WildFly extension APIs.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.common", "MEDIUM",
                "WebLogic common internal classes are proprietary",
                "Replace with standard Java alternatives.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "weblogic.net.http", "MEDIUM",
                "WebLogic HTTP client API (weblogic.net.http) is proprietary",
                "Replace with java.net.HttpURLConnection, Apache HttpClient, or java.net.http.HttpClient (Java 11+).",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "WLInitialContextFactory", "CRITICAL",
                "WebLogic JNDI context factory class will not load on WildFly",
                "Remove from jndi.properties / InitialContext env. "
                + "WildFly uses org.jboss.naming.remote.client.InitialContextFactory for remote, "
                + "or no factory for in-VM lookups.",
                ScanMode.BOTH);

        add("WEBLOGIC_API", "t3://", "CRITICAL",
                "WebLogic T3/T3S protocol is not supported on WildFly",
                "Replace with remote+http:// (EAP 7.4+) or http-remoting:// "
                + "for remote EJB and JMS lookups.",
                ScanMode.SOURCE);
    }

    // ── 2. DEPLOYMENT DESCRIPTORS (common) ───────────────────────────────────

    private void loadDeploymentDescriptorRules() {
        add("DEPLOYMENT_DESCRIPTOR", "weblogic.xml", "CRITICAL",
                "weblogic.xml deployment descriptor is not read by WildFly",
                "Create WEB-INF/jboss-web.xml for context-root, security-domain, etc. "
                + "Use jboss-deployment-structure.xml for classloading control.",
                ScanMode.SOURCE);

        add("DEPLOYMENT_DESCRIPTOR", "weblogic-application.xml", "CRITICAL",
                "weblogic-application.xml is not read by WildFly",
                "Create META-INF/jboss-deployment-structure.xml for module isolation "
                + "and jboss-app.xml for application-level settings.",
                ScanMode.SOURCE);

        add("DEPLOYMENT_DESCRIPTOR", "weblogic-ejb-jar.xml", "CRITICAL",
                "weblogic-ejb-jar.xml is not read by WildFly — must be replaced with jboss-ejb3.xml",
                "Create META-INF/jboss-ejb3.xml with the following structure: "
                + "For each SESSION bean (add a <session> block): "
                + "<ejb-name> = same as ejb-jar.xml; "
                + "<home> = home interface FQCN; "
                + "<remote> = remote interface FQCN; "
                + "<ejb-class> = bean implementation FQCN; "
                + "<session-type> = Stateless or Stateful; "
                + "<transaction-type> = Container. "
                + "WildFly auto-derives the JNDI binding from the <home> class name — "
                + "this matches the <jndi-name> in weblogic-ejb-jar.xml exactly, "
                + "so all HomeInterfaceHelper.lookupHome() calls work unchanged. "
                + "For each MDB: add a <message-driven> block with <activation-config> "
                + "specifying destinationLookup (WildFly/Artemis JNDI name) and maxSession. "
                + "For ENTITY beans: DO NOT add to jboss-ejb3.xml — "
                + "use the POJOHome pattern instead (see javax.ejb.EntityBean rule). "
                + "Transaction attributes and timeouts go in <assembly-descriptor><container-transaction>. "
                + "Exclude weblogic-ejb-jar.xml from the WildFly build "
                + "(Maven resource exclude or move outside META-INF).",
                ScanMode.SOURCE);

        add("DEPLOYMENT_DESCRIPTOR", "weblogic-ra.xml", "HIGH",
                "weblogic-ra.xml is not read by WildFly",
                "Create META-INF/ironjacamar.xml or configure the resource adapter "
                + "via WildFly CLI / standalone.xml.",
                ScanMode.SOURCE);

        add("DEPLOYMENT_DESCRIPTOR", "weblogic-cmp-rdbms-jar.xml", "HIGH",
                "Container-Managed Persistence (CMP) config is WebLogic-specific and unsupported",
                "Migrate entity beans to JPA entities. "
                + "CMP EJBs are not supported — see EJB_LEGACY category.",
                ScanMode.SOURCE);

        add("DEPLOYMENT_DESCRIPTOR", "weblogic-webservices.xml", "MEDIUM",
                "weblogic-webservices.xml is not read by WildFly",
                "Use standard JAX-WS annotations or CXF-specific configuration in beans.xml.",
                ScanMode.SOURCE);

        add("DEPLOYMENT_DESCRIPTOR", "prefer-application-packages", "HIGH",
                "WebLogic 'prefer-application-packages' classloading setting not supported",
                "Use jboss-deployment-structure.xml to control module dependencies and exclusions.",
                ScanMode.SOURCE);

        add("DEPLOYMENT_DESCRIPTOR", "prefer-web-inf-classes", "HIGH",
                "WebLogic 'prefer-web-inf-classes' classloading setting not supported",
                "Add <class-loading><local-last value=\"true\"/></class-loading> in jboss-web.xml.",
                ScanMode.SOURCE);
    }

    // ── 3. JNDI NAMING CONVENTIONS (common) ──────────────────────────────────

    private void loadJndiNamingRules() {
        add("JNDI_NAMING", "java:comp/env/jdbc", "HIGH",
                "WebLogic JNDI naming for datasources differs from WildFly standard",
                "WildFly datasource JNDI names use java:jboss/datasources/<name>. "
                + "Update all JNDI lookups and resource references.",
                ScanMode.SOURCE);

        add("JNDI_NAMING", "java:comp/env/jms", "HIGH",
                "JMS JNDI naming conventions differ between WebLogic and WildFly",
                "Update JNDI names for connection factories and destinations. "
                + "WildFly/Artemis default factory: java:/JmsXA or java:/ConnectionFactory.",
                ScanMode.SOURCE);

        add("JNDI_NAMING", "java:comp/env/ejb", "MEDIUM",
                "EJB JNDI lookup names differ between WebLogic and WildFly",
                "WildFly global EJB JNDI: java:global/<app>/<module>/<bean>!<interface>. "
                + "Prefer @EJB injection over programmatic JNDI lookup.",
                ScanMode.SOURCE);
    }

    // ── 4. CLASSLOADING DIFFERENCES (common) ─────────────────────────────────

    private void loadClassloadingRules() {
        add("CLASSLOADING", "weblogic.utils.classloaders", "HIGH",
                "WebLogic classloader hierarchy is very different from WildFly modular classloading",
                "WildFly uses JBoss Modules. Review classloading isolation and "
                + "create jboss-deployment-structure.xml to declare module dependencies.",
                ScanMode.BOTH);

        add("CLASSLOADING", "FilteringClassLoader", "MEDIUM",
                "WebLogic FilteringClassLoader not available on WildFly",
                "Use jboss-deployment-structure.xml <exclusions> for the same isolation.",
                ScanMode.BOTH);
    }

    // ── 5. JMS / MESSAGING (common) ───────────────────────────────────────────

    private void loadJmsConfigRules() {
        add("JMS_CONFIG", "weblogic.jms.extensions", "CRITICAL",
                "WebLogic JMS extensions (unit-of-order, unit-of-work, etc.) are proprietary",
                "Re-implement using standard JMS API features or Artemis-specific extensions. "
                + "Review each extension individually for WildFly Artemis equivalents.",
                ScanMode.BOTH);

        add("JMS_CONFIG", "weblogic.jms.common", "HIGH",
                "WebLogic JMS internal classes are proprietary",
                "Use only standard javax.jms.* / jakarta.jms.* API.",
                ScanMode.BOTH);

        add("JMS_CONFIG", "WLQueueSession", "HIGH",
                "WebLogic QueueSession extension not available on WildFly",
                "Use standard javax.jms.QueueSession / jakarta.jms.QueueSession.",
                ScanMode.BOTH);

        add("JMS_CONFIG", "WLTopicSession", "HIGH",
                "WebLogic TopicSession extension not available on WildFly",
                "Use standard javax.jms.TopicSession / jakarta.jms.TopicSession.",
                ScanMode.BOTH);
    }

    // ── 6. EJB LEGACY — CMP/BMP entity beans (common, not supported by WildFly) ─

    private void loadEjbLegacyRules() {
        add("EJB_LEGACY", "javax.ejb.EntityBean", "CRITICAL",
                "EJB 2.x Entity Beans (CMP/BMP) are NOT supported by WildFly — "
                + "WildFly will ignore entity bean declarations in ejb-jar.xml; "
                + "do NOT add entity beans to jboss-ejb3.xml",
                "Two migration options: "
                + "(A) POJOHome pattern [recommended for large codebases]: "
                + "Create a POJOHome class (e.g. AttributePOJOHome) that implements EJBHome "
                + "and manages entity instances in-memory using direct JDBC, bypassing the EJB container. "
                + "Set nc.core.metamodel.pojo_access=true (or your equivalent property) in WildFly server config. "
                + "HomeInterfaceHelper.lookupHome() detects this property and returns the POJOHome instead of doing a JNDI lookup. "
                + "Call sites (lookupHome, create, remove) remain 100% unchanged. "
                + "Entity beans remain in ejb-jar.xml but are simply ignored by WildFly. "
                + "(B) JPA migration [for greenfield or smaller scope]: "
                + "Annotate with @Entity, replace finder methods with JPQL (@NamedQuery), "
                + "replace ejbCreate/ejbLoad/ejbStore with @PrePersist/@PostLoad lifecycle callbacks, "
                + "and inject EntityManager via @PersistenceContext.",
                ScanMode.BOTH);

        add("EJB_LEGACY", "javax.ejb.EJBHome", "HIGH",
                "EJB 2.x remote home interface (EJBHome) detected",
                "For SESSION beans: the home interface class itself does NOT need to change. "
                + "Declare it in META-INF/jboss-ejb3.xml: "
                + "<session><home>com.example.MyHome</home><remote>com.example.MyRemote</remote>...</session>. "
                + "WildFly auto-binds the home interface to JNDI using its fully-qualified class name "
                + "(e.g. 'com.example.MyHome'), so HomeInterfaceHelper.lookupHome('com.example.MyHome', MyHome.class) "
                + "works completely unchanged — no modifications to call sites. "
                + "For ENTITY beans: do NOT declare in jboss-ejb3.xml. "
                + "Create a POJOHome class implementing EJBHome that manages instances via JDBC directly. "
                + "HomeInterfaceHelper checks nc.core.metamodel.pojo_access property and returns the "
                + "POJOHome without doing a JNDI lookup, so all lookupHome() call sites remain unchanged.",
                ScanMode.BOTH);

        add("EJB_LEGACY", "javax.ejb.EJBLocalHome", "HIGH",
                "EJB 2.x local home interface (EJBLocalHome) is obsolete",
                "Replace with @Local-annotated business interface and @Stateless/@Stateful beans. "
                + "Use @EJB injection instead of home factory pattern.",
                ScanMode.BOTH);

        add("EJB_LEGACY", "javax.ejb.EJBObject", "MEDIUM",
                "EJB 2.x remote object interface (EJBObject) is obsolete",
                "Migrate to EJB 3.x business interface with @Remote annotation.",
                ScanMode.BOTH);

        add("EJB_LEGACY", "javax.ejb.EJBLocalObject", "MEDIUM",
                "EJB 2.x local object interface (EJBLocalObject) is obsolete",
                "Migrate to EJB 3.x business interface with @Local annotation.",
                ScanMode.BOTH);

        add("EJB_LEGACY", "ejbCreate", "MEDIUM",
                "EJB 2.x lifecycle method ejbCreate() detected — indicates legacy entity/session bean",
                "Replace with @PostConstruct (stateful/stateless) or JPA entity lifecycle annotations. "
                + "For entity beans, create a JPA @Entity with a no-arg constructor.",
                ScanMode.SOURCE);

        add("EJB_LEGACY", "ejbLoad", "MEDIUM",
                "EJB 2.x entity bean lifecycle method ejbLoad() — CMP/BMP pattern detected",
                "Remove ejbLoad/ejbStore. Use JPA — the persistence provider manages state automatically.",
                ScanMode.SOURCE);

        add("EJB_LEGACY", "ejbStore", "MEDIUM",
                "EJB 2.x entity bean lifecycle method ejbStore() — CMP/BMP pattern detected",
                "Remove ejbLoad/ejbStore. Use JPA — the persistence provider manages state automatically.",
                ScanMode.SOURCE);

        add("EJB_LEGACY", "javax.ejb.FinderException", "MEDIUM",
                "EJB 2.x FinderException used in home interface — entity bean pattern",
                "Remove home interface. Replace finder methods with @NamedQuery / EntityManager.createQuery().",
                ScanMode.BOTH);

        add("EJB_LEGACY", "javax.ejb.CreateException", "MEDIUM",
                "EJB 2.x CreateException used in home interface",
                "Remove home interface. Use constructor injection or factory methods in service layer.",
                ScanMode.BOTH);

        // ── EJB 2.x bean interfaces ───────────────────────────────────────────
        add("EJB_LEGACY", "javax.ejb.SessionBean", "HIGH",
                "Implementing javax.ejb.SessionBean is the EJB 2.x pattern for session beans",
                "For WildFly 26 (Java EE 8): the bean Java class does NOT need to change — "
                + "'implements SessionBean' is still accepted by WildFly. "
                + "The only required change is to add a <session> entry in META-INF/jboss-ejb3.xml: "
                + "<session><ejb-name>MyBean</ejb-name>"
                + "<home>com.example.MyHome</home>"
                + "<remote>com.example.MyRemote</remote>"
                + "<ejb-class>com.example.MyBean</ejb-class>"
                + "<session-type>Stateless</session-type>"
                + "<transaction-type>Container</transaction-type></session>. "
                + "WildFly auto-binds the home interface to JNDI using its fully-qualified class name, "
                + "so existing HomeInterfaceHelper.lookupHome() calls work unchanged. "
                + "Also add the bean to the <assembly-descriptor><container-transaction> block for transaction config. "
                + "For WildFly 27+ (Jakarta EE 10): additionally rename javax.ejb.* to jakarta.ejb.* and "
                + "consider migrating to @Stateless / @Stateful annotation style.",
                ScanMode.BOTH);

        add("EJB_LEGACY", "javax.ejb.MessageDrivenBean", "HIGH",
                "Implementing javax.ejb.MessageDrivenBean is the EJB 2.x pattern for MDBs",
                "For WildFly 26 (Java EE 8): the MDB Java class does NOT need to change — "
                + "'implements MessageDrivenBean' is still accepted by WildFly. "
                + "Add a <message-driven> entry in META-INF/jboss-ejb3.xml: "
                + "<message-driven><ejb-name>MyMDB</ejb-name>"
                + "<ejb-class>com.example.MyMDB</ejb-class>"
                + "<transaction-type>Container</transaction-type>"
                + "<message-destination-type>javax.jms.Topic</message-destination-type>"
                + "<activation-config>"
                + "<activation-config-property><activation-config-property-name>destinationLookup</activation-config-property-name>"
                + "<activation-config-property-value>java:/jms/MyTopic</activation-config-property-value></activation-config-property>"
                + "<activation-config-property><activation-config-property-name>maxSession</activation-config-property-name>"
                + "<activation-config-property-value>16</activation-config-property-value></activation-config-property>"
                + "</activation-config></message-driven>. "
                + "Update JMS JNDI names from WebLogic conventions to WildFly/Artemis names "
                + "(e.g. java:/jms/MyTopic). "
                + "For WildFly 27+ (Jakarta EE 10): additionally rename javax.ejb.* to jakarta.ejb.* and "
                + "migrate to @MessageDriven annotation style.",
                ScanMode.BOTH);

        // ── EJB 2.x context ───────────────────────────────────────────────────
        add("EJB_LEGACY", "javax.ejb.EntityContext", "MEDIUM",
                "EntityContext is an EJB 2.x CMP/BMP artifact",
                "Remove setEntityContext/unsetEntityContext and the EntityContext field. "
                + "In JPA entities the container manages identity and lifecycle automatically. "
                + "Primary key access: use the @Id field directly.",
                ScanMode.BOTH);

        // ── EJB 2.x lifecycle methods (source scan only) ──────────────────────
        add("EJB_LEGACY", "setEntityContext", "MEDIUM",
                "setEntityContext(EntityContext) is an EJB 2.x entity bean lifecycle method",
                "Remove this method and the EntityContext field. JPA manages entity lifecycle automatically.",
                ScanMode.SOURCE);

        add("EJB_LEGACY", "setSessionContext", "MEDIUM",
                "setSessionContext(SessionContext) is an EJB 2.x session bean lifecycle method",
                "Remove from session beans migrated to EJB 3.x annotation style. "
                + "If SessionContext is still needed inject it via @Resource SessionContext ctx.",
                ScanMode.SOURCE);

        add("EJB_LEGACY", "ejbPassivate", "MEDIUM",
                "ejbPassivate() is an EJB 2.x stateful/entity bean lifecycle method",
                "Remove method. In EJB 3.x use @PrePassivate if passivation logic is required.",
                ScanMode.SOURCE);

        add("EJB_LEGACY", "ejbActivate", "MEDIUM",
                "ejbActivate() is an EJB 2.x stateful/entity bean lifecycle method",
                "Remove method. In EJB 3.x use @PostActivate if activation logic is required.",
                ScanMode.SOURCE);

        // ── EJB 2.x remote narrowing ──────────────────────────────────────────
        add("EJB_LEGACY", "javax.rmi.PortableRemoteObject", "CRITICAL",
                "PortableRemoteObject.narrow() is the EJB 2.x IIOP/CORBA way to cast remote object references — "
                + "not needed on WildFly which uses JBoss Remoting (not IIOP)",
                "Remove all PortableRemoteObject.narrow() calls. "
                + "Replace: (MyType) PortableRemoteObject.narrow(obj, MyType.class) "
                + "→ simply: (MyType) obj. "
                + "For the POJOHome pattern: POJOHome.create() already returns the correct concrete type, "
                + "so narrow() is completely unnecessary — the cast is a no-op. "
                + "For session beans on WildFly: JNDI lookup returns the correct EJBHome type directly; "
                + "cast with a plain Java cast. "
                + "Alternatively, replace the entire lookup+narrow+create pattern with @EJB injection "
                + "for modern EJB 3.x style.",
                ScanMode.BOTH);
    }

    // ── 7. JAVAX → JAKARTA NAMESPACE (WildFly 27+ / Java 21 only) ────────────

    private void loadJavaxToJakartaRules() {
        add("JAVAX_TO_JAKARTA", "javax.servlet", "CRITICAL",
                "javax.servlet.* renamed to jakarta.servlet.* in Jakarta EE 9+",
                "Global search-and-replace: javax.servlet → jakarta.servlet. "
                + "Update pom.xml dependency to jakarta.servlet:jakarta.servlet-api:6.x",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.persistence", "CRITICAL",
                "javax.persistence.* renamed to jakarta.persistence.*",
                "Replace all imports. Update dependency to jakarta.persistence:jakarta.persistence-api:3.x. "
                + "Also update persistence.xml provider class names.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.ejb", "CRITICAL",
                "javax.ejb.* renamed to jakarta.ejb.*",
                "Replace all imports and annotations (@Stateless, @Stateful, @MessageDriven, etc.).",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.ws.rs", "CRITICAL",
                "javax.ws.rs.* (JAX-RS) renamed to jakarta.ws.rs.*",
                "Replace all imports. Update pom.xml to jakarta.ws.rs:jakarta.ws.rs-api:3.x.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.jms", "CRITICAL",
                "javax.jms.* renamed to jakarta.jms.*",
                "Replace all imports and connection factory lookups.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.inject", "CRITICAL",
                "javax.inject.* renamed to jakarta.inject.*",
                "Replace @Inject, @Named, @Singleton, @Provider.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.enterprise", "CRITICAL",
                "javax.enterprise.* (CDI) renamed to jakarta.enterprise.*",
                "Replace @ApplicationScoped, @RequestScoped, Event<>, BeanManager, etc.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.faces", "CRITICAL",
                "javax.faces.* (JSF) renamed to jakarta.faces.*",
                "Replace all imports and update faces-config.xml. "
                + "Upgrade to Mojarra 4.x or MyFaces 4.x.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.validation", "CRITICAL",
                "javax.validation.* renamed to jakarta.validation.*",
                "Replace @NotNull, @Valid, Validator, etc. Update to hibernate-validator 8.x.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.transaction", "CRITICAL",
                "javax.transaction.* renamed to jakarta.transaction.*",
                "Replace @Transactional, UserTransaction, TransactionManager.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.xml.bind", "CRITICAL",
                "javax.xml.bind.* (JAXB) removed from JDK 11 and renamed to jakarta.xml.bind.*",
                "Add explicit dependency: jakarta.xml.bind:jakarta.xml.bind-api:4.x "
                + "and implementation com.sun.xml.bind:jaxb-impl. Replace all imports.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.xml.ws", "CRITICAL",
                "javax.xml.ws.* (JAX-WS) removed from JDK 11 and renamed to jakarta.xml.ws.*",
                "Add explicit dependency: jakarta.xml.ws:jakarta.xml.ws-api:4.x "
                + "and CXF or Metro implementation. Regenerate WSDL stubs.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.annotation", "HIGH",
                "javax.annotation.* renamed to jakarta.annotation.*",
                "Replace @PostConstruct, @PreDestroy, @Resource, @ManagedBean.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.interceptor", "HIGH",
                "javax.interceptor.* renamed to jakarta.interceptor.*",
                "Replace @Interceptor, @AroundInvoke, @InterceptorBinding.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.resource", "HIGH",
                "javax.resource.* (JCA) renamed to jakarta.resource.*",
                "Replace all JCA SPI / API classes. Update ra.xml namespace.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.mail", "HIGH",
                "javax.mail.* renamed to jakarta.mail.*",
                "Add dependency: jakarta.mail:jakarta.mail-api:2.x and Eclipse Angus implementation.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.batch", "HIGH",
                "javax.batch.* renamed to jakarta.batch.*",
                "Replace all batch API classes and update job XML namespace.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.json", "HIGH",
                "javax.json.* (JSON-P) renamed to jakarta.json.*",
                "Replace JsonObject, JsonArray, JsonParser, etc. "
                + "Update dependency to jakarta.json:jakarta.json-api:2.x.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.websocket", "HIGH",
                "javax.websocket.* renamed to jakarta.websocket.*",
                "Replace @ServerEndpoint, @OnMessage, Session, etc.",
                ScanMode.BOTH);

        add("JAVAX_TO_JAKARTA", "javax.security.enterprise", "HIGH",
                "javax.security.enterprise.* renamed to jakarta.security.enterprise.*",
                "Replace @CustomFormAuthenticationMechanismDefinition and security annotations.",
                ScanMode.BOTH);
    }

    // ── 8. JAVA 21 REMOVED / RESTRICTED APIs (WildFly 27+ / Java 21 only) ────

    private void loadJava21IncompatibleRules() {
        add("JAVA21_INCOMPATIBLE", "Thread.stop", "CRITICAL",
                "Thread.stop() was deprecated since Java 1.2 and removed in Java 21",
                "Redesign thread coordination using interrupt(), volatile flags, "
                + "or java.util.concurrent primitives.",
                ScanMode.BOTH);

        add("JAVA21_INCOMPATIBLE", "Thread.suspend", "CRITICAL",
                "Thread.suspend() removed in Java 21",
                "Use wait()/notify() or higher-level java.util.concurrent abstractions.",
                ScanMode.BOTH);

        add("JAVA21_INCOMPATIBLE", "Thread.resume", "CRITICAL",
                "Thread.resume() removed in Java 21",
                "Use wait()/notify() or higher-level java.util.concurrent abstractions.",
                ScanMode.BOTH);

        add("JAVA21_INCOMPATIBLE", "Thread.countStackFrames", "CRITICAL",
                "Thread.countStackFrames() removed in Java 21",
                "Use StackWalker API (introduced in Java 9) for stack inspection.",
                ScanMode.BOTH);

        add("JAVA21_INCOMPATIBLE", "SecurityManager", "HIGH",
                "SecurityManager deprecated in Java 17 and scheduled for removal",
                "Remove all SecurityManager installation and policy file usage. "
                + "JBoss EAP 8 does not require a SecurityManager.",
                ScanMode.BOTH);

        add("JAVA21_INCOMPATIBLE", "sun.misc.Unsafe", "HIGH",
                "sun.misc.Unsafe is restricted in Java 21 via strong encapsulation",
                "Use VarHandle (java.lang.invoke.VarHandle) or MethodHandles for low-level access.",
                ScanMode.BOTH);

        add("JAVA21_INCOMPATIBLE", "sun.reflect", "HIGH",
                "sun.reflect.* is an internal JDK package inaccessible in Java 21",
                "Use standard java.lang.reflect.* API instead.",
                ScanMode.BOTH);

        add("JAVA21_INCOMPATIBLE", "com.sun.xml.internal", "HIGH",
                "com.sun.xml.internal.* is a private JDK package inaccessible in Java 21",
                "Use jakarta.xml.bind with an explicit JAXB implementation dependency.",
                ScanMode.BOTH);

        add("JAVA21_INCOMPATIBLE", "com.sun.org.apache", "MEDIUM",
                "com.sun.org.apache.* (internal Xerces/Xalan) is inaccessible in Java 21",
                "Use standard XML APIs or add Xerces/Xalan as an explicit dependency.",
                ScanMode.BOTH);

        add("JAVA21_INCOMPATIBLE", "finalize", "MEDIUM",
                "Object.finalize() is deprecated for removal in Java 18+ (JEP 421)",
                "Replace with try-with-resources or java.lang.ref.Cleaner for cleanup logic.",
                ScanMode.BOTH);

        add("JAVA21_INCOMPATIBLE", "java.applet", "CRITICAL",
                "Applet API removed in Java 17 (JEP 398)",
                "Migrate applet code to a web application or desktop application framework.",
                ScanMode.BOTH);

        add("JAVA21_INCOMPATIBLE", "java.rmi.activation", "HIGH",
                "RMI Activation (java.rmi.activation) removed in Java 17 (JEP 407)",
                "Remove RMI Activation usage. Use REST, messaging, or EJB remote as alternatives.",
                ScanMode.BOTH);

        add("JAVA21_INCOMPATIBLE", "javax.xml.soap.SOAPElementFactory", "MEDIUM",
                "Some SAAJ internal classes were removed or restricted in Java 11+",
                "Use the jakarta.xml.soap API only.",
                ScanMode.BOTH);
    }

    // ── 9. JAVA 8 COMPATIBILITY NOTES (WildFly 26 / Java 8 only) ─────────────

    private void loadJava8CompatRules() {
        add("JAVA8_COMPAT", "Thread.stop", "HIGH",
                "Thread.stop() is deprecated since Java 1.2 — unsafe and unreliable",
                "Redesign thread coordination using interrupt(), volatile flags, "
                + "or java.util.concurrent primitives. This will also ease future Java upgrades.",
                ScanMode.BOTH);

        add("JAVA8_COMPAT", "Thread.suspend", "HIGH",
                "Thread.suspend() is deprecated — can cause deadlocks",
                "Use wait()/notify() or java.util.concurrent abstractions instead.",
                ScanMode.BOTH);

        add("JAVA8_COMPAT", "Thread.resume", "HIGH",
                "Thread.resume() is deprecated — paired with deprecated Thread.suspend()",
                "Use wait()/notify() or java.util.concurrent abstractions instead.",
                ScanMode.BOTH);

        add("JAVA8_COMPAT", "sun.misc.Unsafe", "MEDIUM",
                "sun.misc.Unsafe is an internal JDK API — not portable across JVMs",
                "Works in Java 8 but restricted in Java 11+. "
                + "Consider migrating to VarHandle or MethodHandles now to ease future Java upgrades.",
                ScanMode.BOTH);

        add("JAVA8_COMPAT", "sun.reflect", "MEDIUM",
                "sun.reflect.* is an internal JDK package — not portable across JVMs",
                "Works in Java 8 but inaccessible in Java 11+. "
                + "Use standard java.lang.reflect.* API.",
                ScanMode.BOTH);

        add("JAVA8_COMPAT", "com.sun.xml.internal", "MEDIUM",
                "com.sun.xml.internal.* is an internal JDK JAXB implementation",
                "Works in Java 8 but removed in Java 11+. "
                + "Declare an explicit JAXB dependency (com.sun.xml.bind:jaxb-impl) to stay portable.",
                ScanMode.BOTH);

        add("JAVA8_COMPAT", "com.sun.org.apache", "MEDIUM",
                "com.sun.org.apache.* (internal Xerces/Xalan) — internal JDK package",
                "Works in Java 8 but restricted in Java 11+. "
                + "Use javax.xml.parsers public APIs or add Xerces/Xalan as explicit dependency.",
                ScanMode.BOTH);

        add("JAVA8_COMPAT", "finalize", "INFO",
                "Object.finalize() is deprecated for removal in a future Java version",
                "Replace with try-with-resources or java.lang.ref.Cleaner for resource cleanup.",
                ScanMode.BOTH);

        add("JAVA8_COMPAT", "java.applet", "HIGH",
                "Applet API is deprecated in Java 8 and removed in Java 17",
                "Migrate applet code to a web application. Applets are unsupported in modern browsers.",
                ScanMode.BOTH);

        add("JAVA8_COMPAT", "java.rmi.activation", "MEDIUM",
                "RMI Activation API is deprecated for removal (removed in Java 17)",
                "Remove or replace with EJB remote, REST, or messaging.",
                ScanMode.BOTH);

        add("JAVA8_COMPAT", "SecurityManager", "INFO",
                "SecurityManager is deprecated as of Java 17 (present in Java 8 but planned for removal)",
                "WildFly 26 does not require a SecurityManager. "
                + "Begin planning removal to ease future Java upgrades.",
                ScanMode.BOTH);
    }

    // ── 10. THIRD-PARTY — WildFly 27 / Java 21 ───────────────────────────────

    private void loadThirdPartyRulesJava21() {
        add("THIRD_PARTY", "log4j.Logger", "HIGH",
                "Log4j 1.x is end-of-life (EOL) with critical security vulnerabilities (CVE-2019-17571)",
                "Migrate to Log4j 2.x or SLF4J + Logback. "
                + "WildFly ships JBoss Logging as a bridge.",
                ScanMode.BOTH);

        add("THIRD_PARTY", "org.apache.log4j", "HIGH",
                "Log4j 1.x classes found — EOL and security risk",
                "Migrate to Log4j 2.x or SLF4J. Remove log4j-1.x.jar from classpath.",
                ScanMode.BOTH);

        add("THIRD_PARTY", "org.apache.commons.logging", "MEDIUM",
                "Apache Commons Logging can conflict with WildFly Logging classloading",
                "Prefer SLF4J. If Commons Logging must stay, exclude the WildFly "
                + "logging module in jboss-deployment-structure.xml.",
                ScanMode.BOTH);

        add("THIRD_PARTY", "org.springframework", "HIGH",
                "Spring Framework found — Spring 5.x uses javax.* and is INCOMPATIBLE with WildFly 27+ (Jakarta EE 10)",
                "Upgrade to Spring 6.x which uses jakarta.* namespace. "
                + "Spring 5.x will cause ClassNotFoundExceptions at runtime on WildFly 27+.",
                ScanMode.BOTH);

        add("THIRD_PARTY", "org.hibernate.Session", "HIGH",
                "Hibernate found — Hibernate 5.x uses javax.persistence and is INCOMPATIBLE with WildFly 27+ (Jakarta EE 10)",
                "Upgrade to Hibernate 6.x (jakarta.persistence). "
                + "Hibernate 5.x will not work with WildFly 27+ / JBoss EAP 8.",
                ScanMode.BOTH);

        add("THIRD_PARTY", "org.quartz", "MEDIUM",
                "Quartz Scheduler found — verify version compatibility with Java 21",
                "Quartz 2.3.x works on Java 11+. Ensure no javax.* API conflicts "
                + "if Quartz uses any Java EE classes internally.",
                ScanMode.BOTH);
    }

    // ── 11. THIRD-PARTY — WildFly 26 / Java 8 ────────────────────────────────

    private void loadThirdPartyRulesJava8() {
        add("THIRD_PARTY", "log4j.Logger", "HIGH",
                "Log4j 1.x is end-of-life (EOL) with critical security vulnerabilities (CVE-2019-17571)",
                "Migrate to Log4j 2.x or SLF4J + Logback. "
                + "WildFly ships JBoss Logging as a bridge.",
                ScanMode.BOTH);

        add("THIRD_PARTY", "org.apache.log4j", "HIGH",
                "Log4j 1.x classes found — EOL and security risk",
                "Migrate to Log4j 2.x or SLF4J. Remove log4j-1.x.jar from classpath.",
                ScanMode.BOTH);

        add("THIRD_PARTY", "org.apache.commons.logging", "MEDIUM",
                "Apache Commons Logging can conflict with WildFly Logging classloading",
                "Prefer SLF4J. If Commons Logging must stay, exclude the WildFly "
                + "logging module in jboss-deployment-structure.xml.",
                ScanMode.BOTH);

        add("THIRD_PARTY", "org.springframework", "INFO",
                "Spring Framework found — Spring 5.x is COMPATIBLE with WildFly 26 / Java 8 (uses javax.*)",
                "Spring 5.x uses javax.* namespace which matches WildFly 26 (Jakarta EE 8). "
                + "Spring 3.x or 4.x are very old — consider upgrading to 5.x for security patches. "
                + "Do NOT upgrade to Spring 6.x without first upgrading to WildFly 27+ (jakarta.*).",
                ScanMode.BOTH);

        add("THIRD_PARTY", "org.hibernate.Session", "INFO",
                "Hibernate found — verify version is 5.x (compatible with WildFly 26 / javax.persistence)",
                "Hibernate 5.x is compatible with WildFly 26 (JPA 2.2 / javax.persistence). "
                + "Hibernate 3.x or 4.x may have classloading conflicts with WildFly's built-in Hibernate. "
                + "Exclude WildFly's Hibernate module in jboss-deployment-structure.xml if bundling your own.",
                ScanMode.BOTH);

        add("THIRD_PARTY", "org.hibernate.classic", "HIGH",
                "Hibernate 3.x/4.x classic session API detected — likely incompatible with WildFly 26",
                "Upgrade to Hibernate 5.x and use the standard Session API. "
                + "Exclude the WildFly built-in Hibernate module if bundling your own version.",
                ScanMode.BOTH);

        add("THIRD_PARTY", "org.quartz", "INFO",
                "Quartz Scheduler found — Quartz 2.x is compatible with Java 8 and WildFly 26",
                "Ensure Quartz datasource is configured via WildFly (not WebLogic JNDI). "
                + "Update any WebLogic-specific JNDI names in quartz.properties.",
                ScanMode.BOTH);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Custom rules from file
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Loads custom rules from wl-jboss-custom-rules.txt on the classpath
     * or from the working directory.
     *
     * File format (pipe-delimited):
     *   CATEGORY|apiPattern|SEVERITY|description|remediation
     */
    private void loadCustomRules() {
        Path externalFile = Path.of("wl-jboss-custom-rules.txt");
        if (Files.exists(externalFile)) {
            try (BufferedReader reader = Files.newBufferedReader(externalFile)) {
                parseRuleFile(reader);
            } catch (IOException e) {
                System.err.println("Warning: could not read " + externalFile + ": " + e.getMessage());
            }
            return;
        }

        try (InputStream is = WlJBossRules.class.getClassLoader()
                .getResourceAsStream("wl-jboss-custom-rules.txt")) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    parseRuleFile(reader);
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read custom rules from classpath: " + e.getMessage());
        }
    }

    private void parseRuleFile(BufferedReader reader) throws IOException {
        String line;
        int lineNum = 0;
        while ((line = reader.readLine()) != null) {
            lineNum++;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("\\|", -1);
            if (parts.length < 5) {
                System.err.println("Warning: skipping malformed rule at line " + lineNum);
                continue;
            }
            add(parts[0].trim(), parts[1].trim(), parts[2].trim(),
                    parts[3].trim(), parts[4].trim(), ScanMode.BOTH);
        }
    }

    private void add(String category, String apiPattern, String severity,
                     String description, String remediation, ScanMode mode) {
        rules.add(new Rule(category, apiPattern, severity, description, remediation, mode));
    }
}
