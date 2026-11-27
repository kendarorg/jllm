package org.kendar.llm.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JllmWebApplicationArgsTest {

    @Test
    void shortFlagsBecomeJllmProperties() {
        assertArrayEquals(new String[]{"--jllm.sandbox=docker", "--jllm.docker-image=img", "--jllm.docker-scope=user",
                        "--jllm.small-model=true", "--jllm.provider=openai", "--server.port=9", "--jllm.model=m", "--jllm.chats-dir=f", "--debug"},
                JllmWebApplication.normalize(new String[]{"--sandbox", "docker", "--docker-image", "img",
                        "--docker-scope=user", "--small-model", "--provider", "openai", "--server.port=9",
                        "--jllm.model=m", "--chats-dir", "f", "--debug"}));
    }

    @Test
    void theImportFlagTakesAFolder() {
        assertEquals("cfg", JllmWebApplication.importConfig(new String[]{"--provider=x", "--import-config", "cfg"}));
        assertEquals("cfg", JllmWebApplication.importConfig(new String[]{"--import-config=cfg"}));
        assertNull(JllmWebApplication.importConfig(new String[]{"--provider=x"}));
        assertThrows(IllegalArgumentException.class, () -> JllmWebApplication.importConfig(new String[]{"--import-config"}));
        assertThrows(IllegalArgumentException.class,
                () -> JllmWebApplication.importConfig(new String[]{"--import-config", "--provider=x"}));
    }

    @Test
    void anImportRunsOnAPrivateLoopbackPort() {
        assertArrayEquals(new String[]{"--jllm.provider=x", "--jllm.import-config=cfg", "--server.port=0",
                        "--server.address=127.0.0.1"},
                JllmWebApplication.withImport(new String[]{"--jllm.provider=x", "--import-config", "cfg"}, "cfg"));
    }
}
