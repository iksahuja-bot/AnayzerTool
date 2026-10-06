package effortanalyzer.source;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Optional SCM credentials supplied interactively for checkout/update commands. */
public record CheckoutCredentials(String username, String password) {

    public static CheckoutCredentials none() {
        return new CheckoutCredentials("", "");
    }

    public boolean present() {
        return username != null && !username.isBlank();
    }

    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }

    /** Returns an HTTPS URL with credentials embedded for one git command invocation. */
    public String applyToHttpsUrl(String repositoryUrl) {
        if (!present() || repositoryUrl == null || !repositoryUrl.toLowerCase().startsWith("http")) {
            return repositoryUrl;
        }
        try {
            URI uri = URI.create(repositoryUrl);
            if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
                return repositoryUrl;
            }
            String userInfo = encode(username) + (hasPassword() ? ":" + encode(password) : "");
            return new URI(uri.getScheme(), userInfo, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (Exception ignored) {
            return repositoryUrl;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
