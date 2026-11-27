package org.kendar.llm.harness.testsupport.docker;

import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy;
import org.testcontainers.dockerclient.InvalidConfigurationException;
import org.testcontainers.dockerclient.TransportConfig;

import java.net.URI;

/**
 * Testcontainers strategy for a daemon on {@value DockerEndpoints#LOCALHOST_TCP}, registered through
 * {@code META-INF/services}. Testcontainers already tries {@code DOCKER_HOST} first; this one ranks above the unix
 * socket, giving the order of {@link DockerEndpoints}: {@code DOCKER_HOST}, localhost, socket.
 * Needs Testcontainers on the class path (an optional dependency of this module); without it the class is never loaded.
 */
public final class LocalhostTcpDockerStrategy extends DockerClientProviderStrategy {

    /** Above the unix socket and Docker Desktop strategies, below an explicit {@code DOCKER_HOST}. */
    public static final int PRIORITY = EnvironmentAndSystemPropertyClientProviderStrategy.PRIORITY - 15;

    @Override
    public TransportConfig getTransportConfig() throws InvalidConfigurationException {
        if (!DockerEndpoints.reachable(DockerEndpoints.LOCALHOST_TCP)) {
            throw new InvalidConfigurationException("Nothing listens on " + DockerEndpoints.LOCALHOST_TCP);
        }
        return TransportConfig.builder().dockerHost(URI.create(DockerEndpoints.LOCALHOST_TCP)).build();
    }

    @Override
    protected boolean isApplicable() {
        return DockerEndpoints.reachable(DockerEndpoints.LOCALHOST_TCP);
    }

    /** Not remembered in {@code ~/.testcontainers.properties}: the daemon may be gone next time. */
    @Override
    protected boolean isPersistable() {
        return false;
    }

    @Override
    public String getDescription() {
        return "local TCP daemon (" + DockerEndpoints.LOCALHOST_TCP + ")";
    }

    @Override
    protected int getPriority() {
        return PRIORITY;
    }
}
