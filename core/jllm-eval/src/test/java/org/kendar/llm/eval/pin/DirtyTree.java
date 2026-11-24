package org.kendar.llm.eval.pin;

/** Applies the dirty-tree policy before a run touches any model. */
public final class DirtyTree {

    private DirtyTree() {
    }

    /**
     * @return the banner to show when the policy is warn and the tree is dirty, else null
     * @throws IllegalStateException when the policy is refuse and the tree is dirty, or when the run would update the
     *                               baseline from a dirty tree (always refused, whatever the policy)
     */
    public static String enforce(DirtyTreePolicy policy, BuildInfo build, boolean updateBaseline) {
        if (!build.dirty()) return null;
        if (updateBaseline) {
            throw new IllegalStateException("Refusing to store a baseline from a dirty working tree (" + build.label()
                    + "): commit or stash the changes first.\n" + files(build));
        }
        if (policy == DirtyTreePolicy.REFUSE) {
            throw new IllegalStateException("Refusing to run: the working tree has uncommitted changes to tracked files "
                    + "(eval.dirtyTree=refuse). Commit or stash them, or set eval.dirtyTree=warn.\n" + files(build));
        }
        if (policy == DirtyTreePolicy.WARN) {
            return "WARNING: dirty working tree (" + build.dirtyFileCount() + " changed tracked file(s) on "
                    + build.shortCommit() + "): the results do not match a commit. Marked in run.json, summary and report.";
        }
        return null;
    }

    /** The changed files, one per line, with a note when the list is cut. */
    public static String files(BuildInfo build) {
        StringBuilder sb = new StringBuilder();
        for (String f : build.dirtyFiles()) sb.append("  ").append(f).append('\n');
        if (build.dirtyFileCount() > build.dirtyFiles().size()) {
            sb.append("  ... and ").append(build.dirtyFileCount() - build.dirtyFiles().size()).append(" more\n");
        }
        return sb.toString().stripTrailing();
    }
}
