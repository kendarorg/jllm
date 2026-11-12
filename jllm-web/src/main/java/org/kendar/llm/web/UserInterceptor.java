package org.kendar.llm.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatSessionService;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sample sign-in by cookie (the fake picker sets it). Every API call except sign-in needs a known user. A chat is
 * visible to its owner and to the admin; anything else answers 404, as if the chat did not exist.
 */
public class UserInterceptor implements HandlerInterceptor {

    public static final String ATTR = "jllm.user";
    public static final String COOKIE = "jllm_user";

    private static final Pattern CHAT = Pattern.compile("^/api/chat/([^/]+)");
    private static final Set<String> OPEN = Set.of(
            "/api/users", "/api/me", "/api/login", "/api/logout", "/api/chat/commands");

    private final UserDirectory users;
    private final ChatSessionService sessions;
    private final ObjectMapper mapper;

    public UserInterceptor(UserDirectory users, ChatSessionService sessions, ObjectMapper mapper) {
        this.users = users;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    /** The user named by the sign-in cookie, if it is a known user. */
    public Optional<WebUser> signedIn(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();
        for (Cookie c : request.getCookies()) {
            if (COOKIE.equals(c.getName())) return users.find(c.getValue());
        }
        return Optional.empty();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || OPEN.contains(path)) return true;
        Optional<WebUser> user = signedIn(request);
        if (user.isEmpty()) {
            return reject(response, 401, "Sign in first");
        }
        request.setAttribute(ATTR, user.get());
        Matcher m = CHAT.matcher(path);
        if (m.find() && !canSee(user.get(), m.group(1))) {
            return reject(response, 404, "Unknown chat " + m.group(1));
        }
        return true;
    }

    /**
     * The admin sees every chat; anyone else sees the chats created for their own id. A chat without an owner is not
     * visible to anyone: every chat of this sample is created with one.
     */
    public boolean canSee(WebUser user, String chatId) {
        Optional<String> owner = sessions.find(chatId).map(ChatSessionData::principal);
        if (owner.isEmpty()) return false;
        return user.admin() || owner.get().equals(user.id());
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(mapper.writeValueAsString(Map.of("error", message)));
        return false;
    }
}
