package org.kendar.llm.web.users;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * Writes of the {@code webauth} files: to a temporary file in the same folder, then moved over the target, so a
 * reader never sees half a file. All writes go through one lock: the files are small and written rarely.
 */
public final class JsonFiles {

    static final Object LOCK = new Object();

    private JsonFiles() {
    }

    /** Reads the object at {@code file} (empty object when absent), lets {@code change} edit it, writes it back. */
    public static void update(ObjectMapper mapper, Path file, Consumer<ObjectNode> change) {
        synchronized (LOCK) {
            try {
                ObjectNode node = mapper.createObjectNode();
                if (Files.isRegularFile(file)) {
                    JsonNode read = mapper.readTree(file.toFile());
                    if (read instanceof ObjectNode o) node = o;
                }
                change.accept(node);
                writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Atomic replace of a text file; creates the parent folders. */
    public static void writeString(Path file, String content) {
        synchronized (LOCK) {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = Files.createTempFile(file.getParent(), ".tmp-", ".part");
                try {
                    Files.writeString(tmp, content, StandardCharsets.UTF_8);
                    try {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public static boolean delete(Path file) {
        synchronized (LOCK) {
            try {
                return Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
