package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.config;

class EnvironmentWhitelistTest {

    @TempDir
    Path base;

    private static final String LIST = System.getProperty("os.name").toLowerCase().contains("win") ? "set" : "env";

    private List<String> names(LocalDirSandboxExecutor box) {
        String out = box.execute("c1", LIST, Duration.ofSeconds(30)).stdout();
        return out.lines().filter(l -> l.contains("=")).map(l -> l.substring(0, l.indexOf('='))).toList();
    }

    @Test
    void allVariablesAreInheritedByDefault() {
        LocalDirSandboxExecutor box = new LocalDirSandboxExecutor(config(base));

        assertTrue(names(box).size() > 3);
    }

    @Test
    void onlyWhitelistedVariablesReachTheShell() {
        LocalDirSandboxExecutor box = new LocalDirSandboxExecutor(config(base));
        box.environmentWhitelist(List.of("PATH"));

        List<String> names = names(box);

        assertFalse(names.isEmpty());
        // the shell itself adds a few of its own (cmd: COMSPEC, PATHEXT, PROMPT, SystemRoot; bash: PWD, SHLVL, ...)
        java.util.Set<String> shellOwn = java.util.Set.of("PATH", "PWD", "_", "SHLVL", "OLDPWD", "COMSPEC", "PATHEXT",
                "PROMPT", "SYSTEMROOT");
        assertTrue(names.stream().allMatch(n -> shellOwn.contains(n.toUpperCase())), names.toString());
        assertTrue(names.stream().anyMatch(n -> n.equalsIgnoreCase("PATH")));
    }

    @Test
    void anEmptyWhitelistPassesNothingAndWildcardsWork() {
        LocalDirSandboxExecutor box = new LocalDirSandboxExecutor(config(base));
        box.environmentWhitelist(List.of("*ath"));

        assertTrue(names(box).stream().anyMatch(n -> n.equalsIgnoreCase("PATH")));
        assertFalse(names(box).stream().anyMatch(n -> n.equalsIgnoreCase("USERNAME") || n.equals("HOME")));
    }

    @Test
    void settingsDefaultToAllAndReadBothLists() throws Exception {
        assertEquals(List.of("*"), HarnessSettings.empty().whitelistedEnvVars());
        assertEquals(List.of("*"), HarnessSettings.empty().toolsWhitelistedEnvVars());
        Path f = base.resolve("s.json");
        Files.writeString(f, "{\"whitelistedEnvVars\":[\"PATH\",\"*key*test\"],\"toolsWhitelistedEnvVars\":[]}");

        HarnessSettings s = HarnessSettings.load(f);

        assertEquals(List.of("PATH", "*key*test"), s.whitelistedEnvVars());
        assertEquals(List.of(), s.toolsWhitelistedEnvVars());
    }
}
