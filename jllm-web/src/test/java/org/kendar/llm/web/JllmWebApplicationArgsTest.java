package org.kendar.llm.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class JllmWebApplicationArgsTest {

    @Test
    void shortFlagsBecomeJllmProperties() {
        assertArrayEquals(new String[]{"--jllm.sandbox=docker", "--jllm.docker-image=img", "--jllm.docker-scope=user",
                        "--jllm.small-model=true", "--jllm.provider=openai", "--server.port=9", "--jllm.model=m", "--jllm.settings=f", "--debug"},
                JllmWebApplication.normalize(new String[]{"--sandbox", "docker", "--docker-image", "img",
                        "--docker-scope=user", "--small-model", "--provider", "openai", "--server.port=9",
                        "--jllm.model=m", "--settings", "f", "--debug"}));
    }
}
