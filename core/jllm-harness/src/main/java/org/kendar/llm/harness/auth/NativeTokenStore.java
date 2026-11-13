package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tokens in the operating system's secret store, for a single user. The backend is chosen by {@code os.name}:
 * <ul>
 *   <li>macOS: the Keychain (generic passwords, service {@code jllm}), through {@code security -i} with the commands
 *       on stdin, so a secret never appears on a command line;</li>
 *   <li>Windows: DPAPI, current-user scope, through PowerShell with the payload on stdin; the protected blob is kept in
 *       {@code ~/.jllm/auth.dpapi} (Credential Manager caps a secret at 2560 bytes, too small for tokens);</li>
 *   <li>Linux: the Secret Service (GNOME Keyring, KWallet) through {@code secret-tool}, the secret on stdin.</li>
 * </ul>
 * No new dependency: the OS tools are run as processes, each with a 10 s timeout; their errors are reported without
 * the secret. Entries are named {@code <principal or "->/<name>}; the value is the token JSON. OS stores cannot list
 * by prefix portably, so an index entry ({@code -/__index__}, names only) is kept next to the tokens.
 * <p>
 * {@link #crossProcess()} is false: locking is in-process, plus {@link #compareAndPut}. Two jllm processes of the same
 * user refreshing the same token at the same instant can still race (rare for one desktop user). Reads are cached in
 * memory; this process is the only writer it knows of.
 */
public final class NativeTokenStore implements TokenStore {

    private static final Logger log = LoggerFactory.getLogger(NativeTokenStore.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String INDEX = "-/__index__";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Runs an OS command. A seam for tests. */
    @FunctionalInterface
    public interface ProcessRunner {
        record Result(int exit, String out, String err) {
        }

        /** @param stdin written to the process and closed; the only place a secret goes */
        Result run(List<String> command, String stdin, Duration timeout) throws IOException;
    }

    /** One OS secret store. Values are opaque strings (token JSON). */
    public interface Backend {
        String describe();

        Optional<String> read(String entry) throws IOException;

        void write(String entry, String value) throws IOException;

        void delete(String entry) throws IOException;
    }

    private final Backend backend;
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public NativeTokenStore(Backend backend) {
        this.backend = backend;
    }

    public Backend backend() {
        return backend;
    }

    /**
     * The backend of this OS, checked with a write, read and delete of a probe entry; null when none works (the
     * caller falls back to the file store). {@code why} receives the reason of a failure.
     */
    public static NativeTokenStore detect(String osName, Path jllmDir, ProcessRunner runner,
                                          java.util.function.Consumer<String> why) {
        Backend b = backendFor(osName, jllmDir, runner);
        if (b == null) {
            why.accept("no OS secret store is supported on " + osName);
            return null;
        }
        String probe = "-/__probe__" + Long.toHexString(System.nanoTime());
        try {
            b.write(probe, "probe");
            Optional<String> back = b.read(probe);
            b.delete(probe);
            if (back.isEmpty() || !back.get().equals("probe")) {
                why.accept(b.describe() + " did not return what was written");
                return null;
            }
            return new NativeTokenStore(b);
        } catch (IOException | RuntimeException e) {
            why.accept(b.describe() + " is not usable: " + e.getMessage());
            return null;
        }
    }

    public static NativeTokenStore detect(java.util.function.Consumer<String> why) {
        return detect(System.getProperty("os.name", ""), FileTokenStore.defaultDir(), NativeTokenStore::exec, why);
    }

    /** The backend for an OS name, unchecked; null when the OS has none. */
    public static Backend backendFor(String osName, Path jllmDir, ProcessRunner runner) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return new MacKeychain(runner);
        if (os.contains("windows")) return new WindowsDpapi(runner, jllmDir.resolve("auth.dpapi"));
        if (os.contains("linux") || os.contains("bsd")) return new SecretService(runner);
        return null;
    }

    private static String entry(String principalId, String name) {
        return (principalId == null ? "-" : principalId) + "/" + name;
    }

    private Optional<String> read(String entry) {
        return cache.computeIfAbsent(entry, e -> {
            try {
                return backend.read(e);
            } catch (IOException ex) {
                throw new UncheckedIOException(backend.describe() + ": cannot read a token: " + ex.getMessage(), ex);
            }
        });
    }

    private void write(String entry, String value) {
        try {
            if (value == null) backend.delete(entry);
            else backend.write(entry, value);
            cache.put(entry, Optional.ofNullable(value));
        } catch (IOException ex) {
            cache.remove(entry);
            throw new UncheckedIOException(backend.describe() + ": cannot write a token: " + ex.getMessage(), ex);
        }
    }

    private Map<String, List<String>> index() {
        Map<String, List<String>> out = new HashMap<>();
        read(INDEX).ifPresent(text -> {
            try {
                MAPPER.readTree(text).fields().forEachRemaining(e -> {
                    List<String> names = new ArrayList<>();
                    e.getValue().forEach(n -> names.add(n.asText()));
                    out.put(e.getKey(), names);
                });
            } catch (IOException ex) {
                log.warn("{}: token index unreadable; rebuilt on the next login", backend.describe());
            }
        });
        return out;
    }

    private void updateIndex(String principalId, String name, boolean present) {
        String key = principalId == null ? "-" : principalId;
        Map<String, List<String>> idx = index();
        TreeSet<String> names = new TreeSet<>(idx.getOrDefault(key, List.of()));
        boolean changed = present ? names.add(name) : names.remove(name);
        if (!changed) return;
        if (names.isEmpty()) idx.remove(key);
        else idx.put(key, List.copyOf(names));
        ObjectNode root = MAPPER.createObjectNode();
        idx.forEach((k, v) -> {
            ArrayNode a = root.putArray(k);
            v.forEach(a::add);
        });
        write(INDEX, root.toString());
    }

    @Override
    public synchronized Optional<OAuthToken> get(String principalId, String name) {
        return read(entry(principalId, name)).map(NativeTokenStore::parse);
    }

    @Override
    public synchronized void put(String principalId, String name, OAuthToken token) {
        write(entry(principalId, name), format(token));
        updateIndex(principalId, name, true);
    }

    @Override
    public synchronized boolean remove(String principalId, String name) {
        boolean had = read(entry(principalId, name)).isPresent();
        if (had) write(entry(principalId, name), null);
        updateIndex(principalId, name, false);
        return had;
    }

    @Override
    public synchronized List<String> names(String principalId) {
        return List.copyOf(new TreeSet<>(index().getOrDefault(principalId == null ? "-" : principalId, List.of())));
    }

    @Override
    public String describe() {
        return backend.describe();
    }

    /**
     * Moves the single-user tokens of {@code file} (today's {@code auth.json}) here, then renames the file to
     * {@code auth.json.migrated}, so tokens are not kept in two places. Done once: a missing file does nothing.
     *
     * @return the number of tokens moved
     */
    public int migrateFrom(Path file) {
        if (!Files.isRegularFile(file)) return 0;
        FileTokenStore old = FileTokenStore.ofFile(file);
        int n = 0;
        for (String name : old.names(null)) {
            Optional<OAuthToken> t = old.get(null, name);
            if (t.isPresent()) {
                put(null, name, t.get());
                n++;
            }
        }
        try {
            Path to = file.resolveSibling(file.getFileName() + ".migrated");
            Files.move(file, to, StandardCopyOption.REPLACE_EXISTING);
            log.info("Moved {} token(s) from {} to {}; the file is now {}", n, file, describe(), to);
        } catch (IOException e) {
            log.warn("Tokens copied to {}, but {} could not be renamed: {}", describe(), file, e.toString());
        }
        return n;
    }

    static String format(OAuthToken t) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("access", t.access());
        if (t.refresh() != null) n.put("refresh", t.refresh());
        n.put("expiresAt", t.expiresAt());
        ObjectNode x = n.putObject("extra");
        t.extra().forEach(x::put);
        return n.toString();
    }

    static OAuthToken parse(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            Map<String, String> extra = new HashMap<>();
            n.path("extra").fields().forEachRemaining(x -> extra.put(x.getKey(), x.getValue().asText()));
            return new OAuthToken(n.path("access").asText(), n.hasNonNull("refresh") ? n.get("refresh").asText() : null,
                    n.path("expiresAt").asLong(0), extra);
        } catch (IOException e) {
            throw new UncheckedIOException("A stored token is not valid JSON", e);
        }
    }

    /** Runs a process: stdin written and closed, stdout and stderr read, killed after the timeout. */
    static ProcessRunner.Result exec(List<String> command, String stdin, Duration timeout) throws IOException {
        Process p = new ProcessBuilder(command).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread to = Thread.ofVirtual().start(() -> copy(p.getInputStream(), out));
        Thread te = Thread.ofVirtual().start(() -> copy(p.getErrorStream(), err));
        try (OutputStream in = p.getOutputStream()) {
            if (stdin != null) in.write(stdin.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // the process may not read its input
        }
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IOException(command.get(0) + " did not answer in " + timeout.toSeconds() + " s");
            }
            to.join(1000);
            te.join(1000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        return new ProcessRunner.Result(p.exitValue(), out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try (in) {
            in.transferTo(out);
        } catch (IOException ignored) {
            // the process ended
        }
    }

    /** First line of stderr, for messages: OS tools never echo the secret there. */
    private static String why(ProcessRunner.Result r) {
        String e = r.err() == null ? "" : r.err().strip();
        int nl = e.indexOf('\n');
        return "exit " + r.exit() + (e.isEmpty() ? "" : ": " + (nl > 0 ? e.substring(0, nl) : e));
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getDecoder().decode(s.strip()), StandardCharsets.UTF_8);
    }

    /** macOS Keychain through {@code security -i}; the values are base64, so no quoting can break the command. */
    static final class MacKeychain implements Backend {
        private final ProcessRunner runner;

        MacKeychain(ProcessRunner runner) {
            this.runner = runner;
        }

        @Override
        public String describe() {
            return "macOS keychain";
        }

        private static String quote(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        private ProcessRunner.Result run(String line) throws IOException {
            return runner.run(List.of("/usr/bin/security", "-i"), line + "\n", TIMEOUT);
        }

        @Override
        public Optional<String> read(String entry) throws IOException {
            ProcessRunner.Result r = run("find-generic-password -s jllm -a " + quote(entry) + " -w");
            String out = r.out() == null ? "" : r.out().strip();
            if (r.exit() != 0 || out.isEmpty() || r.err() != null && r.err().contains("could not be found")) {
                return Optional.empty();
            }
            try {
                return Optional.of(unb64(out));
            } catch (IllegalArgumentException e) {
                throw new IOException("unexpected keychain value");
            }
        }

        @Override
        public void write(String entry, String value) throws IOException {
            ProcessRunner.Result r = run("add-generic-password -U -s jllm -a " + quote(entry) + " -w " + quote(b64(value)));
            if (r.exit() != 0 || r.err() != null && !r.err().isBlank()) throw new IOException(why(r));
        }

        @Override
        public void delete(String entry) throws IOException {
            run("delete-generic-password -s jllm -a " + quote(entry)); // a missing entry is fine
        }
    }

    /** Linux Secret Service through {@code secret-tool}; the secret goes on stdin. */
    static final class SecretService implements Backend {
        private final ProcessRunner runner;

        SecretService(ProcessRunner runner) {
            this.runner = runner;
        }

        @Override
        public String describe() {
            return "Secret Service (secret-tool)";
        }

        @Override
        public Optional<String> read(String entry) throws IOException {
            ProcessRunner.Result r = runner.run(List.of("secret-tool", "lookup", "service", "jllm", "entry", entry),
                    null, TIMEOUT);
            if (r.exit() != 0 || r.out() == null || r.out().isEmpty()) return Optional.empty();
            return Optional.of(r.out());
        }

        @Override
        public void write(String entry, String value) throws IOException {
            ProcessRunner.Result r = runner.run(List.of("secret-tool", "store", "--label=jllm " + entry, "service",
                    "jllm", "entry", entry), value, TIMEOUT);
            if (r.exit() != 0) throw new IOException(why(r));
        }

        @Override
        public void delete(String entry) throws IOException {
            runner.run(List.of("secret-tool", "clear", "service", "jllm", "entry", entry), null, TIMEOUT);
        }
    }

    /**
     * Windows DPAPI (current user) through PowerShell. All entries live in one protected blob, a JSON object, in
     * {@code auth.dpapi}: only the same Windows user can decrypt it.
     */
    static final class WindowsDpapi implements Backend {
        private static final String PROTECT = "Add-Type -AssemblyName System.Security; "
                + "$i=[Console]::In.ReadToEnd(); $b=[Convert]::FromBase64String($i.Trim()); "
                + "$o=[Security.Cryptography.ProtectedData]::%s($b,$null,"
                + "[Security.Cryptography.DataProtectionScope]::CurrentUser); "
                + "[Console]::Out.Write([Convert]::ToBase64String($o))";

        private final ProcessRunner runner;
        private final Path file;
        /** The decrypted entries as last read or written by this process: one PowerShell call less per access. */
        private ObjectNode cached;

        WindowsDpapi(ProcessRunner runner, Path file) {
            this.runner = runner;
            this.file = file;
        }

        @Override
        public String describe() {
            return "Windows DPAPI (" + file + ")";
        }

        private String call(String method, String base64) throws IOException {
            ProcessRunner.Result r = runner.run(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    String.format(PROTECT, method)), base64, TIMEOUT);
            if (r.exit() != 0 || r.out() == null || r.out().isBlank()) throw new IOException(why(r));
            return r.out().strip();
        }

        private ObjectNode load() throws IOException {
            if (cached != null) return cached.deepCopy();
            if (!Files.isRegularFile(file)) return MAPPER.createObjectNode();
            String blob = Files.readString(file).strip();
            if (blob.isEmpty()) return MAPPER.createObjectNode();
            String plain = unb64(call("Unprotect", blob));
            JsonNode n = MAPPER.readTree(plain);
            cached = n instanceof ObjectNode o ? o : MAPPER.createObjectNode();
            return cached.deepCopy();
        }

        private void save(ObjectNode all) throws IOException {
            String blob = call("Protect", b64(all.toString()));
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "auth", ".tmp");
            FileTokenStore.restrict(tmp);
            Files.writeString(tmp, blob);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            cached = all.deepCopy();
        }

        @Override
        public synchronized Optional<String> read(String entry) throws IOException {
            JsonNode v = load().get(entry);
            return v == null || v.isNull() ? Optional.empty() : Optional.of(v.asText());
        }

        @Override
        public synchronized void write(String entry, String value) throws IOException {
            ObjectNode all = load();
            all.put(entry, value);
            save(all);
        }

        @Override
        public synchronized void delete(String entry) throws IOException {
            ObjectNode all = load();
            if (all.remove(entry) != null) save(all);
        }
    }
}
