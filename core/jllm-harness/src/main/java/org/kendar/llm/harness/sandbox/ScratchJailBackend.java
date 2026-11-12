package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link FileBackend} whose work dir is the scratch directory of one agent: the file jail of a script when the
 * project directory is shared (the CLI) and the files live in a container. Every path, read or write, goes to the
 * scratch tree; the work dir ({@code /workspace}) and the scratchpads of other agents cannot be reached.
 */
public final class ScratchJailBackend implements FileBackend {

    private final FileBackend inner;
    /** The scratch directory relative to the work dir, e.g. {@code .kendhar/scratchpad/main}; null when elsewhere. */
    private final String prefix;

    public ScratchJailBackend(FileBackend inner) {
        this.inner = inner;
        String work = inner.workRoot().endsWith("/") ? inner.workRoot() : inner.workRoot() + "/";
        String scratch = inner.scratchRoot();
        this.prefix = scratch.startsWith(work) ? scratch.substring(work.length()) : null;
    }

    @Override
    public String workRoot() {
        return inner.scratchRoot();
    }

    @Override
    public String scratchRoot() {
        return inner.scratchRoot();
    }

    /** The scratch tree is the only tree: plan-mode targets of the inner backend are exactly that. */
    @Override
    public Target readTarget(String path) {
        return inner.writeTarget(path, true);
    }

    @Override
    public Target writeTarget(String path, boolean planMode) {
        return inner.writeTarget(path, true);
    }

    @Override
    public Stat stat(Target t) throws IOException {
        return inner.stat(t);
    }

    @Override
    public byte[] read(Target t, long offset, long limit) throws IOException {
        return inner.read(t, offset, limit);
    }

    @Override
    public void write(Target t, byte[] data, boolean append) throws IOException {
        inner.write(t, data, append);
    }

    private static void noParent(String p) {
        if (p == null) return;
        for (String seg : p.replace('\\', '/').split("/")) {
            if (seg.equals("..")) throw new IllegalArgumentException("Path escapes the scratch dir: " + p);
        }
        if (p.startsWith("/")) throw new IllegalArgumentException("Path escapes the scratch dir: " + p);
    }

    private String scoped(String path) {
        if (prefix == null) throw new IllegalArgumentException("Search is not available in this scratch directory");
        noParent(path);
        return path == null || path.isBlank() || path.equals(".") ? prefix : prefix + "/" + path;
    }

    private List<String> unscope(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) out.add(l.startsWith(prefix + "/") ? l.substring(prefix.length() + 1) : l);
        return out;
    }

    @Override
    public List<String> glob(String pattern) throws IOException {
        noParent(pattern);
        if (prefix == null) throw new IllegalArgumentException("Search is not available in this scratch directory");
        return unscope(inner.glob(prefix + "/" + pattern));
    }

    @Override
    public List<String> grep(String pattern, String path, String glob) throws IOException {
        return unscope(inner.grep(pattern, scoped(path), glob));
    }

    @Override
    public JsonNode list(Target t, boolean all) throws IOException {
        return inner.list(t, all);
    }

    @Override
    public String tree(Target t, int depth) throws IOException {
        return inner.tree(t, depth);
    }

    @Override
    public void mkdir(Target t) throws IOException {
        inner.mkdir(t);
    }

    @Override
    public void move(Root root, String from, String to) throws IOException {
        inner.move(root, from, to);
    }

    @Override
    public void delete(Target t) throws IOException {
        inner.delete(t);
    }
}
