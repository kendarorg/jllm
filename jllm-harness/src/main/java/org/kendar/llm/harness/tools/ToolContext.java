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
 */
public record ToolContext(String chatId, Path workDir, Path scratchDir, boolean readOnlyWork, String toolUseId,
                          PermissionMode mode, AgentRun run, FileBackend files) {

    public ToolContext {
        if (mode == null) mode = PermissionMode.DEFAULT;
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

    public ToolContext withToolUseId(String toolUseId) {
        return new ToolContext(chatId, workDir, scratchDir, readOnlyWork, toolUseId, mode, run, files);
    }
}
