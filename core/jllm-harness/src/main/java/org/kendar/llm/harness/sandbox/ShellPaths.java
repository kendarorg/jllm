package org.kendar.llm.harness.sandbox;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Finds relative paths in a shell command line that climb out of the work dir ({@code type ..\secret.txt},
 * {@code cat ../../etc/passwd}, {@code cd ..}): the file tools refuse them, the shell on the host must not be the way
 * around. A guard rail, not a security boundary (variables, scripts, absolute paths): use an isolating sandbox
 * executor for real containment.
 */
public final class ShellPaths {

    private ShellPaths() {
    }

    /**
     * @param cwd     the directory the command runs in
     * @param roots   the directories the command must stay in (the work dir, the scratch dir); null ones are skipped
     * @return the first path token that resolves outside every root, or null
     */
    public static String escapingPath(String command, Path cwd, Path... roots) {
        if (command == null || cwd == null) return null;
        java.util.List<Path> allowed = new java.util.ArrayList<>();
        for (Path r : roots) if (r != null) allowed.add(r.toAbsolutePath().normalize());
        if (allowed.isEmpty()) return null;
        Path base = cwd.toAbsolutePath().normalize();
        for (String token : command.split("[\\s&|;<>()\"'`=,]+")) {
            if (token.isEmpty() || token.contains("://") || !climbs(token)) continue;
            Path resolved;
            try {
                resolved = base.resolve(token.replace('\\', '/')).normalize();
            } catch (InvalidPathException e) {
                return token; // not a valid path on this OS, yet it climbs: refuse rather than guess
            }
            if (allowed.stream().noneMatch(resolved::startsWith)) return token;
        }
        return null;
    }

    private static boolean climbs(String token) {
        for (String seg : token.split("[/\\\\]")) {
            if (seg.equals("..")) return true;
        }
        return false;
    }
}
