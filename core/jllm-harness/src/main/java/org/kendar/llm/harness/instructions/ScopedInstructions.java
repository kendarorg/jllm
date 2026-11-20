package org.kendar.llm.harness.instructions;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.safety.ContentScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Directory-scoped instruction files: when a file tool works on a path inside the work dir, the instruction files
 * (same names as {@code instructionFiles}) found in each directory from the work dir down to that path are added to
 * the conversation, once per chat.
 * <p>
 * The blocks are appended to the message log (never to the system prompt, so the cached prefix is kept). The log is
 * also where the loaded set is kept: a file whose block is in the log is not added again, also after a resume. A
 * block the log no longer holds (summarized away) is added again on the next access below its directory.
 */
public final class ScopedInstructions {

    private static final Logger log = LoggerFactory.getLogger(ScopedInstructions.class);

    /** Default of {@code files.scopedInstructionsDepth}: directory levels below the work dir that are looked at. */
    public static final int DEFAULT_MAX_DEPTH = 8;

    private static final String HEADER = "Scoped instructions from ";
    private static final Pattern BLOCK = Pattern.compile(
            "^" + Pattern.quote(HEADER) + "(.+?), for the work under [^\\n]*:\\n", Pattern.DOTALL);

    private final List<String> names;
    private final int maxDepth;
    private final Set<Path> preloadedPaths = new HashSet<>();
    private final Set<String> preloadedTexts = new HashSet<>();

    /**
     * @param instructionFiles the {@code instructionFiles} setting: their file names are the names looked for
     * @param maxDepth         directory levels below the work dir that are looked at; deeper ones are ignored
     * @param preloaded        the files already in the system prompt: neither they nor a file with the same content
     *                         are added again
     */
    public ScopedInstructions(List<String> instructionFiles, int maxDepth, List<InstructionLoader.Loaded> preloaded) {
        Set<String> n = new LinkedHashSet<>();
        for (String f : instructionFiles) {
            try {
                Path name = Path.of(f).getFileName();
                if (name != null && !name.toString().isBlank() && !name.toString().equals("~")) n.add(name.toString());
            } catch (InvalidPathException e) {
                log.warn("Instruction file name {} is not a valid path, not looked for in subdirectories", f);
            }
        }
        this.names = List.copyOf(n);
        this.maxDepth = Math.max(0, maxDepth);
        for (InstructionLoader.Loaded l : preloaded) {
            preloadedTexts.add(l.text());
            try {
                preloadedPaths.add(l.path().toRealPath());
            } catch (IOException e) {
                preloadedPaths.add(l.path().toAbsolutePath().normalize());
            }
        }
    }

    /** The file names looked for, in the order of the setting. */
    public List<String> names() {
        return names;
    }

    /**
     * One scoped instruction file.
     *
     * @param file the file relative to the work dir, with {@code /} separators
     * @param text     its content, stripped
     * @param findings what the {@link ContentScanner} found in the file as read; empty for a block taken from the
     *                 log. The text is added unchanged whatever they are
     */
    public record Block(String file, String text, List<ContentScanner.Finding> findings) {

        public Block {
            findings = List.copyOf(findings);
        }

        /** The directory of the file relative to the work dir, with a trailing {@code /}; {@code ./} for the root. */
        public String dir() {
            int i = file.lastIndexOf('/');
            return i < 0 ? "./" : file.substring(0, i + 1);
        }

        /** The text added to the conversation: a header naming the file and its directory, then the content. */
        public String render() {
            return HEADER + file + ", for the work under " + dir() + ":\n" + text;
        }

        private int depth() {
            int d = 0;
            for (int i = 0; i < file.length(); i++) if (file.charAt(i) == '/') d++;
            return d;
        }
    }

    /**
     * The path a file tool call works on, relative to the work dir or absolute; {@code ""} for the work dir itself.
     * {@code glob} works on the literal directory its pattern starts with.
     */
    public static String pathOf(String tool, JsonNode input) {
        if (input == null) return "";
        if ("glob".equals(tool)) {
            String pattern = input.path("pattern").asText("").replace('\\', '/');
            int wild = pattern.length();
            for (int i = 0; i < pattern.length(); i++) {
                if ("*?[{".indexOf(pattern.charAt(i)) >= 0) {
                    wild = i;
                    break;
                }
            }
            int slash = pattern.lastIndexOf('/', wild - 1);
            return slash < 0 ? "" : pattern.substring(0, slash);
        }
        return input.hasNonNull("path") ? input.get("path").asText("") : "";
    }

    /** The blocks already in the conversation: text blocks of user messages that start with the scoped header. */
    public static List<Block> inLog(JsonNode messages) {
        List<Block> out = new ArrayList<>();
        if (messages == null) return out;
        for (JsonNode m : messages) {
            if (!"user".equals(m.path("role").asText())) continue;
            for (JsonNode b : m.path("content")) {
                if (!"text".equals(b.path("type").asText())) continue;
                String t = b.path("text").asText("");
                if (!t.startsWith(HEADER)) continue;
                Matcher matcher = BLOCK.matcher(t);
                if (matcher.find()) out.add(new Block(matcher.group(1), t.substring(matcher.end()), List.of()));
            }
        }
        return out;
    }

    /**
     * The instruction files to add for calls that work on {@code paths}: for each path inside {@code root}, the files
     * in the directories from the root down to it (at most {@code maxDepth} levels below the root), that neither the
     * system prompt nor {@code messages} hold yet. Paths outside the root are ignored, and so is a file whose real
     * location is outside the root (a link out of the work dir).
     *
     * @return the new blocks, shallowest directory first; empty when there are none
     */
    public List<Block> discover(Path root, Collection<String> paths, JsonNode messages) {
        if (names.isEmpty() || paths.isEmpty()) return List.of();
        Path rootAbs = root.toAbsolutePath().normalize();
        Path realRoot;
        try {
            realRoot = rootAbs.toRealPath();
        } catch (IOException e) {
            return List.of();
        }
        Map<String, Path> found = new LinkedHashMap<>();
        for (String p : paths) {
            for (Path dir : dirsDown(rootAbs, p)) {
                for (String name : names) {
                    Path f = dir.resolve(name);
                    if (!Files.isRegularFile(f)) continue;
                    found.putIfAbsent(rootAbs.relativize(f).toString().replace('\\', '/'), f);
                }
            }
        }
        if (found.isEmpty()) return List.of();
        Set<String> loadedFiles = new HashSet<>();
        Set<String> loadedTexts = new HashSet<>(preloadedTexts);
        for (Block b : inLog(messages)) {
            loadedFiles.add(b.file());
            loadedTexts.add(b.text().strip());
        }
        List<Block> out = new ArrayList<>();
        for (Map.Entry<String, Path> e : found.entrySet()) {
            if (loadedFiles.contains(e.getKey())) continue;
            try {
                Path real = e.getValue().toRealPath();
                if (!real.startsWith(realRoot) || preloadedPaths.contains(real)) continue;
                String raw = Files.readString(e.getValue(), StandardCharsets.UTF_8);
                String text = raw.strip();
                if (text.isEmpty() || !loadedTexts.add(text)) continue;
                out.add(new Block(e.getKey(), text, ContentScanner.scan(e.getKey(), raw)));
            } catch (IOException | RuntimeException ex) {
                log.warn("Cannot read scoped instruction file {}: {}", e.getValue(), ex.getMessage());
            }
        }
        out.sort(Comparator.comparingInt(Block::depth));
        return out;
    }

    /**
     * The directories from the root down to {@code path} (the path itself when it is a directory, else its parent),
     * at most {@link #maxDepth} levels below the root; empty when the path is outside the root or invalid.
     */
    private List<Path> dirsDown(Path rootAbs, String path) {
        Path target;
        try {
            target = rootAbs.resolve(path).normalize();
        } catch (InvalidPathException e) {
            return List.of();
        }
        if (!target.startsWith(rootAbs)) return List.of();
        if (!target.equals(rootAbs) && !Files.isDirectory(target)) target = target.getParent();
        List<Path> dirs = new ArrayList<>();
        dirs.add(rootAbs);
        if (target.equals(rootAbs)) return dirs;
        Path rel = rootAbs.relativize(target);
        Path cur = rootAbs;
        for (int i = 0; i < rel.getNameCount() && i < maxDepth; i++) {
            cur = cur.resolve(rel.getName(i));
            if (!Files.isDirectory(cur)) break;
            dirs.add(cur);
        }
        return dirs;
    }
}
