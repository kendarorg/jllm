package org.kendar.llm.web.commons;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.kendar.llm.harness.session.ChatSessionService;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every {@code /api/**} call except the open ones needs a signed-in user (401 otherwise), stored as
 * {@link WebPrincipal#ATTR}. A chat the user may not see answers 404, as if it did not exist.
 */
public class ApiInterceptor implements HandlerInterceptor {

    private static final Pattern CHAT = Pattern.compile("^/api/chat/([^/]+)");
    /** Open on the common API: the command list adapts to the caller, signed in or not. */
    private static final Set<String> OPEN = Set.of("/api/chat/commands");

    private final WebAuthentication authentication;
    private final ChatAccess access;
    private final ChatSessionService sessions;
    private final ObjectMapper mapper;

    public ApiInterceptor(WebAuthentication authentication, ChatAccess access, ChatSessionService sessions,
                          ObjectMapper mapper) {
        this.authentication = authentication;
        this.access = access;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || OPEN.contains(path) || authentication.openPaths().contains(path)) return true;
        Optional<WebPrincipal> user = authentication.current(request);
        if (user.isEmpty()) {
            return reject(response, 401, "Sign in first");
        }
        request.setAttribute(WebPrincipal.ATTR, user.get());
        Matcher m = CHAT.matcher(path);
        if (m.find() && !canSee(user.get(), m.group(1))) {
            return reject(response, 404, "Unknown chat " + m.group(1));
        }
        return true;
    }

    private boolean canSee(WebPrincipal user, String chatId) {
        return sessions.find(chatId).map(chat -> access.canSee(user, chat)).orElse(false);
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(mapper.writeValueAsString(Map.of("error", message)));
        return false;
    }
}
