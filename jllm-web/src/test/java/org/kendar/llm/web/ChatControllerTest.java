package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {

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
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(harness, mapper, new HarnessPricing(0, 0, 0, 0)))
                .build();
    }

    @Test
    void createListGetDelete() throws Exception {
        String created = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).path("chatId").asText();
        assertTrue(!id.isBlank());

        JsonNode list = mapper.readTree(mvc.perform(get("/api/chat")).andReturn().getResponse().getContentAsString());
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
}
