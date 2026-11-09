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
            for (java.nio.file.Path seg : dir) if (seg.toString().equalsIgnoreCase(".git")) return null;
            return dir.toString().replace('\\', '/');
        } catch (java.nio.file.InvalidPathException e) {
            return null;
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
            if (seg.equalsIgnoreCase(".git")) return null;
        }
        try {
            java.nio.file.Path root = workDir.toAbsolutePath().normalize();
            java.nio.file.Path p = root.resolve(t).normalize();
            if (!p.startsWith(root)) return null;
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

    public void grant(String chatId, String tool) {
        Set<String> g = grantsOf(chatId);
        if (g.add(tool)) {
            com.fasterxml.jackson.databind.node.ArrayNode a = MAPPER.createArrayNode();
            new java.util.TreeSet<>(g).forEach(a::add);
            state.save(chatId, GRANTS, a);
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

    /** Stores an approved command verbatim so the same command is not asked again; ignored at 512 chars or more. */
    public void grantCommand(String chatId, String tool, String command) {
        if (command == null || command.isEmpty() || command.length() >= MAX_COMMAND_GRANT) return;
        grant(chatId, commandKey(tool, command));
    }

    public boolean commandGranted(String chatId, String tool, String command) {
        if (command == null || command.length() >= MAX_COMMAND_GRANT) return false;
        return granted(chatId, commandKey(tool, command));
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
