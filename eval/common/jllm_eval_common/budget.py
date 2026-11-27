"""Per-task and per-run caps on cost and turns.

The turn and time caps go to the CLI (``--max-turns``, ``--timeout``). The CLI has no cost cap of its own, so the cost
cap is applied to the result: a task whose cost exceeds ``max_task_cost_usd`` is scored as failed, whatever the
benchmark says, and a run stops starting tasks once ``max_run_cost_usd`` is spent.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any

from .result import JllmResult


@dataclass(frozen=True)
class Limits:
    max_turns: int
    timeout_sec: int
    max_task_cost_usd: float | None = None
    max_run_cost_usd: float | None = None

    def __post_init__(self) -> None:
        if self.max_turns <= 0:
            raise ValueError("max_turns must be positive")
        if self.timeout_sec <= 0:
            raise ValueError("timeout_sec must be positive")
        for name in ("max_task_cost_usd", "max_run_cost_usd"):
            v = getattr(self, name)
            if v is not None and v <= 0:
                raise ValueError(f"{name} must be positive when set")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def task_violations(result: JllmResult, limits: Limits) -> list[str]:
    """Reasons why a task broke a cap; empty when it stayed within all of them."""
    out: list[str] = []
    if limits.max_task_cost_usd is not None and result.cost_usd > limits.max_task_cost_usd:
        out.append(f"cost {result.cost_usd:.4f} USD > cap {limits.max_task_cost_usd:.4f} USD")
    if result.turns > limits.max_turns:
        out.append(f"turns {result.turns} > cap {limits.max_turns}")
    return out


@dataclass
class RunBudget:
    """Running total over a run; ``exhausted`` turns true once the run cap is reached."""

    limits: Limits
    spent_usd: float = 0.0
    tasks: int = 0
    over_cap: list[str] = field(default_factory=list)

    def record(self, task_id: str, result: JllmResult) -> list[str]:
        self.tasks += 1
        self.spent_usd += result.cost_usd
        violations = task_violations(result, self.limits)
        if violations:
            self.over_cap.append(task_id)
        return violations

    @property
    def exhausted(self) -> bool:
        cap = self.limits.max_run_cost_usd
        return cap is not None and self.spent_usd >= cap
