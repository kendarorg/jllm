package org.kendar.llm.workflows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Where runs are kept. An interface so a file store and an SQL store (or the web host's plugin storage) can both
 * exist. A run belongs to the user who started it; {@link #list} only ever returns that user's runs.
 */
public interface RunStore {

    void save(RunRecord run);

    Optional<RunRecord> load(String runId);

    List<RunRecord> list(String user);

    /** All runs of all users; for system tasks such as expiring waits, never for user-facing calls. */
    List<RunRecord> listAll();

    /** Runs of {@code user} that wait for an answer: "what is waiting for me". */
    default List<RunRecord> waiting(String user) {
        return list(user).stream().filter(r -> RunRecord.WAITING.equals(r.status)).toList();
    }

    /** Volatile store, for tests and one-shot runs. Copies on save so callers cannot alias stored state. */
    final class Memory implements RunStore {
        private final Map<String, String> data = new ConcurrentHashMap<>();

        @Override
        public void save(RunRecord run) {
            data.put(run.runId, run.toJson().toString());
        }

        @Override
        public Optional<RunRecord> load(String runId) {
            String s = data.get(runId);
            return s == null ? Optional.empty() : Optional.of(RunRecord.fromJson(Json.parse(s)));
        }

        @Override
        public List<RunRecord> list(String user) {
            return listAll().stream().filter(r -> r.user.equals(user)).toList();
        }

        @Override
        public List<RunRecord> listAll() {
            return data.values().stream().map(s -> RunRecord.fromJson(Json.parse(s))).toList();
        }
    }

    /** One JSON file per run, written atomically, so a restart loses nothing. */
    final class Files implements RunStore {
        private final Path dir;

        public Files(Path dir) {
            this.dir = dir;
            try {
                java.nio.file.Files.createDirectories(dir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private Path file(String runId) {
            if (!runId.matches("[A-Za-z0-9-]+")) throw new IllegalArgumentException("Bad run id");
            return dir.resolve(runId + ".json");
        }

        @Override
        public synchronized void save(RunRecord run) {
            try {
                Path tmp = java.nio.file.Files.createTempFile(dir, "run", ".tmp");
                java.nio.file.Files.writeString(tmp, Json.M.writerWithDefaultPrettyPrinter().writeValueAsString(run.toJson()));
                java.nio.file.Files.move(tmp, file(run.runId), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Optional<RunRecord> load(String runId) {
            Path f = file(runId);
            if (!java.nio.file.Files.exists(f)) return Optional.empty();
            try {
                return Optional.of(RunRecord.fromJson(Json.parse(java.nio.file.Files.readString(f))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public List<RunRecord> list(String user) {
            return listAll().stream().filter(r -> r.user.equals(user)).toList();
        }

        @Override
        public List<RunRecord> listAll() {
            try (Stream<Path> s = java.nio.file.Files.list(dir)) {
                return s.filter(p -> p.toString().endsWith(".json")).map(p -> {
                    try {
                        return RunRecord.fromJson(Json.parse(java.nio.file.Files.readString(p)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }).toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
