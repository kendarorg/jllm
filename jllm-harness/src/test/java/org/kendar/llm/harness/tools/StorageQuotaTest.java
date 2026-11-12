package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.sandbox.LocalDirSandboxExecutor;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class StorageQuotaTest {

    @TempDir
    Path base;

    private LocalDirSandboxExecutor box;

    private HarnessTool write(long max) {
        box = new LocalDirSandboxExecutor(config(base));
        return new FileSystemAgentTools(null, null, new StorageQuota(max, box)).tools().stream()
                .filter(t -> t.name().equals("write_file")).findFirst().orElseThrow();
    }

    private ToolContext ctx(String chat) {
        return new ToolContext(chat, box.workDir(chat));
    }

    private static JsonNode in(String path, String content, boolean append) {
        return json("{\"path\":\"" + path + "\",\"content\":\"" + content + "\",\"append\":" + append + "}");
    }

    @Test
    void writeAboveTheLimitFailsAndCreatesNoFile() throws Exception {
        HarnessTool w = write(10);
        ToolContext c = ctx("c1");

        w.execute(c, in("a.txt", "12345678", false));
        Exception e = assertThrows(Exception.class, () -> w.execute(c, in("b.txt", "12345", false)));

        assertTrue(e.getMessage().contains("Storage quota exceeded"), e.getMessage());
        assertFalse(Files.exists(c.workDir().resolve("b.txt")));
    }

    @Test
    void rejectedWriteDoesNotChangeTheTotalAndOverwriteCountsTheDelta() throws Exception {
        HarnessTool w = write(10);
        ToolContext c = ctx("c1");

        w.execute(c, in("a.txt", "12345678", false));
        assertThrows(Exception.class, () -> w.execute(c, in("b.txt", "123456", false)));
        w.execute(c, in("a.txt", "1234567890", false)); // grows by 2: exactly at the limit
        assertThrows(Exception.class, () -> w.execute(c, in("a.txt", "x", true)));
        w.execute(c, in("a.txt", "x", false)); // shrinks: frees room
        w.execute(c, in("b.txt", "123456789", false));

        assertEquals("x", Files.readString(c.workDir().resolve("a.txt")));
    }

    @Test
    void appendCountsAndMinusOneMeansNoLimit() throws Exception {
        HarnessTool w = write(5);
        ToolContext c = ctx("c1");
        w.execute(c, in("a.txt", "123", true));
        assertThrows(Exception.class, () -> w.execute(c, in("a.txt", "456", true)));

        HarnessTool free = write(-1);
        free.execute(ctx("c2"), in("big.txt", "x".repeat(10_000), false));
        assertEquals(10_000, Files.size(ctx("c2").workDir().resolve("big.txt")));
    }

    @Test
    void totalIsRecomputedFromDiskAfterARestartAndPerChat() throws Exception {
        HarnessTool w = write(10);
        w.execute(ctx("c1"), in("a.txt", "12345678", false));

        HarnessTool restarted = write(10); // new quota object: counter starts from the directory
        assertThrows(Exception.class, () -> restarted.execute(ctx("c1"), in("b.txt", "123", false)));
        restarted.execute(ctx("c2"), in("b.txt", "123", false)); // another chat has its own storage
    }

    @Test
    void settingsReadStorageMaxBytes() throws Exception {
        Path f = base.resolve("settings.json");
        Files.writeString(f, "{\"storage\":{\"maxBytes\":123}}");

        assertEquals(123, HarnessSettings.load(f).storageMaxBytes());
        assertEquals(-1, HarnessSettings.empty().storageMaxBytes());
    }
}
