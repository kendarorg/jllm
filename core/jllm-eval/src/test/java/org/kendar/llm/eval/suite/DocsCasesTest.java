package org.kendar.llm.eval.suite;

import org.kendar.llm.docs.DocsPlugin;
import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.fixture.Documents;
import org.kendar.llm.eval.fixture.Fact;
import org.kendar.llm.eval.fixture.Fixtures;

import java.util.List;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/** 2.2 docs plugin. */
class DocsCasesTest extends SuiteBase {

    @Override
    public List<EvalCase> cases() {
        Fixtures a = Fixtures.create().docx("contract.docx"), b = Fixtures.create().docx("contract.docx"),
                c = Fixtures.create().file("old.doc", "not a real legacy document");
        Fact paragraph = Documents.docxFacts().get(0), cell = Documents.docxFacts().get(2);
        return List.of(
                smoke(DOCS, "fact-in-paragraph")
                        .harness(bld -> {
                            a.harness().accept(bld);
                            bld.plugin(new DocsPlugin());
                        }).setup(a.setup())
                        .user("Read contract.docx and answer: " + paragraph.question())
                        .check(toolCalled("read_file")).check(answerContains(paragraph.answer())).build(),
                full(DOCS, "fact-in-table-cell")
                        .harness(bld -> {
                            b.harness().accept(bld);
                            bld.plugin(new DocsPlugin());
                        }).setup(b.setup())
                        .user("Read contract.docx and answer: " + cell.question())
                        .check(answerContains(cell.answer())).build(),
                full(DOCS, "failure-legacy-doc-reported")
                        .harness(bld -> {
                            c.harness().accept(bld);
                            bld.plugin(new DocsPlugin());
                        }).setup(c.setup())
                        .user("Read old.doc and tell me what it contains. If you cannot read it, tell me why and what I should do.")
                        .check(toolCalled("read_file")).check(anyToolResultContains("legacy .doc not supported"))
                        .check(toolNotCalled("bash")).build());
    }
}
