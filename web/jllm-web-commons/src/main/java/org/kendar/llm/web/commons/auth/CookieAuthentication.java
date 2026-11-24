package org.kendar.llm.web.commons.auth;

import org.kendar.llm.harness.identity.ChatAccess;
import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatVisibility;
import org.kendar.llm.web.commons.Cookies;
import org.kendar.llm.web.commons.WebAuthentication;

import java.util.Optional;
import java.util.Set;

/**
 * Sign-in by session cookie: the cookie holds a token of the {@link LoginSessionRepository}, which names a user of the
 * {@link UserRepository}. A deleted user is signed out at once; a role change applies on the next request. A chat is
 * visible to its owner and to the admin.
 */
public class CookieAuthentication implements WebAuthentication, ChatAccess {

    public static final String COOKIE = "jllm_session";

    private static final Set<String> OPEN = Set.of("/api/me", "/api/login", "/api/logout", "/api/auth/config");

    private final UserRepository users;
    private final LoginSessionRepository sessions;

    public CookieAuthentication(UserRepository users, LoginSessionRepository sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    /** The session token of the request, if it carries one. */
    public static Optional<String> token(Cookies cookies) {
        String v = cookies.get(COOKIE);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
    }

    /** The user of the request's live session, if it is a known user. */
    public Optional<User> signedIn(Cookies cookies) {
        return token(cookies).flatMap(sessions::find).flatMap(users::find);
    }

    @Override
    public Optional<Principal> current(Cookies cookies) {
        return signedIn(cookies).map(Principal.class::cast);
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
    public boolean canSee(Principal user, ChatSessionData chat) {
        String owner = chat.principal();
        if (owner == null) return false;
        return user.admin() || owner.equals(user.id());
    }

    /** The same rule as {@link #canSee}, for the store: the admin lists owned chats, anyone else their own. */
    @Override
    public ChatVisibility visibility(Principal user) {
        return user.admin() ? ChatVisibility.owned() : ChatVisibility.ownedBy(user.id());
    }
}
