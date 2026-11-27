package org.kendar.llm.web.commons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The chats directory is written by users (their commands run there). What the server keeps for itself (the webauth
 * folder, the SQLite file, the configuration folder, the plugin folder, the flows seed folder) must not be inside it:
 * a user could read or replace it. Compared as canonical paths, symbolic links resolved.
 */
public final class ChatsDirGuard {

    private ChatsDirGuard() {
    }

    /**
     * @param chatsDir    the chats directory
     * @param serverPaths what the server keeps, by name (for the message); null or blank paths are skipped
     * @throws IllegalStateException when one of them is the chats directory or inside it
     */
    public static void check(Path chatsDir, Map<String, Path> serverPaths) {
        Path chats = canonical(chatsDir);
        for (var e : serverPaths.entrySet()) {
            Path p = e.getValue();
            if (p == null) continue;
            Path c = canonical(p);
            if (c.startsWith(chats)) {
                throw new IllegalStateException("Refusing to start: " + e.getKey() + " (" + c + ") is inside jllm.chats-dir ("
                        + chats + "), where the users' commands run. Move it outside, or set jllm.chats-dir elsewhere");
            }
        }
    }

    /**
     * The absolute path with symbolic links resolved as far as it exists; the part that does not exist yet is
     * appended as it is.
     */
    public static Path canonical(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        Path existing = abs;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return abs;
        try {
            Path real = existing.toRealPath();
            return real.resolve(existing.relativize(abs)).normalize();
        } catch (IOException e) {
            return abs;
        }
    }
}
