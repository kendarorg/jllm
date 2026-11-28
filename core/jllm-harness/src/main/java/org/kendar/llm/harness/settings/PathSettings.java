package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings that name a file: an agent's {@code promptFile}, a skill's {@code path} and {@code instructionFiles}.
 * A virtual configuration has none of them: the text is stored, never read from a path of the server.
 */
public final class PathSettings {

    private PathSettings() {
    }

    /** What in the document names a file, as {@code key} or {@code agents[i].promptFile}; empty when nothing does. */
    public static List<String> find(JsonNode tree) {
        List<String> out = new ArrayList<>();
        if (tree == null || !tree.isObject()) return out;
        if (tree.has("instructionFiles")) out.add("instructionFiles");
        int i = 0;
        for (JsonNode a : tree.path("agents")) {
            if (a.hasNonNull("promptFile")) out.add("agents[" + i + "].promptFile");
            i++;
        }
        i = 0;
        for (JsonNode s : tree.path("skills")) {
            if (s.hasNonNull("path")) out.add("skills[" + i + "].path");
            i++;
        }
        return out;
    }

    /** @throws IllegalArgumentException when the document names a file */
    public static void check(JsonNode tree, String origin) {
        List<String> paths = find(tree);
        if (!paths.isEmpty()) {
            throw new IllegalArgumentException("Settings " + origin + " name files (" + String.join(", ", paths)
                    + "): a virtual configuration holds the text itself, not a path");
        }
    }
}
