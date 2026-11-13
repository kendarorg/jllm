package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The settings files: {@link HarnessSettings#globalFile()}, then {@link HarnessSettings#projectFile(Path)}, then the
 * explicit file. Missing global and project files are skipped; a missing explicit file is an error. When the explicit
 * file is the project file it counts as explicit (trusted).
 *
 * @param explicit   the explicit file; null = none
 * @param projectDir the project; null = no project layer
 */
public record FileSettingsSource(Path explicit, Path projectDir) implements SettingsSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<SettingsLayer> layers() {
        Path projectFile = projectDir == null ? null : HarnessSettings.projectFile(projectDir);
        List<SettingsLayer> out = new ArrayList<>();
        add(out, SettingsLayer.Kind.GLOBAL, HarnessSettings.globalFile());
        if (projectFile != null) {
            add(out, projectFile.equals(explicit) ? SettingsLayer.Kind.EXPLICIT : SettingsLayer.Kind.PROJECT,
                    projectFile);
        }
        if (explicit != null) {
            if (!Files.isRegularFile(explicit)) {
                throw new UncheckedIOException("Cannot read settings " + explicit,
                        new FileNotFoundException(explicit.toString()));
            }
            add(out, SettingsLayer.Kind.EXPLICIT, explicit);
        }
        return out;
    }

    private static void add(List<SettingsLayer> out, SettingsLayer.Kind kind, Path file) {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonNode tree = MAPPER.readTree(file.toFile());
            out.add(new SettingsLayer(kind, file.toString(), tree));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read settings " + file, e);
        }
    }
}
