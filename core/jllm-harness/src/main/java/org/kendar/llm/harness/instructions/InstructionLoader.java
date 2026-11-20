package org.kendar.llm.harness.instructions;

import org.kendar.llm.harness.safety.ContentScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the instruction files (by default KENDAR.md, AGENTS.md, CLAUDE.md) listed in the settings into one block for
 * the system prompt.
 */
public final class InstructionLoader {

    private static final Logger log = LoggerFactory.getLogger(InstructionLoader.class);

    private InstructionLoader() {
    }

    /**
     * One instruction file that was read.
     *
     * @param path the file as listed ({@code ~} expanded)
     * @param text     its content, stripped
     * @param findings what the {@link ContentScanner} found in it (line numbers of the file as read); the text is
     *                 loaded unchanged whatever they are
     */
    public record Loaded(Path path, String text, List<ContentScanner.Finding> findings) {
        public Loaded {
            findings = List.copyOf(findings);
        }
    }

    /** @return the files in order, each under a header; empty when none could be read. See {@link #read}. */
    public static String load(List<String> files) {
        return render(read(files));
    }

    /**
     * Reads the files in order. Missing files are skipped, and so is a file with the same content as one already read
     * (for example a symlink to another of the list).
     */
    public static List<Loaded> read(List<String> files) {
        List<Loaded> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String f : files) {
            Path path = Path.of(f.startsWith("~") ? System.getProperty("user.home") + f.substring(1) : f);
            if (!Files.isRegularFile(path)) {
                log.info("Instruction file {} not found, skipped", path);
                continue;
            }
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                String text = raw.strip();
                if (!seen.add(text)) {
                    log.info("Instruction file {} has the same content as one already loaded, skipped", path);
                    continue;
                }
                out.add(new Loaded(path, text, ContentScanner.scan(path.toString(), raw)));
            } catch (IOException e) {
                log.warn("Cannot read {}: {}", path, e.getMessage());
            }
        }
        return out;
    }

    /** The files in order, each under {@code Instructions from <file name>:}; empty when there are none. */
    public static String render(List<Loaded> loaded) {
        StringBuilder sb = new StringBuilder();
        for (Loaded l : loaded) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append("Instructions from ").append(l.path().getFileName()).append(":\n").append(l.text());
        }
        return sb.toString();
    }
}
