package org.kendar.llm.workflows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OwnershipTest {
    @TempDir
    Path dir;

    private static FlowDef flow(String s) {
        return FlowDef.parse(s.replace('\'', '"'));
    }

    private static final String SIMPLE = "{'name':'%s','start':'e','steps':{'e':{'type':'finish','result':{'r':'%s'}}}}";
    private static final String ASKING = """
            {'name':'ask','start':'q','steps':{
             'q':{'type':'ask','kind':'single','text':'Pick for {{input.who}}','options':{'a':'Alpha','b':'Beta'},'next':'e'},
             'e':{'type':'finish','result':{'picked':'{{steps.q}}'}}}}""";

    private Workflows wf(Registry reg) {
        Map<String, Set<String>> roles = Map.of("root", Set.of("admin"), "ann", Set.of("user"), "bob", Set.of("user"));
        return new Workflows(reg, new RunStore.Files(dir.resolve("runs")), new FlowStore.Files(dir.resolve("flows")),
                u -> roles.getOrDefault(u, Set.of()), "admin");
    }

    @Test
    void usersSeeOwnAndGlobalFlowsAndNeverEachOthersRuns() {
        var w = wf(new Registry());
        w.save("root", FlowLibrary.GLOBAL, flow(SIMPLE.formatted("shared", "g")));
        w.save("ann", FlowLibrary.PERSONAL, flow(SIMPLE.formatted("mine", "a")));
        assertThrows(FlowException.class, () -> w.save("ann", FlowLibrary.GLOBAL, flow(SIMPLE.formatted("x", "x"))));
        assertEquals(2, w.list("ann").size());
        assertEquals(1, w.list("bob").size());
        assertThrows(FlowException.class, () -> w.start("bob", "mine", null, null));
        var r = w.start("ann", "shared", null, null);
        assertEquals("g", r.result().path("r").asText());
        assertEquals(1, w.runs("ann").size());
        assertTrue(w.runs("bob").isEmpty());
        assertThrows(FlowException.class, () -> w.history("bob", r.runId()));
        assertThrows(FlowException.class, () -> w.cancel("bob", r.runId()));
    }

    @Test
    void sameNameNeedsScopeAndCopyIsIndependent() {
        var w = wf(new Registry());
        w.save("root", FlowLibrary.GLOBAL, flow(SIMPLE.formatted("dup", "g")));
        w.save("ann", FlowLibrary.PERSONAL, flow(SIMPLE.formatted("dup", "p")));
        assertThrows(FlowException.class, () -> w.start("ann", "dup", null, null));
        assertEquals("p", w.start("ann", "dup", "personal", null).result().path("r").asText());
        w.copyToPersonal("bob", "dup", "dupcopy");
        w.save("root", FlowLibrary.GLOBAL, flow(SIMPLE.formatted("dup", "changed")));
        assertEquals("g", w.start("bob", "dupcopy", null, null).result().path("r").asText());
    }

    @Test
    void roleRestrictedGlobalFlowAndActionChecksAtSave() {
        var reg = new Registry().action("pay", c -> Json.parse("{}")).restrict("pay", "finance");
        var w = wf(reg);
        String uses = "{'name':'pay','start':'p','steps':{'p':{'type':'action','action':'pay','next':'e'},'e':{'type':'finish'}}%s}";
        assertThrows(FlowException.class, () -> w.save("root", FlowLibrary.GLOBAL, flow(uses.formatted(""))));
        assertThrows(FlowException.class, () -> w.save("root", FlowLibrary.GLOBAL, flow(uses.formatted(",'roles':['user']"))));
        w.save("root", FlowLibrary.GLOBAL, flow(uses.formatted(",'roles':['finance']")));
        assertTrue(w.list("ann").isEmpty(), "restricted: not shared with ann");
        assertThrows(FlowException.class, () -> w.start("ann", "pay", null, null));
    }

    @Test
    void cliStartsPausesAndSecondInstanceResumes() {
        var w = wf(new Registry());
        w.save("ann", FlowLibrary.PERSONAL, flow(ASKING));
        var cli = new WorkflowCli(new Registry());
        String[] base = {"--dir", dir.toString(), "--user", "ann"};
        String out1 = cli(cli, base, "", "start", "ask", "{\"who\":\"Ann\"}");
        assertTrue(out1.contains("paused"), out1);
        String id = out1.lines().filter(l -> l.startsWith("paused")).findFirst().get().split(" ")[1];
        assertTrue(cli(cli, base, "", "inputs", "ask").contains("who"));
        assertTrue(cli(new WorkflowCli(new Registry()), new String[]{"--dir", dir.toString(), "--user", "bob"}, "",
                "cancel", id).contains("error"), "other user cannot touch it");
        String out2 = cli(new WorkflowCli(new Registry()), base, "", "resume", id, "b");
        assertTrue(out2.contains("completed") && out2.contains("\"picked\":\"b\""), out2);
        String out3 = cli(cli, new String[]{"--dir", dir.toString(), "--user", "ann", "--ask"}, "9\n1\n",
                "start", "ask", "{\"who\":\"Ann\"}");
        assertTrue(out3.contains("refused") && out3.contains("\"picked\":\"a\""), out3);
        assertTrue(cli(cli, base, "", "history", id).contains("answer"));
    }

    private static String cli(WorkflowCli cli, String[] base, String stdin, String... cmd) {
        String[] args = new String[base.length + cmd.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(cmd, 0, args, base.length, cmd.length);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        cli.run(args, new ByteArrayInputStream(stdin.getBytes()), new PrintStream(bo));
        return bo.toString();
    }
}
