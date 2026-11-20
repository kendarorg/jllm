package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** The built-in {@code run-command} action, through the platform shell (cmd on Windows, bash or zsh elsewhere). */
class RunCommandTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @TempDir
    Path dir;

    private static JsonNode j(String s) {
        return Json.parse(s.replace('\'', '"'));
    }

    private static Registry builtins() {
        Registry r = new Registry();
        new BuiltinActions().register(r);
        return r;
    }

    private static JsonNode run(ObjectNode with) throws Exception {
        var call = new Registry.ActionCall("run", "step", new FlowContext("run", "u", null), null, with, q -> {
            throw new AssertionError("run-command asks nothing");
        });
        return builtins().action(BuiltinActions.RUN_COMMAND).run(call);
    }

    private ObjectNode with(String command) {
        ObjectNode w = Json.M.createObjectNode();
        w.put("command", command).put("cwd", dir.toString()).put("timeoutSeconds", 60);
        return w;
    }

    /** Three output lines a, b, c. */
    private static String threeLines() {
        return WINDOWS ? "echo a&echo b&echo c" : "echo a; echo b; echo c";
    }

    /** Waits far longer than any test timeout. */
    private static String longWait() {
        return WINDOWS ? "ping -n 60 127.0.0.1 >nul" : "sleep 60";
    }

    @Test
    void exitZeroKeepsTheTail() throws Exception {
        JsonNode r = run(with(threeLines()));
        assertEquals(0, r.path("exitCode").asInt());
        assertFalse(r.path("timedOut").booleanValue());
        assertEquals(threeLines(), r.path("command").asText());
        assertEquals("a\nb\nc", r.path("tail").asText().strip().replace("\r", ""));
        assertTrue(r.path("durationMs").isNumber());
    }

    @Test
    void tailLinesKeepsOnlyTheLast() throws Exception {
        ObjectNode w = with(threeLines());
        w.put("tailLines", 2);
        assertEquals("b\nc", run(w).path("tail").asText().strip().replace("\r", ""));
        w.put("tailLines", 0);
        assertEquals("", run(w).path("tail").asText());
    }

    @Test
    void nonZeroExitIsReported() throws Exception {
        JsonNode r = run(with("exit 3"));
        assertEquals(3, r.path("exitCode").asInt());
        assertFalse(r.path("timedOut").booleanValue());
    }

    @Test
    void runsInTheWorkingDirectory() throws Exception {
        Files.writeString(dir.resolve("marker.txt"), "found");
        JsonNode r = run(with(WINDOWS ? "type marker.txt" : "cat marker.txt"));
        assertEquals(0, r.path("exitCode").asInt());
        assertTrue(r.path("tail").asText().contains("found"), r.toString());
    }

    @Test
    void timeoutKillsTheCommand() throws Exception {
        ObjectNode w = with(longWait());
        w.put("timeoutSeconds", 1);
        long t0 = System.currentTimeMillis();
        JsonNode r = run(w);
        assertTrue(r.path("timedOut").booleanValue(), r.toString());
        assertEquals(-1, r.path("exitCode").asInt());
        assertTrue(System.currentTimeMillis() - t0 < 30_000, "the command was not killed");
    }

    @Test
    void missingCommandOrDirectoryFails() {
        ObjectNode w = with("echo x");
        w.remove("command");
        assertThrows(IllegalArgumentException.class, () -> run(w));
        ObjectNode bad = with("echo x");
        bad.put("cwd", dir.resolve("nope").toString());
        assertThrows(IllegalArgumentException.class, () -> run(bad));
    }

    /** The sample flow's shape: run the tests, gate on the exit code, give the fix step the real output tail. */
    @Test
    void exitCodeGateInAFlow() {
        String flow = """
                {'name':'tests','start':'runtests','limits':{'retries':0},'steps':{
                  'runtests':{'type':'action','action':'run-command',
                              'with':{'command':'{{input.testCommand}}','cwd':'{{input.projectDir}}','timeoutSeconds':60},
                              'next':'testgate'},
                  'testgate':{'type':'decide','by':'java','chooser':'gate',
                              'with':{'from':'steps.runtests','field':'exitCode','type':'exitCode',
                                      'map':{'0':'passed','*':'failed'},'fallback':'failed'},
                              'outcomes':{'passed':'done','failed':'fix'},'default':'fix'},
                  'done':{'type':'finish','result':{'r':'done'}},
                  'fix':{'type':'finish','result':{'r':'fix','tail':'{{steps.runtests.tail}}'}}}}""";
        FlowDef f = FlowDef.parse(flow.replace('\'', '"'));
        var e = FlowEngine.simple(builtins());
        ObjectNode in = Json.M.createObjectNode();
        in.put("projectDir", dir.toString());

        in.put("testCommand", WINDOWS ? "echo 2 tests failed&exit 1" : "echo 2 tests failed; exit 1");
        var failed = e.run(f, in, "u");
        assertEquals(FlowEngine.Status.COMPLETED, failed.status(), String.valueOf(failed.error()));
        assertEquals("fix", failed.result().path("r").asText());
        assertTrue(failed.result().path("tail").asText().contains("2 tests failed"), failed.result().toString());

        in.put("testCommand", "echo all good");
        var passed = e.run(f, in, "u");
        assertEquals("done", passed.result().path("r").asText());
        JsonNode d = passed.history().stream().filter(x -> x.kind().equals("decision")).findFirst().orElseThrow().data();
        assertEquals(0, d.path("detail").path("value").asInt());
        assertEquals("passed", d.path("outcome").asText());
    }
}
