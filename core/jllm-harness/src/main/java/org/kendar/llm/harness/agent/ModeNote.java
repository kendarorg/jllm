package org.kendar.llm.harness.agent;

import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.tools.ToolContext;

import java.time.LocalDate;

/** The text that tells the model the date, the directories and the permission mode of the request. */
final class ModeNote {
    private final SandboxExecutor sandbox;

    ModeNote(SandboxExecutor sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * Date, OS, directories and permission mode: after the cached system prefix. The shell is described by the bash
     * tool itself, so a chat without it (policy, chat toggle, deny rule) is never told about one.
     */
    String of(PermissionMode mode, ToolContext ctx) {
        String scratch = "\n\nToday's date: " + LocalDate.now()
                + "\nOS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "."
                + "\nWorking directory (project files go here, relative paths resolve here): "
                + sandbox.displayPath(ctx.workDir())
                + "\nScratch directory (only for throwaway files, never for deliverables): "
                + sandbox.displayPath(ctx.scratchDir());
        return switch (mode) {
            case PLAN -> "\n\nMode: PLAN. Investigate read-only and write a plan; do not change the working directory ("
                    + sandbox.displayPath(ctx.workDir()) + "). Do not call write_file or edit_file to carry out the request:"
                    + " the changes it asks for are steps of the plan, done only after the user approves (a write would"
                    + " only reach a scratch copy, never the project). Shell commands run in the scratch directory."
                    + " The answer is the plan: numbered steps naming each file and what changes in it, the risks or"
                    + " open questions, and a closing request for the user's approval. Never report a file as created"
                    + " or changed." + scratch;
            case EDIT -> "\n\nMode: EDIT. File edits are applied without asking; shell commands may ask." + scratch;
            case AUTO -> "\n\nMode: AUTO. Tool calls run without asking." + scratch;
            default -> "\n\nMode: DEFAULT. Tool calls that change things ask the user first." + scratch;
        };
    }
}
