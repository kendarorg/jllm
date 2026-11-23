package org.kendar.llm.web.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.web.commons.Gate;
import org.kendar.llm.web.commons.WebAuthentication;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies the {@link Gate} to {@code /api/**}; stores the signed-in user as {@link Principal#ATTR}. */
public class ApiInterceptor implements HandlerInterceptor {

    private final Gate gate;
    private final WebAuthentication authentication;
    private final ObjectMapper mapper;

    /** The only paths of a user on the default password besides {@code /api/auth/*}, {@code /api/login}, {@code /api/logout}. */
    private static final Set<String> PASSWORD_CHANGE_PATHS = Set.of("/api/me", "/api/me/password", "/api/login",
            "/api/logout");

    /** The chat id variable of a chat handler pattern (the pattern the framework matched, never the request URI). */
    private static final Pattern CHAT_PATTERN = Pattern.compile("^/api/chat/\\{([^}/:]+)[}:]");

    private final boolean enforceOnLoopback;
    private final boolean boundToLoopback;

    /**
     * @param enforceOnLoopback also hold back default-password users whose request comes from the loopback address
     * @param boundToLoopback   the server listens on a loopback address only; if not, the loopback exemption is off,
     *                          since a proxy, tunnel or sidecar on the host makes remote callers look local
     */
    public ApiInterceptor(Gate gate, WebAuthentication authentication, ObjectMapper mapper,
                          boolean enforceOnLoopback, boolean boundToLoopback) {
        this.boundToLoopback = boundToLoopback;
        this.gate = gate;
        this.authentication = authentication;
        this.mapper = mapper;
        this.enforceOnLoopback = enforceOnLoopback;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String path = path(request);
        Principal user = null;
        if (!gate.isOpen(path)) {
            user = authentication.current(Requests.cookies(request)).orElse(null);
            if (user != null) request.setAttribute(Principal.ATTR, user);
        }
        Gate.Decision decision = gate.decide(path, user);
        if (decision.allowed() && !gate.isOpen(path)) decision = decideChat(request, user);
        if (decision.allowed() && !gate.isOpen(path)) decision = decideChatParameter(request, user);
        if (decision.allowed() && mustChangePassword(path, user, request)) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write(mapper.writeValueAsString(Map.of("error", "password_change_required")));
            return false;
        }
        return Verdicts.apply(decision, response, mapper);
    }

    /** The request path without the context path; a URI outside the context path stays unclassifiable (not open). */
    static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context == null || context.isEmpty()) return uri;
        if (!uri.startsWith(context)) return "/api;outside-context";
        return uri.substring(context.length());
    }

    /** Chat ownership by the id variable the framework matched for the handler, whatever URI form reached it. */
    private Gate.Decision decideChat(HttpServletRequest request, Principal user) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) return Gate.Decision.ALLOW;
        Matcher m = CHAT_PATTERN.matcher(pattern.toString());
        if (!m.find()) return Gate.Decision.ALLOW;
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String id = vars == null ? null : vars.get(m.group(1));
        if (id == null) return new Gate.Decision(404, "Unknown chat");
        return gate.decideChat(user, id);
    }

    /**
     * A {@code chatId} query parameter on any gated call (e.g. {@code POST /api/oauth/{source}/start?chatId=}, whose
     * login result is posted into that chat's stream) names a chat the caller must own.
     */
    private Gate.Decision decideChatParameter(HttpServletRequest request, Principal user) {
        String id = request.getParameter("chatId");
        if (id == null || id.isBlank()) return Gate.Decision.ALLOW;
        return gate.decideChat(user, id);
    }

    private boolean mustChangePassword(String path, Principal user, HttpServletRequest request) {
        if (!(user instanceof User u) || !u.usesDefaultPassword()) return false;
        if (PASSWORD_CHANGE_PATHS.contains(path) || path.startsWith("/api/auth/")) return false;
        return enforceOnLoopback || !boundToLoopback || !loopback(request);
    }

    /** Whether a {@code server.address} value is a loopback address; unset, blank, any-local or unresolvable is not. */
    public static boolean isLoopbackAddress(String address) {
        if (address == null || address.isBlank()) return false;
        String a = address.trim();
        if (a.equalsIgnoreCase("localhost")) return true;
        if (!a.matches("[0-9.]+") && !a.contains(":")) return false; // a host name: not resolved here
        try {
            return InetAddress.getByName(a).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
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
