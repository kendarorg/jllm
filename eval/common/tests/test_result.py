import json

from jllm_eval_common.result import (
    EXIT_LIMIT,
    EXIT_USAGE,
    MISSING,
    JllmResult,
    exit_code_meaning,
    read_result,
)

SAMPLE = {
    "chatId": "c-1",
    "outcome": "completed",
    "turns": 7,
    "durationMs": 12345,
    "usage": {"input": 100, "output": 50, "cacheRead": 1000, "cacheCreation": 200},
    "costUsd": 0.0123,
    "toolCalls": [{"name": "bash", "input": {"command": "ls"}, "isError": False}, {"name": "edit", "isError": True}],
    "error": None,
}


def test_reads_full_result(tmp_path):
    p = tmp_path / "r.json"
    p.write_text(json.dumps(SAMPLE), encoding="utf-8")
    r = read_result(p)
    assert r.completed
    assert r.chat_id == "c-1"
    assert r.turns == 7
    assert r.duration_ms == 12345
    assert r.usage.total_input == 1300
    assert r.usage.output == 50
    assert r.cost_usd == 0.0123
    assert [c.name for c in r.tool_calls] == ["bash", "edit"]
    assert r.tool_calls[1].is_error


def test_missing_and_malformed_files_do_not_raise(tmp_path):
    assert read_result(tmp_path / "none.json").outcome == MISSING
    bad = tmp_path / "bad.json"
    bad.write_text("{not json", encoding="utf-8")
    r = read_result(bad)
    assert r.outcome == MISSING and "unreadable" in r.error
    arr = tmp_path / "arr.json"
    arr.write_text("[]", encoding="utf-8")
    assert read_result(arr).outcome == MISSING


def test_failure_result_keeps_error():
    r = JllmResult.from_dict({"outcome": "failed", "error": "boom"})
    assert not r.completed and r.error == "boom" and r.cost_usd == 0.0


def test_exit_code_meaning():
    assert exit_code_meaning(0) == "completed"
    assert "max_turns" in exit_code_meaning(EXIT_LIMIT)
    assert exit_code_meaning(EXIT_USAGE) == "usage error"
    assert "unexpected" in exit_code_meaning(99)
