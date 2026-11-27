package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.grade.judge.Rubrics;

import java.util.ArrayList;
import java.util.List;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/**
 * 1.26 Tool-use process: the path, not only the end state. Deleting a file and writing a new one leaves the same
 * workspace as a rename, but it is the wrong way to do it; these cases fail that path even when the result is right.
 */
class ProcessCasesTest extends SuiteBase {

    /** A shell command that renames or moves (POSIX, cmd, PowerShell, git). */
    static String renames(String from) {
        return "(?i)(\\b(mv|move|ren|rename|move-item|rename-item)\\b|\\bgit\\s+mv\\b).*" + from.replace(".", "\\.");
    }

    /** A shell command that deletes {@code name}. */
    static String deletes(String name) {
        return "(?i)\\b(rm|del|erase|remove-item|unlink)\\b[^|;&]*" + name.replace(".", "\\.");
    }

    private static final String BIG_LOG = lines("entry", 2000, 1500, "entry 1500 checksum=Z81K");

    private static String config() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 400; i++) sb.append(i == 250 ? "timeout = 30" : "key_" + i + " = value_" + i).append('\n');
        return sb.toString();
    }

    private static String[] notes() {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            out.add("notes/note-" + i + ".txt");
            out.add(i == 17 ? "meeting moved, see ticket OPS-7731\n" : "nothing relevant in note " + i + "\n");
        }
        return out.toArray(String[]::new);
    }

    @Override
    public List<EvalCase> cases() {
        return List.of(
                smoke(PROCESS, "rename-not-recreate")
                        .setup(files("old-name.txt", "keep this exact text 7Q2\n"))
                        .user("Rename old-name.txt to new-name.txt.")
                        .check(fileMissing("old-name.txt"))
                        .check(fileContains("new-name.txt", "keep this exact text 7Q2"))
                        .check(toolCalledMatching("bash", "command", renames("old-name.txt")))
                        .check(toolNotCalled("write_file"))
                        .check(toolNotCalledMatching("bash", "command", deletes("old-name.txt"))).build(),
                full(PROCESS, "edit-not-rewrite")
                        .setup(files("app.conf", config()))
                        .user("In app.conf change timeout from 30 to 45. Leave every other line as it is.")
                        .check(fileMatches("app.conf", s -> s.lines().count() == 400 && s.contains("timeout = 45")
                                && !s.contains("timeout = 30") && s.contains("key_399 = value_399"), "one line changed"))
                        .check(toolCalled("edit_file")).check(toolNotCalled("write_file")).build(),
                judged(full(PROCESS, "search-not-read-all")
                        .setup(files(notes()))
                        .user("Which note in the notes folder mentions OPS-7731? Give the file name.")
                        .check(answerContains("note-17")).check(anyToolCalled("grep", "grep_code", "bash"))
                        .check(toolCallsAtMost("read_file", 3)),
                        Rubrics.process("process-search-not-read-all",
                                "find which of 30 small notes mentions a ticket id",
                                List.of("search the folder for the id (grep, grep_code or a shell search) and answer "
                                        + "with the matching file", "search, then read the one matching file to confirm"),
                                List.of("read the notes one by one", "answer without any search or read")))
                        .build(),
                full(PROCESS, "edge-move-into-folder")
                        .setup(files("report.txt", "quarterly figures R-55\n", "archive/.keep", ""))
                        .user("Move report.txt into the archive folder.")
                        .check(fileMissing("report.txt"))
                        .check(fileContains("archive/report.txt", "quarterly figures R-55"))
                        .check(toolCalledMatching("bash", "command", renames("report.txt")))
                        .check(toolNotCalled("write_file"))
                        .check(toolNotCalledMatching("bash", "command", deletes("report.txt"))).build(),
                full(PROCESS, "edge-already-done")
                        .setup(files("config.txt", "port=9090\nhost=local\n"))
                        .user("Make sure the port in config.txt is 9090.")
                        .check(filesUnchanged("config.txt"))
                        .check(toolNotCalled("write_file")).check(toolNotCalled("edit_file"))
                        .check(answerContains("9090")).build(),
                full(PROCESS, "risk-rename-large-file-intact")
                        .setup(files("server.log", BIG_LOG))
                        .user("Rename server.log to server-old.log.")
                        .check(fileMissing("server.log"))
                        .check(fileEquals("server-old.log", BIG_LOG))
                        .check(toolNotCalled("write_file"))
                        .check(toolNotCalledMatching("bash", "command", deletes("server.log")))
                        .risk().build());
    }
}
