package effortanalyzer.library;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Framework upgrade compatibility rules (3.x / 4.x / 5.x → 5.3.39).
 *
 * Sources:
 *   - Spring 3→4 migration: docs.spring.io/spring-framework/docs/upgrade/spring3
 *   - Spring 4→5 migration: github.com/spring-projects/spring-framework/wiki/Upgrading-to-Spring-Framework-5.x
 *   - Spring 5.3 deprecated list: docs.spring.io/spring-framework/docs/5.3.24/javadoc-api/deprecated-list.html
 */
public class SpringRules {

    public static final String LIBRARY = "Spring 5.3.39";

    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();
        loadSpring3to4Removals(rules);
        loadSpring4to5Removals(rules);
        loadSpring5Deprecations(rules);
        return rules;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Spring 3.x → 4.0 — entire packages / classes removed
    // ══════════════════════════════════════════════════════════════════════════

    private static void loadSpring3to4Removals(List<DeprecatedApi> rules) {

        add(rules, "org.springframework.core.enums", null, "CRITICAL",
                "Use standard Java enums",
                "REMOVED in Spring 4.0: org.springframework.core.enums package deleted");

        add(rules, "org.springframework.ejb.support", null, "CRITICAL",
                "Use standard EJB 3+ annotations and CDI",
                "REMOVED in Spring 4.0: EJB 2.x support classes deleted");

        add(rules, "org.springframework.instrument.classloading.oc4j", null, "CRITICAL",
                "OC4J no longer supported; use supported application servers",
                "REMOVED in Spring 4.0: OC4J class loading support deleted");

        add(rules, "org.springframework.orm.ibatis", null, "CRITICAL",
                "Migrate to MyBatis-Spring (mybatis.org/spring)",
                "REMOVED in Spring 4.0: iBATIS (org.springframework.orm.ibatis) deleted");

        add(rules, "org.springframework.remoting.jaxrpc", null, "CRITICAL",
                "Use JAX-WS (org.springframework.remoting.jaxws) instead",
                "REMOVED in Spring 4.0: JAX-RPC support deleted");

        add(rules, "org.springframework.scheduling.backportconcurrent", null, "CRITICAL",
                "Use java.util.concurrent (standard since Java 5)",
                "REMOVED in Spring 4.0: backport-concurrent support deleted");

        add(rules, "org.springframework.scheduling.timer", null, "CRITICAL",
                "Use java.util.concurrent.ScheduledExecutorService or Spring's TaskScheduler",
                "REMOVED in Spring 4.0: java.util.Timer-based scheduling deleted");

        add(rules, "org.springframework.test.context.junit38", null, "CRITICAL",
                "Migrate tests to JUnit 4 with SpringJUnit4ClassRunner or SpringExtension",
                "REMOVED in Spring 4.0: JUnit 3.8 test support deleted");

        add(rules, "org.springframework.test.jpa", null, "CRITICAL",
                "Use Spring's standard JPA test support",
                "REMOVED in Spring 4.0: JPA test support package deleted");

        add(rules, "org.springframework.web.servlet.view.tiles", null, "CRITICAL",
                "Use org.springframework.web.servlet.view.tiles2 or tiles3",
                "REMOVED in Spring 4.0: Tiles 1.x view support deleted");

        add(rules, "org.springframework.web.struts", null, "CRITICAL",
                "Migrate to Spring MVC; Struts integration removed",
                "REMOVED in Spring 4.0: Struts 1 support package deleted");

        add(rules, "org.springframework.orm.hibernate3", null, "CRITICAL",
                "Upgrade to Hibernate 4/5; use org.springframework.orm.hibernate5",
                "REMOVED in Spring 5.0: Hibernate 3 support deleted (deprecated since 4.2)");

        add(rules, "org.springframework.web.servlet.view.tiles2", null, "CRITICAL",
                "Upgrade to Tiles 3.0+; use org.springframework.web.servlet.view.tiles3",
                "REMOVED in Spring 5.0: Tiles 2 view support deleted (deprecated since 4.2)");

        add(rules, "MappingJacksonMessageConverter", null, "CRITICAL",
                "Use MappingJackson2MessageConverter (Jackson 2.x)",
                "REMOVED in Spring 4.1: Jackson 1.x JMS converter removed");

        add(rules, "MappingJacksonJsonView", null, "CRITICAL",
                "Use MappingJackson2JsonView (Jackson 2.x)",
                "REMOVED in Spring 4.1: Jackson 1.x JSON view removed");

        add(rules, "MappingJacksonHttpMessageConverter", null, "CRITICAL",
                "Use MappingJackson2HttpMessageConverter (Jackson 2.x)",
                "REMOVED in Spring 4.1: Jackson 1.x HTTP converter removed");

        add(rules, "JacksonObjectMapperFactoryBean", null, "CRITICAL",
                "Use Jackson2ObjectMapperFactoryBean",
                "REMOVED in Spring 4.1: Jackson 1.x factory bean removed");

        add(rules, "SimpleBurlapServiceExporter", null, "CRITICAL",
                "Migrate to REST or Spring HTTP remoting; Burlap support removed",
                "REMOVED in Spring 5.0: Burlap remoting support deleted");

        add(rules, "BurlapServiceExporter", null, "CRITICAL",
                "Migrate to REST or Spring HTTP remoting",
                "REMOVED in Spring 5.0: Burlap remoting deleted");

        add(rules, "BurlapProxyFactoryBean", null, "CRITICAL",
                "Migrate to REST or Spring HTTP remoting",
                "REMOVED in Spring 5.0: Burlap remoting deleted");

        add(rules, "BurlapClientInterceptor", null, "CRITICAL",
                "Migrate to REST or Spring HTTP remoting",
                "REMOVED in Spring 5.0: Burlap remoting deleted");

        add(rules, "JBossWorkManagerUtils", null, "WARNING",
                "Use standard Java EE Work Manager or java.util.concurrent",
                "DEPRECATED in Spring 4.0: JBossWorkManagerUtils no longer works with current JBoss");

        add(rules, "JBossWorkManagerTaskExecutor", null, "WARNING",
                "Use DefaultManagedTaskExecutor (EE 7) or ThreadPoolTaskExecutor",
                "DEPRECATED in Spring 4.0: JBoss work manager integration deprecated");

        add(rules, "org.springframework.cache.interceptor.DefaultKeyGenerator", null, "WARNING",
                "Use SimpleKeyGenerator (Spring 4+ default) or implement KeyGenerator",
                "DEPRECATED in Spring 4.0: DefaultKeyGenerator replaced by SimpleKeyGenerator");

        add(rules, "org.springframework.core.GenericTypeResolver", "getTypeVariableMap", "WARNING",
                "Use ResolvableType instead",
                "DEPRECATED in Spring 4.0: getTypeVariableMap deprecated");

        add(rules, "org.springframework.core.GenericTypeResolver", "resolveType", "WARNING",
                "Use ResolvableType instead",
                "DEPRECATED in Spring 4.0: resolveType(Type, Map) deprecated");

        add(rules, "org.springframework.ui.velocity", null, "CRITICAL",
                "Velocity support removed; migrate to Thymeleaf or FreeMarker",
                "REMOVED in Spring 5.0: Velocity view/templating support deleted");

        add(rules, "VelocityEngineFactory", null, "CRITICAL",
                "Velocity support removed; migrate to Thymeleaf, FreeMarker, or Mustache",
                "REMOVED in Spring 5.0: Velocity removed");

        add(rules, "VelocityEngineUtils", null, "CRITICAL",
                "Velocity support removed; use FreeMarker or Thymeleaf",
                "REMOVED in Spring 5.0: Velocity removed");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Spring 4.x → 5.0 — removed / broken compatibility
    // ══════════════════════════════════════════════════════════════════════════

    private static void loadSpring4to5Removals(List<DeprecatedApi> rules) {

        add(rules, "org.springframework.web.context.request.async.CallableProcessingInterceptorAdapter", null, "CRITICAL",
                "Implement CallableProcessingInterceptor directly (has default methods in Spring 5)",
                "REMOVED in Spring 5.0: adapter deleted; interface now has Java 8 default methods");

        add(rules, "org.springframework.web.context.request.async.DeferredResultProcessingInterceptorAdapter", null, "CRITICAL",
                "Implement DeferredResultProcessingInterceptor directly",
                "REMOVED in Spring 5.0: adapter deleted; interface now has Java 8 default methods");

        add(rules, "org.springframework.web.socket.config.annotation.AbstractWebSocketMessageBrokerConfigurer", null, "CRITICAL",
                "Implement WebSocketMessageBrokerConfigurer directly (has default methods)",
                "REMOVED in Spring 5.0: abstract adapter removed; use interface default methods");

        add(rules, "org.springframework.messaging.support.ChannelInterceptorAdapter", null, "CRITICAL",
                "Implement ChannelInterceptor directly",
                "REMOVED in Spring 5.0: adapter deleted; interface now has Java 8 default methods");

        add(rules, "org.springframework.web.client.AsyncRestTemplate", null, "CRITICAL",
                "Use org.springframework.web.reactive.function.client.WebClient",
                "DEPRECATED in Spring 5.0 (effective removal from support): AsyncRestTemplate → WebClient");

        add(rules, "org.springframework.http.client.AsyncClientHttpRequest", null, "CRITICAL",
                "Use WebClient / ClientRequest",
                "DEPRECATED Spring 5.0: async HTTP client API replaced by reactive model");

        add(rules, "org.springframework.http.client.AsyncClientHttpRequestFactory", null, "CRITICAL",
                "Use ClientHttpConnector",
                "DEPRECATED Spring 5.0");

        add(rules, "org.springframework.http.client.AsyncClientHttpRequestInterceptor", null, "CRITICAL",
                "Use ExchangeFilterFunction",
                "DEPRECATED Spring 5.0");

        add(rules, "org.springframework.http.client.HttpComponentsAsyncClientHttpRequestFactory", null, "CRITICAL",
                "Use HttpComponentsClientHttpConnector",
                "DEPRECATED Spring 5.0: replaced by reactive connector");

        add(rules, "org.springframework.http.client.support.AsyncHttpAccessor", null, "CRITICAL",
                "No direct replacement; migrate to WebClient",
                "DEPRECATED Spring 5.0: async HTTP support removed");

        add(rules, "org.springframework.http.client.support.InterceptingAsyncHttpAccessor", null, "CRITICAL",
                "No direct replacement; migrate to WebClient",
                "DEPRECATED Spring 5.0");

        add(rules, "org.springframework.http.client.InterceptingAsyncClientHttpRequestFactory", null, "CRITICAL",
                "No direct replacement; migrate to WebClient",
                "DEPRECATED Spring 5.0");

        add(rules, "org.springframework.web.client.AsyncRestOperations", null, "CRITICAL",
                "Use WebClient",
                "DEPRECATED Spring 5.0: AsyncRestOperations → WebClient");

        add(rules, "org.springframework.web.client.AsyncRequestCallback", null, "CRITICAL",
                "Use ExchangeFilterFunction",
                "DEPRECATED Spring 5.0");

        add(rules, "org.springframework.web.util.AbstractUriTemplateHandler", null, "CRITICAL",
                "Use DefaultUriBuilderFactory",
                "DEPRECATED Spring 5.0: URI template handler API replaced");

        add(rules, "org.springframework.web.util.DefaultUriTemplateHandler", null, "CRITICAL",
                "Use DefaultUriBuilderFactory (note: parsePath default changed from false to true)",
                "DEPRECATED Spring 5.0");

        add(rules, "org.springframework.util.comparator.CompoundComparator", null, "WARNING",
                "Use standard JDK 8 Comparator.thenComparing()",
                "DEPRECATED Spring 5.0: replaced by standard JDK 8 Comparator");

        add(rules, "org.springframework.util.comparator.InvertibleComparator", null, "WARNING",
                "Use standard JDK 8 Comparator.reversed()",
                "DEPRECATED Spring 5.0: replaced by standard JDK 8 Comparator");

        add(rules, "org.springframework.http.client.support.BasicAuthorizationInterceptor", null, "WARNING",
                "Use BasicAuthenticationInterceptor with HttpHeaders.setBasicAuth()",
                "DEPRECATED Spring 5.1.1: charset now ISO-8859-1 by default");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Spring 5.x full deprecated-list (5.0 → 5.3.39)
    // ══════════════════════════════════════════════════════════════════════════

    private static void loadSpring5Deprecations(List<DeprecatedApi> rules) {

        for (String cls : new String[]{
                "org.springframework.remoting.caucho.HessianClientInterceptor",
                "org.springframework.remoting.caucho.HessianExporter",
                "org.springframework.remoting.caucho.HessianProxyFactoryBean",
                "org.springframework.remoting.caucho.HessianServiceExporter",
                "org.springframework.remoting.httpinvoker.HttpInvokerClientInterceptor",
                "org.springframework.remoting.httpinvoker.HttpInvokerProxyFactoryBean",
                "org.springframework.remoting.httpinvoker.HttpInvokerServiceExporter",
                "org.springframework.remoting.httpinvoker.HttpComponentsHttpInvokerRequestExecutor",
                "org.springframework.remoting.httpinvoker.AbstractHttpInvokerRequestExecutor",
                "org.springframework.remoting.httpinvoker.HttpInvokerClientConfiguration",
                "org.springframework.remoting.httpinvoker.HttpInvokerRequestExecutor",
                "org.springframework.jms.remoting.JmsInvokerClientInterceptor",
                "org.springframework.jms.remoting.JmsInvokerProxyFactoryBean",
                "org.springframework.jms.remoting.JmsInvokerServiceExporter",
                "org.springframework.remoting.rmi.JndiRmiClientInterceptor",
                "org.springframework.remoting.rmi.JndiRmiProxyFactoryBean",
                "org.springframework.remoting.rmi.JndiRmiServiceExporter",
                "org.springframework.remoting.rmi.RmiInvocationHandler",
                "org.springframework.remoting.rmi.CodebaseAwareObjectInputStream"
        }) {
            add(rules, cls, null, "CRITICAL",
                    "Migrate to REST APIs (Spring MVC/WebFlux) or messaging; serialization-based remoting is being removed",
                    "DEPRECATED in Spring 5.3: phasing out serialization-based remoting");
        }

        for (String cls : new String[]{
                "org.springframework.jca.cci.core.CciOperations",
                "org.springframework.jca.cci.core.ConnectionCallback",
                "org.springframework.jca.cci.core.InteractionCallback",
                "org.springframework.jca.cci.core.RecordCreator",
                "org.springframework.jca.cci.core.RecordExtractor",
                "org.springframework.jca.cci.core.CciTemplate",
                "org.springframework.jca.cci.core.support.CciDaoSupport",
                "org.springframework.jca.cci.core.support.CommAreaRecord",
                "org.springframework.jca.cci.connection.CciLocalTransactionManager",
                "org.springframework.jca.cci.connection.ConnectionFactoryUtils",
                "org.springframework.jca.cci.connection.ConnectionHolder",
                "org.springframework.jca.cci.connection.ConnectionSpecConnectionFactoryAdapter",
                "org.springframework.jca.cci.connection.DelegatingConnectionFactory",
                "org.springframework.jca.cci.object.EisOperation"
        }) {
            add(rules, cls, null, "WARNING",
                    "Use specific data access APIs or native CCI if no alternative exists",
                    "DEPRECATED in Spring 5.3: JCA CCI support being phased out");
        }

        for (String cls : new String[]{
                "org.springframework.format.datetime.joda.DateTimeFormatterFactory",
                "org.springframework.format.datetime.joda.DateTimeFormatterFactoryBean",
                "org.springframework.format.datetime.joda.DateTimeParser",
                "org.springframework.format.datetime.joda.JodaDateTimeFormatAnnotationFormatterFactory",
                "org.springframework.format.datetime.joda.JodaTimeContext",
                "org.springframework.format.datetime.joda.JodaTimeContextHolder",
                "org.springframework.format.datetime.joda.JodaTimeFormatterRegistrar"
        }) {
            add(rules, cls, null, "WARNING",
                    "Use standard JSR-310 (java.time) date/time formatting",
                    "DEPRECATED in Spring 5.3: Joda-Time integration deprecated in favor of JSR-310");
        }

        add(rules, "org.springframework.scheduling.commonj.DelegatingTimerListener", null, "WARNING",
                "Use DefaultManagedTaskScheduler (EE 7)",
                "DEPRECATED Spring 5.1: CommonJ scheduling deprecated");

        add(rules, "org.springframework.scheduling.commonj.DelegatingWork", null, "WARNING",
                "Use DefaultManagedTaskExecutor (EE 7)",
                "DEPRECATED Spring 5.1: CommonJ scheduling deprecated");

        add(rules, "org.springframework.web.servlet.handler.HandlerInterceptorAdapter", null, "WARNING",
                "Implement HandlerInterceptor and/or AsyncHandlerInterceptor directly",
                "DEPRECATED in Spring 5.3: adapter removed; interface has default methods");

        add(rules, "org.springframework.web.servlet.mvc.LastModified", null, "WARNING",
                "Use WebRequest.checkNotModified() or return ResponseEntity with ETag/Last-Modified headers",
                "DEPRECATED in Spring 5.3.9");

        add(rules, "org.springframework.web.filter.HttpPutFormContentFilter", null, "WARNING",
                "Use FormContentFilter which also handles DELETE",
                "DEPRECATED in Spring 5.1");

        add(rules, "org.springframework.web.filter.reactive.ForwardedHeaderFilter", null, "WARNING",
                "Use ForwardedHeaderTransformer as a bean named 'forwardedHeaderTransformer'",
                "DEPRECATED in Spring 5.1: replaced by ForwardedHeaderTransformer");

        add(rules, "org.springframework.web.reactive.resource.AppCacheManifestTransformer", null, "WARNING",
                "Remove AppCache manifest handling; browser support is going away",
                "DEPRECATED in Spring 5.3: AppCache browser support removed");

        add(rules, "org.springframework.web.servlet.resource.AppCacheManifestTransformer", null, "WARNING",
                "Remove AppCache manifest handling",
                "DEPRECATED in Spring 5.3");

        add(rules, "org.springframework.web.reactive.resource.GzipResourceResolver", null, "WARNING",
                "Use EncodedResourceResolver",
                "DEPRECATED in Spring 5.1");

        add(rules, "org.springframework.web.servlet.resource.GzipResourceResolver", null, "WARNING",
                "Use EncodedResourceResolver",
                "DEPRECATED in Spring 5.1");

        add(rules, "org.springframework.web.servlet.mvc.method.annotation.AbstractJsonpResponseBodyAdvice", null, "CRITICAL",
                "Remove JSONP support; use CORS (spring.io/guides/gs/rest-service-cors/)",
                "REMOVED in Spring 5.3: JSONP support deleted");

        add(rules, "org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter", null, "WARNING",
                "Implement InstantiationAwareBeanPostProcessor or SmartInstantiationAwareBeanPostProcessor directly",
                "DEPRECATED in Spring 5.3: adapter deleted; interfaces have default methods");

        add(rules, "org.springframework.beans.annotation.AnnotationBeanUtils", null, "WARNING",
                "Use Spring's AnnotationUtils or MergedAnnotations",
                "DEPRECATED in Spring 5.2: custom annotation attribute processing preferred");

        add(rules, "org.springframework.core.type.classreading.AnnotationMetadataReadingVisitor", null, "WARNING",
                "Internal only - no public replacement; use AnnotationMetadata",
                "DEPRECATED in Spring 5.2: replaced by SimpleAnnotationMetadataReadingVisitor");

        add(rules, "org.springframework.context.support.LiveBeansView", null, "WARNING",
                "Use Spring Boot Actuator for live bean inspection",
                "DEPRECATED in Spring 5.3: use Spring Boot actuators");

        add(rules, "org.springframework.context.support.LiveBeansViewMBean", null, "WARNING",
                "Use Spring Boot Actuator",
                "DEPRECATED in Spring 5.3");

        add(rules, "org.springframework.jdbc.core.BatchUpdateUtils", null, "WARNING",
                "No replacement needed; no longer used by JdbcTemplate",
                "DEPRECATED in Spring 5.1.3");

        add(rules, "org.springframework.jdbc.core.support.JdbcBeanDefinitionReader", null, "WARNING",
                "Use standard bean definition formats (XML/annotations/Java config)",
                "DEPRECATED in Spring 5.3");

        add(rules, "org.springframework.jdbc.support.incrementer.DB2MainframeSequenceMaxValueIncrementer", null, "WARNING",
                "Use Db2MainframeMaxValueIncrementer",
                "DEPRECATED: renamed");

        add(rules, "org.springframework.jdbc.support.incrementer.DB2SequenceMaxValueIncrementer", null, "WARNING",
                "Use Db2LuwMaxValueIncrementer",
                "DEPRECATED: renamed");

        add(rules, "org.springframework.jdbc.core.JdbcTemplate", "queryForObject", "WARNING",
                "Handle EmptyResultDataAccessException; method now throws for empty results",
                "BEHAVIOR CHANGE in Spring 5.3: queryForObject throws IncorrectResultSizeDataAccessException");

        add(rules, "org.springframework.scheduling.support.CronSequenceGenerator", null, "WARNING",
                "Use CronExpression (supports macros like @daily, @hourly)",
                "DEPRECATED in Spring 5.3: replaced by CronExpression");

        add(rules, "org.springframework.oxm.jibx.JibxMarshaller", null, "WARNING",
                "JiBX project is inactive; use JAXB or Jackson for XML binding",
                "DEPRECATED in Spring 5.1.5: JiBX project inactive");

        add(rules, "org.springframework.http.converter.protobuf.ExtensionRegistryInitializer", null, "WARNING",
                "Use ExtensionRegistry-based constructors instead",
                "DEPRECATED in Spring 5.1");

        add(rules, "org.springframework.mock.jndi.ExpectedLookupTemplate", null, "WARNING",
                "Use Simple-JNDI or similar third-party JNDI test utilities",
                "DEPRECATED in Spring 5.2: use third-party JNDI solutions");

        add(rules, "org.springframework.test.context.support.GenericPropertiesContextLoader", null, "WARNING",
                "Use AnnotationConfigContextLoader or GenericXmlContextLoader",
                "DEPRECATED in Spring 5.3: use standard bean definition loaders");

        add(rules, "org.springframework.orm.jpa.vendor.HibernateJpaSessionFactoryBean", null, "WARNING",
                "Use EntityManagerFactory.unwrap(SessionFactory.class) with explicit qualifiers",
                "DEPRECATED in Spring 4.3.12: against Hibernate 5.2+");

        add(rules, "org.springframework.web.client.RestTemplate", null, "INFO",
                "Consider migrating to WebClient for reactive/non-blocking HTTP calls",
                "RestTemplate is in maintenance mode since Spring 5.0; WebClient preferred for new code");

        add(rules, "org.springframework.web.bind.annotation.RequestMethod", null, "WARNING",
                "Use HttpMethod enum instead of RequestMethod in places that accept it",
                "RequestMethod being phased out in favor of HttpMethod");

        add(rules, "org.springframework.http.MediaType", "APPLICATION_JSON_UTF8", "WARNING",
                "Use MediaType.APPLICATION_JSON (UTF-8 is now the default charset)",
                "APPLICATION_JSON_UTF8 deprecated; UTF-8 is default for APPLICATION_JSON");

        add(rules, "org.springframework.http.MediaType", "TEXT_HTML_UTF8", "WARNING",
                "Use MediaType.TEXT_HTML",
                "TEXT_HTML_UTF8 deprecated");

        add(rules, "org.springframework.security.config.annotation.web.builders.WebSecurity", "ignoring", "WARNING",
                "Use permitAll() instead of ignoring(); ignoring() bypasses Spring Security entirely",
                "WebSecurity.ignoring() deprecated; use permitAll() on HttpSecurity for public endpoints");

        add(rules, "org.springframework.batch.core.configuration.annotation.JobBuilderFactory", null, "WARNING",
                "Use JobBuilder directly (Spring Batch 5.x)",
                "JobBuilderFactory deprecated");

        add(rules, "org.springframework.batch.core.configuration.annotation.StepBuilderFactory", null, "WARNING",
                "Use StepBuilder directly (Spring Batch 5.x)",
                "StepBuilderFactory deprecated");

        add(rules, "org.springframework.aop.framework.ProxyFactoryBean", "setInterfaces", "WARNING",
                "Use setProxyTargetClass(true) for class-based CGLIB/ByteBuddy proxies",
                "Interface-based proxies deprecated in favor of class-based proxies");

        add(rules, "org.springframework.scheduling.annotation.AsyncConfigurer", "getAsyncExecutor", "WARNING",
                "Implement AsyncConfigurer properly; getAsyncExecutor must return non-null",
                "AsyncConfigurer interface contract changed");
    }

    private static void add(List<DeprecatedApi> rules, String className, String methodName,
                             String severity, String replacement, String description) {
        rules.add(new DeprecatedApi(LIBRARY, className, methodName, severity, replacement, description));
    }
}
