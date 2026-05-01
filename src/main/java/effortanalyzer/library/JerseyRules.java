package effortanalyzer.library;

import java.util.ArrayList;
import java.util.List;

/**
 * Jersey 1.x → 2.22.2 migration compatibility rules.
 *
 * Jersey 2.x is a complete rewrite aligned with JAX-RS 2.0 (JSR-339).
 * The entire public API moved from com.sun.jersey.* to org.glassfish.jersey.*
 * and the JAX-RS standard interfaces (javax.ws.rs.*) must be used instead of
 * the Jersey 1 proprietary ones wherever possible.
 *
 * Sources:
 *   - eclipse-ee4j.github.io/jersey.github.io/documentation/latest/migration.html
 *   - eclipse-ee4j.github.io/jersey.github.io/documentation/latest/client.html
 */
public class JerseyRules {

    public static final String LIBRARY = "Jersey 2.22.2";

    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();

        // ── Top-level package rename (catches everything at once) ─────────────
        add(rules, "com.sun.jersey", null, "CRITICAL",
                "Migrate all com.sun.jersey.* imports to org.glassfish.jersey.* or javax.ws.rs.*. "
                + "Jersey 2.x is a complete rewrite; no binary compatibility with 1.x.",
                "BREAKING in Jersey 2.x: entire API moved from com.sun.jersey to "
                + "org.glassfish.jersey / javax.ws.rs");

        // ── Client API ────────────────────────────────────────────────────────
        add(rules, "com.sun.jersey.api.client.Client", null, "CRITICAL",
                "Replace with javax.ws.rs.client.ClientBuilder.newClient() (JAX-RS 2.0 standard). "
                + "Example: Client c = ClientBuilder.newClient();",
                "REMOVED in Jersey 2.x: com.sun.jersey.api.client.Client → javax.ws.rs.client.Client");

        add(rules, "com.sun.jersey.api.client.WebResource", null, "CRITICAL",
                "Replace with javax.ws.rs.client.WebTarget. "
                + "Example: WebTarget t = client.target(uri).path(\"resource\");",
                "REMOVED in Jersey 2.x: WebResource → WebTarget");

        add(rules, "com.sun.jersey.api.client.ClientResponse", null, "CRITICAL",
                "Replace with javax.ws.rs.core.Response. "
                + "Example: Response r = target.request().get(); int status = r.getStatus();",
                "REMOVED in Jersey 2.x: ClientResponse → javax.ws.rs.core.Response");

        add(rules, "com.sun.jersey.api.client.ClientHandler", null, "CRITICAL",
                "Use javax.ws.rs.client.ClientRequestFilter and ClientResponseFilter instead.",
                "REMOVED in Jersey 2.x: ClientHandler → Client(Request|Response)Filter");

        add(rules, "com.sun.jersey.api.client.filter.ClientFilter", null, "CRITICAL",
                "Implement javax.ws.rs.client.ClientRequestFilter and/or ClientResponseFilter.",
                "REMOVED in Jersey 2.x: ClientFilter → javax.ws.rs.client ClientRequestFilter/ClientResponseFilter");

        add(rules, "com.sun.jersey.api.client.filter", null, "CRITICAL",
                "Replace all filter classes with javax.ws.rs.client.ClientRequestFilter / "
                + "ClientResponseFilter implementations registered via client.register(MyFilter.class).",
                "REMOVED in Jersey 2.x: com.sun.jersey.api.client.filter package deleted");

        add(rules, "com.sun.jersey.api.client.UniformInterfaceException", null, "CRITICAL",
                "Replace with javax.ws.rs.WebApplicationException or javax.ws.rs.ProcessingException. "
                + "Check response status with Response.getStatus() instead of catching exceptions.",
                "REMOVED in Jersey 2.x: UniformInterfaceException → WebApplicationException / ProcessingException");

        add(rules, "com.sun.jersey.api.client.AsyncWebResource", null, "CRITICAL",
                "Use javax.ws.rs.client.AsyncInvoker: target.request().async().get(Future.class).",
                "REMOVED in Jersey 2.x: AsyncWebResource → AsyncInvoker");

        // ── Server / ResourceConfig ───────────────────────────────────────────
        add(rules, "com.sun.jersey.api.core.ResourceConfig", null, "CRITICAL",
                "Replace with org.glassfish.jersey.server.ResourceConfig. "
                + "Extend it or instantiate it and call packages(\"..\") or register(MyResource.class).",
                "REMOVED in Jersey 2.x: com.sun.jersey.api.core.ResourceConfig "
                + "→ org.glassfish.jersey.server.ResourceConfig");

        add(rules, "com.sun.jersey.api.core.PackagesResourceConfig", null, "CRITICAL",
                "Use new ResourceConfig().packages(\"com.example\") instead.",
                "REMOVED in Jersey 2.x: PackagesResourceConfig → ResourceConfig.packages()");

        add(rules, "com.sun.jersey.api.core.ClassNamesResourceConfig", null, "CRITICAL",
                "Use new ResourceConfig().register(MyClass.class) instead.",
                "REMOVED in Jersey 2.x: ClassNamesResourceConfig → ResourceConfig.register()");

        add(rules, "com.sun.jersey.api.core.DefaultResourceConfig", null, "CRITICAL",
                "Use org.glassfish.jersey.server.ResourceConfig directly.",
                "REMOVED in Jersey 2.x: DefaultResourceConfig → ResourceConfig");

        // ── Servlet integration ───────────────────────────────────────────────
        add(rules, "com.sun.jersey.spi.container.servlet.ServletContainer", null, "CRITICAL",
                "Update web.xml to use org.glassfish.jersey.servlet.ServletContainer. "
                + "Also update <init-param> from 'com.sun.jersey.config.property.packages' "
                + "to 'jersey.config.server.provider.packages'.",
                "REMOVED in Jersey 2.x: com.sun.jersey.spi.container.servlet.ServletContainer "
                + "→ org.glassfish.jersey.servlet.ServletContainer");

        add(rules, "com.sun.jersey.spi.container.ContainerRequestFilter", null, "CRITICAL",
                "Implement javax.ws.rs.container.ContainerRequestFilter and annotate with @Provider. "
                + "Context is injected via @Context ContainerRequestContext.",
                "REMOVED in Jersey 2.x: server ContainerRequestFilter moved to javax.ws.rs.container");

        add(rules, "com.sun.jersey.spi.container.ContainerResponseFilter", null, "CRITICAL",
                "Implement javax.ws.rs.container.ContainerResponseFilter and annotate with @Provider.",
                "REMOVED in Jersey 2.x: server ContainerResponseFilter moved to javax.ws.rs.container");

        add(rules, "com.sun.jersey.spi.container", null, "CRITICAL",
                "Replace all com.sun.jersey.spi.container classes with their javax.ws.rs.container equivalents.",
                "REMOVED in Jersey 2.x: com.sun.jersey.spi.container package deleted");

        // ── Injection / IoC ───────────────────────────────────────────────────
        add(rules, "com.sun.jersey.spi.inject.Injectable", null, "CRITICAL",
                "Replace with HK2 (@Inject) or CDI. Jersey 2.x uses HK2 as its IoC container. "
                + "Register bindings via AbstractBinder: bind(MyImpl.class).to(MyService.class).",
                "REMOVED in Jersey 2.x: Jersey 1 Injectable → HK2 @Inject");

        add(rules, "com.sun.jersey.spi.inject.InjectableProvider", null, "CRITICAL",
                "Replace with HK2 AbstractBinder or InjectionResolver.",
                "REMOVED in Jersey 2.x: InjectableProvider → HK2 InjectionResolver");

        add(rules, "com.sun.jersey.spi.inject", null, "CRITICAL",
                "Replace Jersey 1 injection SPI with HK2 or CDI equivalents.",
                "REMOVED in Jersey 2.x: com.sun.jersey.spi.inject package deleted");

        // ── Guice integration ─────────────────────────────────────────────────
        add(rules, "com.sun.jersey.guice", null, "CRITICAL",
                "Replace with jersey-hk2 or jersey-cdi2-se. "
                + "Add dependency: org.glassfish.jersey.ext.cdi:jersey-cdi1x or "
                + "org.glassfish.jersey.ext:jersey-spring5 for Spring DI.",
                "REMOVED in Jersey 2.x: com.sun.jersey.guice integration deleted; use HK2 or CDI");

        // ── JSON support ──────────────────────────────────────────────────────
        add(rules, "com.sun.jersey.api.json", null, "CRITICAL",
                "Replace with Jackson JAX-RS provider. Add dependency: "
                + "org.glassfish.jersey.media:jersey-media-json-jackson and register "
                + "JacksonFeature.class on your ResourceConfig.",
                "REMOVED in Jersey 2.x: com.sun.jersey.api.json deleted; use Jersey Jackson media module");

        add(rules, "com.sun.jersey.api.json.JSONJAXBContext", null, "CRITICAL",
                "Replace with Jackson's JacksonJaxbJsonProvider or Jettison provider. "
                + "Register JacksonFeature on ResourceConfig.",
                "REMOVED in Jersey 2.x: JSONJAXBContext deleted");

        add(rules, "com.sun.jersey.api.json.JSONConfiguration", null, "CRITICAL",
                "Configure Jackson via JacksonFeature or ObjectMapper provider instead.",
                "REMOVED in Jersey 2.x: JSONConfiguration deleted");

        // ── Multipart ─────────────────────────────────────────────────────────
        add(rules, "com.sun.jersey.multipart", null, "CRITICAL",
                "Replace with org.glassfish.jersey.media.multipart. "
                + "Add: org.glassfish.jersey.media:jersey-media-multipart and register MultiPartFeature.",
                "REMOVED in Jersey 2.x: com.sun.jersey.multipart → org.glassfish.jersey.media.multipart");

        add(rules, "com.sun.jersey.multipart.FormDataMultiPart", null, "CRITICAL",
                "Use org.glassfish.jersey.media.multipart.FormDataMultiPart. "
                + "Add jersey-media-multipart dependency and register MultiPartFeature.",
                "REMOVED in Jersey 2.x: FormDataMultiPart package changed");

        // ── Test framework ────────────────────────────────────────────────────
        add(rules, "com.sun.jersey.test.framework", null, "HIGH",
                "Replace with org.glassfish.jersey.test framework. "
                + "Extend org.glassfish.jersey.test.JerseyTest and override configure() "
                + "to return your ResourceConfig.",
                "REMOVED in Jersey 2.x: com.sun.jersey.test.framework → org.glassfish.jersey.test");

        add(rules, "com.sun.jersey.test.framework.JerseyTest", null, "HIGH",
                "Extend org.glassfish.jersey.test.JerseyTest instead. "
                + "Override configure() returning ResourceConfig rather than AppDescriptor.",
                "REMOVED in Jersey 2.x: JerseyTest class moved to org.glassfish.jersey.test");

        add(rules, "com.sun.jersey.test.framework.WebAppDescriptor", null, "HIGH",
                "Remove WebAppDescriptor usage; override configure() in JerseyTest to return ResourceConfig.",
                "REMOVED in Jersey 2.x: WebAppDescriptor removed from test framework");

        // ── Utility / misc ────────────────────────────────────────────────────
        add(rules, "com.sun.jersey.core.util.MultivaluedMapImpl", null, "HIGH",
                "Replace with javax.ws.rs.core.MultivaluedHashMap or "
                + "org.glassfish.jersey.internal.util.collection.MultivaluedStringMap.",
                "REMOVED in Jersey 2.x: MultivaluedMapImpl moved");

        add(rules, "com.sun.jersey.api.representation.Form", null, "HIGH",
                "Replace with javax.ws.rs.core.Form. "
                + "Example: Form form = new Form(); form.param(\"key\", \"value\");",
                "REMOVED in Jersey 2.x: Jersey Form → javax.ws.rs.core.Form");

        add(rules, "com.sun.jersey.api.uri.UriBuilderImpl", null, "HIGH",
                "Use javax.ws.rs.core.UriBuilder.fromUri(...) or UriBuilder.newInstance().",
                "REMOVED in Jersey 2.x: UriBuilderImpl is internal; use JAX-RS UriBuilder API");

        // ── web.xml init-param keys changed ──────────────────────────────────
        add(rules, "com.sun.jersey.config.property.packages", null, "HIGH",
                "Update web.xml <init-param>: replace 'com.sun.jersey.config.property.packages' "
                + "with 'jersey.config.server.provider.packages'.",
                "CHANGED in Jersey 2.x: init-param name changed for package scanning");

        add(rules, "com.sun.jersey.config.feature", null, "MEDIUM",
                "Update web.xml <init-param> feature flags: Jersey 2.x uses "
                + "'jersey.config.server.*' property namespace. Consult Jersey 2 ServerProperties class.",
                "CHANGED in Jersey 2.x: feature init-param namespace changed");

        return rules;
    }

    private static void add(List<DeprecatedApi> rules, String className, String methodName,
                             String severity, String replacement, String description) {
        rules.add(new DeprecatedApi(LIBRARY, className, methodName, severity, replacement, description));
    }
}
