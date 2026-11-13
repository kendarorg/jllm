package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.commands.SlashCommands;
import org.kendar.llm.harness.session.ChatSessionData;

import java.io.PrintStream;
import java.util.List;
import java.util.function.Supplier;

/**
 * Decides at startup which chat the CLI opens. The user always chooses; nothing is restored silently.
 * {@code --resume prompt} (default) asks when a saved chat exists, {@code no} starts a new chat, anything else is a
 * chat id (or unique prefix).
 */
final class ResumeChooser {

    /** How many trailing messages are replayed on resume. */
    static final int REPLAY = 6;

    private ResumeChooser() {
    }

    /**
     * @param resume      the {@code --resume} value, null = {@code prompt}
     * @param interactive false for {@code --prompt} runs and piped stdin: never ask
     * @param readLine    next input line, or null at end of input
     * @return the chat id to open, or null to start a new chat
     * @throws IllegalArgumentException for an unknown or ambiguous id given to {@code --resume}
     */
    static String choose(AgentHarness harness, String resume, boolean interactive, Supplier<String> readLine,
                         PrintStream out) {
        String mode = resume == null || resume.isBlank() ? "prompt" : resume.strip();
        if (mode.equals("no")) return null;
        if (!mode.equals("prompt")) return harness.sessions().resolve(mode).chatId();
        if (!interactive) return null;
        List<ChatSessionData> saved = harness.sessions().list();
        if (saved.isEmpty()) return null;
        ChatSessionData newest = saved.get(0);
        boolean several = saved.size() > 1;
        out.println((several ? "Newest chat " : "Previous chat ") + SlashCommands.chatLine(newest)
                + (several ? ", " + (saved.size() - 1) + " more saved." : ""));
        out.print("Continue it? [Y/n" + (several ? "/l" : "") + "] ");
        out.flush();
        String a = read(readLine);
        if (a.isEmpty() || a.startsWith("y")) return newest.chatId();
        if (several && a.startsWith("l")) return menu(saved, readLine, out);
        return null;
    }

    private static String menu(List<ChatSessionData> saved, Supplier<String> readLine, PrintStream out) {
        for (int i = 0; i < saved.size(); i++) out.println((i + 1) + ") " + SlashCommands.chatLine(saved.get(i)));
        out.println("n) New chat");
        out.print("Choose: ");
        out.flush();
        String a = read(readLine);
        if (a.matches("\\d+")) {
            int n = Integer.parseInt(a);
            if (n >= 1 && n <= saved.size()) return saved.get(n - 1).chatId();
        }
        return null;
    }

    private static String read(Supplier<String> readLine) {
        String l = readLine.get();
        return l == null ? "n" : l.strip().toLowerCase();
    }

    /** Prints the last {@link #REPLAY} messages of a resumed chat. */
    static void replay(AgentHarness harness, String chatId, PrintStream out) {
        var messages = harness.sessions().messages(harness.sessions().get(chatId));
        int from = Math.max(0, messages.size() - REPLAY);
        if (from > 0) out.println("... " + from + " earlier message(s)");
        for (int i = from; i < messages.size(); i++) {
            JsonNode m = messages.get(i);
            out.println("[" + m.path("role").asText() + "] " + abbreviate(Renderer.clean(text(m.path("content")))));
        }
    }

    static String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : content) {
            switch (b.path("type").asText()) {
                case "text" -> sb.append(b.path("text").asText());
                case "tool_use" -> sb.append("[tool ").append(b.path("name").asText()).append(']');
                case "tool_result" -> sb.append("[result] ").append(b.path("content").asText());
                default -> { }
            }
            sb.append(' ');
        }
        return sb.toString().strip();
    }

    private static String abbreviate(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > 300 ? one.substring(0, 300) + "..." : one;
    }
}
