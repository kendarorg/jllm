package org.kendar.llm.harness.tools;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Extracts the text of a non-text file format for {@code read_file}. Registered through
 * {@code PluginRegistrar.documentReader} or {@code AgentHarnessBuilder.documentReader}.
 */
public interface DocumentReader {

    /** Lower-case file extensions without the dot, e.g. {@code pdf}. */
    Set<String> extensions();

    /**
     * The full text, one logical line per line, with structural markers. The stream was opened through the path jail
     * and is closed by the caller; it is read once from the start.
     *
     * @param in       the content of the file
     * @param fileName the name of the file (last path component), for its extension and messages
     * @throws IOException with a message suitable for the model (corrupt, encrypted, unsupported)
     */
    String extract(InputStream in, String fileName) throws IOException;
}
