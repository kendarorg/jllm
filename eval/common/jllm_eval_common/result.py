"""Reads the file written by ``jllm-cli --json-result FILE`` and maps CLI exit codes to outcomes.

File shape: ``{chatId, outcome, turns, durationMs, usage{input,output,cacheRead,cacheCreation}, costUsd,
toolCalls[{name,input,isError}], error}``. The CLI writes it also on failure; a missing or unreadable file is reported
as outcome ``missing`` with the reason in ``error``.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

# CLI exit codes of the --prompt path.
EXIT_COMPLETED = 0
EXIT_FAILED = 1  # failed, refused
EXIT_STUCK = 2
EXIT_LIMIT = 3  # max_turns, truncated, unverified
EXIT_CANCELLED = 4  # cancelled, timeout
EXIT_USAGE = 64

OUTCOMES_BY_EXIT: dict[int, tuple[str, ...]] = {
    EXIT_COMPLETED: ("completed",),
    EXIT_FAILED: ("failed", "refused"),
    EXIT_STUCK: ("stuck",),
    EXIT_LIMIT: ("max_turns", "truncated", "unverified"),
    EXIT_CANCELLED: ("cancelled", "timeout"),
}

MISSING = "missing"


def exit_code_meaning(code: int) -> str:
    if code == EXIT_USAGE:
        return "usage error"
    outcomes = OUTCOMES_BY_EXIT.get(code)
    return "/".join(outcomes) if outcomes else f"unexpected exit code {code}"


@dataclass(frozen=True)
class Usage:
    input: int = 0
    output: int = 0
    cache_read: int = 0
    cache_creation: int = 0

    @property
    def total_input(self) -> int:
        """Input tokens including cache reads and writes."""
        return self.input + self.cache_read + self.cache_creation


@dataclass(frozen=True)
class ToolCall:
    name: str
    input: Any = None
    is_error: bool = False


@dataclass(frozen=True)
class JllmResult:
    outcome: str
    chat_id: str | None = None
    turns: int = 0
    duration_ms: int = 0
    usage: Usage = field(default_factory=Usage)
    cost_usd: float = 0.0
    tool_calls: tuple[ToolCall, ...] = ()
    error: str | None = None

    @property
    def completed(self) -> bool:
        return self.outcome == "completed"

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def missing(cls, reason: str) -> JllmResult:
        return cls(outcome=MISSING, error=reason)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> JllmResult:
        usage = data.get("usage") or {}
        calls = data.get("toolCalls") or []
        return cls(
            outcome=str(data.get("outcome") or MISSING),
            chat_id=data.get("chatId"),
            turns=int(data.get("turns") or 0),
            duration_ms=int(data.get("durationMs") or 0),
            usage=Usage(
                input=int(usage.get("input") or 0),
                output=int(usage.get("output") or 0),
                cache_read=int(usage.get("cacheRead") or 0),
                cache_creation=int(usage.get("cacheCreation") or 0),
            ),
            cost_usd=float(data.get("costUsd") or 0.0),
            tool_calls=tuple(
                ToolCall(name=str(c.get("name")), input=c.get("input"), is_error=bool(c.get("isError")))
                for c in calls
            ),
            error=data.get("error"),
        )


def read_result(path: str | Path) -> JllmResult:
    """Parses a --json-result file; never raises for a missing or malformed file."""
    p = Path(path)
    if not p.is_file():
        return JllmResult.missing(f"no result file at {p}")
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        return JllmResult.missing(f"unreadable result file {p}: {e}")
    if not isinstance(data, dict):
        return JllmResult.missing(f"result file {p} is not a JSON object")
    return JllmResult.from_dict(data)
