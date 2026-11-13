package org.kendar.llm.web.commons;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;
import java.util.Set;

/** Who is calling. Implemented by the application (sign-in); the common API only reads it. */
public interface WebAuthentication {

    /** Nobody is ever signed in: every protected call answers 401. */
    WebAuthentication NONE = new WebAuthentication() {
        @Override
        public Optional<WebPrincipal> current(HttpServletRequest request) {
            return Optional.empty();
        }

        @Override
        public Set<String> openPaths() {
            return Set.of();
        }
    };

    /** The user of the request, if signed in. */
    Optional<WebPrincipal> current(HttpServletRequest request);

    /** {@code /api/...} paths reachable without a signed-in user (the sign-in endpoints themselves). */
    Set<String> openPaths();
}
