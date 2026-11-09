package org.kendar.llm.harness.instructions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reads the instruction files (e.g. KENDAR.md) listed in the settings into one block for the system prompt. */
public final class InstructionLoader {

    private static final Logger log = LoggerFactory.getLogger(InstructionLoader.class);

    private InstructionLoader() {
    }

    /** @return the files in order, each under a header; empty when none could be read. Missing files are skipped. */
    public static String load(List<String> files) {
        StringBuilder sb = new StringBuilder();
        for (String f : files) {
            Path path = Path.of(f.startsWith("~") ? System.getProperty("user.home") + f.substring(1) : f);
            if (!Files.isRegularFile(path)) {
                log.info("Instruction file {} not found, skipped", path);
                continue;
            }
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8).strip();
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append("Instructions from ").append(path.getFileName()).append(":\n").append(text);
            } catch (IOException e) {
                log.warn("Cannot read {}: {}", path, e.getMessage());
            }
        }
        return sb.toString();
    }
}
