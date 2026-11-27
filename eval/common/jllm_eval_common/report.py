"""Markdown summary of one benchmark run and the history index of all runs of a benchmark.

Layout under ``eval/results/``::

    <benchmark>/index.md              one row per run, newest first (upserted by run id)
    <benchmark>/<run_id>/summary.md   parameters, totals and one row per task
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

INDEX_HEADER = (
    "| Date | Run | Model | Passed | Pass rate | Cost USD | Cost/task | jllm commit |\n"
    "|---|---|---|---|---|---|---|---|\n"
)


@dataclass(frozen=True)
class TaskRow:
    task_id: str
    passed: bool | None  # None: not scored (error, skipped)
    outcome: str
    turns: int = 0
    cost_usd: float = 0.0
    duration_ms: int = 0
    over_cap: tuple[str, ...] = ()
    error: str | None = None

    @property
    def counted_pass(self) -> bool:
        """A task over a cap never counts as passed."""
        return bool(self.passed) and not self.over_cap


@dataclass(frozen=True)
class RunInfo:
    benchmark: str
    run_id: str
    date: str  # ISO date-time of the start
    model: str
    commit: str
    params: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Totals:
    tasks: int
    passed: int
    cost_usd: float
    over_cap: int
    errors: int

    @property
    def pass_rate(self) -> float:
        return self.passed / self.tasks if self.tasks else 0.0

    @property
    def cost_per_task(self) -> float:
        return self.cost_usd / self.tasks if self.tasks else 0.0


def totals(rows: list[TaskRow]) -> Totals:
    return Totals(
        tasks=len(rows),
        passed=sum(1 for r in rows if r.counted_pass),
        cost_usd=sum(r.cost_usd for r in rows),
        over_cap=sum(1 for r in rows if r.over_cap),
        errors=sum(1 for r in rows if r.passed is None),
    )


def _cell(v: Any) -> str:
    return str(v).replace("|", "\\|").replace("\n", " ")


def _flat(params: dict[str, Any], prefix: str = "") -> list[tuple[str, Any]]:
    out: list[tuple[str, Any]] = []
    for k, v in params.items():
        key = f"{prefix}{k}"
        if isinstance(v, dict) and v:
            out += _flat(v, key + ".")
        else:
            out.append((key, v))
    return out


def render_summary(info: RunInfo, rows: list[TaskRow]) -> str:
    t = totals(rows)
    lines = [
        f"# {info.benchmark} run {info.run_id}",
        "",
        f"- Date: {info.date}",
        f"- Model: {info.model}",
        f"- jllm commit: {info.commit}",
        f"- Passed: {t.passed}/{t.tasks} ({t.pass_rate:.1%})",
        f"- Cost: {t.cost_usd:.4f} USD ({t.cost_per_task:.4f} per task)",
        f"- Over a cap (scored as failed): {t.over_cap}",
        f"- Not scored (errors): {t.errors}",
        "",
        "## Parameters",
        "",
        "| Parameter | Value |",
        "|---|---|",
    ]
    lines += [f"| {_cell(k)} | {_cell(v)} |" for k, v in sorted(_flat(info.params))]
    lines += [
        "",
        "## Tasks",
        "",
        "| Task | Result | Outcome | Turns | Cost USD | Duration s | Note |",
        "|---|---|---|---|---|---|---|",
    ]
    for r in sorted(rows, key=lambda r: r.task_id):
        result = "error" if r.passed is None else ("pass" if r.counted_pass else "fail")
        note = "; ".join([*r.over_cap, *([r.error] if r.error else [])])
        lines.append(
            f"| {_cell(r.task_id)} | {result} | {_cell(r.outcome)} | {r.turns} | {r.cost_usd:.4f} "
            f"| {r.duration_ms / 1000:.0f} | {_cell(note)} |"
        )
    return "\n".join(lines) + "\n"


def index_row(info: RunInfo, rows: list[TaskRow]) -> str:
    t = totals(rows)
    return (
        f"| {info.date[:10]} | [{_cell(info.run_id)}]({info.run_id}/summary.md) | {_cell(info.model)} "
        f"| {t.passed}/{t.tasks} | {t.pass_rate:.1%} | {t.cost_usd:.2f} | {t.cost_per_task:.4f} | {_cell(info.commit[:12])} |"
    )


def update_index(index: Path, info: RunInfo, rows: list[TaskRow]) -> None:
    """Inserts or replaces the row of ``info.run_id``; rows stay sorted newest first."""
    marker = f"[{info.run_id}]("
    existing: list[str] = []
    if index.is_file():
        for line in index.read_text(encoding="utf-8").splitlines():
            if line.startswith("| ") and "](" in line and marker not in line:
                existing.append(line)
    existing.append(index_row(info, rows))
    existing.sort(key=lambda line: (line.split("|")[1].strip(), line), reverse=True)
    index.parent.mkdir(parents=True, exist_ok=True)
    index.write_text(
        f"# {info.benchmark} runs\n\n" + INDEX_HEADER + "\n".join(existing) + "\n", encoding="utf-8", newline="\n"
    )


def write_summary(results: Path, info: RunInfo, rows: list[TaskRow]) -> Path:
    """Writes ``<results>/<benchmark>/<run_id>/summary.md`` and updates the benchmark index; returns the summary."""
    bench = results / info.benchmark
    summary = bench / info.run_id / "summary.md"
    summary.parent.mkdir(parents=True, exist_ok=True)
    summary.write_text(render_summary(info, rows), encoding="utf-8", newline="\n")
    update_index(bench / "index.md", info, rows)
    return summary
