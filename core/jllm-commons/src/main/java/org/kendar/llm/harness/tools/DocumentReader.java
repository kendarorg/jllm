package org.kendar.llm.harness.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Extracts the text of a non-text file format for {@code read_file}. Registered through
 * {@code PluginRegistrar.documentReader} or {@code AgentHarness.Builder.documentReader}.
 */
public interface DocumentReader {

    /** Lower-case file extensions without the dot, e.g. {@code pdf}. */
    Set<String> extensions();

    /**
     * The full text, one logical line per line, with structural markers. The path is already inside the path jail.
     *
     * @throws IOException with a message suitable for the model (corrupt, encrypted, unsupported)
     */
    String extract(Path file) throws IOException;
}
