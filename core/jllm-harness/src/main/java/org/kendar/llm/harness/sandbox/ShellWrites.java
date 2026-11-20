package org.kendar.llm.harness.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the files a shell command line writes: output redirections ({@code > f}, {@code >> f}, {@code 2> f},
 * {@code &> f}), {@code tee}, {@code touch}, the destination of {@code cp}/{@code mv}/{@code install}/{@code ln} and of
 * {@code copy}/{@code move}, {@code dd of=}, the files of {@code sed -i}, and the PowerShell cmdlets that write files
 * ({@code Out-File}, {@code Set-Content}, {@code Add-Content}, {@code New-Item}, {@code Tee-Object},
 * {@code Copy-Item}, {@code Move-Item}). Lets the write deny rules cover the shell too. A guard rail, not a security
 * boundary (variables, scripts, interpreters): use an isolating sandbox executor for real containment.
 */
public final class ShellWrites {

    private ShellWrites() {
    }

    /** {@code >}, {@code >>}, {@code N>}, {@code N>>}, {@code &>}, {@code &>>}, {@code >|}; not {@code >&N}. */
    private static final Pattern REDIRECT = Pattern.compile(
            "(?<![<>&])(?:&|\\d)?>>?\\|?\\s*(\"[^\"]*\"|'[^']*'|[^\\s;&|<>()]+)");

    /** Redirection targets that write nothing. */
    private static final java.util.Set<String> DISCARD = java.util.Set.of("/dev/null", "nul", "$null");

    /** Command separators: a new command starts after them. */
    private static final Pattern SEPARATOR = Pattern.compile("\\|\\||&&|[;|\\n]");

    /** @return the paths the command writes, as written in the command (unquoted); empty when none is found */
    public static List<String> targets(String command) {
        List<String> out = new ArrayList<>();
        if (command == null || command.isBlank()) return out;
        Matcher m = REDIRECT.matcher(command);
        while (m.find()) {
            String t = unquote(m.group(1));
            if (!t.isEmpty() && !t.startsWith("&") && !DISCARD.contains(t.toLowerCase(Locale.ROOT))) out.add(t);
        }
        for (String segment : SEPARATOR.split(command.replaceAll(REDIRECT.pattern(), " "))) {
            List<String> words = words(segment);
            if (words.isEmpty()) continue;
            String cmd = words.get(0);
            int slash = Math.max(cmd.lastIndexOf('/'), cmd.lastIndexOf('\\'));
            cmd = cmd.substring(slash + 1).toLowerCase(Locale.ROOT);
            if (cmd.endsWith(".exe")) cmd = cmd.substring(0, cmd.length() - 4);
            List<String> args = words.subList(1, words.size());
            switch (cmd) {
                case "tee", "touch" -> out.addAll(positional(args));
                case "cp", "mv", "install", "ln", "copy", "move", "xcopy", "robocopy" -> last(positional(args), out);
                case "dd" -> {
                    for (String a : args) if (a.startsWith("of=")) out.add(unquote(a.substring(3)));
                }
                case "sed" -> {
                    if (args.stream().anyMatch(a -> a.startsWith("-i") || a.startsWith("--in-place"))) {
                        List<String> p = positional(args);
                        boolean scriptByOption = args.stream().anyMatch(a -> a.equals("-e") || a.equals("-f"));
                        out.addAll(scriptByOption ? p : p.subList(Math.min(1, p.size()), p.size()));
                    }
                }
                case "out-file", "set-content", "add-content", "new-item", "tee-object", "sc", "ac", "ni" ->
                        powershell(args, false, out);
                case "copy-item", "move-item", "cpi", "mi" -> powershell(args, true, out);
                default -> {
                }
            }
        }
        return out;
    }

    /** {@code -Path}/{@code -FilePath}/{@code -LiteralPath}/{@code -Destination} values, else a positional path. */
    private static void powershell(List<String> args, boolean destination, List<String> out) {
        List<String> positional = new ArrayList<>();
        boolean named = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.startsWith("-")) {
                String name = a.substring(1).toLowerCase(Locale.ROOT);
                int colon = name.indexOf(':');
                String value = colon >= 0 ? unquote(a.substring(colon + 2)) : i + 1 < args.size() ? args.get(i + 1) : null;
                if (colon >= 0) name = name.substring(0, colon);
                boolean takesValue = !name.equals("force") && !name.equals("append") && !name.equals("noclobber")
                        && !name.equals("recurse") && !name.equals("nonewline") && !name.equals("passthru");
                boolean wanted = destination ? name.startsWith("dest")
                        : name.equals("path") || name.equals("filepath") || name.equals("literalpath");
                if (wanted && value != null) {
                    out.add(value);
                    named = true;
                }
                if (takesValue && colon < 0) i++;
            } else {
                positional.add(a);
            }
        }
        if (named) return;
        if (destination) last(positional, out);
        else if (!positional.isEmpty()) out.add(positional.get(0));
    }

    private static List<String> positional(List<String> args) {
        List<String> p = new ArrayList<>();
        for (String a : args) if (!a.startsWith("-")) p.add(a);
        return p;
    }

    private static void last(List<String> p, List<String> out) {
        if (p.size() >= 2) out.add(p.get(p.size() - 1));
    }

    /** Splits on whitespace, keeping quoted parts together and dropping the quotes. */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                any = true;
            } else if (Character.isWhitespace(c)) {
                if (any || !cur.isEmpty()) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(c);
            }
        }
        if (any || !cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static String unquote(String s) {
        String t = s.strip();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
