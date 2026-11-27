package org.kendar.llm.harness.tools;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SSRF guard of {@code web_fetch} applied to a shell command line, for roles whose sandbox has network access:
 * finds the addresses the command names and tests each with the {@link AddressPolicy}. Caught: URLs
 * ({@code scheme://host}), IPv4 and bracketed IPv6 literals, decimal and hex IPv4 forms and host operands ({@code host},
 * {@code user@host:path}) of the usual network programs ({@code curl, wget, nc, ssh, scp, ping, ...}).
 *
 * <p>A guard rail, not a boundary: a command can build an address from variables, a script or a DNS name found at
 * run time, and a program can follow a redirect on its own. Real containment is the network of the sandbox (no
 * network, or a network without a route to the internal addresses).
 */
public final class ShellAddresses {

    private ShellAddresses() {
    }

    private static final Pattern URL = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s\"'`<>|;&()\\\\]+");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])\\d{1,3}(\\.\\d{1,3}){3}(?!\\.?\\w)");
    private static final Pattern IPV6 = Pattern.compile("\\[[0-9a-fA-F:.]*:[0-9a-fA-F:.]*]");
    private static final Pattern NUMERIC = Pattern.compile("(?i)0x[0-9a-f]{1,8}|\\d{8,10}");
    private static final Pattern HOSTISH = Pattern.compile("(?i)localhost|[a-z0-9-]+(\\.[a-z0-9-]+)+");
    private static final Set<String> NETWORK_PROGRAMS = Set.of(
            "curl", "wget", "nc", "ncat", "netcat", "telnet", "ssh", "scp", "sftp", "ftp", "ping", "ping6", "nmap",
            "dig", "nslookup", "http", "https", "httpie", "socat", "traceroute", "tracepath");
    /** Options whose next token is a value (a file, a header), never a host. */
    private static final Set<String> VALUE_OPTIONS = Set.of(
            "-o", "-O", "-F", "-T", "-d", "-H", "-X", "-A", "-e", "-b", "-c", "-u", "-i", "-l", "-p", "-D",
            "--output", "--data", "--header", "--request", "--user-agent", "--referer", "--cookie", "--user",
            "--output-document", "--post-data", "--post-file", "--upload-file", "--form", "--identity");

    /**
     * @return the refusal text when the command names an address the policy blocks, else null
     */
    public static String blocked(String command, AddressPolicy policy) {
        if (command == null || policy == null || !policy.blocksAnything()) return null;
        for (String host : hosts(command)) {
            try (var ignored = policy.check(URI.create("http://" + (host.indexOf(':') >= 0 && !host.startsWith("[")
                    ? "[" + host + "]" : host) + "/"))) {
                // allowed
            } catch (IllegalStateException | IllegalArgumentException e) {
                return "Command refused: it reaches " + host + " from a shell with network access. "
                        + e.getMessage().replaceFirst("^Access to '[^']*' is refused: ", "Refused: ")
                        + ". Do not try another way to reach that address; tell the user if you need it.";
            }
        }
        return null;
    }

    /** The host parts the command names, in order of appearance, without duplicates. */
    static Set<String> hosts(String command) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = URL.matcher(command);
        while (m.find()) {
            String h = hostOfUrl(m.group());
            // an unparsable URL is refused rather than guessed: the shell may parse it differently
            out.add(h == null ? m.group() : h);
        }
        String rest = URL.matcher(command).replaceAll(" ");
        m = IPV6.matcher(rest);
        while (m.find()) out.add(m.group());
        m = IPV4.matcher(rest);
        while (m.find()) out.add(m.group());
        List<String> tokens = new ArrayList<>(List.of(rest.split("[\\s\"'`<>|;&()]+")));
        boolean network = false;
        String previous = "";
        for (String raw : tokens) {
            if (raw.isEmpty()) continue;
            String t = raw;
            String name = t.contains("/") ? t.substring(t.lastIndexOf('/') + 1) : t;
            if (NETWORK_PROGRAMS.contains(name.toLowerCase(Locale.ROOT).replaceFirst("\\.exe$", ""))) {
                network = true;
                previous = t;
                continue;
            }
            if (network && !t.startsWith("-") && !VALUE_OPTIONS.contains(previous)) {
                String h = operandHost(t);
                if (h != null) out.add(h);
            }
            previous = t;
        }
        return out;
    }

    private static String hostOfUrl(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null || h.isBlank() ? null : h;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code user@host:path}, {@code host:port}, {@code host/path}, a numeric IPv4 form; null for anything else. */
    private static String operandHost(String token) {
        String t = token;
        int at = t.lastIndexOf('@');
        if (at >= 0) t = t.substring(at + 1);
        int cut = t.length();
        for (char c : new char[]{'/', ':', '?', '#'}) {
            int i = t.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        t = t.substring(0, cut);
        if (NUMERIC.matcher(t).matches()) return dotted(t);
        return HOSTISH.matcher(t).matches() ? t : null;
    }

    /** {@code 2130706433} or {@code 0x7f000001} as {@code 127.0.0.1}; the token itself when out of range. */
    private static String dotted(String t) {
        try {
            long v = t.regionMatches(true, 0, "0x", 0, 2) ? Long.parseLong(t.substring(2), 16) : Long.parseLong(t);
            if (v < 0 || v > 0xFFFFFFFFL) return t;
            return ((v >> 24) & 255) + "." + ((v >> 16) & 255) + "." + ((v >> 8) & 255) + "." + (v & 255);
        } catch (NumberFormatException e) {
            return t;
        }
    }
}
