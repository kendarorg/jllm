package org.kendar.llm.cli;

import org.kendar.llm.harness.settings.FolderTrust;
import org.kendar.llm.harness.settings.ProjectTrustFilter;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The decision about a project folder, taken before anything in it is read (.jllm/, instruction files), before MCP
 * servers, hooks or git start. A declined question is never stored: an unapproved folder asks again on every start.
 */
final class FolderTrustGate {

    private FolderTrustGate() {
    }

    /**
     * @param trustedProjects the user's {@code trustedProjects} setting (paths)
     * @param ask             asks a yes/no question
     * @param warn            where the refusal is reported
     * @return true when the session may go on
     */
    static boolean allow(Path dir, Map<String, String> args, List<String> trustedProjects, FolderTrust trust,
                         boolean canAsk, Function<String, Boolean> ask, Consumer<String> warn) {
        if (trust.isTrusted(dir)) return true;
        String key = FolderTrust.projectKey(dir);
        boolean listed = false;
        for (String t : trustedProjects) listed |= FolderTrust.projectKey(Path.of(t)).equals(key);
        if (args.containsKey("trust-project") || listed) return true;
        boolean changed = trust.record(dir).isPresent();
        if (!canAsk) {
            warn.accept("The folder " + key + " is not trusted: nothing from it was loaded. Run interactively to "
                    + "trust it, or pass --trust-project, or list it in trustedProjects of ~/.jllm/settings.json.");
            return false;
        }
        Boolean yes = ask.apply((changed ? "The .jllm content of " + key + " changed since you trusted it.\n"
                : "The folder " + key + " is not trusted yet.\n") + describe(dir)
                + "\nIts settings, instruction files, MCP servers and hooks will take effect. Trust this folder?");
        if (!Boolean.TRUE.equals(yes)) {
            warn.accept("Folder " + key + " not trusted: nothing from it was loaded, no session started.");
            return false; // nothing is stored: the next start asks again
        }
        trust.trust(dir, Set.of());
        return true;
    }

    /**
     * True when the project's {@code .jllm/SYSTEM.md} may replace the system prompt: approved for this folder, or
     * approved now when someone can answer (remembered in the folder record).
     */
    static boolean projectSystemPrompt(Path dir, FolderTrust trust, boolean canAsk, Function<String, Boolean> ask,
                                       Consumer<String> warn) {
        if (!Files.isRegularFile(dir.resolve(".jllm").resolve("SYSTEM.md"))) return false;
        String hash = FolderTrust.hashOf(dir);
        boolean approved = trust.record(dir).filter(r -> r.sha256().equals(hash))
                .map(r -> r.loosenings().contains(ProjectTrustFilter.SYSTEM_MD_KEY)).orElse(false);
        if (approved) return true;
        if (canAsk && Boolean.TRUE.equals(ask.apply("The project has .jllm/SYSTEM.md, which replaces the system "
                + "prompt (it loosens the built-in safety rules). Use it?"))) {
            trust.approveLoosening(dir, ProjectTrustFilter.SYSTEM_MD_KEY);
            return true;
        }
        warn.accept("Project .jllm/SYSTEM.md ignored: it was not approved for this folder.");
        return false;
    }

    private static String describe(Path dir) {
        StringBuilder sb = new StringBuilder();
        Path jllm = dir.resolve(".jllm");
        if (Files.isDirectory(jllm)) {
            sb.append(".jllm/ holds:");
            try (DirectoryStream<Path> s = Files.newDirectoryStream(jllm)) {
                int n = 0;
                for (Path p : s) {
                    String name = p.getFileName().toString();
                    if (FolderTrust.RUNTIME_DIRS.contains(name) || n++ >= 15) continue;
                    sb.append(' ').append(name);
                }
            } catch (IOException e) {
                sb.append(" (unreadable)");
            }
            sb.append('\n');
        }
        for (String f : new String[]{"AGENTS.md", "CLAUDE.md", "KENDAR.md"}) {
            if (Files.isRegularFile(dir.resolve(f))) sb.append("instruction file ").append(f).append('\n');
        }
        return sb.toString();
    }
}
