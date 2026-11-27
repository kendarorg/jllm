package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The one place that decides which runner a front end gets: {@code sandbox.backend=local} is the
 * {@link LocalSandboxExecutor} over the layout (no isolation, for testing and trusted single-user use),
 * {@code sandbox.backend=shared-local} is the {@link SharedLocalSandbox} (minimal environment, bwrap on Linux),
 * {@code sandbox.backend=docker} is the {@link RemoteExecSandboxExecutor} (standard or hardened profile, scope chat or
 * user, from the settings and the optional per-user policy). The CLI and the web both use it.
 *
 * <pre>
 * SandboxExecutor box = new SandboxFactory().userOf(userOfChat).policy(policyOfChat)
 *         .create(settings, config, new ChatDirLayout(base), RemoteExecSandboxExecutor.Kind.WEB);
 * </pre>
 */
public final class SandboxFactory {

    private DockerCommandRunner runner = new ProcessDockerCommandRunner();
    private Function<String, String> userOf;
    private Function<String, SandboxPolicy> policy;
    private Consumer<String> warn;
    private BackupRecordStore backupStore;
    private boolean confinePaths = true;

    /** The docker CLI runner; default: the docker binary as a subprocess. */
    public SandboxFactory runner(DockerCommandRunner runner) {
        this.runner = runner;
        return this;
    }

    /** Chat id to user id: the key of a {@code user}-scope container. */
    public SandboxFactory userOf(Function<String, String> userOf) {
        this.userOf = userOf;
        return this;
    }

    /** Chat id to the policy of its user: profile, scope, limits, network, docker host, clean-up. */
    public SandboxFactory policy(Function<String, SandboxPolicy> policy) {
        this.policy = policy;
        return this;
    }

    /** Where sandbox warnings go (default: the log). */
    public SandboxFactory onWarning(Consumer<String> warn) {
        this.warn = warn;
        return this;
    }

    /** Where backup records are kept (default: a file next to each tarball). */
    public SandboxFactory backupStore(BackupRecordStore store) {
        this.backupStore = store;
        return this;
    }

    /**
     * False: the local runner is {@link LocalSandboxExecutor#unconfined} (no path jail); default true. Ignored when the
     * settings select docker.
     */
    public SandboxFactory confinePaths(boolean confine) {
        this.confinePaths = confine;
        return this;
    }

    /**
     * @param kind which front end owns the containers (used when the settings select docker; the layout is the
     *             front end's: {@link CwdLayout} for the CLI, {@link ChatDirLayout} for the web)
     */
    public SandboxExecutor create(HarnessSettings settings, HarnessConfig config, Layout layout,
                                  RemoteExecSandboxExecutor.Kind kind) {
        if (settings.sharedLocalSandbox()) {
            return new SharedLocalSandbox(layout, c -> false, null);
        }
        if (!settings.dockerSandbox()) {
            return confinePaths ? new LocalSandboxExecutor(layout) : LocalSandboxExecutor.unconfined(layout);
        }
        RemoteExecSandboxExecutor box = new RemoteExecSandboxExecutor(config, runner, System.getenv("DOCKER_HOST"),
                layout, kind, layout.location(), userOf, policy, backupStore);
        if (warn != null) box.onWarning(warn);
        return box;
    }
}
