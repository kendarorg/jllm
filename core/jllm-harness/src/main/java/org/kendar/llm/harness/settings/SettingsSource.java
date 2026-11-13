package org.kendar.llm.harness.settings;

import java.util.List;

/**
 * Where the settings layers are kept. {@link HarnessSettings#loadLayered(SettingsSource, java.nio.file.Path,
 * java.util.function.Function, ProjectMcpTrust.Prompt, ProjectMcpTrust, java.util.function.Consumer)} merges them;
 * {@link FileSettingsSource} reads the settings files.
 */
@FunctionalInterface
public interface SettingsSource {

    /**
     * The layers present, global first, then project, then explicit; a missing layer is left out.
     *
     * @throws java.io.UncheckedIOException when a layer that must exist cannot be read
     */
    List<SettingsLayer> layers();
}
