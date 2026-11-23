package org.kendar.llm.harness.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.tools.ExternalLinks;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** The loop gives every tool call the {@code files.externalLinks} of the configuration. */
class ExternalLinksLoopTest {

    @TempDir
    Path base;

    @Test
    void toolsSeeTheConfiguredMode() {
        List<ExternalLinks> seen = new ArrayList<>();
        for (ExternalLinks mode : ExternalLinks.values()) {
            Rig rig = new Rig(base.resolve(mode.name())).configure(c -> c.withExternalLinks(mode));
            List<ExternalLinks> got = new CopyOnWriteArrayList<>();
            rig.register(tool("probe", "Misc", ToolKind.READ_ONLY, (c, in) -> {
                got.add(c.links());
                return "ok";
            }));
            String chat = rig.newChat();
            rig.model.enqueue(toolUse("t1", "probe", "{}"));
            rig.model.enqueue(text("done"));

            rig.loop.run(chat, "go", new Events(), () -> false, null);

            assertEquals(List.of(mode), got);
            seen.add(got.get(0));
        }
        assertEquals(List.of(ExternalLinks.NONE, ExternalLinks.READ, ExternalLinks.READ_WRITE), seen);
    }
}
