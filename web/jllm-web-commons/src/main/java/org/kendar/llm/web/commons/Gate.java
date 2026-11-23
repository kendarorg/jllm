package org.kendar.llm.web.commons;

import org.kendar.llm.harness.identity.ChatAccess;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.harness.session.ChatSessionService;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who may call what. Every {@code /api/**} call except the open ones needs a signed-in user (401 otherwise). A chat
 * the user may not see answers 404, as if it did not exist. {@code /api/admin/**} needs an admin (403 otherwise).
 * A pure function of its inputs: the framework looks the user up and applies the verdict.
 */
public final class Gate {

    private static final Pattern CHAT = Pattern.compile("^/api/chat/([^/]+)");
    /** Open on the common API: the command list adapts to the caller, signed in or not. */
    private static final Set<String> OPEN = Set.of("/api/chat/commands");

    /** The verdict: allowed (status 0), or the status and message to answer. */
    public record Decision(int status, String message) {
        static final Decision ALLOW = new Decision(0, null);

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

    /** Whether the path is outside the gate: no user needs to be looked up. */
    public boolean isOpen(String path) {
        return !path.startsWith("/api/") || OPEN.contains(path) || authentication.openPaths().contains(path);
    }

    /** The verdict for a call to {@code path} by {@code user} (null = nobody signed in). */
    public Decision decide(String path, Principal user) {
        if (isOpen(path)) return Decision.ALLOW;
        if (user == null) return new Decision(401, "Sign in first");
        Matcher m = CHAT.matcher(path);
        if (m.find() && !canSee(user, m.group(1))) return new Decision(404, "Unknown chat " + m.group(1));
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
