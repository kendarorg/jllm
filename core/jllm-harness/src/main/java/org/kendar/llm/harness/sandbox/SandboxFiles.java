package org.kendar.llm.harness.sandbox;

/** File access of a {@link SandboxExecutor} when the work dir is not on the host. */
public interface SandboxFiles {

    /**
     * File access for the file tools of one agent when the work dir is not on the host (the container of {@link
     * RemoteExecSandboxExecutor}); null when the tools should use the host file system ({@link Layout#workDir}).
     *
     * @param agentId scratch id of the agent; null for a view without a scratch directory of its own
     */
    default FileBackend files(String chatId, String agentId) {
        return null;
    }
}
