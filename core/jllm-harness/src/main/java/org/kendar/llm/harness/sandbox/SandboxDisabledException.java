package org.kendar.llm.harness.sandbox;

/**
 * The user's role has no sandbox ({@link SandboxKind#NONE}): file tools and the shell are refused. Unlike {@link
 * SandboxUnavailableException} it does not offer to run locally. It reaches the model as the error of the tool call.
 */
public class SandboxDisabledException extends RuntimeException {

    public static final String MESSAGE = "No sandbox is enabled for your role: files and shell commands are not "
            + "available. Ask an administrator to give the role a sandbox.";

    public SandboxDisabledException() {
        super(MESSAGE);
    }
}
