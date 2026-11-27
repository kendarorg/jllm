import pytest

from jllm_eval_common.budget import Limits, RunBudget, task_violations
from jllm_eval_common.result import JllmResult


def test_limits_validate():
    with pytest.raises(ValueError):
        Limits(max_turns=0, timeout_sec=10)
    with pytest.raises(ValueError):
        Limits(max_turns=1, timeout_sec=10, max_task_cost_usd=0)


def test_task_violations():
    limits = Limits(max_turns=10, timeout_sec=60, max_task_cost_usd=1.0)
    assert task_violations(JllmResult(outcome="completed", turns=5, cost_usd=0.5), limits) == []
    v = task_violations(JllmResult(outcome="completed", turns=11, cost_usd=1.5), limits)
    assert len(v) == 2 and "cost" in v[0] and "turns" in v[1]


def test_run_budget_exhausts():
    b = RunBudget(Limits(max_turns=10, timeout_sec=60, max_task_cost_usd=1.0, max_run_cost_usd=2.0))
    b.record("a", JllmResult(outcome="completed", cost_usd=0.9))
    assert not b.exhausted
    assert b.record("b", JllmResult(outcome="completed", cost_usd=1.2))
    assert b.exhausted and b.over_cap == ["b"] and b.tasks == 2


def test_no_run_cap_never_exhausts():
    b = RunBudget(Limits(max_turns=10, timeout_sec=60))
    b.record("a", JllmResult(outcome="completed", cost_usd=1000))
    assert not b.exhausted
