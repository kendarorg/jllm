package org.kendar.llm.web.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.web.commons.Gate;
import org.kendar.llm.web.commons.WebAuthentication;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.Set;

/** Applies the {@link Gate} to {@code /api/**}; stores the signed-in user as {@link Principal#ATTR}. */
public class ApiInterceptor implements HandlerInterceptor {

    private final Gate gate;
    private final WebAuthentication authentication;
    private final ObjectMapper mapper;

    /** The only paths of a user on the default password besides {@code /api/auth/*}, {@code /api/login}, {@code /api/logout}. */
    private static final Set<String> PASSWORD_CHANGE_PATHS = Set.of("/api/me", "/api/me/password", "/api/login",
            "/api/logout");

    private final boolean enforceOnLoopback;

    /**
     * @param enforceOnLoopback also hold back default-password users whose request comes from the loopback address
     */
    public ApiInterceptor(Gate gate, WebAuthentication authentication, ObjectMapper mapper,
                          boolean enforceOnLoopback) {
        this.gate = gate;
        this.authentication = authentication;
        this.mapper = mapper;
        this.enforceOnLoopback = enforceOnLoopback;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String path = request.getRequestURI();
        Principal user = null;
        if (!gate.isOpen(path)) {
            user = authentication.current(Requests.cookies(request)).orElse(null);
            if (user != null) request.setAttribute(Principal.ATTR, user);
        }
        Gate.Decision decision = gate.decide(path, user);
        if (decision.allowed() && mustChangePassword(path, user, request)) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write(mapper.writeValueAsString(Map.of("error", "password_change_required")));
            return false;
        }
        return Verdicts.apply(decision, response, mapper);
    }

    private boolean mustChangePassword(String path, Principal user, HttpServletRequest request) {
        if (!(user instanceof User u) || !u.usesDefaultPassword()) return false;
        if (PASSWORD_CHANGE_PATHS.contains(path) || path.startsWith("/api/auth/")) return false;
        return enforceOnLoopback || !loopback(request);
    }

    /** Loopback peer and no forwarding header: a local reverse proxy must not make remote callers look local. */
    private static boolean loopback(HttpServletRequest request) {
        if (request.getHeader("X-Forwarded-For") != null || request.getHeader("Forwarded") != null
                || request.getHeader("X-Real-IP") != null) return false;
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /** Writes a refusal in the shape of the API: {@code {"error": ...}}. */
    static final class Verdicts {
        private Verdicts() {
        }

        static boolean apply(Gate.Decision decision, HttpServletResponse response, ObjectMapper mapper)
                throws IOException {
            if (decision.allowed()) return true;
            response.setStatus(decision.status());
            response.setContentType("application/json");
            response.getWriter().write(mapper.writeValueAsString(Map.of("error", decision.message())));
            return false;
        }
    }
}
