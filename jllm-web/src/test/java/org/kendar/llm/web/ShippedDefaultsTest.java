package org.kendar.llm.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** What ships must be safe by default: no clear-text token override, loopback only, a token key is required. */
class ShippedDefaultsTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();
    private static final Path ROOT = MODULE.getParent();
    private static final Set<String> SKIP = Set.of("tmp", "target", "chats", "node_modules", ".git", "data");

    @Test
    void theShippedApplicationPropertiesDoNotAllowClearTextTokens() throws IOException {
        Path props = MODULE.resolve("src/main/resources/application.properties");
        assertTrue(Files.isRegularFile(props), props.toString());
        String active = activeLines(props);

        assertFalse(active.contains("allow-unencrypted-tokens"), "no clear-text override in the shipped properties:\n" + active);
        assertTrue(active.contains("server.address=127.0.0.1"), "local only by default:\n" + active);
    }

    @Test
    void noShippedConfigurationAllowsClearTextTokens() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String dir : List.of("release/template", "runConfigs", ".run", "jllm-web/src/main")) {
            Path start = ROOT.resolve(dir);
            if (!Files.isDirectory(start)) continue;
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                    return SKIP.contains(d.getFileName().toString()) && !d.equals(start) ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    String n = f.getFileName().toString();
                    boolean config = n.endsWith(".properties") || n.endsWith(".xml") || n.endsWith(".json")
                            || n.endsWith(".cmd") || n.endsWith(".sh") || n.endsWith(".ps1") || n.endsWith(".yml");
                    if (config && a.size() < 1_000_000 && activeLines(f).contains("allow-unencrypted-tokens=true")) {
                        offenders.add(ROOT.relativize(f).toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        assertTrue(offenders.isEmpty(), "shipped with allow-unencrypted-tokens=true: " + offenders);
    }

    @Test
    void theWebSafeRunConfigurationNeedsATokenKeyAndSaysSo() throws IOException {
        Path props = ROOT.resolve("runConfigs/webSafe/application.properties");
        if (!Files.isRegularFile(props)) return;
        String all = Files.readString(props);
        assertTrue(all.contains("use-real-password=true"));
        assertTrue(all.contains("JLLM_TOKEN_KEY"), "the file tells where the key comes from");
        assertFalse(activeLines(props).contains("allow-unencrypted-tokens"));
    }

    /** The lines that are not comments. */
    private static String activeLines(Path f) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String l : Files.readAllLines(f)) {
            if (!l.strip().startsWith("#")) sb.append(l.strip()).append('\n');
        }
        return sb.toString();
    }
}
