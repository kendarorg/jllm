package org.kendar.llm.eval;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.DeviceRequest;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * llama.cpp in Docker through Testcontainers. The container is labelled so a later run can find it and compare its
 * fingerprint; Ryuk is disabled by the surefire config so the container outlives the JVM.
 */
final class TestcontainersBackend implements LocalServer.Backend {

    static final String LABEL = "jllm.eval";
    static final String LABEL_FP = "jllm.eval.fingerprint";
    static final String NAME = "jllm-eval-llama";
    private static final int CONTAINER_PORT = 8080;

    private DockerClient docker() {
        return DockerClientFactory.instance().client();
    }

    @Override
    public Optional<LocalServer.Existing> find() {
        List<Container> found = docker().listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(LABEL, "llama")).exec();
        return found.stream().findFirst().map(c -> new LocalServer.Existing(c.getId(),
                "running".equalsIgnoreCase(c.getState()), c.getLabels().getOrDefault(LABEL_FP, "")));
    }

    @Override
    public void remove(String id) {
        docker().removeContainerCmd(id).withForce(true).exec();
    }

    @Override
    public void create(LocalSettings s, String fingerprint) {
        LocalSettings.Model m = s.model();
        List<String> cmd = new ArrayList<>(List.of("-m", "/models/" + m.file(), "--alias", m.alias(),
                "-c", String.valueOf(m.contextTokens()), "--parallel", "1", "--host", "0.0.0.0",
                "--port", String.valueOf(CONTAINER_PORT)));
        if (s.gpu()) cmd.addAll(List.of("-ngl", "999"));
        if (m.extraArgs() != null && !m.extraArgs().isBlank()) cmd.addAll(List.of(m.extraArgs().trim().split("\\s+")));

        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(s.image()))
                .withCommand(cmd.toArray(String[]::new))
                .withFileSystemBind(s.modelsDir(), "/models", BindMode.READ_ONLY)
                .withFileSystemBind(s.cacheDir(), "/cache", BindMode.READ_WRITE)
                .withEnv("LLAMA_CACHE", "/cache")
                .withLabel(LABEL, "llama")
                .withLabel(LABEL_FP, fingerprint)
                .withStartupTimeout(Duration.ofSeconds(s.startupTimeoutSeconds()))
                .withCreateContainerCmdModifier(x -> {
                    x.withName(NAME);
                    if (s.gpu()) {
                        x.getHostConfig().withDeviceRequests(List.of(new DeviceRequest().withDriver("nvidia")
                                .withCount(-1).withCapabilities(List.of(List.of("gpu")))));
                    }
                });
        c.setPortBindings(List.of(s.port() + ":" + CONTAINER_PORT));
        c.start();
    }
}
