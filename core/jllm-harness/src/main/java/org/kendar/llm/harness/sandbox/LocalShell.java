package org.kendar.llm.harness.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The shell the {@code bash} tool runs on this machine ({@code sandbox.shell}). The kind is told by the executable's
 * file name: {@code cmd} (Windows cmd.exe), {@code powershell} / {@code pwsh} (PowerShell), anything else a POSIX-style
 * shell taking {@code -c}. Unset: the OS default ({@code cmd} on Windows, {@code zsh} on macOS, {@code bash}
 * elsewhere).
 *
 * @param executable name on the PATH or absolute path
 */
public record LocalShell(String executable, Kind kind) {

    public enum Kind {CMD, POWERSHELL, POSIX}

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    /** @param setting {@code sandbox.shell}; null or blank = the OS default */
    public static LocalShell of(String setting) {
        if (setting == null || setting.isBlank()) return osDefault();
        String exe = setting.strip();
        return new LocalShell(exe, kindOf(exe));
    }

    public static LocalShell osDefault() {
        if (OS.contains("win")) return new LocalShell("cmd", Kind.CMD);
        if (OS.contains("mac")) return new LocalShell("zsh", Kind.POSIX);
        return new LocalShell("bash", Kind.POSIX);
    }

    static Kind kindOf(String executable) {
        String name = Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) name = name.substring(0, name.length() - 4);
        return switch (name) {
            case "cmd" -> Kind.CMD;
            case "powershell", "pwsh" -> Kind.POWERSHELL;
            default -> Kind.POSIX;
        };
    }

    /** The process command line that runs {@code command}. */
    public List<String> argv(String command) {
        List<String> a = new ArrayList<>();
        a.add(executable);
        switch (kind) {
            case CMD -> a.add("/c");
            case POWERSHELL -> a.addAll(List.of("-NoProfile", "-NonInteractive", "-Command"));
            case POSIX -> a.add("-c");
        }
        a.add(command);
        return a;
    }

    /** Part of the bash tool description: which shell and syntax. */
    public String description() {
        String name = Path.of(executable).getFileName().toString();
        return switch (kind) {
            case CMD -> "Runs Windows cmd.exe (cmd /c), not bash: use cmd syntax (dir, type, findstr, where, &&, 2>nul). "
                    + "head, tail, grep, ls, cat, sed and /dev/null do not exist.";
            case POWERSHELL -> "Runs " + name + " -Command (PowerShell), not bash: use PowerShell syntax "
                    + "(Get-ChildItem, Get-Content, Select-String, $null)."
                    + (name.toLowerCase(Locale.ROOT).startsWith("pwsh") ? "" : " Windows PowerShell has no && or ||: chain with ;.");
            case POSIX -> "Runs " + name + " -c.";
        };
    }
}
