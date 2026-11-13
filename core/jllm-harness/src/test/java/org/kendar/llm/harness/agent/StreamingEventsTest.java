package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.llm.CancelToken;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.StreamListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** Deltas reach the front end coalesced and live only; the log and the final text event are unchanged. */
class StreamingEventsTest {

    @TempDir
    Path base;

    @Test
    void coalescingBoundsTheNumberOfEvents() {
        Events ev = new Events();
        long[] now = {0};
        DeltaCoalescer c = new DeltaCoalescer(ev, MAPPER, null, () -> now[0]);
        for (int i = 0; i < 1000; i++) c.onText(1, "x");
        c.flush();
        List<JsonNode> deltas = ev.ofType("text_delta");
        assertTrue(deltas.size() <= 1000 / DeltaCoalescer.MAX_CHARS + 1, "events: " + deltas.size());
        assertEquals(1000, deltas.stream().mapToInt(d -> d.path("text").asText().length()).sum());
        assertEquals(1, deltas.get(0).path("block").asInt());

        // time also flushes
        Events timed = new Events();
        DeltaCoalescer t = new DeltaCoalescer(timed, MAPPER, null, () -> now[0]);
        t.onText(0, "a");
        now[0] += DeltaCoalescer.MAX_MILLIS;
        t.onText(0, "b");
        assertEquals("ab", timed.ofType("text_delta").get(0).path("text").asText());
        t.onReset();
        assertEquals(1, timed.ofType("text_reset").size());
    }

    /** A model that streams its text in pieces before returning it. */
    static final class Streaming implements ModelClient {
        final ModelClient inner;

        Streaming(ModelClient inner) {
            this.inner = inner;
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            return inner.complete(request);
        }

        @Override
        public ModelResponse stream(ModelRequest request, CancelToken cancel, StreamListener listener) {
            ModelResponse r = inner.complete(request);
            for (int i = 0; i < r.content().size(); i++) {
                JsonNode b = r.content().get(i);
                if (!"text".equals(b.path("type").asText())) continue;
                for (String part : b.path("text").asText().split("(?<= )")) listener.onText(i, part);
            }
            return r;
        }

        @Override
        public String simpleComplete(String system, String user, int maxTokens) {
            return inner.simpleComplete(system, user, maxTokens);
        }
    }

    @Test
    void theLoopEmitsDeltasThenTheFinalTextAndTheLogIsTheSame() {
        Rig rig = new Rig(base);
        String chatId = rig.newChat();
        rig.loop = rig.loopWith(new Streaming(rig.model));
        rig.model.enqueue(text("one two three"));
        Events ev = new Events();
        rig.loop.run(chatId, "go", ev, () -> false);

        String streamed = String.join("", ev.ofType("text_delta").stream().map(d -> d.path("text").asText()).toList());
        assertEquals("one two three", streamed);
        assertEquals(1, ev.ofType("text").size());
        assertTrue(ev.types().indexOf("text_delta") < ev.types().indexOf("text"));
        ArrayNode log = rig.sessions.messages(rig.sessions.get(chatId));
        assertEquals("one two three", log.get(1).path("content").get(0).path("text").asText());
    }

    @Test
    void deltasAreDeliveredLiveButNeverReplayed() {
        Rig rig = new Rig(base);
        String chatId = rig.newChat();
        AgentService service = new AgentService(rig.loop, rig.sessions, rig.approvals, MAPPER, rig.config);
        List<AgentEvent> live = Collections.synchronizedList(new ArrayList<>());
        Runnable unsubscribe = service.subscribe(chatId, 0, live::add);
        service.notify(chatId, "warning", MAPPER.createObjectNode().put("message", "kept"));
        service.notify(chatId, "text_delta", MAPPER.createObjectNode().put("text", "piece"));
        service.notify(chatId, "warning", MAPPER.createObjectNode().put("message", "kept too"));
        unsubscribe.run();

        assertEquals(List.of("warning", "text_delta", "warning"), live.stream().map(AgentEvent::type).toList());
        assertEquals(0, live.get(1).seq());
        assertEquals(2, live.get(2).seq()); // the delta took no seq
        List<AgentEvent> replay = new ArrayList<>();
        service.subscribe(chatId, 0, replay::add).run();
        assertEquals(List.of("warning", "warning"), replay.stream().map(AgentEvent::type).toList());
    }
}
