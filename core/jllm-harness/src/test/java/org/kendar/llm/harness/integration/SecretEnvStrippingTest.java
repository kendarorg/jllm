package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport;
import org.kendar.llm.harness.sandbox.LocalSandboxExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Secrets of the harness process never reach a child it starts (an MCP stdio server, a shell command), whatever the
 * whitelist says. The environment of a JVM cannot be changed, so the test runs a probe in a second JVM that is started
 * with secrets in its environment.
 */
class SecretEnvStrippingTest {

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @TempDir
    Path tmp;

    /** Child of the probe that writes the names of its environment to the file in args[0]. */
    public static final class EnvDumpServer {
        public static void main(String[] args) throws Exception {
            Set<String> names = new TreeSet<>(System.getenv().keySet());
            Files.writeString(Path.of(args[0]), String.join("\n", names));
            McpStdioClientTest.FakeMcpServer.main(new String[0]);
        }
    }

    /** Runs in the second JVM: {@code mcp <out> <patterns>} or {@code shell <out> <patterns>} (comma separated). */
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            // the Windows launcher expands a bare * in its arguments: "all" stands for it
            List<String> patterns = List.of(args[2].equals("all") ? "*" : args[2]).stream()
                    .flatMap(p -> java.util.Arrays.stream(p.split(","))).toList();
            if (args[0].equals("mcp")) {
                try (McpStdioClient client = new McpStdioClient(new ObjectMapper(), "probe", JAVA,
                        List.of("-cp", System.getProperty("java.class.path"), EnvDumpServer.class.getName(), args[1]),
                        java.util.Map.of(), patterns)) {
                    client.listTools();
                }
            } else {
                Path base = Files.createDirectories(Path.of(args[1]).resolveSibling("shell-base"));
                LocalSandboxExecutor box = LocalSandboxExecutor.of(HarnessTestSupport.config(base));
                box.environmentWhitelist(patterns);
                String out = box.execute("c1", WINDOWS ? "set" : "env", Duration.ofSeconds(30)).stdout();
                Files.writeString(Path.of(args[1]), out);
            }
        }
    }

    /** The variable names the probe's child saw. */
    private Set<String> probe(String mode, String patterns) throws Exception {
        Path out = tmp.resolve(mode + ".txt");
        List<String> cmd = new ArrayList<>(List.of(JAVA, "-cp", System.getProperty("java.class.path"),
                Probe.class.getName(), mode, out.toString(), patterns));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("GITHUB_TOKEN", "ghp_secret");
        pb.environment().put("MY_API_KEY", "k");
        pb.environment().put("DB_PASSWORD", "p");
        pb.environment().put("JLLM_ADMIN", "a");
        pb.environment().put("REPO_REMOTE", "https://user:pw@example.com/r.git");
        pb.environment().put("SAFE_VAR", "ok");
        Process p = pb.start();
        String log = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), log);
        assertEquals(0, p.exitValue(), log);
        Set<String> names = new TreeSet<>();
        for (String l : Files.readString(out).split("\\R")) {
            int eq = l.indexOf('=');
            String n = eq > 0 ? l.substring(0, eq) : l.strip();
            if (!n.isBlank()) names.add(n.toUpperCase(Locale.ROOT));
        }
        return names;
    }

    private static void assertNoSecrets(Set<String> names) {
        for (String secret : List.of("GITHUB_TOKEN", "MY_API_KEY", "DB_PASSWORD", "JLLM_ADMIN", "REPO_REMOTE")) {
            assertFalse(names.contains(secret), secret + " reached the child: " + names);
        }
    }

    @Test
    void mcpStdioServerNeverInheritsSecretsEvenWhenTheWhitelistMatchesThem() throws Exception {
        Set<String> names = probe("mcp", "all");
        assertTrue(names.contains("SAFE_VAR"), "the wildcard lets plain variables through: " + names);
        assertNoSecrets(names);
        Set<String> named = probe("mcp", "GITHUB_TOKEN,REPO_REMOTE,SAFE_VAR");
        assertTrue(named.contains("SAFE_VAR"), named.toString());
        assertNoSecrets(named);
    }

    @Test
    void shellCommandsNeverInheritSecretsEvenWhenTheWhitelistNamesThem() throws Exception {
        Set<String> names = probe("shell", "all");
        assertTrue(names.contains("SAFE_VAR"), "the wildcard lets plain variables through: " + names);
        assertNoSecrets(names);
        Set<String> named = probe("shell", "GITHUB_TOKEN,REPO_REMOTE,SAFE_VAR");
        assertTrue(named.contains("SAFE_VAR"), named.toString());
        assertNoSecrets(named);
    }
}
