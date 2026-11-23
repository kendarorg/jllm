package org.kendar.llm.js;

import java.nio.file.DirectoryIteratorException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The flavour of GraalJS on the classpath. For diagnostics only: nothing in the plugin behaves differently by edition,
 * what is available is decided by the capability probe.
 */
enum GraalEdition {
    COMMUNITY, ORACLE, UNKNOWN;

    /** Artifacts whose presence on the classpath (Maven metadata of a fat jar or an exploded dependency) says community. */
    private static final List<String> COMMUNITY_ARTIFACTS = List.of(
            "org.graalvm.polyglot/js-community", "org.graalvm.polyglot/js-isolate-community",
            "org.graalvm.js/js-isolate-linux-amd64-community", "org.graalvm.js/js-isolate-linux-aarch64-community",
            "org.graalvm.js/js-isolate-darwin-aarch64-community", "org.graalvm.js/js-isolate-windows-amd64-community");
    private static final List<String> ORACLE_ARTIFACTS = List.of(
            "org.graalvm.polyglot/js-isolate", "org.graalvm.js/js-isolate-linux-amd64",
            "org.graalvm.js/js-isolate-linux-aarch64", "org.graalvm.js/js-isolate-darwin-aarch64",
            "org.graalvm.js/js-isolate-windows-amd64");

    /**
     * Tells the edition from, in order: a manifest file the release leaves in {@code manifestDir} (a file named
     * {@code graaljs*} naming the edition), Maven metadata on the class path, and the engine's implementation name.
     *
     * @param manifestDir folder of the release's GraalJS manifest, or null
     */
    static GraalEdition detect(ClassLoader loader, String implementationName, Path manifestDir) {
        GraalEdition e = fromManifest(manifestDir);
        if (e == UNKNOWN) e = fromMaven(loader);
        if (e == UNKNOWN) e = fromImplementation(implementationName);
        return e;
    }

    private static GraalEdition fromManifest(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return UNKNOWN;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "graaljs*")) {
            for (Path f : files) {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.contains("community")) return COMMUNITY;
                if (n.contains("oracle")) return ORACLE;
            }
        } catch (IOException | DirectoryIteratorException e) {
            return UNKNOWN;
        }
        return UNKNOWN;
    }

    private static GraalEdition fromMaven(ClassLoader loader) {
        if (loader == null) return UNKNOWN;
        for (String a : COMMUNITY_ARTIFACTS) if (hasMetadata(loader, a)) return COMMUNITY;
        for (String a : ORACLE_ARTIFACTS) if (hasMetadata(loader, a)) return ORACLE;
        return UNKNOWN;
    }

    private static boolean hasMetadata(ClassLoader loader, String groupAndArtifact) {
        try (InputStream in = loader.getResourceAsStream("META-INF/maven/" + groupAndArtifact + "/pom.properties")) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static GraalEdition fromImplementation(String name) {
        if (name == null) return UNKNOWN;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("oracle")) return ORACLE;
        if (n.contains("community") || n.contains(" ce")) return COMMUNITY;
        return UNKNOWN;
    }
}
