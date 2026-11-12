package org.kendar.llm.harness.tools;

import org.kendar.llm.harness.agent.AgentRun;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.sandbox.FileBackend;

import java.nio.file.Path;

/**
 * @param workDir      root of the file tools' path jail (the project directory)
 * @param scratchDir   per chat and agent scratch directory; always writable; same as workDir when not separate
 * @param readOnlyWork true in plan mode: file tools may only write under scratchDir and the shell runs from there
 * @param toolUseId    id of the tool call being executed; null outside a model-driven call
 * @param mode         permission mode of the calling agent; null = {@link PermissionMode#DEFAULT}
 * @param run          event sink and cancel flag of the calling run; null outside a model-driven call
 * @param files        file access inside a container (docker daemon mode); null: the file tools use {@code workDir}
 *                     and {@code scratchDir} on the host
 * @param parent       null for a call from the model; {@code run_js:<toolUseId>} for a call made by a script
 * @param hosts        asks the user before a web tool contacts a host; null allows every host
 * @param scope        what the calling agent can reach (its tool list and the invoke path); null outside a
 *                     model-driven call
 */
public record ToolContext(String chatId, Path workDir, Path scratchDir, boolean readOnlyWork, String toolUseId,
                          PermissionMode mode, AgentRun run, FileBackend files, String parent, RunScope scope, HostApprover hosts) {

    public ToolContext {
        if (mode == null) mode = PermissionMode.DEFAULT;
    }

    public ToolContext(String chatId, Path workDir, Path scratchDir, boolean readOnlyWork, String toolUseId,
                       PermissionMode mode, AgentRun run, FileBackend files) {
        this(chatId, workDir, scratchDir, readOnlyWork, toolUseId, mode, run, files, null, null, null);
    }

    public ToolContext(String chatId, Path workDir, Path scratchDir, boolean readOnlyWork, String toolUseId,
                       PermissionMode mode, AgentRun run, FileBackend files, String parent, RunScope scope) {
        this(chatId, workDir, scratchDir, readOnlyWork, toolUseId, mode, run, files, parent, scope, null);
    }

    public ToolContext(String chatId, Path workDir, Path scratchDir, boolean readOnlyWork, String toolUseId,
                       PermissionMode mode, AgentRun run) {
        this(chatId, workDir, scratchDir, readOnlyWork, toolUseId, mode, run, null);
    }

    public ToolContext(String chatId, Path workDir, Path scratchDir, boolean readOnlyWork, String toolUseId) {
        this(chatId, workDir, scratchDir, readOnlyWork, toolUseId, PermissionMode.DEFAULT, null);
    }

    public ToolContext(String chatId, Path workDir, String toolUseId) {
        this(chatId, workDir, workDir, false, toolUseId);
    }

    public ToolContext(String chatId, Path workDir) {
        this(chatId, workDir, null);
    }

    public ToolContext withHosts(HostApprover hosts) {
        return new ToolContext(chatId, workDir, scratchDir, readOnlyWork, toolUseId, mode, run, files, parent, scope,
                hosts);
    }

    public ToolContext withToolUseId(String toolUseId) {
        return new ToolContext(chatId, workDir, scratchDir, readOnlyWork, toolUseId, mode, run, files, parent, scope,
                hosts);
    }

    public ToolContext withFiles(FileBackend files) {
        return new ToolContext(chatId, workDir, scratchDir, readOnlyWork, toolUseId, mode, run, files, parent, scope,
                hosts);
    }

    public ToolContext withParent(String parent) {
        return new ToolContext(chatId, workDir, scratchDir, readOnlyWork, toolUseId, mode, run, files, parent, scope,
                hosts);
    }

    public ToolContext withScope(RunScope scope) {
        return new ToolContext(chatId, workDir, scratchDir, readOnlyWork, toolUseId, mode, run, files, parent, scope,
                hosts);
    }

    /** The same context with both {@code workDir} and {@code scratchDir} set to {@code root} (the file jail). */
    public ToolContext withRoot(Path root) {
        return new ToolContext(chatId, root, root, readOnlyWork, toolUseId, mode, run, files, parent, scope, hosts);
    }
}
