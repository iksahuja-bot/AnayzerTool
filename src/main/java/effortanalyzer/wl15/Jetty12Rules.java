package effortanalyzer.wl15;

import effortanalyzer.library.DeprecatedApi;
import java.util.ArrayList;
import java.util.List;

/**
 * Jetty 9.x/10.x/11.x to Jetty 12.0.33 migration rules.
 * Jetty 12 is a major rewrite: javax.servlet -> jakarta.servlet,
 * Handler API rewrite, and new ee10 deployment model.
 */
public class Jetty12Rules {

    private static final String LIBRARY = "Jetty 12.0.33";

    private Jetty12Rules() {}

    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();

        // Full javax.servlet -> jakarta.servlet migration required
        add(rules, "javax.servlet", null, "CRITICAL",
                "Replace all javax.servlet.* with jakarta.servlet.* for Jetty 12 (EE10).",
                "Jetty 12 uses jakarta.servlet (EE10); javax.servlet no longer supported");

        // Handler API completely rewritten
        add(rules, "org.eclipse.jetty.server.handler.AbstractHandler", null, "CRITICAL",
                "Rewrite Handler implementations using the new Handler.Abstract base class in Jetty 12.",
                "AbstractHandler removed in Jetty 12; use Handler.Abstract or implement Handler directly");

        add(rules, "org.eclipse.jetty.server.handler.HandlerWrapper", null, "CRITICAL",
                "Replace HandlerWrapper with Handler.Wrapper in Jetty 12.",
                "HandlerWrapper removed in Jetty 12; use Handler.Wrapper");

        add(rules, "org.eclipse.jetty.server.handler.HandlerList", null, "CRITICAL",
                "Replace HandlerList with Handler.Sequence in Jetty 12.",
                "HandlerList removed in Jetty 12; use Handler.Sequence");

        add(rules, "org.eclipse.jetty.server.handler.HandlerCollection", null, "CRITICAL",
                "Replace HandlerCollection with Handler.Collection in Jetty 12.",
                "HandlerCollection removed in Jetty 12; use Handler.Collection");

        // Server configuration changes
        add(rules, "org.eclipse.jetty.server.Server", "setHandler", "HIGH",
                "Server.setHandler() still exists but Handler API signature changed; review implementation.",
                "Jetty 12 Server.setHandler() requires new Handler interface implementations");

        // Connector / HttpConfiguration
        add(rules, "org.eclipse.jetty.server.HttpConfiguration", "addCustomizer", "WARNING",
                "Review HttpConfiguration customizer chain; some customizers renamed or moved in Jetty 12.",
                "HttpConfiguration customizer API changed in Jetty 12; verify all customizer classes");

        // WebSocketHandler / WebSocket API
        add(rules, "org.eclipse.jetty.websocket.server.WebSocketHandler", null, "CRITICAL",
                "Migrate to Jetty 12 WebSocket via jakarta.websocket or jetty-ee10-websocket-api.",
                "WebSocketHandler removed in Jetty 12; use jakarta.websocket / jetty-ee10 WebSocket API");

        add(rules, "org.eclipse.jetty.websocket.api.WebSocketAdapter", null, "CRITICAL",
                "Replace with jakarta.websocket.Endpoint or use @OnWebSocketMessage annotations.",
                "Jetty WebSocket API fully replaced by jakarta.websocket in Jetty 12");

        // Context handlers
        add(rules, "org.eclipse.jetty.servlet.ServletContextHandler", null, "HIGH",
                "Replace with org.eclipse.jetty.ee10.servlet.ServletContextHandler in Jetty 12 (ee10).",
                "ServletContextHandler moved to jetty-ee10-servlet module in Jetty 12");

        add(rules, "org.eclipse.jetty.webapp.WebAppContext", null, "HIGH",
                "Replace with org.eclipse.jetty.ee10.webapp.WebAppContext in Jetty 12 (ee10).",
                "WebAppContext moved to jetty-ee10-webapp module in Jetty 12");

        // Default servlet
        add(rules, "org.eclipse.jetty.servlet.DefaultServlet", null, "HIGH",
                "Replace with org.eclipse.jetty.ee10.servlet.DefaultServlet in Jetty 12 (ee10).",
                "DefaultServlet moved to jetty-ee10-servlet in Jetty 12");

        // JNDI module
        add(rules, "org.eclipse.jetty.plus.jndi", null, "WARNING",
                "Update to jetty-plus module for Jetty 12; JNDI configuration syntax changed.",
                "Jetty JNDI module restructured in Jetty 12; update dependencies and configuration");

        // Request/Response API
        add(rules, "org.eclipse.jetty.server.Request", "getParameterNames", "WARNING",
                "Request API significantly changed in Jetty 12; review all Request/Response usage.",
                "Jetty 12 Request/Response API rewritten; methods moved to new HttpServletRequest wrappers");

        return rules;
    }

    private static void add(List<DeprecatedApi> rules, String className, String methodName,
                             String severity, String replacement, String description) {
        rules.add(new DeprecatedApi(LIBRARY, className, methodName, severity, replacement, description));
    }
}