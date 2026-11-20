package org.kendar.llm.harness.safety;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for suspicious content in a file the harness loads and hands to the model (skills, instruction files, agent
 * prompts, the free text of the settings): hidden Unicode, prompt-injection phrasing, remote execution and
 * exfiltration patterns. It only reports: the content is loaded unchanged whatever it finds. Pure: no I/O.
 * <p>
 * The patterns are heuristics kept conservative, so that normal documentation (a README with a {@code curl} example,
 * a skill that says "ignore the build directory") is not flagged; they are in {@link #INJECTION} and
 * {@link #REMOTE_EXECUTION}, one entry per pattern, to be extended there.
 */
public final class ContentScanner {

    /** What a finding is about. */
    public enum Kind {
        /** Zero-width, bidi control, Unicode tag or other invisible format characters. */
        HIDDEN_UNICODE,
        /** Phrasing that tries to override the instructions or to hide something from the user. */
        PROMPT_INJECTION,
        /** A download piped into a shell or an interpreter. */
        REMOTE_EXECUTION,
        /** A long base64 run: content the reader cannot see. */
        ENCODED_BLOB,
        /** A {@code data:} URL. */
        DATA_URL,
        /** A URL together with a secret (token, password, API key, .env) and a way to send it. */
        SECRET_URL
    }

    /**
     * @param file    the file (or the settings file) the finding is in
     * @param line    1-based line of the text that was scanned
     * @param kind    what was found
     * @param excerpt a short piece of the line around it: invisible characters shown as {@code <U+200B>}, values that
     *                look like secrets replaced by {@code <redacted>}
     */
    public record Finding(String file, int line, Kind kind, String excerpt) {
    }

    /** At most this many findings per scanned text: a file full of them needs one look, not a flood. */
    public static final int MAX_FINDINGS = 20;
    private static final int EXCERPT_CHARS = 100;
    private static final int EXCERPT_BEFORE = 30;
    private static final int MIN_BLOB = 200;

    /** Case-insensitive; one entry per pattern. Kept narrow: each needs the whole phrase, not a single word. */
    static final List<Pattern> INJECTION = compile(
            // "ignore all previous instructions", "disregard the above rules", "forget your prior prompt"
            "\\b(ignore|disregard|forget|override)\\s+(all\\s+|any\\s+|the\\s+|your\\s+|of\\s+)*"
                    + "(previous|prior|above|earlier|preceding|system|original)\\s+(instructions?|prompts?|rules|directions)",
            // "disregard your instructions", "disregard any safety rules"
            "\\bdisregard\\s+(all\\s+|any\\s+|the\\s+|your\\s+|these\\s+|those\\s+)*(\\w+\\s+)?(instructions|rules|guidelines)\\b",
            // "you are now DAN", "you are now an unrestricted assistant"; not "you are now in the project folder"
            "\\byou\\s+are\\s+now\\s+(a|an|the|no\\s+longer|dan|unrestricted|jailbroken|free)\\b",
            "\\bnew\\s+system\\s+prompt\\b",
            "\\b(do\\s+not|don'?t|never)\\s+(tell|inform|notify|alert|warn)\\s+the\\s+user\\b",
            "\\bwithout\\s+(telling|informing|notifying|alerting|warning)\\s+the\\s+user\\b",
            "\\bhide\\s+(this|it|that)\\s+from\\s+the\\s+user\\b",
            // "send your system prompt", "print the api key", "upload the .env"; not "print the token count"
            "\\b(reveal|print|send|leak|exfiltrate|upload|post|share|email|output)\\s+(me\\s+)?(your|the|all|any)\\s+"
                    + "(system\\s+prompt|secrets?|api[\\s_-]?keys?|access\\s+keys?|private\\s+keys?|tokens?|credentials|"
                    + "passwords?|\\.env\\b|env(ironment)?\\s+variables)(?!\\s+(count|usage|limit|budget|cost|window))"
    );

    /** Case-insensitive; one entry per pattern. A {@code curl} example alone is fine: only a pipe into a shell is not. */
    static final List<Pattern> REMOTE_EXECUTION = compile(
            "\\b(curl|wget)\\b[^\\n|]*\\|\\s*(sudo\\s+)?(sh|bash|zsh|ksh|dash|python[0-9.]*|perl|ruby|node)\\b",
            "\\b(sh|bash|zsh)\\s+(-c\\s+)?[\"']?\\s*<\\(\\s*(curl|wget)\\b",
            "\\b(sh|bash|zsh)\\s+-c\\s+[\"']?\\$\\(\\s*(curl|wget)\\b",
            "\\b(iex|invoke-expression)\\b[^\\n]*(downloadstring|downloadfile|\\biwr\\b|\\birm\\b|invoke-webrequest|"
                    + "invoke-restmethod|net\\.webclient|https?://)",
            "\\b(iwr|irm|invoke-webrequest|invoke-restmethod)\\b[^\\n]*\\|\\s*(iex|invoke-expression)\\b"
    );

    private static final Pattern BLOB = Pattern.compile("[A-Za-z0-9+/_-]{" + MIN_BLOB + ",}={0,2}");
    private static final Pattern DATA_URL = Pattern.compile(
            "\\bdata:[a-z]+/[a-z0-9.+-]+(;[a-z0-9=.+-]+)*,", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("\\bhttps?://[^\\s<>\"')]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_WORD = Pattern.compile(
            "\\b(secrets?|tokens?|api[\\s_-]?keys?|apikey|access[\\s_-]?keys?|secret[\\s_-]?keys?|private[\\s_-]?keys?|"
                    + "ssh[\\s_-]?keys?|passwords?|passwd|credentials?|cookies?)\\b|\\.env\\b|id_rsa\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SEND = Pattern.compile(
            "\\b(send|sends|post|upload|forward|submit|exfiltrate|leak|curl|wget|invoke-webrequest|iwr|irm)\\b",
            Pattern.CASE_INSENSITIVE);
    /** An environment variable whose name says it holds a secret: {@code $GITHUB_TOKEN}, {@code %API_KEY%}. */
    private static final Pattern SECRET_ENV_REF = Pattern.compile(
            "(\\$\\{?|%)[A-Za-z0-9_]*(TOKEN|SECRET|KEY|PASS|CRED|AUTH)[A-Za-z0-9_]*", Pattern.CASE_INSENSITIVE);

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(token|secret|password|passwd|pwd|api[_-]?key|apikey|key|auth|access[_-]?token|client[_-]?secret)"
                    + "(\\s*[=:]\\s*[\"']?)([^\\s&\"'<>]{4,})");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)(\\s+)([A-Za-z0-9._~+/=-]{8,})");
    private static final Pattern SECRET_LIKE = Pattern.compile("[A-Za-z0-9_+=~-]{20,}");

    /** Free-text settings keys whose values reach the model. */
    static final Set<String> SETTINGS_TEXT_KEYS = Set.of("description", "prompt", "systemPrompt", "instructions",
            "appendSystemPrompt", "whenToUse");

    private ContentScanner() {
    }

    private static List<Pattern> compile(String... regexes) {
        List<Pattern> out = new ArrayList<>();
        for (String r : regexes) out.add(Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        return List.copyOf(out);
    }

    /** The findings of {@code text}, in line order; at most {@link #MAX_FINDINGS}. */
    public static List<Finding> scan(String file, String text) {
        List<Finding> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] lines = text.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length && out.size() < MAX_FINDINGS; i++) {
            scanLine(file, i + 1, lines[i], i == 0, out);
        }
        return out.size() > MAX_FINDINGS ? List.copyOf(out.subList(0, MAX_FINDINGS)) : out;
    }

    /**
     * The findings in the free-text values of a settings document ({@code description}, {@code prompt}, ... at any
     * depth, see {@link #SETTINGS_TEXT_KEYS}); each excerpt starts with the JSON pointer of the value, its line is the
     * line within the value.
     */
    public static List<Finding> scanSettings(String file, JsonNode tree) {
        List<Finding> out = new ArrayList<>();
        walk(file, tree, "", false, out);
        return out.size() > MAX_FINDINGS ? List.copyOf(out.subList(0, MAX_FINDINGS)) : out;
    }

    private static void walk(String file, JsonNode node, String pointer, boolean text, List<Finding> out) {
        if (node == null || out.size() >= MAX_FINDINGS) return;
        if (node.isTextual()) {
            if (!text) return;
            for (Finding f : scan(file, node.asText())) {
                out.add(new Finding(file, f.line(), f.kind(), pointer + ": " + f.excerpt()));
            }
        } else if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                walk(file, e.getValue(), pointer + "/" + e.getKey().replace("~", "~0").replace("/", "~1"),
                        SETTINGS_TEXT_KEYS.contains(e.getKey()), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) walk(file, node.get(i), pointer + "/" + i, text, out);
        }
    }

    private static void scanLine(String file, int line, String s, boolean firstLine, List<Finding> out) {
        int hidden = firstHidden(s, firstLine);
        if (hidden >= 0) out.add(new Finding(file, line, Kind.HIDDEN_UNICODE, excerpt(s, hidden)));
        for (Pattern p : INJECTION) {
            Matcher m = p.matcher(s);
            if (m.find()) {
                out.add(new Finding(file, line, Kind.PROMPT_INJECTION, excerpt(s, m.start())));
                break;
            }
        }
        for (Pattern p : REMOTE_EXECUTION) {
            Matcher m = p.matcher(s);
            if (m.find()) {
                out.add(new Finding(file, line, Kind.REMOTE_EXECUTION, excerpt(s, m.start())));
                break;
            }
        }
        Matcher blob = BLOB.matcher(s);
        while (blob.find()) {
            if (mixed(blob.group())) {
                // never the content: it may be a key
                out.add(new Finding(file, line, Kind.ENCODED_BLOB, "<" + blob.group().length() + " chars of base64>"));
                break;
            }
        }
        Matcher data = DATA_URL.matcher(s);
        if (data.find()) out.add(new Finding(file, line, Kind.DATA_URL, render(data.group()) + "…"));
        Matcher url = URL.matcher(s);
        if (url.find()) {
            String u = url.group();
            int q = u.indexOf('?');
            // a docs page about tokens is fine: the secret must be in the query or be an expanded variable
            boolean secretInUrl = q >= 0 && SECRET_WORD.matcher(u.substring(q)).find()
                    || SECRET_ENV_REF.matcher(u).find();
            boolean secretInLine = SECRET_WORD.matcher(s).find();
            if (secretInUrl || secretInLine && SEND.matcher(s).find()) {
                out.add(new Finding(file, line, Kind.SECRET_URL, excerpt(s, url.start())));
            }
        }
    }

    /** Letters of both cases and digits: a base64 payload, not a row of dashes or a long word. */
    private static boolean mixed(String s) {
        boolean upper = false, lower = false, digit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            upper |= c >= 'A' && c <= 'Z';
            lower |= c >= 'a' && c <= 'z';
            digit |= c >= '0' && c <= '9';
        }
        return upper && lower && digit;
    }

    /** Index of the first hidden character of the line; -1 when there is none. */
    static int firstHidden(String s, boolean firstLine) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);
            if (cp == 0x1F3F4) { // black flag: an emoji flag tag sequence (England, Scotland, Wales) is harmless
                int end = flagTagsEnd(s, i + len);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            if (hidden(cp, s, i, firstLine)) return i;
            i += len;
        }
        return -1;
    }

    /** End of the tag characters after a black flag when they form a short sequence ended by CANCEL TAG; else -1. */
    private static int flagTagsEnd(String s, int from) {
        int i = from;
        int n = 0;
        while (i < s.length() && n <= 8) {
            int cp = s.codePointAt(i);
            if (cp == 0xE007F) return n > 0 ? i + Character.charCount(cp) : -1;
            if (cp < 0xE0020 || cp > 0xE007E) return -1;
            i += Character.charCount(cp);
            n++;
        }
        return -1;
    }

    private static boolean hidden(int cp, String s, int at, boolean firstLine) {
        if (cp == 0xFEFF) return !(firstLine && at == 0); // a BOM at the start of the file is harmless
        if (cp == 0x200D) return !joinsEmoji(s, at);     // zero-width joiner inside an emoji sequence
        if (cp >= 0x200B && cp <= 0x200D || cp == 0x2060) return true;
        if (cp >= 0x202A && cp <= 0x202E || cp >= 0x2066 && cp <= 0x2069) return true;
        if (cp >= 0xE0000 && cp <= 0xE007F) return true;
        if (Character.getType(cp) != Character.FORMAT) return false;
        // harmless format characters: soft hyphen, left/right-to-left and Arabic letter marks
        return cp != 0x00AD && cp != 0x200E && cp != 0x200F && cp != 0x061C;
    }

    /** A zero-width joiner between two pictographs (family, profession emoji): part of the emoji, not hidden text. */
    private static boolean joinsEmoji(String s, int at) {
        if (at == 0 || at + 1 >= s.length()) return false;
        int before = s.codePointBefore(at);
        if (before == 0xFE0F && at - 1 > 0) before = s.codePointBefore(at - 1); // after a variation selector
        int after = s.codePointAt(at + 1);
        return pictograph(before) && pictograph(after);
    }

    private static boolean pictograph(int cp) {
        int t = Character.getType(cp);
        return cp > 0x2000 && (t == Character.OTHER_SYMBOL || t == Character.MODIFIER_SYMBOL);
    }

    /** A short piece of the line from a little before {@code at}: secrets redacted, invisible characters visible. */
    static String excerpt(String line, int at) {
        // redacted before it is cut: a secret cut in half at the edge would no longer look like one
        String before = redact(line.substring(0, at));
        String after = redact(line.substring(at));
        int start = Math.max(0, before.length() - EXCERPT_BEFORE);
        if (start > 0 && Character.isLowSurrogate(before.charAt(start))) start--;
        int end = Math.min(after.length(), EXCERPT_CHARS - (before.length() - start));
        if (end > 0 && end < after.length() && Character.isHighSurrogate(after.charAt(end - 1))) end--;
        String piece = (before.substring(start) + after.substring(0, end)).strip();
        return (start > 0 ? "…" : "") + render(piece) + (end < after.length() ? "…" : "");
    }

    /** Values after {@code token=}, {@code password:}, {@code Bearer} and long token-like runs become {@code <redacted>}. */
    static String redact(String s) {
        String t = SECRET_ASSIGNMENT.matcher(s).replaceAll(m -> Matcher.quoteReplacement(
                m.group(1) + m.group(2) + "<redacted>"));
        t = BEARER.matcher(t).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + "<redacted>"));
        return SECRET_LIKE.matcher(t).replaceAll(m -> {
            String g = m.group();
            boolean letters = false, digits = false;
            for (int i = 0; i < g.length(); i++) {
                char c = g.charAt(i);
                letters |= Character.isLetter(c);
                digits |= Character.isDigit(c);
            }
            // 20+ letters and digits without a path or domain separator: a key, not prose
            return Matcher.quoteReplacement(letters && digits ? "<redacted>" : g);
        });
    }

    /** Invisible, format and control characters (except tab) as {@code <U+XXXX>}. */
    static String render(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            int t = Character.getType(cp);
            if (cp != '\t' && (t == Character.FORMAT || t == Character.CONTROL || cp >= 0xE0000 && cp <= 0xE007F)) {
                sb.append("<U+").append(String.format(Locale.ROOT, "%04X", cp)).append('>');
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }
}
