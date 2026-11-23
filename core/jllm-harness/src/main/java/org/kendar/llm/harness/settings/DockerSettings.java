package org.kendar.llm.harness.settings;

import org.kendar.llm.harness.HarnessConfig;

/** Same names and meaning as {@link HarnessConfig.Docker}; unset values keep the defaults. */
public record DockerSettings(String binary, String image, String memory, String cpus, Integer pidsLimit, String user,
                             String network, String mode, Boolean remote, String remoteHost,
                             Boolean allowInsecure, String trustStore, String trustStorePassword) {

    HarnessConfig.Docker over(HarnessConfig.Docker d) {
        return new HarnessConfig.Docker(notBlank(binary, d.binary()), notBlank(image, d.image()),
                notBlank(memory, d.memory()), notBlank(cpus, d.cpus()), pidsLimit != null ? pidsLimit : d.pidsLimit(),
                notBlank(user, d.user()), notBlank(network, d.network()), notBlank(mode, d.mode()),
                remote != null ? remote : d.remote(), notBlank(remoteHost, d.remoteHost()),
                allowInsecure != null ? allowInsecure : d.allowInsecure(), notBlank(trustStore, d.trustStore()),
                notBlank(trustStorePassword, d.trustStorePassword()));
    }

    private static String notBlank(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }
}
