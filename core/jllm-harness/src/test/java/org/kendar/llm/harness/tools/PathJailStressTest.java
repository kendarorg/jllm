package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.kendar.llm.harness.HarnessTestSupport.json;

/**
 * One thread flips a directory between real and a link to a place outside the work dir, as fast as it can, while
 * another reads and writes through it. Zero escapes: the secret is never read and nothing is written outside.
 * Excluded by default (tag {@code slow}); run with {@code mvn test -DexcludedGroups=none -Dgroups=slow}.
 */
@Tag("slow")
class PathJailStressTest {

    private static final int MILLIS = 5_000;

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() {
        JailTestSupport.reset();
    }

    @Test
    void aDirectoryFlippingBetweenRealAndLinkNeverLeaksOrEscapes() throws Exception {
        assumeTrue(JailTestSupport.canLinkDirs(tmp), "links are not permitted on this machine");
        Path root = Files.createDirectories(tmp.resolve("work"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path real = Files.createDirectories(root.resolve("sub.real"));
        Files.writeString(real.resolve("f.txt"), "INSIDE");
        Files.writeString(outside.resolve("f.txt"), "TOP SECRET");
        Path sub = root.resolve("sub");
        ToolContext ctx = new ToolContext("c1", root);
        FileSystemAgentTools fs = new FileSystemAgentTools();
        HarnessTool read = fs.tools().stream().filter(t -> t.name().equals("read_file")).findFirst().orElseThrow();
        HarnessTool write = fs.tools().stream().filter(t -> t.name().equals("write_file")).findFirst().orElseThrow();

        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger flips = new AtomicInteger();
        Thread flipper = new Thread(() -> {
            while (!stop.get()) {
                try {
                    // sub is a link to outside, then a link to the real directory
                    if (JailTestSupport.linkDir(sub, outside)) {
                        flips.incrementAndGet();
                        Thread.onSpinWait();
                        JailTestSupport.removeLink(sub);
                    }
                    if (JailTestSupport.linkDir(sub, real)) {
                        Thread.onSpinWait();
                        JailTestSupport.removeLink(sub);
                    }
                } catch (IOException e) {
                    // the other thread holds it for a moment: try again
                }
            }
        }, "flipper");
        flipper.start();

        int reads = 0;
        int leaks = 0;
        long end = System.currentTimeMillis() + MILLIS;
        try {
            for (int i = 0; System.currentTimeMillis() < end; i++) {
                try {
                    String out = read.execute(ctx, json("{'path':'sub/f.txt'}"));
                    reads++;
                    if (out.contains("SECRET")) leaks++;
                } catch (Exception e) {
                    // refused or missing while flipped: fine
                }
                try {
                    write.execute(ctx, json("{'path':'sub/w" + (i % 5) + ".txt','content':'x'}"));
                } catch (Exception e) {
                    // same
                }
            }
        } finally {
            stop.set(true);
            flipper.join();
        }

        assertEquals(0, leaks, "the secret was read");
        assertEquals(java.util.List.of("f.txt"), JailTestSupport.files(outside), "something was written outside");
        assertTrue(flips.get() > 0, "the flipper never ran");
        assertTrue(reads >= 0);
    }
}
