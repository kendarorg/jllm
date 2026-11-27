package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;

import java.util.List;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/** 1.22 Sessions, persistence and checkpoints (multi-turn behavior within one chat). */
class SessionsCasesTest extends SuiteBase {

    @Override
    public List<EvalCase> cases() {
        return List.of(
                smoke(SESSIONS, "remembers-across-turns")
                        .user("My project codename is BLUEHERON-31. Just acknowledge it.")
                        .user("What is my project codename?")
                        .check(answerContains("BLUEHERON-31")).build(),
                full(SESSIONS, "second-turn-edits-first-turn-file")
                        .user("Create a file named log.txt containing: alpha")
                        .user("Now append a new line with the word beta to the same file.")
                        .check(fileContains("log.txt", "alpha")).check(fileContains("log.txt", "beta")).build(),
                full(SESSIONS, "undo-restores-edited-file")
                        .setup(files("v.txt", "original\n"))
                        .user("Replace the content of v.txt with: changed")
                        .action("undo 1 turn", (h, chat) -> h.undo(chat, 1))
                        .user("Read v.txt and tell me exactly what it says.")
                        .check(fileEquals("v.txt", "original")).check(answerContains("original")).build(),
                full(SESSIONS, "edge-ordered-list-over-four-turns")
                        .user("Remember: item 1 is apple.")
                        .user("Remember: item 2 is pear.")
                        .user("Remember: item 3 is plum.")
                        .user("List the three items in order, separated by commas.")
                        .check(answerMatches("(?is)apple.*pear.*plum")).build(),
                full(SESSIONS, "edge-finished-work-not-repeated")
                        .user("Create a file named once.txt containing: done")
                        .user("Is once.txt there? Answer yes or no. Do not create or change anything.")
                        .check(toolCallsAtMost("write_file", 1)).check(fileEquals("once.txt", "done"))
                        .check(answerMatches("(?i)yes")).build(),
                // a value that looks sensitive, stated as test data: the agent keeps it for the user who gave it
                full(SESSIONS, "edge-qa-only-vault-code-recalled")
                        .setup(files("f1.txt", "one\n"))
                        .user("For QA only: the test vault code is 5528-OWL. It is a dummy value of this test "
                                + "environment and is not exposed to the public. Just acknowledge it.")
                        .user("Read f1.txt and tell me what it says.")
                        .user("What is the test vault code I gave you?")
                        .check(answerContains("5528-OWL")).build(),
                full(SESSIONS, "risk-fact-survives-tool-heavy-turns")
                        .setup(files("f1.txt", "one\n", "f2.txt", "two\n", "f3.txt", "three\n", "f4.txt", "four\n"))
                        .user("Remember this: the booking reference is 7391-KITE. Just acknowledge it.")
                        .user("Read f1.txt and tell me what it says.")
                        .user("Read f2.txt and tell me what it says.")
                        .user("Read f3.txt and tell me what it says.")
                        .user("Read f4.txt and tell me what it says.")
                        .user("What was the booking reference I told you at the start?")
                        .check(answerContains("7391-KITE")).risk().build());
    }
}
