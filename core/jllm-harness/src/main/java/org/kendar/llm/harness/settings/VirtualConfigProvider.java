package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;

/**
 * A provider whose configuration is not on a project or chat directory: a database or a server-only directory. The only
 * kind a web host accepts. It can be written (by the administrators and the one-time import).
 */
public interface VirtualConfigProvider extends ConfigProvider {

    /**
     * Replaces the global settings document.
     *
     * @throws IllegalArgumentException when it names a file ({@link PathSettings#check})
     */
    void putSettings(JsonNode tree);

    /** The global settings document; an empty object when there is none. */
    JsonNode settingsDocument();

    /** What the provider keeps on the local disk (the SQLite file, its directory); empty for a remote database. */
    List<Path> serverPaths();

    /** A short name for logs. */
    String describe();
}
