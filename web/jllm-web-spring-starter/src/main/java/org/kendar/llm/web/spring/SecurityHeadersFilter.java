package org.kendar.llm.web.spring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds {@code Content-Security-Policy}, {@code X-Content-Type-Options} and {@code Referrer-Policy} to every response
 * (static files, API, SSE). Headers a handler already set are left alone.
 *
 * <p>The default policy matches the bundled UI: same-origin scripts, styles, fetch and EventSource; inline styles
 * ({@code style=""} attributes in templates); {@code 'unsafe-eval'} because Vue compiles its string templates in the
 * browser; no framing, plugins, foreign {@code <base>} or form targets.
 */
class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String DEFAULT_CSP = "default-src 'self'; script-src 'self' 'unsafe-eval'; "
            + "style-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'none'";

    private final String csp;

    /** @param configured blank/null = {@link #DEFAULT_CSP}; {@code off} = no CSP header; else used as given */
    SecurityHeadersFilter(String configured) {
        String c = configured == null ? "" : configured.trim();
        this.csp = c.isEmpty() ? DEFAULT_CSP : c.equalsIgnoreCase("off") ? null : c;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (csp != null && !response.containsHeader("Content-Security-Policy")) {
            response.setHeader("Content-Security-Policy", csp);
        }
        if (!response.containsHeader("X-Content-Type-Options")) {
            response.setHeader("X-Content-Type-Options", "nosniff");
        }
        if (!response.containsHeader("Referrer-Policy")) {
            response.setHeader("Referrer-Policy", "no-referrer");
        }
        chain.doFilter(request, response);
    }
}
