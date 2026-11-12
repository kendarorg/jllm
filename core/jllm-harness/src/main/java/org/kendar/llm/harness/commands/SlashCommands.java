package org.kendar.llm.harness.commands;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.agent.AgentService;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatSessionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Slash commands ({@code /clear}, {@code /mode}, ...) shared by every front end. A front end asks
 * {@link #handles} first and sends the line to the model when it is not a command, then renders the
 * {@link Result}: print the output, switch to {@link Result#switchToChatId()} and reload when
 * {@link Result#refresh()}.
 */
public final class SlashCommands {

    /**
     * @param switchToChatId chat the front end must attach to, or null
     * @param refresh        the chat state changed
     * @param menu           a numbered menu is open: the front end sends the next input to {@link #menuInput}
     */
    public record Result(String output, String switchToChatId, boolean refresh, boolean menu) {
        public Result(String output, String switchToChatId, boolean refresh) {
            this(output, switchToChatId, refresh, false);
        }

        static Result text(String output) {
            return new Result(output, null, false);
        }
    }

    public record Info(String name, String description) {
    }

    private static final List<Info> COMMANDS = List.of(
            new Info("/help", "List the commands"),
            new Info("/clear", "Clear this conversation"),
            new Info("/new", "Start a new chat, keeping this one"),
            new Info("/compact", "Summarize older context to free space (optional focus text)"),
            new Info("/mode", "Menu: choose the mode (plan|edit|auto); /mode <name> sets it directly"),
            new Info("/undo", "Restore files changed by write_file/edit_file: /undo [turns] (not shell changes)"),
            new Info("/model", "Menu: choose the model for new runs; /model <provider> sets it directly"),
            new Info("/default", "Mode: ask before flagged tools"),
            new Info("/plan", "Mode: working directory read-only, scratch directory writable"),
            new Info("/edit", "Mode: file edits run without asking"),
            new Info("/auto", "Mode: nothing asks for approval"),
            new Info("/cancel", "Cancel the current run"),
            new Info("/steer", "Steer the running agent: /steer <text> (applied after the current tool calls)"),
            new Info("/followup", "Queue a message for when the agent would finish: /followup <text>"),
            new Info("/tools", "Menu: enable or disable tools and groups"),
            new Info("/mcp", "Menu: enable, disable or check MCP servers"),
            new Info("/apis", "Menu: enable, disable or check REST APIs"),
            new Info("/skills", "Menu: enable or disable skills"),
            new Info("/validation", "Tool argument check: /validation shows it, /validation off|lenient|strict sets it for this chat"),
            new Info("/permissions", "List the permission rules from the settings (read-only)"),
            new Info("/back", "Go back one level in an open menu (Esc in a terminal)"),
            new Info("/cost", "Token usage and estimated cost of this chat"),
            new Info("/where", "Show the working directory and the scratch directory"),
            new Info("/sandbox", "Docker daemon mode: /sandbox clean removes the sandbox container (project files stay), /sandbox backup copies a remote container back"),
            new Info("/status", "Context usage, cost and chat count"),
            new Info("/chats", "List the chats; /chats delete <id>, /chats clean [days]"),
            new Info("/resume", "Switch to another chat: /resume <id or prefix>"),
            new Info("/title", "Rename this chat: /title <text>"),
            new Info("/export", "Write this chat as a transcript: /export [md|html] [file] (default md, into the scratch directory)"),
            new Info("/login", "Log in to an OAuth provider or MCP server: /login <name> (no name lists them). Blocks until done"),
            new Info("/logout", "Remove the stored login: /logout <name>"),
            new Info("/exit", "Close the chat (command line only; does nothing elsewhere)"));

    /** Input that steps back in a menu: the Esc character, {@code /back} or {@code b}. */
    private static final String ESC = "\u001b";

    private final AgentHarness harness;
    private final MenuController menus;
    private final Map<String, CustomCommand> custom = new ConcurrentHashMap<>();
    private final List<Info> customInfo = new CopyOnWriteArrayList<>();

    public SlashCommands(AgentHarness harness) {
        this.harness = harness;
        this.menus = new MenuController(harness);
    }

    /** True while a numbered menu is open for the chat; its input goes to {@link #menuInput}. */
    public boolean menuActive(String chatId) {
        return menus.active(chatId);
    }

    /** Handles a line typed while a menu is open: a number selects, Esc, {@code /back} or {@code b} goes back. */
    public Result menuInput(String chatId, String line) {
        String in = line == null ? "" : line.strip();
        if (in.startsWith(ESC) || in.equals("/back") || in.equals("b")) return menus.back(chatId);
        try {
            return menus.select(chatId, Integer.parseInt(in));
        } catch (NumberFormatException e) {
            return new Result("Type a number, or Esc / b to go back", null, false, true);
        }
    }

    /** Adds a command (from a plugin). @throws IllegalArgumentException if the name is taken */
    public void register(String name, String description, CustomCommand command) {
        if (!name.startsWith("/") || name.length() < 2 || name.contains(" ")) {
            throw new IllegalArgumentException("Invalid command name '" + name + "'");
        }
        if (COMMANDS.stream().anyMatch(c -> c.name().equals(name)) || custom.putIfAbsent(name, command) != null) {
            throw new IllegalArgumentException("Duplicate command " + name);
        }
        customInfo.add(new Info(name, description));
    }

    public List<Info> list() {
        List<Info> all = new ArrayList<>(COMMANDS);
        all.addAll(customInfo);
        return all;
    }

    /** True if the first word of the line is a known command; other lines are meant for the model. */
    public boolean handles(String line) {
        if (line == null || !line.startsWith("/")) return false;
        String name = line.strip().split("\\s+", 2)[0];
        return custom.containsKey(name) || COMMANDS.stream().anyMatch(c -> c.name().equals(name));
    }

    /** Runs a line for which {@link #handles} is true. Failures come back as output, never as exceptions. */
    public Result execute(String chatId, String line) {
        String[] parts = line.strip().split("\\s+", 2);
        String name = parts[0];
        String arg = parts.length > 1 ? parts[1].strip() : "";
        ChatSessionService sessions = harness.sessions();
        try {
            return switch (name) {
                case "/help" -> Result.text(help());
                case "/exit" -> Result.text(""); // the command line quits before getting here; elsewhere it is a no-op
                case "/clear" -> {
                    if (harness.isRunning(chatId)) yield Result.text("A run is in progress");
                    ArrayNode messages = sessions.messages(sessions.get(chatId));
                    messages.removeAll();
                    sessions.saveMessages(chatId, messages);
                    yield new Result("Conversation cleared", null, true);
                }
                case "/new" -> {
                    String id = sessions.create(null).chatId();
                    sessions.setMode(id, PermissionMode.parse(sessions.get(chatId).mode()));
                    yield new Result("chat " + id, id, true);
                }
                case "/compact" -> {
                    var r = harness.compact(chatId, arg);
                    yield new Result(r.message() != null ? r.message() : r.changed()
                            ? "Compacted: ~" + r.tokensBefore() + " -> ~" + r.tokensAfter() + " tokens"
                            : "Nothing to compact", null, true);
                }
                case "/mode" -> arg.isEmpty() ? menus.open(chatId, MenuController.Target.MODE) : setMode(chatId, arg);
                case "/undo" -> {
                    int n = arg.matches("\\d+") ? Integer.parseInt(arg) : 1;
                    yield new Result(harness.undo(chatId, n).message(), null, true);
                }
                case "/model" -> arg.isEmpty() ? menus.open(chatId, MenuController.Target.MODEL)
                        : Result.text(harness.switchModel(chatId, arg, false).message());
                case "/default" -> setMode(chatId, "default");
                case "/plan" -> setMode(chatId, "plan");
                case "/edit" -> setMode(chatId, "edit");
                case "/auto" -> setMode(chatId, "auto");
                case "/cancel" -> {
                    harness.cancel(chatId);
                    yield Result.text("Cancel requested");
                }
                case "/steer" -> {
                    if (arg.isEmpty()) yield Result.text("usage: /steer <text>");
                    harness.steer(chatId, arg);
                    yield new Result("", null, true);
                }
                case "/followup" -> {
                    if (arg.isEmpty()) yield Result.text("usage: /followup <text>");
                    harness.followUp(chatId, arg);
                    yield new Result("", null, true);
                }
                case "/tools" -> menuCommand(chatId, MenuController.Target.TOOLS, arg);
                case "/mcp" -> menuCommand(chatId, MenuController.Target.MCP, arg);
                case "/apis" -> menuCommand(chatId, MenuController.Target.API, arg);
                case "/skills" -> menuCommand(chatId, MenuController.Target.SKILL, arg);
                case "/validation" -> {
                    if (arg.isEmpty()) {
                        yield Result.text("Tool argument validation: " + harness.toolValidation(chatId).name().toLowerCase());
                    }
                    var m = org.kendar.llm.harness.tools.ValidationMode.parse(arg);
                    harness.setToolValidation(chatId, m);
                    yield Result.text("Tool argument validation: " + m.name().toLowerCase());
                }
                case "/permissions" -> {
                    var rules = harness.permissionRules().describe();
                    yield Result.text(rules.isEmpty() ? "No permission rules" : String.join("\n", rules));
                }
                case "/back" -> menus.back(chatId);
                case "/cost" -> {
                    ChatSessionData s = sessions.get(chatId);
                    yield Result.text(usage(s));
                }
                case "/where" -> Result.text("working directory: "
                        + harness.sandbox().displayPath(harness.workDir(chatId)) + "\n"
                        + "scratch directory: " + harness.sandbox().displayPath(harness.scratchDir(chatId)));
                case "/sandbox" -> {
                    if (arg.equals("backup")) {
                        String done = harness.sandbox().backup(chatId);
                        yield Result.text(done == null ? "This sandbox needs no backup (only remote docker does)" : done);
                    }
                    if (!arg.equals("clean")) yield Result.text("usage: /sandbox clean | /sandbox backup");
                    String cleaned = harness.sandbox().clean(chatId);
                    yield Result.text(cleaned == null ? "This sandbox has no container to clean" : cleaned);
                }
                case "/status" -> Result.text(status(chatId));
                case "/chats" -> Result.text(chatsCommand(chatId, arg));
                case "/resume" -> {
                    if (arg.isEmpty()) yield Result.text("usage: /resume <id or unique prefix>");
                    ChatSessionData s = sessions.resolve(arg);
                    yield new Result("chat " + s.chatId() + " " + s.title(), s.chatId(), true);
                }
                case "/login" -> Result.text(login(arg));
                case "/logout" -> Result.text(arg.isEmpty() ? "usage: /logout <name>"
                        : harness.tokenStore().remove(arg) ? "Logged out of " + arg : "No stored login for " + arg);
                case "/export" -> Result.text(export(chatId, arg));
                case "/title" -> {
                    if (arg.isEmpty()) yield Result.text("usage: /title <text>");
                    sessions.setTitle(chatId, arg);
                    yield new Result("Title: " + arg, null, true);
                }
                default -> custom.containsKey(name) ? Result.text(custom.get(name).run(chatId, arg))
                        : Result.text("unknown command " + name);
            };
        } catch (AgentService.RunAlreadyActiveException e) {
            return Result.text("A run is in progress");
        } catch (Exception e) {
            return Result.text("error: " + e.getMessage());
        }
    }

    private String login(String name) {
        var logins = harness.logins();
        if (name.isEmpty()) {
            return logins.isEmpty() ? "No logins available" : "logins: " + String.join(", ", logins.keySet())
                    + "\nlogged in: " + String.join(", ", harness.tokenStore().names());
        }
        var login = logins.get(name);
        if (login == null) return "unknown login " + name + " (" + String.join(", ", logins.keySet()) + ")";
        StringBuilder out = new StringBuilder();
        try {
            harness.tokenStore().put(name, login.login(new org.kendar.llm.harness.auth.UserInteraction() {
                @Override
                public void show(String message) {
                    out.append(message).append('\n');
                    System.err.println(message); // the result is returned only when the login ends
                }

                @Override
                public String ask(String prompt) {
                    return null;
                }
            }));
            return out + "Logged in to " + name;
        } catch (org.kendar.llm.harness.auth.OAuthException e) {
            return out + "Login failed: " + e.getMessage();
        }
    }

    private String export(String chatId, String arg) throws java.io.IOException {
        String[] a = arg.isEmpty() ? new String[0] : arg.split("\\s+", 2);
        boolean html = a.length > 0 && a[0].equalsIgnoreCase("html");
        boolean fmt = a.length > 0 && (html || a[0].equalsIgnoreCase("md"));
        String file = fmt ? (a.length > 1 ? a[1].strip() : "") : arg;
        ChatSessionData s = harness.sessions().get(chatId);
        if (file.isEmpty()) file = "chat-" + chatId + (html ? ".html" : ".md");
        java.nio.file.Path dir = harness.scratchDir(chatId).toAbsolutePath().normalize();
        java.nio.file.Path target = dir.resolve(file).normalize();
        if (!target.startsWith(dir)) return "error: the file must be inside the scratch directory";
        var messages = harness.sessions().messages(s);
        java.nio.file.Files.createDirectories(target.getParent());
        java.nio.file.Files.writeString(target, html ? org.kendar.llm.harness.session.ChatExporter.html(s.title(), messages)
                : org.kendar.llm.harness.session.ChatExporter.markdown(s.title(), messages));
        return "exported " + messages.size() + " messages to " + harness.sandbox().displayPath(target);
    }

    private Result setMode(String chatId, String mode) {
        PermissionMode m = PermissionMode.parse(mode);
        harness.sessions().setMode(chatId, m);
        return new Result("Mode: " + m.name(), null, true);
    }

    private String usage(ChatSessionData s) {
        return "input " + s.inputTokens() + ", output " + s.outputTokens() + ", cache read " + s.cacheReadTokens()
                + ", cache write " + s.cacheCreationTokens() + String.format(", cost $%.4f", harness.cost(s))
                + (s.qualifierInputTokens() + s.qualifierOutputTokens() == 0 ? ""
                : "\nnoise qualifier: input " + s.qualifierInputTokens() + ", output " + s.qualifierOutputTokens());
    }

    private String status(String chatId) {
        ChatSessionData s = harness.sessions().get(chatId);
        long used = harness.contextTokens(chatId);
        long budget = harness.contextBudget();
        StringBuilder agents = new StringBuilder();
        var active = harness.activeSubAgents(chatId);
        long now = System.currentTimeMillis();
        agents.append("running agents: ").append(active.isEmpty() ? "none" : active.size()).append('\n');
        for (var a : active) {
            String p = a.prompt().replaceAll("\\s+", " ");
            agents.append("  ").append(a.label()).append(" (").append((now - a.startedAtMillis()) / 1000).append("s) ")
                    .append(p.length() > 70 ? p.substring(0, 70) + "…" : p).append('\n');
        }
        return "chat: " + s.chatId() + " \"" + s.title() + "\"\n"
                + "mode: " + s.mode() + "\n"
                + agents
                + "context strategy: " + harness.contextStrategy().kind().name().toLowerCase() + "\n"
                + "context: ~" + used + " / " + budget + " tokens (" + (budget > 0 ? used * 100 / budget : 0) + "%)\n"
                + "usage: " + usage(s) + "\n"
                + "chats: " + harness.sessions().list().size();
    }

    private String help() {
        StringBuilder sb = new StringBuilder();
        for (Info c : list()) sb.append(c.name()).append("  ").append(c.description()).append('\n');
        return sb.toString().stripTrailing();
    }

    /** {@code /mcp} opens the menu, {@code /mcp 2} selects, {@code /mcp enable|disable|check <name>} is for scripts. */
    private Result menuCommand(String chatId, MenuController.Target target, String arg) {
        if (arg.isEmpty()) return menus.open(chatId, target);
        if (arg.matches("\\d+")) {
            if (!menus.active(chatId)) menus.open(chatId, target);
            return menus.select(chatId, Integer.parseInt(arg));
        }
        String[] p = arg.split("\\s+", 2);
        if (p.length < 2 && !(p[0].equals("check") && target != MenuController.Target.TOOLS)) {
            return Result.text("usage: /" + target.name().toLowerCase() + " [number | enable|disable|check <name>]");
        }
        return Result.text(menus.scripted(target, p[0], p.length > 1 ? p[1] : null));
    }

    private final Map<String, List<String>> pendingClean = new ConcurrentHashMap<>();

    /** {@code /chats}, {@code /chats delete <id>}, {@code /chats clean [days]} then {@code /chats clean confirm}. */
    private String chatsCommand(String current, String arg) {
        if (arg.isEmpty()) return chats(current);
        String[] p = arg.split("\\s+", 2);
        switch (p[0]) {
            case "delete" -> {
                if (p.length < 2) return "usage: /chats delete <id or unique prefix>";
                ChatSessionData s = harness.sessions().resolve(p[1]);
                if (s.chatId().equals(current)) return "Cannot delete the current chat";
                harness.deleteChat(s.chatId());
                return "Deleted chat " + s.chatId() + " \"" + s.title() + "\"";
            }
            case "clean" -> {
                String a = p.length > 1 ? p[1].strip() : "";
                if (a.equals("confirm")) {
                    List<String> ids = pendingClean.remove(current);
                    if (ids == null) return "Nothing to confirm: run /chats clean [days] first";
                    int n = 0;
                    for (String id : ids) {
                        if (id.equals(current) || harness.sessions().find(id).isEmpty()) continue;
                        harness.deleteChat(id);
                        n++;
                    }
                    return "Deleted " + n + " chat(s)";
                }
                int days = a.isEmpty() ? 30 : a.matches("\\d+") ? Integer.parseInt(a) : -1;
                if (days < 0) return "usage: /chats clean [days]";
                List<ChatSessionData> stale = harness.staleChats(days, current);
                if (stale.isEmpty()) return "No chat older than " + days + " days";
                pendingClean.put(current, stale.stream().map(ChatSessionData::chatId).toList());
                StringBuilder sb = new StringBuilder(stale.size() + " chat(s) not updated for " + days
                        + " days will be deleted:\n");
                for (ChatSessionData s : stale) sb.append("  ").append(chatLine(s)).append('\n');
                return sb.append("Type /chats clean confirm to delete them").toString();
            }
            default -> {
                return "usage: /chats | /chats delete <id> | /chats clean [days]";
            }
        }
    }

    /** {@code id  "title"  (N messages, updated <time>)} */
    public static String chatLine(ChatSessionData s) {
        return s.chatId() + "  \"" + s.title() + "\"  (" + s.messageCount() + " messages, updated "
                + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(s.updatedAt().atZone(java.time.ZoneId.systemDefault())) + ")";
    }

    private String chats(String current) {
        StringBuilder sb = new StringBuilder();
        for (ChatSessionData s : harness.sessions().list()) {
            sb.append(s.chatId().equals(current) ? "* " : "  ").append(chatLine(s)).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
