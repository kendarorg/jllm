package org.kendar.llm.web.commons;

import org.kendar.llm.harness.identity.ChatAccess;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.harness.session.ChatSessionService;

import java.util.Locale;
import java.util.Set;

/**
 * Who may call what. Every {@code /api/**} call except the open ones needs a signed-in user (401 otherwise). A chat
 * the user may not see answers 404, as if it did not exist. {@code /api/admin/**} needs an admin (403 otherwise).
 * A pure function of its inputs: the framework looks the user up and applies the verdict.
 */
public final class Gate {

    /** Open on the common API: the command list adapts to the caller, signed in or not. */
    private static final Set<String> OPEN = Set.of("/api/chat/commands");

    /** The verdict: allowed (status 0), or the status and message to answer. */
    public record Decision(int status, String message) {
        public static final Decision ALLOW = new Decision(0, null);

        public boolean allowed() {
            return status == 0;
        }
    }

    private final WebAuthentication authentication;
    private final ChatAccess access;
    private final ChatSessionService sessions;

    public Gate(WebAuthentication authentication, ChatAccess access, ChatSessionService sessions) {
        this.authentication = authentication;
        this.access = access;
        this.sessions = sessions;
    }

    /**
     * Whether the path is outside the gate: no user needs to be looked up. {@code path} is the request path without
     * the context path. Fails closed: only the explicitly open API paths and well-formed paths outside {@code /api}
     * (the static content) are open; anything malformed (dot segments, empty segments, {@code ;}, {@code %},
     * backslash) or that cannot be classified needs a signed-in user.
     */
    public boolean isOpen(String path) {
        if (path == null || !wellFormed(path)) return false;
        if (OPEN.contains(path) || authentication.openPaths().contains(path)) return true;
        String lower = path.toLowerCase(Locale.ROOT);
        return !(lower.equals("/api") || lower.startsWith("/api/"));
    }

    private static boolean wellFormed(String path) {
        if (!path.startsWith("/")) return false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == ';' || c == '%' || c == '\\' || c < 0x20) return false;
        }
        for (String segment : path.substring(1).split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) return false;
        }
        return !path.contains("//");
    }

    /** The verdict for a call to {@code path} by {@code user} (null = nobody signed in); chat ownership is {@link #decideChat}. */
    public Decision decide(String path, Principal user) {
        if (isOpen(path)) return Decision.ALLOW;
        if (user == null) return new Decision(401, "Sign in first");
        return Decision.ALLOW;
    }

    /** The verdict for a call on the chat the framework matched as {@code chatId}: 404 unless {@code user} may see it. */
    public Decision decideChat(Principal user, String chatId) {
        if (user == null) return new Decision(401, "Sign in first");
        if (!canSee(user, chatId)) return new Decision(404, "Unknown chat " + chatId);
        return Decision.ALLOW;
    }

    /** The verdict for a call under {@code /api/admin/}. */
    public Decision decideAdmin(Principal user) {
        if (user == null) return new Decision(401, "Sign in first");
        if (!user.admin()) return new Decision(403, "Admins only");
        return Decision.ALLOW;
    }

    private boolean canSee(Principal user, String chatId) {
        return sessions.find(chatId).map(chat -> access.canSee(user, chat)).orElse(false);
    }
}
