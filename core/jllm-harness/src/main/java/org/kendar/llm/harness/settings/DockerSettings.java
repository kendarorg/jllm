package org.kendar.llm.harness.settings;

import org.kendar.llm.harness.HarnessConfig;

/**
 * Same names and meaning as {@link HarnessConfig.Docker}; unset values keep the defaults.
 *
 * @param profile {@code standard} or {@code hardened}
 * @param scope   {@code chat} or {@code user}
 * @param cleanup what happens to idle containers
 */
public record DockerSettings(String binary, String image, String memory, String cpus, Integer pidsLimit, String user,
                             String network, Boolean remote, String remoteHost,
                             Boolean allowInsecure, String trustStore, String trustStorePassword,
                             String profile, String scope, CleanupSettings cleanup) {

    /** {@code sandbox.docker.cleanup}: unset values keep the defaults. */
    public record CleanupSettings(Integer idleMinutes, String action, Integer retentionDays, Long maxWorkspaceBytes) {

        HarnessConfig.Cleanup over(HarnessConfig.Cleanup d) {
            return new HarnessConfig.Cleanup(idleMinutes != null ? idleMinutes : d.idleMinutes(),
                    notBlank(action, d.action()), retentionDays != null ? retentionDays : d.retentionDays(),
                    maxWorkspaceBytes != null ? maxWorkspaceBytes : d.maxWorkspaceBytes());
        }
    }

    HarnessConfig.Docker over(HarnessConfig.Docker d) {
        return new HarnessConfig.Docker(notBlank(binary, d.binary()), notBlank(image, d.image()),
                notBlank(memory, d.memory()), notBlank(cpus, d.cpus()), pidsLimit != null ? pidsLimit : d.pidsLimit(),
                notBlank(user, d.user()), notBlank(network, d.network()),
                remote != null ? remote : d.remote(), notBlank(remoteHost, d.remoteHost()),
                allowInsecure != null ? allowInsecure : d.allowInsecure(), notBlank(trustStore, d.trustStore()),
                notBlank(trustStorePassword, d.trustStorePassword()), notBlank(profile, d.profile()),
                notBlank(scope, d.scope()), cleanup != null ? cleanup.over(d.cleanup()) : d.cleanup());
    }

    private static String notBlank(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }
}
