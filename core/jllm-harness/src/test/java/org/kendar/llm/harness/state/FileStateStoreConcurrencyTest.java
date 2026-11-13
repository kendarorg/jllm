package org.kendar.llm.harness.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class FileStateStoreConcurrencyTest {

    @TempDir
    Path root;

    @Test
    void concurrentSavesOfOneDocumentNeverFailOrLeaveTemporaryFiles() throws Exception {
        ObjectMapper m = new ObjectMapper();
        // two stores on the same root, as the harness and a tool may have
        FileStateStore s1 = new FileStateStore(root);
        FileStateStore s2 = new FileStateStore(root);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                int n = i;
                FileStateStore s = i % 2 == 0 ? s1 : s2;
                all.add(pool.submit(() -> {
                    s.save("chat", "grants", m.createArrayNode().add("t" + n));
                    assertNotNull(s.load("chat", "grants"));
                }));
            }
            for (Future<?> f : all) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertTrue(s1.load("chat", "grants").get(0).asText().startsWith("t"));
        try (var files = Files.list(root.resolve("chat").resolve("state"))) {
            assertEquals(List.of("grants.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }
}
