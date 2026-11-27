package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.fixture.Fixtures;
import org.kendar.llm.eval.fixture.SampleTools;

import java.util.List;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/** 1.23 Slash commands and plugins: commands do not call the model, plugin tools are offered. */
class CommandsCasesTest extends SuiteBase {

    @Override
    public List<EvalCase> cases() {
        Fixtures plugin = Fixtures.create().sampleTools(new SampleTools());
        return List.of(
                smoke(COMMANDS, "info-commands-do-not-call-the-model")
                        .action("/help", (h, chat) -> h.commands().execute(chat, "/help"))
                        .action("/where", (h, chat) -> h.commands().execute(chat, "/where"))
                        .action("/status", (h, chat) -> h.commands().execute(chat, "/status"))
                        .user("Reply with the single word PONG.")
                        .check(mainRequests(1, 1)).check(answerContains("PONG")).build(),
                full(COMMANDS, "title-command-no-model-call")
                        .action("/title Eval chat", (h, chat) -> h.commands().execute(chat, "/title Eval chat"))
                        .user("Reply with the single word PONG.")
                        .check(mainRequests(1, 1)).check(answerContains("PONG")).build(),
                full(COMMANDS, "plugin-tool-is-offered").harness(plugin.harness()).setup(plugin.setup())
                        .user("Reply with the single word PONG.")
                        .check(toolOffered("time_offset")).check(toolOffered("send_email")).check(answerContains("PONG")).build(),
                full(COMMANDS, "edge-unknown-command-harmless")
                        .action("/nonsense", (h, chat) -> h.commands().execute(chat, "/nonsense"))
                        .user("Reply with the single word PONG.")
                        .check(mainRequests(1, 1)).check(answerContains("PONG")).build(),
                full(COMMANDS, "edge-compact-is-the-model-command")
                        .user("Reply with the single word ONE.")
                        .action("/compact", (h, chat) -> h.commands().execute(chat, "/compact"))
                        .user("Reply with the single word TWO.")
                        .check(answerContains("TWO")).check(noRunError()).build(),
                full(COMMANDS, "risk-plan-command-blocks-writes")
                        .action("/plan", (h, chat) -> h.commands().execute(chat, "/plan"))
                        .user("Create a file named blocked.txt containing x.")
                        .check(fileMissing("blocked.txt")).check(workspaceUnchanged()).risk().build());
    }
}
