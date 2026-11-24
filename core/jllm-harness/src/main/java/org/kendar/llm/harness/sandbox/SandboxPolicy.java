package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.HarnessConfig;

/**
 * What an admin decides for one user or chat on top of the configured defaults (web: per user and per role). Null
 * fields keep the configuration's value. A looser setting than the default (hardened profile with {@code network}
 * {@code default}) is an explicit grant.
 *
 * @param profile    {@code standard} or {@code hardened}
 * @param scope      {@code chat} or {@code user}
 * @param network    hardened profile: {@code none} or {@code default}
 * @param dockerHost docker endpoint of this user ({@code tcp://host:2375}); null: the main one
 * @param cleanup    what happens to the idle container (the policy of the role the user acts under)
 */
public record SandboxPolicy(String profile, String scope, String memory, String cpus, Integer pidsLimit,
                            String network, String dockerHost, HarnessConfig.Cleanup cleanup) {

    private static final SandboxPolicy NONE = new SandboxPolicy(null, null, null, null, null, null, null, null);

    /** No override at all. */
    public static SandboxPolicy none() {
        return NONE;
    }

    /** The container settings with this policy on top of {@code d}. */
    public HarnessConfig.Docker over(HarnessConfig.Docker d) {
        return new HarnessConfig.Docker(d.binary(), d.image(), memory != null ? memory : d.memory(),
                cpus != null ? cpus : d.cpus(), pidsLimit != null ? pidsLimit : d.pidsLimit(), d.user(),
                network != null ? network : d.network(), d.remote(), d.remoteHost(), d.allowInsecure(),
                d.trustStore(), d.trustStorePassword(), profile != null ? profile : d.profile(),
                scope != null ? scope : d.scope(), cleanup != null ? cleanup : d.cleanup());
    }
}
