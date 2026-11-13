package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/** Stores the decisions next to the chat history: {@code <root>/<chatId>/<agentKey>/mnemonic.json}. */
public class FileMnemonicStore implements MnemonicStore {

    private static final Logger log = LoggerFactory.getLogger(FileMnemonicStore.class);
    static final String FILE = "mnemonic.json";

    private final java.util.function.Function<String, Path> chatRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileMnemonicStore(Path root) {
        this.chatRoot = id -> root.resolve(safe(id));
    }

    /** {@code chatRoot(chatId)/<agentKey>/mnemonic.json} */
    public FileMnemonicStore(java.util.function.Function<String, Path> chatRoot) {
        this.chatRoot = chatRoot;
    }

    /**
     * An unreadable file is quarantined (renamed to {@code mnemonic.json.corrupt-<timestamp>}) and the agent starts
     * over with no decisions: the log itself is intact, only masks and segments are lost. The returned state carries a
     * {@link MnemonicState#warning} for the user.
     */
    @Override
    public MnemonicState load(String chatId, String agentKey) {
        Path f = file(chatId, agentKey);
        if (!Files.isRegularFile(f)) return new MnemonicState();
        try {
            return mapper.readValue(f.toFile(), MnemonicState.class);
        } catch (IOException | RuntimeException e) {
            return quarantine(f, e);
        }
    }

    private MnemonicState quarantine(Path f, Exception cause) {
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now());
        Path moved = f.resolveSibling(FILE + ".corrupt-" + ts);
        String where;
        try {
            Files.move(f, moved, StandardCopyOption.REPLACE_EXISTING);
            where = "moved to " + moved.getFileName();
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot quarantine {}: {}", f, e.getMessage());
            where = "left in place";
        }
        log.warn("Unreadable mnemonic state {} ({}), {}: starting over", f, cause.getMessage(), where);
        MnemonicState fresh = new MnemonicState();
        fresh.warning = "The saved context decisions (" + FILE + ") could not be read and were " + where
                + "; masking starts over from the full history.";
        return fresh;
    }

    @Override
    public void save(String chatId, String agentKey, MnemonicState state) {
        Path f = file(chatId, agentKey);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(FILE + ".tmp");
            mapper.writeValue(tmp.toFile(), state);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + f, e);
        }
    }

    @Override
    public void delete(String chatId) {
        Path dir = chatRoot.apply(chatId);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(p -> p.getFileName().toString().startsWith(FILE))
                    .sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete mnemonic state of chat " + chatId, e);
        }
    }

    private Path file(String chatId, String agentKey) {
        return chatRoot.apply(chatId).resolve(safe(agentKey)).resolve(FILE);
    }

    /** One directory-safe path segment. */
    static String safe(String id) {
        String s = id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "_");
        return s.isEmpty() ? "_" : s;
    }
}
