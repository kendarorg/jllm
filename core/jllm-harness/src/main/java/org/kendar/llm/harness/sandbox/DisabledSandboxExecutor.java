package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The runner of a role without a sandbox ({@link SandboxKind#NONE}): the directories exist (the chat's history lives
 * there) but nothing can be read, written or run through the tools. Every attempt throws {@link
 * SandboxDisabledException}, which the agent loop returns to the model as the error of that tool call.
 */
public final class DisabledSandboxExecutor implements SandboxExecutor {

    private final Layout layout;
    private final FileBackend refusing = new Refusing();

    public DisabledSandboxExecutor(Layout layout) {
        this.layout = layout;
    }

    @Override
    public Path location() {
        return layout.location();
    }

    @Override
    public Path workDir(String chatId) {
        return layout.workDir(chatId);
    }

    @Override
    public Path scratchDir(String chatId, String agentId) {
        return layout.scratchDir(chatId, agentId);
    }

    @Override
    public boolean sharedWorkDir() {
        return layout.sharedWorkDir();
    }

    @Override
    public Path chatStorage(String chatId) {
        return layout.chatStorage(chatId);
    }

    @Override
    public void deleteWorkDir(String chatId) {
        layout.deleteWorkDir(chatId);
    }

    @Override
    public ExecutionResult execute(String chatId, String command, Duration timeout) {
        throw new SandboxDisabledException();
    }

    @Override
    public ExecutionResult execute(String chatId, Path scratchDir, boolean planMode, String command, Duration timeout) {
        throw new SandboxDisabledException();
    }

    @Override
    public FileBackend files(String chatId, String agentId) {
        return refusing;
    }

    @Override
    public String shellDescription() {
        return "Not available: the user's role has no sandbox.";
    }

    @Override
    public String sandboxStatus(String chatId) {
        return "runner: none (the role has no sandbox: no files, no shell)";
    }

    /** Every operation that touches data fails, so the file tools end in the failing call. */
    private static final class Refusing implements FileBackend {
        @Override
        public String workRoot() {
            return "/workspace";
        }

        @Override
        public String scratchRoot() {
            return "/scratch";
        }

        @Override
        public Target readTarget(String path) {
            throw new SandboxDisabledException();
        }

        @Override
        public Target writeTarget(String path, boolean planMode) {
            throw new SandboxDisabledException();
        }

        @Override
        public Stat stat(Target t) throws IOException {
            throw new SandboxDisabledException();
        }

        @Override
        public byte[] read(Target t, long offset, long limit) throws IOException {
            throw new SandboxDisabledException();
        }

        @Override
        public void write(Target t, byte[] data, boolean append) throws IOException {
            throw new SandboxDisabledException();
        }

        @Override
        public List<String> glob(String pattern) throws IOException {
            throw new SandboxDisabledException();
        }

        @Override
        public List<String> grep(String pattern, String path, String glob) throws IOException {
            throw new SandboxDisabledException();
        }

        @Override
        public JsonNode list(Target t, boolean all) throws IOException {
            throw new SandboxDisabledException();
        }

        @Override
        public String tree(Target t, int depth) throws IOException {
            throw new SandboxDisabledException();
        }

        @Override
        public void mkdir(Target t) throws IOException {
            throw new SandboxDisabledException();
        }

        @Override
        public void move(Root root, String from, String to) throws IOException {
            throw new SandboxDisabledException();
        }

        @Override
        public void delete(Target t) throws IOException {
            throw new SandboxDisabledException();
        }
    }
}
