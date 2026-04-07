package net.javahippie.fitpub.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Custom authentication entry point that handles unauthenticated requests.
 * - Redirects to /login for HTML page requests (browser navigation)
 * - Returns 403 Forbidden for API requests (AJAX, fetch calls)
 */
@Component
@Slf4j
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {

        String requestUri = request.getRequestURI();
        String accept = request.getHeader("Accept");

        log.debug("Unauthenticated request to {} with Accept: {}", requestUri, accept);

        // API requests should get 403 Forbidden
        if (requestUri.startsWith("/api/")) {
            log.debug("API request - returning 403 Forbidden");
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access Denied");
            return;
        }

        // Check if this is a JSON/API request based on Accept header
        if (accept != null && (accept.contains("application/json") ||
                               accept.contains("application/activity+json") ||
                               accept.contains("application/ld+json"))) {
            log.debug("JSON API request - returning 403 Forbidden");
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access Denied");
            return;
        }

        // HTML page requests should redirect to login. The redirect parameter is built
        // from the original request URI so the login page can later send the user back.
        // We must restrict it to a single internal path — anything starting with "//" or
        // "/\" could be interpreted by browsers as a protocol-relative URL pointing at
        // an external host, turning this into an open redirect.
        log.debug("HTML page request - redirecting to /login");
        String redirectUrl;
        if (isSafeInternalPath(requestUri)) {
            redirectUrl = "/login?redirect=" + URLEncoder.encode(requestUri, StandardCharsets.UTF_8);
        } else {
            log.warn("Refusing to propagate suspicious redirect target: {}", requestUri);
            redirectUrl = "/login";
        }
        response.sendRedirect(redirectUrl);
    }

    /**
     * Returns true if the given path is a safe single-slash internal path that can be
     * round-tripped through a {@code redirect} query parameter without enabling an open
     * redirect. Rejects null/empty, anything not starting with {@code /}, and anything
     * starting with {@code //} or {@code /\} (both of which browsers may interpret as
     * protocol-relative URLs to a different host).
     */
    private static boolean isSafeInternalPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (path.charAt(0) != '/') {
            return false;
        }
        if (path.length() >= 2) {
            char second = path.charAt(1);
            if (second == '/' || second == '\\') {
                return false;
            }
        }
        return true;
    }
}
