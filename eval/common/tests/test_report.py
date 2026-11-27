from jllm_eval_common.report import RunInfo, TaskRow, render_summary, totals, write_summary


def rows():
    return [
        TaskRow("b", passed=True, outcome="completed", turns=3, cost_usd=0.5, duration_ms=60000),
        TaskRow("a", passed=True, outcome="completed", turns=9, cost_usd=2.5, over_cap=("cost 2.5 > cap 2",)),
        TaskRow("c", passed=False, outcome="max_turns", turns=50, cost_usd=1.0),
        TaskRow("d", passed=None, outcome="missing", error="container failed | x"),
    ]


def info(run_id="r1", date="2026-11-20T10:00:00"):
    return RunInfo("swe", run_id, date, "anthropic/claude-sonnet-5-5", "abc123def4567890", {"limits": {"max_turns": 50}})


def test_totals_count_over_cap_as_fail():
    t = totals(rows())
    assert (t.tasks, t.passed, t.over_cap, t.errors) == (4, 1, 1, 1)
    assert t.cost_usd == 4.0 and t.pass_rate == 0.25


def test_render_summary():
    md = render_summary(info(), rows())
    assert "Passed: 1/4 (25.0%)" in md
    assert "| limits.max_turns | 50 |" in md
    assert "| a | fail | completed | 9 | 2.5000 |" in md
    assert "| d | error | missing |" in md
    assert "container failed \\| x" in md


def test_index_upserts_and_sorts(tmp_path):
    write_summary(tmp_path, info("r1", "2026-11-20T10:00:00"), rows())
    write_summary(tmp_path, info("r2", "2026-11-22T10:00:00"), rows()[:1])
    write_summary(tmp_path, info("r1", "2026-11-20T10:00:00"), rows()[:2])
    index = (tmp_path / "swe" / "index.md").read_text(encoding="utf-8")
    data = [line for line in index.splitlines() if "](" in line]
    assert len(data) == 2
    assert "[r2](r2/summary.md)" in data[0]
    assert "| 1/2 |" in data[1]
    assert (tmp_path / "swe" / "r1" / "summary.md").is_file()
