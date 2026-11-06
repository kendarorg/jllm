package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessMetrics;
import org.kendar.llm.harness.context.scope.TodoStore;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.state.FileStateStore;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;
import static org.kendar.llm.harness.context.mnemonic.MnemonicTestSupport.*;

/** The context state of a chat is read from disk on first use after a restart (lazy, no cache filled at startup). */
class FileRestoreTest {

    @TempDir
    Path root;

    private ArrayNode pairs(int n) {
        ArrayNode log = log();
        user(log, "Read all the files and report");
        for (int i = 0; i < n; i++) readPair(log, i);
        return log;
    }

    @Test
    void mnemonicRendersTheSameBytesAfterRebuildingOverTheSameRoot() {
        ArrayNode log = pairs(15);
        MnemonicStrategy before = new MnemonicStrategy(config(), MAPPER, HarnessMetrics.noop(),
                new FileMnemonicStore(root), SubAgentRunLog.noop());
        String view = before.prepare(turn(log, null)).messages().toString();

        MnemonicStrategy after = new MnemonicStrategy(config(), MAPPER, HarnessMetrics.noop(),
                new FileMnemonicStore(root), SubAgentRunLog.noop());
        var v = after.prepare(turn(log, null));

        assertFalse(v.changed(), "no new decision: the stored ones are used");
        assertEquals(view, v.messages().toString());
    }

    @Test
    void todosAreReadFromDiskOnDemand() {
        TodoStore first = new TodoStore(new FileStateStore(root), MAPPER);
        var list = MAPPER.createArrayNode();
        list.addObject().put("content", "write docs").put("status", "in_progress");
        first.replace(CHAT, AGENT, list);

        TodoStore second = new TodoStore(new FileStateStore(root), MAPPER);
        assertEquals("write docs", second.read(CHAT, AGENT).get(0).path("content").asText());
    }
}
