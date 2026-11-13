package org.kendar.llm.web.commons;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.FileChatSessionStore;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.springframework.http.MediaType;
import java.util.Map;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {

    private static final TestUser ADMIN = new TestUser("admin", true);

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ModelClient unused = new ModelClient() {
            @Override
            public ModelResponse complete(ModelRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String simpleComplete(String system, String user, int maxTokens) {
                return "";
            }
        };
        AgentHarness harness = AgentHarness.builder()
                .modelClient(unused)
                .sessionStore(new InMemoryChatSessionStore())
                .objectMapper(mapper)
                .build();
        mvc = MockMvcBuilders.standaloneSetup(TestUser.chatController(harness, mapper))
                .build();
    }

    @Test
    void createListGetDelete() throws Exception {
        String created = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .requestAttr(WebPrincipal.ATTR, ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).path("chatId").asText();
        assertTrue(!id.isBlank());

        JsonNode list = mapper.readTree(mvc.perform(get("/api/chat").requestAttr(WebPrincipal.ATTR, ADMIN))
                .andReturn().getResponse().getContentAsString());
        assertEquals(1, list.size());

        mvc.perform(get("/api/chat/" + id)).andExpect(status().isOk());
        mvc.perform(post("/api/chat/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/chat/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/chat/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void commandsAreListed() throws Exception {
        JsonNode cmds = mapper.readTree(mvc.perform(get("/api/chat/commands")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(cmds.size() > 0);
    }

    @Test
    void listsAndLoadsChatsOfAPrePopulatedRoot(@TempDir java.nio.file.Path root) throws Exception {
        String use = "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"bash\",\"input\":{}}]}";
        new FileChatSessionStore(root, mapper).save(ChatSessionData.create("c1", "agent-c1", "Fix login", "admin")
                .withMessagesJson("{\"role\":\"user\",\"content\":\"hi\"}\n" + use));

        ModelClient unused = new ModelClient() {
            @Override
            public ModelResponse complete(ModelRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String simpleComplete(String system, String user, int maxTokens) {
                return "";
            }
        };
        AgentHarness harness = AgentHarness.builder().modelClient(unused)
                .sessionStore(new FileChatSessionStore(root, mapper)).objectMapper(mapper).build();
        MockMvc restarted = MockMvcBuilders.standaloneSetup(
                TestUser.chatController(harness, mapper)).build();

        JsonNode list = mapper.readTree(restarted.perform(get("/api/chat").requestAttr(WebPrincipal.ATTR, ADMIN))
                .andReturn().getResponse().getContentAsString());
        assertEquals(1, list.size());
        assertEquals("c1", list.get(0).path("chatId").asText());
        assertEquals("Fix login", list.get(0).path("title").asText());
        assertEquals(2, list.get(0).path("messageCount").asInt());

        JsonNode chat = mapper.readTree(restarted.perform(get("/api/chat/c1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(3, chat.path("messages").size(), "the interrupted call got its synthetic result");
        assertTrue(chat.path("messages").get(2).toString().contains("interrupted: process stopped"));
        restarted.perform(get("/api/chat/c1/references")).andExpect(status().isOk());
        restarted.perform(get("/api/chat/none/references")).andExpect(status().isNotFound());
    }
}
