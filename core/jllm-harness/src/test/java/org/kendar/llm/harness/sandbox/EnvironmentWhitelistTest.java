package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.HarnessSettingsLoader;
import org.kendar.llm.harness.util.EnvWhitelist;

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

    private List<String> names(LocalSandboxExecutor box) {
        String out = box.execute("c1", LIST, Duration.ofSeconds(30)).stdout();
        return out.lines().filter(l -> l.contains("=")).map(l -> l.substring(0, l.indexOf('='))).toList();
    }

    @Test
    void onlyTheDefaultVariablesAreInheritedByDefault() {
        LocalSandboxExecutor box = LocalSandboxExecutor.of(config(base));

        java.util.Set<String> allowed = new java.util.HashSet<>();
        EnvWhitelist.defaults().forEach(n -> allowed.add(n.toUpperCase()));
        allowed.addAll(java.util.Set.of("PWD", "_", "SHLVL", "OLDPWD", "PROMPT"));
        assertTrue(names(box).stream().allMatch(n -> allowed.contains(n.toUpperCase())), names(box).toString());
    }

    @Test
    void onlyWhitelistedVariablesReachTheShell() {
        LocalSandboxExecutor box = LocalSandboxExecutor.of(config(base));
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
        LocalSandboxExecutor box = LocalSandboxExecutor.of(config(base));
        box.environmentWhitelist(List.of("*ath"));

        assertTrue(names(box).stream().anyMatch(n -> n.equalsIgnoreCase("PATH")));
        assertFalse(names(box).stream().anyMatch(n -> n.equalsIgnoreCase("USERNAME") || n.equals("HOME")));
    }

    @Test
    void settingsDefaultToTheMinimalSetAndReadBothLists() throws Exception {
        assertEquals(EnvWhitelist.defaults(), HarnessSettings.empty().whitelistedEnvVars());
        assertEquals(EnvWhitelist.defaults(), HarnessSettings.empty().toolsWhitelistedEnvVars());
        assertTrue(EnvWhitelist.defaults().containsAll(List.of("PATH", "HOME", "LANG", "TERM")));
        Path f = base.resolve("s.json");
        Files.writeString(f, "{\"whitelistedEnvVars\":[\"PATH\",\"*key*test\"],\"toolsWhitelistedEnvVars\":[]}");

        HarnessSettings s = HarnessSettingsLoader.load(f);

        assertEquals(List.of("PATH", "*key*test"), s.whitelistedEnvVars());
        assertEquals(List.of(), s.toolsWhitelistedEnvVars());
    }
}
