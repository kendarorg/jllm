package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.permission.ApprovalService;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Turns an {@code approval_request} event into a question and the typed answer into a resolution. */
final class ApprovalPrompter {

    /** Where the decision goes (the harness). */
    @FunctionalInterface
    interface Resolver {
        void resolve(String chatId, String id, boolean approved, String edited, boolean remember, boolean rememberDir);
    }

    /** The next typed answer. */
    @FunctionalInterface
    interface Answers {
        String next() throws InterruptedException;
    }

    private final Resolver resolver;
    private final Consumer<String> say;
    private final Consumer<String> ask;
    private final Answers answers;
    private final Path workDir;

    /**
     * @param say     a message line
     * @param ask     a question (the answer is read from {@code answers})
     * @param answers the typed answers; {@link LineCommands#INTERRUPTED} means the question was already rejected
     */
    ApprovalPrompter(Resolver resolver, Consumer<String> say, Consumer<String> ask, Answers answers, Path workDir) {
        this.resolver = resolver;
        this.say = say;
        this.ask = ask;
        this.answers = answers;
        this.workDir = workDir;
    }

    /** The next answer; null when the question was interrupted (already rejected). */
    private String answer() throws InterruptedException {
        String a = answers.next();
        return LineCommands.INTERRUPTED.equals(a) ? null : a;
    }

    void prompt(String chatId, JsonNode d) throws InterruptedException {
        String id = d.path("id").asText();
        String type = d.path("type").asText();
        // title and preview carry model and tool text: no terminal control sequences from them
        say.accept(Renderer.YELLOW + "[approval] " + Renderer.clean(type) + " "
                + Renderer.clean(d.path("title").asText()) + Renderer.RESET + "\n"
                + Renderer.clean(d.path("preview").asText()));
        String host = Renderer.clean(d.path("host").asText());
        switch (type) {
            case "continue" -> {
                ask.accept("Turns to allow [" + Renderer.clean(d.path("proposed").asText()) + ", n = stop] ");
                String t = answer();
                if (t == null) return;
                String v = t.equals(LineCommands.EOF) ? "n" : t.strip();
                boolean stop = v.toLowerCase().startsWith("n");
                resolver.resolve(chatId, id, !stop, v.isEmpty() ? null : v, false, false);
            }
            case "stuck" -> yesNo(chatId, id, "Let the agent continue? [y/N] ");
            case "insecure_cert" -> yesNo(chatId, id,
                    "Continue without checking the certificate of " + host + " (this call only)? [y/N] ");
            case "host" -> {
                ask.accept("Allow " + host + "? [y=once/N/a=this host and its subdomains for this chat] ");
                String h = answer();
                if (h == null) return;
                String ha = h.equals(LineCommands.EOF) ? "" : h.strip().toLowerCase();
                boolean always = ha.startsWith("a");
                resolver.resolve(chatId, id, always || ha.startsWith("y"), null, always, false);
            }
            default -> tool(chatId, id, Renderer.clean(d.path("writeDir").asText("")));
        }
    }

    private void yesNo(String chatId, String id, String question) throws InterruptedException {
        ask.accept(question);
        String c = answer();
        if (c == null) return;
        resolver.resolve(chatId, id, LineCommands.startsWithYes(c), null, false, false);
    }

    private void tool(String chatId, String id, String writeDir) throws InterruptedException {
        ask.accept("Allow? [y/N/a=allow for this chat"
                + (writeDir.isEmpty() ? "" : "/d=allow writes in " + writeDir + "/ and subfolders"
                + ", or 'd <path>' for another folder of the project") + "] ");
        String answer = answer();
        if (answer == null) return;
        String raw = answer.equals(LineCommands.EOF) ? "" : answer.strip();
        String a = raw.toLowerCase();
        boolean remember = a.startsWith("a");
        boolean rememberDir = !writeDir.isEmpty() && a.startsWith("d");
        String typedDir = rememberDir && raw.length() > 1 ? raw.substring(1).strip() : null;
        if (typedDir != null && typedDir.isEmpty()) typedDir = null;
        if (typedDir != null && ApprovalService.grantDirOf(workDir, typedDir) == null) {
            say.accept("[warning] Path not allowed (absolute, '..' or .git): no folder granted");
        }
        boolean ok = remember || rememberDir || a.startsWith("y");
        resolver.resolve(chatId, id, ok, typedDir, remember, rememberDir);
    }
}
