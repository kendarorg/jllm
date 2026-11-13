package org.kendar.llm.web.auth;

import org.kendar.llm.web.commons.users.LoginSessionRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.web.commons.ChatAccess;
import org.kendar.llm.web.commons.WebAuthentication;
import org.kendar.llm.web.commons.WebPrincipal;
import org.kendar.llm.web.commons.users.UserRepository;
import org.kendar.llm.web.commons.users.WebUser;

import java.util.Optional;
import java.util.Set;

/**
 * Sign-in by session cookie: the cookie holds a token of the {@link LoginSessionRepository}, which names a user of the
 * {@link UserRepository}. A deleted user is signed out at once; a role change applies on the next request. A chat is
 * visible to its owner and to the admin.
 */
public class CookieAuthentication implements WebAuthentication, ChatAccess {

    public static final String COOKIE = "jllm_session";

    private static final Set<String> OPEN = Set.of("/api/me", "/api/login", "/api/logout");

    private final UserRepository users;
    private final LoginSessionRepository sessions;

    public CookieAuthentication(UserRepository users, LoginSessionRepository sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    /** The session token of the request, if it carries one. */
    public static Optional<String> token(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();
        for (Cookie c : request.getCookies()) {
            if (COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }

    /** The user of the request's live session, if it is a known user. */
    public Optional<WebUser> signedIn(HttpServletRequest request) {
        return token(request).flatMap(sessions::find).flatMap(users::find);
    }

    @Override
    public Optional<WebPrincipal> current(HttpServletRequest request) {
        return signedIn(request).map(WebPrincipal.class::cast);
    }

    @Override
    public Set<String> openPaths() {
        return OPEN;
    }

    /**
     * The admin sees every chat; anyone else sees the chats created for their own id. A chat without an owner is not
     * visible to anyone: every chat of this sample is created with one.
     */
    @Override
    public boolean canSee(WebPrincipal user, ChatSessionData chat) {
        String owner = chat.principal();
        if (owner == null) return false;
        return user.admin() || owner.equals(user.id());
    }
}
