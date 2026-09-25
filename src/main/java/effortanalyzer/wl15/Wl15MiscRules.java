package effortanalyzer.wl15;

import effortanalyzer.library.DeprecatedApi;
import java.util.ArrayList;
import java.util.List;

/**
 * Miscellaneous WL15 library migration rules for:
 * EhCache 3.11.1, commons-fileupload 1.6.0, commons-beanutils 1.11.0,
 * hibernate-validator 6.2.0, c3p0 0.12.0, mina-core 2.0.28,
 * nimbus-jose-jwt 9.37.2, owasp-java-html-sanitizer 20280101.1,
 * lz4-java, neethi 3.2.0, commons-vfs2, assertj-core 3.27.
 */
public class Wl15MiscRules {

    private static final String EHCACHE   = "EhCache 3.11.1";
    private static final String FILEUPLOAD = "commons-fileupload 1.6.0";
    private static final String BEANUTILS = "commons-beanutils 1.11.0";
    private static final String HV        = "hibernate-validator 6.2.0";
    private static final String C3P0      = "c3p0 0.12.0";
    private static final String MINA      = "mina-core 2.0.28";
    private static final String NIMBUS    = "nimbus-jose-jwt 9.37.2";
    private static final String OWASP     = "owasp-java-html-sanitizer 20280101.1";
    private static final String LZ4       = "lz4-java 1.8.x";
    private static final String NEETHI    = "neethi 3.2.0";
    private static final String VFS       = "commons-vfs2 2.9.0";
    private static final String ASSERTJ   = "assertj-core 3.27";

    private Wl15MiscRules() {}

    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();
        addEhCacheRules(rules);
        addCommonsRules(rules);
        addHibernateValidatorRules(rules);
        addC3p0Rules(rules);
        addMinaRules(rules);
        addNimbusRules(rules);
        addOwaspRules(rules);
        addMiscRules(rules);
        return rules;
    }

    // EhCache 2.x (net.sf.ehcache) -> EhCache 3.x (org.ehcache) -- FULL namespace change
    private static void addEhCacheRules(List<DeprecatedApi> rules) {
        add(rules, EHCACHE, "net.sf.ehcache", null, "CRITICAL",
                "Migrate from net.sf.ehcache (EhCache 2.x) to org.ehcache (EhCache 3.x); API completely rewritten.",
                "EhCache 3.x uses org.ehcache namespace; net.sf.ehcache is EhCache 2.x and incompatible");

        add(rules, EHCACHE, "net.sf.ehcache.CacheManager", null, "CRITICAL",
                "Replace CacheManager with org.ehcache.CacheManager; instantiate via CacheManagerBuilder.newCacheManagerBuilder().",
                "net.sf.ehcache.CacheManager removed; use org.ehcache.CacheManager with builder API");

        add(rules, EHCACHE, "net.sf.ehcache.Cache", null, "CRITICAL",
                "Replace net.sf.ehcache.Cache with org.ehcache.Cache<K,V> (type-safe in EhCache 3).",
                "net.sf.ehcache.Cache removed; use org.ehcache.Cache<K,V>");

        add(rules, EHCACHE, "net.sf.ehcache.Element", null, "CRITICAL",
                "Remove Element wrapper; EhCache 3 uses cache.put(key, value) directly.",
                "net.sf.ehcache.Element removed; EhCache 3 is a direct key-value store without Element wrapper");

        add(rules, EHCACHE, "net.sf.ehcache.config.CacheConfiguration", null, "HIGH",
                "Replace XML/programmatic CacheConfiguration with EhCache 3 ResourcePoolsBuilder + CacheConfigurationBuilder.",
                "net.sf.ehcache.config.CacheConfiguration replaced by EhCache 3 builder API");
    }

    // commons-fileupload 1.6.0 and commons-beanutils 1.11.0
    private static void addCommonsRules(List<DeprecatedApi> rules) {
        add(rules, FILEUPLOAD, "org.apache.commons.fileupload.FileUpload", null, "HIGH",
                "Migrate to org.apache.commons.fileupload2.core.FileUpload (commons-fileupload2).",
                "commons-fileupload 1.6 is a compatibility bridge; full API moved to fileupload2");

        add(rules, FILEUPLOAD, "org.apache.commons.fileupload.DiskFileItemFactory", null, "HIGH",
                "Replace with org.apache.commons.fileupload2.disk.DiskFileItemFactory.",
                "DiskFileItemFactory moved to commons-fileupload2 in 1.6.0");

        add(rules, FILEUPLOAD, "org.apache.commons.fileupload.servlet.ServletFileUpload", null, "HIGH",
                "Replace with org.apache.commons.fileupload2.jakarta.JakartaServletFileUpload.",
                "ServletFileUpload moved to commons-fileupload2; use Jakarta variant for Servlet 5+");

        add(rules, BEANUTILS, "org.apache.commons.beanutils.BeanUtils", "populate", "WARNING",
                "Review BeanUtils.populate(); type conversion strictness increased in 1.11.0.",
                "BeanUtils.populate() type conversion tightened in commons-beanutils 1.11.0");

        add(rules, BEANUTILS, "org.apache.commons.beanutils.ConvertUtils", "convert", "WARNING",
                "Review ConvertUtils.convert(); null-handling changed in commons-beanutils 1.11.0.",
                "ConvertUtils.convert() null behaviour changed in commons-beanutils 1.11.0");
    }

    // hibernate-validator 6.2.0
    private static void addHibernateValidatorRules(List<DeprecatedApi> rules) {
        add(rules, HV, "org.hibernate.validator.constraints.NotEmpty", null, "HIGH",
                "Replace @NotEmpty with standard @jakarta.validation.constraints.NotEmpty.",
                "org.hibernate.validator.constraints.NotEmpty deprecated; use jakarta.validation equivalent");

        add(rules, HV, "org.hibernate.validator.constraints.NotBlank", null, "HIGH",
                "Replace @NotBlank with standard @jakarta.validation.constraints.NotBlank.",
                "org.hibernate.validator.constraints.NotBlank deprecated; use jakarta.validation equivalent");

        add(rules, HV, "org.hibernate.validator.constraints.Range", null, "WARNING",
                "Prefer @Min/@Max constraints from jakarta.validation for simple range checks.",
                "org.hibernate.validator.constraints.Range retained but jakarta.validation @Min/@Max preferred");

        add(rules, HV, "org.hibernate.validator.internal.engine.ConstraintViolationImpl", null, "WARNING",
                "Do not depend on internal implementation class; use jakarta.validation.ConstraintViolation.",
                "ConstraintViolationImpl is internal; use public API jakarta.validation.ConstraintViolation");
    }

    // c3p0 0.12.0
    private static void addC3p0Rules(List<DeprecatedApi> rules) {
        add(rules, C3P0, "com.mchange.v2.c3p0.ComboPooledDataSource", "setMaxStatements", "WARNING",
                "Review c3p0 connection pool properties; naming conventions changed in 0.12.x.",
                "c3p0 0.12.0 property naming for statement caching changed");

        add(rules, C3P0, "com.mchange.v2.c3p0.C3P0Registry", null, "WARNING",
                "C3P0Registry API changed in 0.12.x; review pool management code.",
                "C3P0Registry API changed in c3p0 0.12.0");
    }

    // Apache MINA 2.0.28
    private static void addMinaRules(List<DeprecatedApi> rules) {
        add(rules, MINA, "org.apache.mina.core.service.IoHandlerAdapter", null, "WARNING",
                "Verify IoHandlerAdapter usage; exception handling signatures changed in 2.0.28.",
                "IoHandlerAdapter.exceptionCaught() signature may need review in MINA 2.0.28");

        add(rules, MINA, "org.apache.mina.core.session.IoSession", "write", "WARNING",
                "Review IoSession.write() for WriteRequest changes in MINA 2.0.28.",
                "IoSession.write() future handling changed in MINA 2.0.28");

        add(rules, MINA, "org.apache.mina.filter.codec.ProtocolCodecFilter", null, "WARNING",
                "Verify ProtocolCodecFilter constructor; factory interface changed in MINA 2.0.28.",
                "ProtocolCodecFilter constructor changed in MINA 2.0.28");
    }

    // Nimbus JOSE+JWT 9.37.2
    private static void addNimbusRules(List<DeprecatedApi> rules) {
        add(rules, NIMBUS, "com.nimbusds.jose.JWSAlgorithm", null, "WARNING",
                "Verify JWSAlgorithm constants; new algorithms added, verify existing usage.",
                "Nimbus JOSE+JWT 9.37: new JWSAlgorithm constants added; verify all algorithm references");

        add(rules, NIMBUS, "com.nimbusds.jwt.JWTClaimsSet", "parse", "HIGH",
                "Replace JWTClaimsSet.parse(JSONObject) with JWTClaimsSet.parse(String).",
                "JWTClaimsSet.parse(JSONObject) removed; use JWTClaimsSet.parse(String) in 9.x");

        add(rules, NIMBUS, "com.nimbusds.jose.proc.SecurityContext", null, "WARNING",
                "Ensure custom SecurityContext implementations compile with updated generics in 9.37.",
                "SecurityContext generic signature changed in Nimbus JOSE+JWT 9.x");

        add(rules, NIMBUS, "com.nimbusds.oauth2.sdk", null, "WARNING",
                "Review oauth2-oidc-sdk dependency; version alignment with nimbus-jose-jwt 9.37 required.",
                "oauth2-oidc-sdk requires version compatible with nimbus-jose-jwt 9.37");
    }

    // OWASP Java HTML Sanitizer
    private static void addOwaspRules(List<DeprecatedApi> rules) {
        add(rules, OWASP, "org.owasp.html.PolicyFactory", "sanitize", "WARNING",
                "PolicyFactory.sanitize() retained; verify HtmlPolicyBuilder usage for new policy features.",
                "OWASP HTML Sanitizer: PolicyFactory.sanitize() signature updated; review policy configuration");

        add(rules, OWASP, "org.owasp.html.HtmlPolicyBuilder", null, "WARNING",
                "Review HtmlPolicyBuilder; attribute/element allow-list API changed in 20280101.1.",
                "HtmlPolicyBuilder API updated in owasp-java-html-sanitizer 20280101.1");
    }

    // lz4-java, neethi, commons-vfs2, assertj-core
    private static void addMiscRules(List<DeprecatedApi> rules) {
        add(rules, LZ4, "net.jpountz.lz4.LZ4Factory", "fastestInstance", "WARNING",
                "LZ4Factory.fastestInstance() retained; verify JNI native loading path in new environment.",
                "lz4-java native loading may need path adjustment in WL15 environment");

        add(rules, NEETHI, "org.apache.neethi.PolicyOperator", null, "WARNING",
                "Verify PolicyOperator usage; neethi 3.2.0 changed policy serialization.",
                "neethi 3.2.0 PolicyOperator serialization changed; verify WS-Policy generation");

        add(rules, VFS, "org.apache.commons.vfs2.VFS", "getManager", "WARNING",
                "Use VFS.getManager() carefully; FileSystemManager is not thread-safe by default.",
                "commons-vfs2 2.9.0 VFS.getManager() thread-safety documentation clarified");

        add(rules, VFS, "org.apache.commons.vfs2.impl.StandardFileSystemManager", null, "WARNING",
                "Verify StandardFileSystemManager configuration; provider registration changed in 2.9.0.",
                "commons-vfs2 2.9.0 file system provider registration changed");

        add(rules, ASSERTJ, "org.assertj.core.api.Assertions", "assertThat", "WARNING",
                "assertThat() retained; verify assertj-core 3.27 deprecation of non-generic overloads.",
                "assertj-core 3.27 deprecated some non-generic assertThat() overloads");
    }

    private static void add(List<DeprecatedApi> rules, String library, String className,
                             String methodName, String severity, String replacement, String description) {
        rules.add(new DeprecatedApi(library, className, methodName, severity, replacement, description));
    }
}