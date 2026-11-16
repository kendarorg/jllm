package org.kendar.llm.workflows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CliActionsTest {
    @TempDir
    Path dir;

    /** Listed under META-INF/services in the test resources, like a deployer's jar would. */
    public static final class Provider implements ActionProvider {
        @Override
        public void register(Registry r) {
            r.action("hello", c -> Json.parse("{\"greeting\":\"hi " + c.context().input().path("who").asText() + "\"}"));
        }
    }

    @Test
    void cliRunsFlowUsingProviderAction() {
        var cli = new WorkflowCli(ActionProvider.load(getClass().getClassLoader()));
        var out = new ByteArrayOutputStream();
        Path flows = dir.resolve("flows");
        String flow = """
                {"name":"greet","start":"h","steps":{"h":{"type":"action","action":"hello","next":"e"},
                 "e":{"type":"finish","result":{"g":"{{steps.h.greeting}}"}}}}""";
        var w = new Workflows(ActionProvider.load(getClass().getClassLoader()), new RunStore.Files(dir.resolve("runs")),
                new FlowStore.Files(flows), u -> java.util.Set.of(), "admin");
        w.save("ann", FlowLibrary.PERSONAL, FlowDef.parse(flow));
        cli.run(new String[]{"--dir", dir.toString(), "--user", "ann", "start", "greet", "{\"who\":\"Ann\"}"},
                new ByteArrayInputStream(new byte[0]), new PrintStream(out));
        assertTrue(out.toString().contains("hi Ann"), out.toString());
    }
}
