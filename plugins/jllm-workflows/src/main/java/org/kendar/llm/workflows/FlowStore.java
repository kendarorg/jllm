package org.kendar.llm.workflows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Where flow definitions are kept: one set per user plus the global set. Edited and deleted in place, no versions.
 * An interface so a file store and an SQL store (or the host's plugin storage) can both exist.
 */
public interface FlowStore {
    List<FlowDef> listGlobal();

    List<FlowDef> listPersonal(String user);

    Optional<FlowDef> get(String scope, String user, String name);

    void save(String scope, String user, FlowDef flow);

    void delete(String scope, String user, String name);

    /** Volatile store for tests. */
    final class Memory implements FlowStore {
        private final Map<String, String> data = new ConcurrentHashMap<>();

        private static String key(String scope, String user, String name) {
            return FlowLibrary.GLOBAL.equals(scope) ? "g/" + name : "p/" + user + "/" + name;
        }

        @Override
        public List<FlowDef> listGlobal() {
            return data.entrySet().stream().filter(e -> e.getKey().startsWith("g/"))
                    .map(e -> FlowDef.parse(e.getValue())).toList();
        }

        @Override
        public List<FlowDef> listPersonal(String user) {
            return data.entrySet().stream().filter(e -> e.getKey().startsWith("p/" + user + "/"))
                    .map(e -> FlowDef.parse(e.getValue())).toList();
        }

        @Override
        public Optional<FlowDef> get(String scope, String user, String name) {
            String s = data.get(key(scope, user, name));
            return s == null ? Optional.empty() : Optional.of(FlowDef.parse(s));
        }

        @Override
        public void save(String scope, String user, FlowDef flow) {
            data.put(key(scope, user, flow.name()), flow.doc().toString());
        }

        @Override
        public void delete(String scope, String user, String name) {
            data.remove(key(scope, user, name));
        }
    }

    /** {@code dir/global/<name>.json} and {@code dir/users/<user>/<name>.json}. */
    final class Files implements FlowStore {
        private final Path dir;

        public Files(Path dir) {
            this.dir = dir;
        }

        private static String safe(String s) {
            if (s == null || !s.matches("[A-Za-z0-9._@-]+") || s.contains("..")) {
                throw new FlowException(FlowException.Kind.INVALID_FLOW, "Bad name '" + s + "'");
            }
            return s;
        }

        private Path folder(String scope, String user) {
            return FlowLibrary.GLOBAL.equals(scope) ? dir.resolve("global") : dir.resolve("users").resolve(safe(user));
        }

        private List<FlowDef> all(Path folder) {
            if (!java.nio.file.Files.isDirectory(folder)) return List.of();
            try (Stream<Path> s = java.nio.file.Files.list(folder)) {
                List<FlowDef> out = new ArrayList<>();
                for (Path p : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    out.add(FlowDef.load(p));
                }
                return out;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public List<FlowDef> listGlobal() {
            return all(folder(FlowLibrary.GLOBAL, null));
        }

        @Override
        public List<FlowDef> listPersonal(String user) {
            return all(folder(FlowLibrary.PERSONAL, user));
        }

        @Override
        public Optional<FlowDef> get(String scope, String user, String name) {
            Path f = folder(scope, user).resolve(safe(name) + ".json");
            try {
                return java.nio.file.Files.exists(f) ? Optional.of(FlowDef.load(f)) : Optional.empty();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void save(String scope, String user, FlowDef flow) {
            try {
                Path d = folder(scope, user);
                java.nio.file.Files.createDirectories(d);
                java.nio.file.Files.writeString(d.resolve(safe(flow.name()) + ".json"),
                        Json.M.writerWithDefaultPrettyPrinter().writeValueAsString(flow.doc()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void delete(String scope, String user, String name) {
            try {
                java.nio.file.Files.deleteIfExists(folder(scope, user).resolve(safe(name) + ".json"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
