package org.kendar.llm.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class JllmWebApplicationArgsTest {

    @Test
    void shortFlagsBecomeJllmProperties() {
        assertArrayEquals(new String[]{"--jllm.sandbox=docker", "--jllm.docker-image=img", "--jllm.docker-mode=daemon",
                        "--jllm.small-model=true", "--jllm.provider=openai", "--server.port=9", "--jllm.model=m", "--jllm.settings-file=f", "--debug"},
                JllmWebApplication.normalize(new String[]{"--sandbox", "docker", "--docker-image", "img",
                        "--docker-mode=daemon", "--small-model", "--provider", "openai", "--server.port=9",
                        "--jllm.model=m", "--settings", "f", "--debug"}));
    }
}
