package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.fixture.Fixtures;
import org.kendar.llm.eval.fixture.LargeOutput;
import org.kendar.llm.eval.fixture.SampleTools;
import org.kendar.llm.eval.grade.CacheChecks;
import org.kendar.llm.eval.grade.CacheChecks.ExpectedBreak;
import org.kendar.llm.eval.grade.RetentionChecks;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.permission.PermissionMode;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/**
 * 4 Context strategies: four long sessions, each replayed for compaction, mnemonic and scope with a small context
 * budget. Facts are planted at the start, in the middle and just before the turn that forces compaction; the last turn
 * asks for all of them. Strategy-specific invariants apply only to their own strategy.
 */
class StrategySessionsCasesTest extends SuiteBase {

    private static final int BUDGET = 10_000;
    private static final String TRACK = " Track the work with the todo_write tool if it is available.";
    private static final String ACK = " Acknowledge in one short sentence.";

    private static EvalCase.Builder session(String name) {
        return EvalCase.named(name).feature(STRATEGIES).tag(EvalCase.Tag.STRATEGY)
                .strategies(ContextStrategyKind.COMPACTION, ContextStrategyKind.MNEMONIC, ContextStrategyKind.SCOPE)
                .contextBudgetTokens(BUDGET)
                .config(c -> c.withKeepRecentTurns(2).withCompaction(new HarnessConfig.Compaction(true, 1500, null)))
                .maxTurns(60).timeoutSeconds(1800).trials(1);
    }

    private static ExpectedBreak breaksWithin(String what, Part... parts) {
        Set<CacheChecks.Part> allowed = EnumSet.noneOf(CacheChecks.Part.class);
        for (Part p : parts) allowed.add(p.part);
        return new ExpectedBreak("only " + what, b -> allowed.containsAll(b.parts()));
    }

    private enum Part {
        MESSAGES(CacheChecks.Part.MESSAGES), SYSTEM(CacheChecks.Part.SYSTEM), TOOLS(CacheChecks.Part.TOOLS);
        final CacheChecks.Part part;

        Part(CacheChecks.Part part) {
            this.part = part;
        }
    }

    /** Checks shared by every session: compaction happened, facts survive, invariants, cache breaks, no write of receipts. */
    private static EvalCase.Builder common(EvalCase.Builder b, ExpectedBreak allowed, RetentionChecks.Fact... facts) {
        return b.check(noRunError()).check(eventPresent("compaction")).check(retained(facts))
                .check(breaksAsExpected(allowed)).check(cachedRatioAtLeast(0.3, false))
                .check(onlyFor(ContextStrategyKind.COMPACTION, compactionSummaryHasSections()))
                .check(onlyFor(ContextStrategyKind.MNEMONIC, mnemonicLogNeverRewritten()))
                .check(onlyFor(ContextStrategyKind.MNEMONIC, mnemonicGuardBlocksMaskedWrites(false)))
                .check(onlyFor(ContextStrategyKind.SCOPE, scopePinned()));
    }

    private static String javaFile(String cls, String method) {
        return "public class " + cls + " {\n// " + filler(3800, cls).replace("\n", " ") + "\n    public int " + method
                + "(int x) {\n        return x + 1;\n    }\n}\n";
    }

    private static EvalCase refactoring() {
        String[][] files = {{"Alpha", "compute", "calcTotal"}, {"Beta", "run", "calcRun"}, {"Gamma", "step", "calcStep"},
                {"Delta", "value", "calcValue"}, {"Epsilon", "limit", "calcLimit"}};
        String[] setup = new String[(files.length + 1) * 2];
        for (int i = 0; i < files.length; i++) {
            setup[i * 2] = "src/" + files[i][0] + ".java";
            setup[i * 2 + 1] = javaFile(files[i][0], files[i][1]);
        }
        setup[files.length * 2] = "src/Big.java";
        setup[files.length * 2 + 1] = "// " + filler(30000, "Big") + "\n";
        EvalCase.Builder b = session("session-refactoring").setup(files(setup))
                .user("Project rules for this refactoring session: the ticket is TKT-4471 and every renamed method must start with the prefix calc." + ACK + TRACK);
        for (int i = 0; i < files.length; i++) {
            b.user("Open src/" + files[i][0] + ".java and rename the method " + files[i][1] + " to " + files[i][2] + ". Keep it short.");
            if (i == 2) b.user("For the record, the reviewer of this change is Priya Raman." + ACK);
        }
        b.user("The release branch for this change is rel/2026-09." + ACK);
        b.user("Now read src/Big.java completely and tell me how many lines it has.");
        b.user("Without opening files again, tell me: the ticket id, the reviewer name, the release branch and the required method prefix.");
        for (String[] f : files) b.check(fileContains("src/" + f[0] + ".java", f[2]));
        return common(b, breaksWithin("history rewrite", Part.MESSAGES),
                fact("What is the ticket id?", "TKT-4471"), fact("Who is the reviewer?", "Priya Raman"),
                fact("What is the release branch?", "rel/2026-09"), fact("What prefix must renamed methods have?", "calc"))
                .build();
    }

    private static EvalCase research() {
        LargeOutput report = new LargeOutput(450, 4, 21);
        Fixtures fx = Fixtures.create().largeOutput(report);
        var q = report.facts();
        EvalCase.Builder b = session("session-research-large-outputs").harness(fx.harness()).setup(fx.setup())
                .user("We are researching operations. The audit window code is WIN-208." + ACK + TRACK)
                .user("Call big_report and tell me in one sentence what kind of lines it contains.")
                .user("From that report, answer: " + q.get(0).question())
                .user("Another fact for the record: the escalation phone is 555-0142." + ACK)
                .user("Call big_report again and answer: " + q.get(1).question())
                .user("One more fact: the vendor contact is Lena Fischer." + ACK)
                .user("Call big_report a third time and answer: " + q.get(2).question())
                .user("Give me the audit window code, the escalation phone and the vendor contact. Also answer: "
                        + q.get(0).question() + " " + q.get(1).question() + " " + q.get(2).question()
                        + " If earlier output was masked or offloaded, recover it with the tool the system offers.");
        b.check(toolCalled("big_report"))
                .check(onlyFor(ContextStrategyKind.SCOPE, scopeOffloadsLargeOutputs(20_000)));
        return common(b, breaksWithin("history rewrite", Part.MESSAGES),
                fact("What is the audit window code?", "WIN-208"), fact("What is the escalation phone?", "555-0142"),
                fact("Who is the vendor contact?", "Lena Fischer"), fact(q.get(0)), fact(q.get(1)), fact(q.get(2)))
                .build();
    }

    private static EvalCase modes() {
        String[] setup = new String[10];
        for (int i = 1; i <= 4; i++) {
            setup[(i - 1) * 2] = "docs/spec" + i + ".txt";
            setup[(i - 1) * 2 + 1] = "Spec " + i + ": " + filler(3500, "spec " + i) + "\n";
        }
        setup[8] = "docs/big.txt";
        setup[9] = filler(30000, "big");
        EvalCase.Builder b = session("session-mode-switches").setup(files(setup))
                .user("Session note: the incident id is INC-7712." + ACK + TRACK)
                .modeSwitch(PermissionMode.EDIT)
                .user("Read docs/spec1.txt and write a two-line summary into notes/s1.txt.")
                .modeSwitch(PermissionMode.PLAN)
                .user("Plan how you would summarize docs/spec2.txt into notes/s2.txt. Only plan, do not execute.")
                .user("Also note: the on-call engineer is Tomasz Brandt." + ACK)
                .modeSwitch(PermissionMode.EDIT)
                .user("Read docs/spec3.txt and write a two-line summary into notes/s3.txt.")
                .modeSwitch(PermissionMode.DEFAULT)
                .user("Read docs/spec4.txt and write a two-line summary into notes/s4.txt.")
                .user("The rollback window ends at 22:45 UTC." + ACK)
                .user("Read docs/big.txt completely and tell me how many lines it has.")
                .user("Without opening files again: what is the incident id, who is on call, when does the rollback window end, and which notes files did you create?");
        b.check(fileExists("notes/s1.txt")).check(fileExists("notes/s3.txt")).check(fileExists("notes/s4.txt"))
                .check(fileMissing("notes/s2.txt"));
        return common(b, breaksWithin("system note or history rewrite", Part.SYSTEM, Part.MESSAGES),
                fact("What is the incident id?", "INC-7712"), fact("Who is the on-call engineer?", "Tomasz Brandt"),
                fact("When does the rollback window end?", "22:45"))
                .build();
    }

    private static EvalCase toggles() {
        LargeOutput report = new LargeOutput(450, 3, 31);
        Fixtures fx = Fixtures.create().sampleTools(new SampleTools()).largeOutput(report);
        EvalCase.Builder b = session("session-tool-toggles").harness(fx.harness())
                .setup((ws, extras) -> {
                    fx.setup().accept(ws, extras);
                    files("docs/big.txt", filler(30000, "big")).accept(ws, extras);
                })
                .user("The customer reference is CUST-5530." + ACK + TRACK)
                .user("Call big_report and tell me in one sentence what kind of lines it contains.")
                .action("/tools disable big_report", (h, chat) -> h.commands().execute(chat, "/tools disable big_report"))
                .user("Is the big_report tool available to you right now? Answer yes or no.")
                .user("The shipping partner is Nordline." + ACK)
                .action("/tools enable big_report", (h, chat) -> h.commands().execute(chat, "/tools enable big_report"))
                .user("Call big_report again and answer: " + report.facts().get(0).question())
                .action("/tools disable send_email", (h, chat) -> h.commands().execute(chat, "/tools disable send_email"))
                .user("The discount code is SAVE-9921." + ACK)
                .user("Read docs/big.txt completely and tell me how many lines it has.")
                .user("Without calling tools: what is the customer reference, the shipping partner, the discount code, and "
                        + report.facts().get(0).question());
        b.check(toolExecutions("send_email", 0, 0)).check(toolCalled("big_report"));
        return common(b, breaksWithin("tool list change or history rewrite", Part.TOOLS, Part.MESSAGES),
                fact("What is the customer reference?", "CUST-5530"), fact("What is the shipping partner?", "Nordline"),
                fact("What is the discount code?", "SAVE-9921"), fact(report.facts().get(0)))
                .build();
    }

    @Override
    public List<EvalCase> cases() {
        return List.of(refactoring(), research(), modes(), toggles());
    }
}
