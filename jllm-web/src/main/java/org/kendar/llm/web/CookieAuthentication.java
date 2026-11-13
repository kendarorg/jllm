package org.kendar.llm.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.web.commons.ChatAccess;
import org.kendar.llm.web.commons.WebAuthentication;
import org.kendar.llm.web.commons.WebPrincipal;

import java.util.Optional;
import java.util.Set;

/**
 * Sample sign-in by cookie (the fake picker sets it): the cookie names a user of the {@link UserDirectory}. A chat is
 * visible to its owner and to the admin.
 */
public class CookieAuthentication implements WebAuthentication, ChatAccess {

    public static final String COOKIE = "jllm_user";

    private static final Set<String> OPEN = Set.of("/api/users", "/api/me", "/api/login", "/api/logout");

    private final UserDirectory users;

    public CookieAuthentication(UserDirectory users) {
        this.users = users;
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
