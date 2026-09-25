package effortanalyzer.wl15;

import effortanalyzer.library.DeprecatedApi;
import java.util.ArrayList;
import java.util.List;

/** Spring 5.x to Spring Framework 6.2.11 / Spring Security 6.5.9 migration rules. */
public class Spring6Rules {

    private static final String SPRING   = "Spring 6.2.11";
    private static final String SECURITY = "Spring Security 6.5.9";
    private static final String MVC      = "Spring MVC 6.x";

    private Spring6Rules() {}

    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();
        addCoreRemovals(rules);
        addSecurityRemovals(rules);
        addMvcRemovals(rules);
        return rules;
    }

    // Spring Framework core removals
    private static void addCoreRemovals(List<DeprecatedApi> rules) {
        add(rules, SPRING, "org.springframework.remoting.httpinvoker", null, "CRITICAL",
                "Remove HTTP Invoker; migrate to REST (WebClient/RestTemplate) or gRPC.",
                "Spring Remoting HTTP Invoker completely removed in Spring 6.0");
        add(rules, SPRING, "org.springframework.remoting.caucho", null, "CRITICAL",
                "Remove Hessian/Burlap remoting; migrate to REST or gRPC.",
                "Spring Remoting Hessian/Burlap completely removed in Spring 6.0");
        add(rules, SPRING, "org.springframework.remoting.rmi", null, "CRITICAL",
                "Remove RMI-based remoting; migrate to REST or gRPC.",
                "Spring RMI remoting completely removed in Spring 6.0");
        add(rules, SPRING, "org.springframework.jms.remoting", null, "CRITICAL",
                "Remove JMS-based remoting; use JMS listeners directly.",
                "Spring JMS Remoting completely removed in Spring 6.0");
        add(rules, SPRING, "org.springframework.jca.cci", null, "CRITICAL",
                "Remove CCI-based resource adapters; use JCA directly or a modern connector.",
                "Spring JCA CCI support completely removed in Spring 6.0");
        add(rules, SPRING, "org.springframework.format.datetime.joda", null, "HIGH",
                "Replace Joda-Time integration with java.time (JSR-310) converters.",
                "Spring Joda-Time integration removed in Spring 6.0; use java.time APIs");
        add(rules, MVC, "org.springframework.web.servlet.handler.HandlerInterceptorAdapter", null, "CRITICAL",
                "Implement HandlerInterceptor interface directly (default methods since Spring 5.0).",
                "HandlerInterceptorAdapter removed in Spring 6.0 -- implement HandlerInterceptor directly");
        add(rules, MVC, "org.springframework.web.multipart.commons.CommonsMultipartResolver", null, "HIGH",
                "Use StandardServletMultipartResolver (Servlet 3.0 API).",
                "CommonsMultipartResolver removed in Spring 6.0; use StandardServletMultipartResolver");
        add(rules, MVC, "org.springframework.web.client.RestTemplate", "exchange", "WARNING",
                "Consider migrating to RestClient (Spring 6.1+) or WebClient for reactive apps.",
                "RestTemplate retained but RestClient/WebClient preferred in Spring 6.x");
    }

    // Spring Security 6.x removals
    private static void addSecurityRemovals(List<DeprecatedApi> rules) {
        add(rules, SECURITY,
                "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter",
                null, "CRITICAL",
                "Replace with @Bean SecurityFilterChain and @Bean WebSecurityCustomizer components.",
                "WebSecurityConfigurerAdapter removed in Spring Security 6.0");
        add(rules, SECURITY,
                "org.springframework.security.config.annotation.web.builders.HttpSecurity",
                "antMatchers", "CRITICAL",
                "Replace antMatchers() with requestMatchers() in the HttpSecurity DSL.",
                "HttpSecurity.antMatchers() removed in Spring Security 6.0 -- use requestMatchers()");
        add(rules, SECURITY,
                "org.springframework.security.config.annotation.web.builders.HttpSecurity",
                "mvcMatchers", "CRITICAL",
                "Replace mvcMatchers() with requestMatchers() in HttpSecurity DSL.",
                "HttpSecurity.mvcMatchers() removed in Spring Security 6.0 -- use requestMatchers()");
        add(rules, SECURITY,
                "org.springframework.security.config.annotation.web.builders.HttpSecurity",
                "regexMatchers", "CRITICAL",
                "Replace regexMatchers() with requestMatchers(new RegexRequestMatcher(...)).",
                "HttpSecurity.regexMatchers() removed in Spring Security 6.0");
        add(rules, SECURITY,
                "org.springframework.security.config.annotation.web.builders.HttpSecurity",
                "authorizeRequests", "CRITICAL",
                "Replace authorizeRequests() with authorizeHttpRequests() in HttpSecurity DSL.",
                "HttpSecurity.authorizeRequests() removed in Spring Security 6.0");
        add(rules, SECURITY,
                "org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity",
                null, "CRITICAL",
                "Replace @EnableGlobalMethodSecurity with @EnableMethodSecurity (available since Security 5.6).",
                "@EnableGlobalMethodSecurity removed in Spring Security 6.0 -- use @EnableMethodSecurity");
        add(rules, SECURITY,
                "org.springframework.security.config.annotation.web.configurers.ExpressionInterceptUrlRegistry",
                null, "HIGH",
                "Use AuthorizationManagerRequestMatcherRegistry from authorizeHttpRequests().",
                "ExpressionInterceptUrlRegistry removed; use authorizeHttpRequests() DSL");
        add(rules, SECURITY,
                "org.springframework.security.crypto.password.NoOpPasswordEncoder",
                null, "CRITICAL",
                "Replace NoOpPasswordEncoder with BCryptPasswordEncoder or Argon2PasswordEncoder.",
                "NoOpPasswordEncoder is insecure; removed in Spring Security 6.x");
        add(rules, SECURITY,
                "org.springframework.security.access.vote.AffirmativeBased",
                null, "WARNING",
                "Replace AffirmativeBased with the AuthorizationManager API in Spring Security 6.x.",
                "AffirmativeBased (AccessDecisionVoter) deprecated -- use AuthorizationManager");
        add(rules, SECURITY,
                "org.springframework.security.access.vote.UnanimousBased",
                null, "WARNING",
                "Replace UnanimousBased with AuthorizationManager in Spring Security 6.x.",
                "UnanimousBased deprecated -- use AuthorizationManager-based approach");
        add(rules, SECURITY,
                "org.springframework.security.access.vote.ConsensusBased",
                null, "WARNING",
                "Replace ConsensusBased with AuthorizationManager in Spring Security 6.x.",
                "ConsensusBased deprecated -- use AuthorizationManager-based approach");
        add(rules, SECURITY,
                "org.springframework.security.web.context.HttpSessionSecurityContextRepository",
                null, "WARNING",
                "Review; DelegatingSecurityContextRepository is the default in Security 6.0.",
                "HttpSessionSecurityContextRepository no longer the default context repo in Security 6.0");
    }

    // Spring MVC / WebFlux removals
    private static void addMvcRemovals(List<DeprecatedApi> rules) {
        add(rules, MVC, "org.springframework.util.AntPathMatcher", null, "WARNING",
                "Prefer PathPatternParser for Spring MVC 6; AntPathMatcher is legacy.",
                "AntPathMatcher is legacy in Spring 6; PathPatternParser is the default strategy");
        add(rules, SPRING,
                "org.springframework.web.reactive.function.server.RouterFunctions",
                "route", "WARNING",
                "RouterFunctions.route() overloads changed; migrate to builder API.",
                "RouterFunctions.route(RequestPredicate,HandlerFunction) overload removed in Spring 6");
        add(rules, SPRING,
                "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
                null, "HIGH",
                "Migrate spring.factories to META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports.",
                "Spring Boot 3/Spring 6 changed auto-configuration loading from spring.factories");
    }

    private static void add(List<DeprecatedApi> rules, String library, String className,
                             String methodName, String severity, String replacement, String description) {
        rules.add(new DeprecatedApi(library, className, methodName, severity, replacement, description));
    }
}
