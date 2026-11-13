package org.kendar.llm.harness.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.state.InMemoryStateStore;
import org.kendar.llm.harness.state.StateStore;
import org.kendar.llm.harness.util.Uuids;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Holds approvals the agent loop is blocked on, per chat. Used for tool calls and context rewrites alike. */
public class ApprovalService {

    /** @param type "tool" or "context"; @param preview what to show (diff/old); @param proposed editable new content */
    public record PendingApproval(String id, String chatId, String type, String title,
                                  String preview, String proposed) {
    }

    /**
     * @param edited   user-edited replacement for {@code proposed}, or null
     * @param remember allow this tool for the rest of the chat (only meaningful when approved)
     */
    public record ApprovalDecision(boolean approved, String edited, boolean remember, boolean rememberDir) {
        public static final ApprovalDecision REJECTED = new ApprovalDecision(false, null, false, false);

        public ApprovalDecision(boolean approved, String edited) {
            this(approved, edited, false, false);
        }

        public ApprovalDecision(boolean approved, String edited, boolean remember) {
            this(approved, edited, remember, false);
        }
    }

    private final int timeoutSeconds;
    private final Map<String, Map<String, Pending>> pending = new ConcurrentHashMap<>();
    /** One open dialog per chat: the others wait here, fairly, and are not shown until it is answered. */
    private final Map<String, ReentrantLock> dialogs = new ConcurrentHashMap<>();
    /** Tools the user allowed for a whole chat; never part of the context, persisted in the state store. */
    private final Map<String, Set<String>> grants = new ConcurrentHashMap<>();
    private final StateStore state;
    private static final String GRANTS = "grants";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Pending(PendingApproval approval, CompletableFuture<ApprovalDecision> future) {
    }

    public ApprovalService(int timeoutSeconds) {
        this(timeoutSeconds, new InMemoryStateStore());
    }

    /** @param state where the grants survive a restart */
    public ApprovalService(int timeoutSeconds, StateStore state) {
        this.timeoutSeconds = timeoutSeconds;
        this.state = state;
    }

    /**
     * Blocks until resolved, timed out (→ rejected) or cancelled (→ rejected). Dialogs of one chat are shown one at a
     * time; the timeout starts when the dialog is shown, and a cancel frees a caller still waiting for its turn.
     */
    public ApprovalDecision await(String chatId, String type, String title, String preview, String proposed,
                                  Consumer<PendingApproval> onCreated, BooleanSupplier cancelled) {
        return await(chatId, type, title, preview, proposed, onCreated, cancelled, null);
    }

    /**
     * As above; {@code alreadyAllowed} is re-checked once this call's turn comes, so a grant given to an earlier
     * dialog of the same chat (e.g. "allow writes in this folder") covers the calls queued behind it.
     */
    public ApprovalDecision await(String chatId, String type, String title, String preview, String proposed,
                                  Consumer<PendingApproval> onCreated, BooleanSupplier cancelled,
                                  BooleanSupplier alreadyAllowed) {
        ReentrantLock lock = dialogs.computeIfAbsent(chatId, k -> new ReentrantLock(true));
        try {
            while (!lock.tryLock(500, TimeUnit.MILLISECONDS)) {
                if (cancelled != null && cancelled.getAsBoolean()) return ApprovalDecision.REJECTED;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApprovalDecision.REJECTED;
        }
        try {
            if (cancelled != null && cancelled.getAsBoolean()) return ApprovalDecision.REJECTED;
            if (alreadyAllowed != null && alreadyAllowed.getAsBoolean()) {
                return new ApprovalDecision(true, null, false, false);
            }
            return show(chatId, type, title, preview, proposed, onCreated, cancelled);
        } finally {
            lock.unlock();
        }
    }

    private ApprovalDecision show(String chatId, String type, String title, String preview, String proposed,
                                  Consumer<PendingApproval> onCreated, BooleanSupplier cancelled) {
        PendingApproval approval = new PendingApproval(Uuids.v7String(), chatId, type, title, preview, proposed);
        Pending p = new Pending(approval, new CompletableFuture<>());
        pending.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>()).put(approval.id(), p);
        try {
            if (onCreated != null) onCreated.accept(approval);
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (cancelled != null && cancelled.getAsBoolean()) return ApprovalDecision.REJECTED;
                try {
                    return p.future().get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // poll again
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ApprovalDecision.REJECTED;
                } catch (Exception e) {
                    return ApprovalDecision.REJECTED;
                }
            }
            return ApprovalDecision.REJECTED;
        } finally {
            Map<String, Pending> m = pending.get(chatId);
            if (m != null) m.remove(approval.id());
        }
    }

    /** @return false when the approval no longer exists (already resolved, timed out or wrong chat) */
    public boolean resolve(String chatId, String approvalId, boolean approved, String edited) {
        return resolve(chatId, approvalId, approved, edited, false);
    }

    /** @param remember allow the tool for the rest of the chat (parent and sub-agents) */
    public boolean resolve(String chatId, String approvalId, boolean approved, String edited, boolean remember) {
        return resolve(chatId, approvalId, approved, edited, remember, false);
    }

    /** @param rememberDir allow file writes in the directory of this call and its subdirectories, for this chat */
    public boolean resolve(String chatId, String approvalId, boolean approved, String edited, boolean remember,
                           boolean rememberDir) {
        Map<String, Pending> m = pending.get(chatId);
        Pending p = m == null ? null : m.get(approvalId);
        if (p == null) return false;
        return p.future().complete(new ApprovalDecision(approved, edited, remember, rememberDir));
    }

    private static final String DIR = "dir:";

    /**
     * The directory (relative to the work dir, '/'-separated, "." for the root) a file write belongs to, or null when
     * it cannot be granted: outside the work dir, or inside a {@code .git} directory (hooks there run code).
     */
    public static String writeDirOf(java.nio.file.Path workDir, String path) {
        if (workDir == null || path == null || path.isBlank()) return null;
        try {
            java.nio.file.Path root = workDir.toAbsolutePath().normalize();
            java.nio.file.Path p = root.resolve(path).normalize();
            if (!p.startsWith(root)) return null;
            java.nio.file.Path dir = root.relativize(p).getParent();
            if (dir == null) return ".";
            for (java.nio.file.Path seg : dir) if (isGitName(seg.toString())) return null;
            if (realPathInGit(root, root.resolve(dir))) return null;
            return dir.toString().replace('\\', '/');
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /**
     * True for a path segment that names, or on Windows may alias, a {@code .git} directory: compared case-insensitively
     * after dropping trailing dots and spaces ({@code .git.}, {@code .GIT }), and 8.3 short names ({@code GIT~1}).
     * Applied on every platform: refusing a grant there is harmless.
     */
    static boolean isGitName(String segment) {
        String s = segment.strip();
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) end--;
        String t = s.substring(0, end).toLowerCase(java.util.Locale.ROOT);
        int colon = t.indexOf(':'); // NTFS stream: ".git::$INDEX_ALLOCATION"
        if (colon >= 0) t = t.substring(0, colon);
        return t.equals(".git") || t.matches("git~\\d+");
    }

    /** True when the deepest existing ancestor of {@code p} really lies inside a {@code .git} directory (short names). */
    private static boolean realPathInGit(java.nio.file.Path root, java.nio.file.Path p) {
        try {
            java.nio.file.Path q = p;
            while (q != null && q.startsWith(root) && !java.nio.file.Files.exists(q)) q = q.getParent();
            if (q == null || !q.startsWith(root)) return false;
            java.nio.file.Path realRoot = root.toRealPath();
            java.nio.file.Path real = q.toRealPath();
            if (!real.startsWith(realRoot)) return true; // a link out of the work dir: not grantable either
            for (java.nio.file.Path seg : realRoot.relativize(real)) if (isGitName(seg.toString())) return true;
            return false;
        } catch (java.io.IOException | SecurityException e) {
            return false; // the lexical check above already ran
        }
    }

    /**
     * A user-typed directory to grant, relative to the work dir ('/'-separated, "." for the root), or null when it is
     * not acceptable: blank, absolute, containing a {@code ..} segment, or inside a {@code .git} directory.
     */
    public static String grantDirOf(java.nio.file.Path workDir, String typed) {
        if (workDir == null || typed == null) return null;
        String t = typed.strip().replace('\\', '/');
        if (t.isEmpty() || t.startsWith("/") || t.matches("^[A-Za-z]:.*")) return null;
        for (String seg : t.split("/")) {
            if (seg.equals("..")) return null;
            if (isGitName(seg)) return null;
        }
        try {
            java.nio.file.Path root = workDir.toAbsolutePath().normalize();
            java.nio.file.Path p = root.resolve(t).normalize();
            if (!p.startsWith(root)) return null;
            if (realPathInGit(root, p)) return null;
            java.nio.file.Path rel = root.relativize(p);
            return rel.toString().isEmpty() ? "." : rel.toString().replace('\\', '/');
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    public void grantDir(String chatId, String dir) {
        if (dir != null) grant(chatId, DIR + dir);
    }

    /** True when {@code dir} is a granted directory or lies below one. */
    public boolean dirGranted(String chatId, String dir) {
        if (dir == null) return false;
        for (String g : grantsOf(chatId)) {
            if (!g.startsWith(DIR)) continue;
            String base = g.substring(DIR.length());
            if (base.equals(".") || dir.equals(base) || dir.startsWith(base + "/")) return true;
        }
        return false;
    }

    private static final String HOST = "host:";

    /** Allows the host and its subdomains for this chat. */
    public void grantHost(String chatId, String host) {
        String h = org.kendar.llm.harness.tools.HostMatcher.normalize(host);
        if (!h.isEmpty()) grant(chatId, HOST + h);
    }

    /** True when a grant covers {@code host}: the same name, or a parent domain on a label boundary. */
    public boolean hostGranted(String chatId, String host) {
        for (String g : grantsOf(chatId)) {
            if (g.startsWith(HOST) && org.kendar.llm.harness.tools.HostMatcher.covers(g.substring(HOST.length()), host)) {
                return true;
            }
        }
        return false;
    }

    public void grant(String chatId, String tool) {
        Set<String> g = grantsOf(chatId);
        synchronized (g) { // the saved snapshot always includes every grant added before it
            if (g.add(tool)) {
                com.fasterxml.jackson.databind.node.ArrayNode a = MAPPER.createArrayNode();
                new java.util.TreeSet<>(g).forEach(a::add);
                state.save(chatId, GRANTS, a);
            }
        }
    }

    public boolean granted(String chatId, String tool) {
        return grantsOf(chatId).contains(tool);
    }

    /** Longest command text stored as an exact-match grant. */
    public static final int MAX_COMMAND_GRANT = 512;

    private static String commandKey(String tool, String command) {
        return "cmd:" + tool + ":" + command;
    }

    private static String prefixKey(String tool, String prefix) {
        return "cmdprefix:" + tool + ":" + prefix;
    }

    /**
     * The stored form of a command: surrounding whitespace removed and runs of blanks (space, tab) outside quotes
     * collapsed to one space, so {@code ls  -la } and {@code ls -la} are one grant. Quoted text, newlines and every
     * other character are kept as they are. Null when the command cannot be granted (blank, or 512 chars or more).
     */
    public static String normalizeCommand(String command) {
        if (command == null) return null;
        String c = command.strip();
        if (c.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(c.length());
        char quote = 0;
        boolean blank = false;
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (quote == 0 && (ch == ' ' || ch == '\t')) {
                blank = true;
                continue;
            }
            if (blank) {
                sb.append(' ');
                blank = false;
            }
            sb.append(ch);
            if (ch == '\\' && quote != '\'' && i + 1 < c.length()) {
                sb.append(c.charAt(++i));
                continue;
            }
            if (quote == 0 && (ch == '\'' || ch == '"')) quote = ch;
            else if (ch == quote) quote = 0;
        }
        String n = sb.toString();
        return n.length() >= MAX_COMMAND_GRANT ? null : n;
    }

    /** Characters that chain, substitute or redirect: a command holding one is never covered by a prefix grant. */
    private static final java.util.regex.Pattern SHELL_META = java.util.regex.Pattern.compile("[;&|`$<>\\n\\r(){}]");

    /** Stores an approved command (normalized) so the same command is not asked again; ignored at 512 chars or more. */
    public void grantCommand(String chatId, String tool, String command) {
        String n = normalizeCommand(command);
        if (n != null) grant(chatId, commandKey(tool, n));
    }

    /**
     * Allows every command that is {@code prefix} or {@code prefix} followed by plain arguments (e.g. {@code git status}
     * covers {@code git status --short}); a command with shell operators, substitutions or redirections is never
     * covered. The prefix itself must be free of them.
     *
     * @return false when the prefix cannot be granted
     */
    public boolean grantCommandPrefix(String chatId, String tool, String prefix) {
        String n = normalizeCommand(prefix);
        if (n == null || SHELL_META.matcher(n).find()) return false;
        grant(chatId, prefixKey(tool, n));
        return true;
    }

    /** True when the command, normalized, was approved before: as the same command or under a prefix grant. */
    public boolean commandGranted(String chatId, String tool, String command) {
        String n = normalizeCommand(command);
        if (n == null) return false;
        Set<String> g = grantsOf(chatId);
        if (g.contains(commandKey(tool, n)) || g.contains(commandKey(tool, command))) return true;
        if (SHELL_META.matcher(n).find()) return false;
        String p = prefixKey(tool, "");
        for (String grant : g) {
            if (!grant.startsWith(p)) continue;
            String prefix = grant.substring(p.length());
            if (n.equals(prefix) || n.startsWith(prefix + " ")) return true;
        }
        return false;
    }

    private Set<String> grantsOf(String chatId) {
        return grants.computeIfAbsent(chatId, k -> {
            Set<String> set = ConcurrentHashMap.newKeySet();
            JsonNode n = state.load(k, GRANTS);
            if (n != null && n.isArray()) n.forEach(t -> set.add(t.asText()));
            return set;
        });
    }

    /** Drops the grants of a deleted chat. */
    public void forget(String chatId) {
        grants.remove(chatId);
        state.delete(chatId);
        dialogs.remove(chatId);
    }

    public List<PendingApproval> pending(String chatId) {
        List<PendingApproval> out = new ArrayList<>();
        Map<String, Pending> m = pending.get(chatId);
        if (m != null) m.values().forEach(p -> out.add(p.approval()));
        return out;
    }

    public void cancelAll(String chatId) {
        Map<String, Pending> m = pending.get(chatId);
        if (m != null) m.values().forEach(p -> p.future().complete(ApprovalDecision.REJECTED));
    }
}
