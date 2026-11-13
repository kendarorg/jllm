package org.kendar.llm.harness.commands;

import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.integration.ExternalSources;
import org.kendar.llm.harness.integration.ExternalSources.Kind;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.session.ChatToolSet;
import org.kendar.llm.harness.skills.SkillCatalog;
import org.kendar.llm.harness.tools.HarnessTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Numbered menus behind {@code /tools}, {@code /mcp} and {@code /apis}: pick an item by number, then an action by
 * number; "back" steps one level up and, at the list, closes the menu. State is kept per chat, so every front end
 * shares one implementation.
 */
final class MenuController {

    enum Target {
        TOOLS("Tools"), MCP("MCP servers"), API("REST APIs"), SKILL("Skills"), MODE("Mode"), MODEL("Model");

        final String title;

        Target(String title) {
            this.title = title;
        }
    }

    /** One selectable line. {@code group} entries stand for all the tools of a group. */
    private record Entry(String key, boolean group, String label, boolean on, String detail) {
    }

    private enum Op { TOGGLE, CHECK }

    private record Action(String label, Op op) {
    }

    private static final class State {
        final Target target;
        /** Toggles change the chat's own set ({@link ChatToolSet}), not the global state (web callers). */
        final boolean perChat;
        Entry selected;
        /** Provider waiting for the answer to "compact first?". */
        String pendingModel;

        State(Target target, boolean perChat) {
            this.target = target;
            this.perChat = perChat;
        }
    }

    private final AgentHarness harness;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    MenuController(AgentHarness harness) {
        this.harness = harness;
    }

    boolean active(String chatId) {
        return states.containsKey(chatId);
    }

    SlashCommands.Result open(String chatId, Target target) {
        return open(chatId, target, false);
    }

    SlashCommands.Result open(String chatId, Target target, boolean perChat) {
        states.put(chatId, new State(target, perChat));
        return render(chatId, null);
    }

    // ---- per-chat set

    /** What is globally enabled now: the chat's set when it starts. */
    ChatToolSet captureNow() {
        List<String> tools = harness.tools().all().stream().map(HarnessTool::name).toList();
        List<String> mcp = harness.sources().list(Kind.MCP).stream().filter(ExternalSources.Status::enabled)
                .map(ExternalSources.Status::name).toList();
        List<String> apis = harness.sources().list(Kind.API).stream().filter(ExternalSources.Status::enabled)
                .map(ExternalSources.Status::name).toList();
        List<String> skills = harness.skills().enabled().stream().map(SkillCatalog.Skill::name).toList();
        return ChatToolSet.capture(tools, mcp, apis, skills);
    }

    private ChatToolSet chatSet(String chatId) {
        return harness.sessions().captureChatToolSet(chatId, this::captureNow);
    }

    private static ChatToolSet.Kind kindOf(Target target) {
        return switch (target) {
            case TOOLS -> ChatToolSet.Kind.TOOL;
            case MCP -> ChatToolSet.Kind.MCP;
            case API -> ChatToolSet.Kind.API;
            case SKILL -> ChatToolSet.Kind.SKILL;
            default -> throw new IllegalArgumentException("No per-chat set for " + target);
        };
    }

    private void setInChat(String chatId, ChatToolSet.Kind kind, List<String> names, boolean on) {
        ChatToolSet s = chatSet(chatId);
        for (String n : names) s = s.with(kind, n, on);
        harness.sessions().setChatToolSet(chatId, s);
    }

    /** Typed per-chat form: {@code enable|disable <name>} (tool or group for /tools), {@code check [name]} for sources. */
    String scriptedInChat(String chatId, Target target, String verb, String name) {
        try {
            ChatToolSet set = chatSet(chatId);
            ChatToolSet.Kind kind = kindOf(target);
            if (verb.equals("check") && (target == Target.MCP || target == Target.API)) {
                Kind k = target == Target.MCP ? Kind.MCP : Kind.API;
                List<String> names = name != null ? List.of(name) : List.copyOf(set.base(kind));
                StringBuilder sb = new StringBuilder();
                for (String n : names) {
                    if (!set.base(kind).contains(n)) {
                        sb.append("'").append(n).append("' is not available in this chat\n");
                        continue;
                    }
                    sb.append(describe(n, harness.sources().check(k, n))).append('\n');
                }
                return sb.isEmpty() ? "(none)" : sb.toString().stripTrailing();
            }
            if (!verb.equals("enable") && !verb.equals("disable")) {
                return "usage: /" + target.name().toLowerCase() + " enable|disable <name>";
            }
            boolean on = verb.equals("enable");
            List<String> names = target == Target.TOOLS
                    ? set.base(kind).stream().filter(n -> n.equals(name) || name.equals(groupOf(n))).toList()
                    : set.base(kind).contains(name) ? List.of(name) : List.of();
            if (names.isEmpty()) return "'" + name + "' is not available in this chat";
            setInChat(chatId, kind, names, on);
            return (on ? "Enabled for this chat: " : "Disabled for this chat: ") + String.join(", ", names);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private String groupOf(String tool) {
        HarnessTool t = harness.tools().get(tool);
        return t == null || t.group() == null ? "(no group)" : t.group();
    }

    /** Modes offered by the mode menu, in order ({@link PermissionMode#DEFAULT} is only reachable by name). */
    static final List<PermissionMode> MODES = List.of(PermissionMode.PLAN, PermissionMode.EDIT, PermissionMode.AUTO);

    SlashCommands.Result select(String chatId, int number) {
        State st = states.get(chatId);
        if (st == null) return SlashCommands.Result.text("No menu is open");
        if (st.target == Target.MODE) {
            if (number < 1 || number > MODES.size()) return render(chatId, "No mode " + number);
            states.remove(chatId);
            PermissionMode m = MODES.get(number - 1);
            try {
                harness.sessions().setMode(chatId, m);
            } catch (RuntimeException e) {
                return SlashCommands.Result.text("error: " + e.getMessage());
            }
            return new SlashCommands.Result("Mode: " + m.name(), null, true);
        }
        if (st.target == Target.MODEL) return selectModel(chatId, st, number);
        if (st.selected == null) {
            List<Entry> entries = entries(chatId, st);
            if (number < 1 || number > entries.size()) return render(chatId, "No item " + number);
            st.selected = entries.get(number - 1);
            return render(chatId, null);
        }
        List<Action> actions = actions(st);
        if (number < 1 || number > actions.size()) return render(chatId, "No action " + number);
        Action action = actions.get(number - 1);
        String message;
        try {
            message = st.perChat ? runInChat(chatId, st, action) : run(st, action);
        } catch (RuntimeException e) {
            message = "error: " + e.getMessage();
        }
        st.selected = null;
        return render(chatId, message);
    }

    private SlashCommands.Result selectModel(String chatId, State st, int number) {
        if (st.pendingModel != null) {
            String name = st.pendingModel;
            st.pendingModel = null;
            if (number != 1) return render(chatId, "Model unchanged");
            try {
                return finishSwitch(chatId, harness.switchModel(chatId, name, true));
            } catch (RuntimeException e) {
                return render(chatId, "error: " + e.getMessage());
            }
        }
        List<String> names = harness.providers().names();
        if (number < 1 || number > names.size()) return render(chatId, "No model " + number);
        String name = names.get(number - 1);
        AgentHarness.SwitchResult r;
        try {
            r = harness.switchModel(chatId, name, false);
        } catch (RuntimeException e) {
            return render(chatId, e.getMessage());
        }
        if (r.status() == AgentHarness.SwitchStatus.NEEDS_COMPACTION) {
            st.pendingModel = name;
            return render(chatId, r.message());
        }
        return finishSwitch(chatId, r);
    }

    private SlashCommands.Result finishSwitch(String chatId, AgentHarness.SwitchResult r) {
        if (r.status() == AgentHarness.SwitchStatus.SWITCHED) {
            states.remove(chatId);
            return new SlashCommands.Result(r.message(), null, true);
        }
        return render(chatId, r.message());
    }

    SlashCommands.Result back(String chatId) {
        State st = states.get(chatId);
        if (st == null) return SlashCommands.Result.text("No menu is open");
        if (st.pendingModel != null) {
            st.pendingModel = null;
            return render(chatId, null);
        }
        if (st.selected != null) {
            st.selected = null;
            return render(chatId, null);
        }
        states.remove(chatId);
        return SlashCommands.Result.text("Menu closed");
    }

    /** Typed form for scripts: {@code enable|disable <name>} and, for sources, {@code check [name]}. */
    String scripted(Target target, String verb, String name) {
        try {
            if (target == Target.TOOLS) {
                if (!verb.equals("enable") && !verb.equals("disable")) return "usage: /tools enable|disable <tool|group>";
                List<String> names = harness.tools().every().stream()
                        .filter(t -> t.name().equals(name) || name.equals(t.group())).map(HarnessTool::name).toList();
                if (names.isEmpty()) return "No tool or group '" + name + "'";
                toggle(names, verb.equals("enable"));
                return (verb.equals("enable") ? "Enabled: " : "Disabled: ") + String.join(", ", names);
            }
            if (target == Target.SKILL) {
                if (!verb.equals("enable") && !verb.equals("disable")) return "usage: /skills enable|disable <name>";
                harness.skills().setEnabled(name, verb.equals("enable"));
                return (verb.equals("enable") ? "Enabled: " : "Disabled: ") + name;
            }
            Kind kind = target == Target.MCP ? Kind.MCP : Kind.API;
            ExternalSources sources = harness.sources();
            return switch (verb) {
                case "enable" -> {
                    String error = sources.enable(kind, name);
                    yield error == null ? "Enabled: " + name : "Cannot enable " + name + ": " + error;
                }
                case "disable" -> {
                    sources.disable(kind, name);
                    yield "Disabled: " + name;
                }
                case "check" -> {
                    List<String> names = name != null ? List.of(name)
                            : sources.list(kind).stream().map(ExternalSources.Status::name).toList();
                    StringBuilder sb = new StringBuilder();
                    for (String n : names) sb.append(describe(n, sources.check(kind, n))).append('\n');
                    yield sb.isEmpty() ? "(none)" : sb.toString().stripTrailing();
                }
                default -> "usage: enable|disable|check <name>";
            };
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    // ---- rendering

    private SlashCommands.Result render(String chatId, String message) {
        State st = states.get(chatId);
        StringBuilder sb = new StringBuilder();
        if (message != null) sb.append(message).append("\n\n");
        if (st.target == Target.MODE) {
            PermissionMode current = PermissionMode.parse(harness.sessions().get(chatId).mode());
            sb.append("Mode  (number + Enter to select, Esc to close)\n");
            for (int i = 0; i < MODES.size(); i++) {
                sb.append(String.format("%3d. %s%s%n", i + 1, MODES.get(i).name().toLowerCase(),
                        MODES.get(i) == current ? "  [current]" : ""));
            }
        } else if (st.target == Target.MODEL) {
            if (st.pendingModel != null) {
                sb.append("Compact the conversation, then switch to ").append(st.pendingModel).append("?\n");
                sb.append("  1. yes\n  2. no\n");
            } else {
                sb.append("Model  (number + Enter to select, Esc to close)\n");
                List<String> names = harness.providers().names();
                for (int i = 0; i < names.size(); i++) {
                    var p = harness.providers().find(names.get(i));
                    String detail = p.toLlmSettings().model() + (p.contextTokens() != null ? ", " + p.contextTokens()
                            + " ctx" : "");
                    sb.append(String.format("%3d. %s  [%s]%s%n", i + 1, names.get(i), detail,
                            names.get(i).equals(harness.currentProvider()) ? "  [current]" : ""));
                }
            }
        } else if (st.selected == null) {
            sb.append(st.target.title).append(st.perChat ? " of this chat" : "")
                    .append("  (number + Enter to select, Esc to close)\n");
            List<Entry> entries = entries(chatId, st);
            if (entries.isEmpty()) sb.append("  (none)\n");
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                sb.append(String.format("%3d. %s%s  [%s]%n", i + 1, e.group() ? "" : "   ", e.label(), e.detail()));
            }
        } else {
            sb.append(st.selected.label()).append("  [").append(st.selected.detail())
                    .append("]  (number + Enter, Esc to go back)\n");
            List<Action> actions = actions(st);
            for (int i = 0; i < actions.size(); i++) {
                sb.append(String.format("%3d. %s%n", i + 1, actions.get(i).label()));
            }
        }
        return new SlashCommands.Result(sb.toString().stripTrailing(), null, false, true);
    }

    // ---- content

    private List<Entry> entries(String chatId, State st) {
        return st.perChat ? chatEntries(chatId, st.target) : entries(st.target);
    }

    /** The chat's own set: only what was enabled when the chat started, on/off for this chat. */
    private List<Entry> chatEntries(String chatId, Target target) {
        if (target == Target.MODE || target == Target.MODEL) return List.of();
        ChatToolSet set = chatSet(chatId);
        ChatToolSet.Kind kind = kindOf(target);
        List<Entry> out = new ArrayList<>();
        if (target != Target.TOOLS) {
            for (String n : set.base(kind)) {
                boolean on = set.isOn(kind, n);
                out.add(new Entry(n, false, n, on, on ? "on" : "off"));
            }
            return out;
        }
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        for (HarnessTool t : harness.tools().every()) {
            if (set.base(kind).contains(t.name())) {
                byGroup.computeIfAbsent(groupOf(t.name()), g -> new ArrayList<>()).add(t.name());
            }
        }
        byGroup.forEach((group, tools) -> {
            long on = tools.stream().filter(n -> set.isOn(kind, n)).count();
            out.add(new Entry(group, true, group, on == tools.size(), on + "/" + tools.size() + " on"));
            for (String n : tools) {
                boolean enabled = set.isOn(kind, n);
                out.add(new Entry(n, false, n, enabled, enabled ? "on" : "off"));
            }
        });
        return out;
    }

    private String runInChat(String chatId, State st, Action action) {
        Entry e = st.selected;
        ChatToolSet.Kind kind = kindOf(st.target);
        if (action.op() == Op.CHECK) {
            return describe(e.label(), harness.sources().check(st.target == Target.MCP ? Kind.MCP : Kind.API, e.key()));
        }
        List<String> names = st.target == Target.TOOLS && e.group()
                ? chatSet(chatId).base(kind).stream().filter(n -> groupOf(n).equals(e.key())).toList()
                : List.of(e.key());
        setInChat(chatId, kind, names, !e.on());
        return action.label() + " for this chat: " + e.label();
    }

    private List<Entry> entries(Target target) {
        return switch (target) {
            case TOOLS -> toolEntries();
            case MCP -> sourceEntries(Kind.MCP);
            case API -> sourceEntries(Kind.API);
            case SKILL -> skillEntries();
            case MODE, MODEL -> List.of();
        };
    }

    private List<Entry> toolEntries() {
        Map<String, List<HarnessTool>> byGroup = new LinkedHashMap<>();
        for (HarnessTool t : harness.tools().every()) {
            byGroup.computeIfAbsent(t.group() == null ? "(no group)" : t.group(), g -> new ArrayList<>()).add(t);
        }
        List<Entry> out = new ArrayList<>();
        byGroup.forEach((group, tools) -> {
            long on = tools.stream().filter(t -> harness.tools().isEnabled(t.name())).count();
            out.add(new Entry(group, true, group, on == tools.size(), on + "/" + tools.size() + " on"));
            for (HarnessTool t : tools) {
                boolean enabled = harness.tools().isEnabled(t.name());
                out.add(new Entry(t.name(), false, t.name(), enabled, enabled ? "on" : "off"));
            }
        });
        return out;
    }

    private List<Entry> skillEntries() {
        List<Entry> out = new ArrayList<>();
        for (SkillCatalog.Skill s : harness.skills().all()) {
            boolean on = harness.skills().isEnabled(s.name());
            out.add(new Entry(s.name(), false, s.name(), on, on ? "on" : "off"));
        }
        return out;
    }

    private List<Entry> sourceEntries(Kind kind) {
        List<Entry> out = new ArrayList<>();
        for (ExternalSources.Status s : harness.sources().list(kind)) {
            String detail = s.enabled() ? "on, " + s.tools() + " tools" : "off";
            if (s.error() != null) detail += ", error: " + s.error();
            out.add(new Entry(s.name(), false, s.name(), s.enabled(), detail));
        }
        return out;
    }

    private List<Action> actions(State st) {
        Entry e = st.selected;
        List<Action> out = new ArrayList<>();
        String toggle = e.group() ? (e.on() ? "Disable all" : "Enable all") : (e.on() ? "Disable" : "Enable");
        out.add(new Action(toggle, Op.TOGGLE));
        if (st.target == Target.MCP || st.target == Target.API) out.add(new Action("Check", Op.CHECK));
        return out;
    }

    private List<String> groupTools(String group) {
        return harness.tools().every().stream()
                .filter(t -> (t.group() == null ? "(no group)" : t.group()).equals(group))
                .map(HarnessTool::name).toList();
    }

    private void toggle(List<String> names, boolean enable) {
        if (enable) harness.tools().enable(names);
        else harness.tools().disable(names);
    }

    /** Runs an action and returns the message shown above the refreshed list. */
    private String run(State st, Action action) {
        Entry e = st.selected;
        if (st.target == Target.TOOLS) {
            toggle(e.group() ? groupTools(e.key()) : List.of(e.key()), !e.on());
            return action.label() + ": " + e.label();
        }
        if (st.target == Target.SKILL) {
            harness.skills().setEnabled(e.key(), !e.on());
            return action.label() + ": " + e.label();
        }
        Kind kind = st.target == Target.MCP ? Kind.MCP : Kind.API;
        ExternalSources sources = harness.sources();
        if (action.op() == Op.CHECK) return describe(e.label(), sources.check(kind, e.key()));
        if (e.on()) {
            sources.disable(kind, e.key());
            return "Disabled: " + e.label();
        }
        String error = sources.enable(kind, e.key());
        return error == null ? "Enabled: " + e.label() : "Cannot enable " + e.label() + ": " + error;
    }

    static String describe(String name, ExternalSources.CheckResult r) {
        return (r.ok() ? "OK " : "FAILED ") + name + " (" + r.millis() + " ms): " + r.message();
    }
}
