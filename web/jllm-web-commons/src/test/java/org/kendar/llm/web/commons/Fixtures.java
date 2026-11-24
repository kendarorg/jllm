package org.kendar.llm.web.commons;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.AgentHarnessBuilder;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.identity.ChatAccess;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.sandbox.ChatDirLayout;
import org.kendar.llm.harness.sandbox.LocalSandboxExecutor;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A harness over a temp directory with a model that always answers "ok", and an event sink that records. */
final class Fixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final ChatAccess OWNER_OR_ADMIN = (user, chat) -> chat.principal() != null
            && (user.admin() || chat.principal().equals(user.id()));

    record User(String id, boolean admin) implements Principal {
    }

    private Fixtures() {
    }

    static AgentHarness harness(Path dir) {
        ModelClient model = new ModelClient() {
            @Override
            public ModelResponse complete(ModelRequest request) {
                ArrayNode content = MAPPER.createArrayNode();
                content.addObject().put("type", "text").put("text", "ok");
                return new ModelResponse(content, "end_turn", new Usage(10, 5, 0, 0));
            }

            @Override
            public String simpleComplete(String system, String user, int maxTokens) {
                return "";
            }
        };
        return new AgentHarnessBuilder().modelClient(model)
                .sandbox(new LocalSandboxExecutor(new ChatDirLayout(dir)))
                .sessionStore(new InMemoryChatSessionStore()).objectMapper(MAPPER).build();
    }

    static ChatApi chatApi(AgentHarness harness) {
        return new ChatApi(harness, MAPPER, new HarnessPricing(0, 0, 0, 0), OWNER_OR_ADMIN);
    }

    /** Records what is written; can be told the client is gone. */
    static final class RecordingSink implements EventSink {
        record Sent(String id, String event, String data) {
        }

        final List<Sent> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> comments = new ArrayList<>();
        final List<Runnable> closeHooks = new ArrayList<>();
        final List<Runnable> timeoutHooks = new ArrayList<>();
        boolean completed;
        Throwable failure;
        boolean clientGone;

        @Override
        public void send(String id, String event, String data) throws IOException {
            if (clientGone) throw new IOException("Broken pipe");
            sent.add(new Sent(id, event, data));
        }

        @Override
        public void comment(String text) throws IOException {
            if (clientGone) throw new IOException("Broken pipe");
            comments.add(text);
        }

        @Override
        public void complete() {
            completed = true;
            closeHooks.forEach(Runnable::run);
        }

        @Override
        public void completeWithError(Throwable error) {
            failure = error;
            closeHooks.forEach(Runnable::run);
        }

        @Override
        public void onClose(Runnable action) {
            closeHooks.add(action);
        }

        @Override
        public void onTimeout(Runnable action) {
            timeoutHooks.add(action);
        }
    }
}
