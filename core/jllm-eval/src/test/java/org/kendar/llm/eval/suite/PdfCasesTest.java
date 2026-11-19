package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.fixture.Documents;
import org.kendar.llm.eval.fixture.Fact;
import org.kendar.llm.eval.fixture.Fixtures;
import org.kendar.llm.pdf.PdfPlugin;

import java.util.List;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/** 2.1 pdf plugin. */
class PdfCasesTest extends SuiteBase {

    @Override
    public List<EvalCase> cases() {
        Fixtures a = Fixtures.create().pdf("report.pdf"), b = Fixtures.create().pdf("report.pdf"),
                c = Fixtures.create().pdf("report.pdf");
        Fact first = Documents.pdfFacts().get(0), third = Documents.pdfFacts().get(2);
        return List.of(
                smoke(PDF, "fact-on-first-page")
                        .harness(bld -> {
                            a.harness().accept(bld);
                            bld.plugin(new PdfPlugin());
                        }).setup(a.setup())
                        .user("Read report.pdf and answer: " + first.question())
                        .check(toolCalled("read_file")).check(answerContains(first.answer())).build(),
                full(PDF, "fact-on-last-page")
                        .harness(bld -> {
                            b.harness().accept(bld);
                            bld.plugin(new PdfPlugin());
                        }).setup(b.setup())
                        .user("Read the whole of report.pdf and answer: " + third.question())
                        .check(answerContains(third.answer())).build(),
                full(PDF, "failure-plugin-off-not-invented")
                        .harness(c.harness()).setup(c.setup())
                        // no shell: a host python with pypdf would read the file without the plugin
                        .action("/tools disable Shell", (h, chat) -> h.commands().execute(chat, "/tools disable Shell"))
                        .user("Read report.pdf and answer: " + first.question() + " If you cannot read it, say so.")
                        .check(toolCalled("read_file")).check(toolNotOffered("bash")).check(answerNotContains(first.answer()))
                        .check(anyToolResultContains("No reader for .pdf")).build());
    }
}
