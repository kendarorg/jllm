"""Run parameters shared by the runners: where the jar is, the jllm commit, the CLI command line, the key variables.

Model, system prompt, limits and the jllm commit are fixed per run and recorded with it; they are not tuned per task.
"""

from __future__ import annotations

import glob
import os
import subprocess
from dataclasses import asdict, dataclass, field
from importlib import resources
from pathlib import Path
from typing import Any

from .budget import Limits

# Environment variables handed to the CLI inside the container, by name only: values never appear on a command line,
# in a preset or in a recorded run.
KEY_ENV_VARS = (
    "ANTHROPIC_API_KEY",
    "OPENAI_API_KEY",
    "GEMINI_API_KEY",
    "GROK_API_KEY",
    "QWEN_API_KEY",
    "BRAVE_API_KEY",
)

# Launcher created by install.sh.
LAUNCHER = "/opt/jllm/jllm"


def repo_root(start: Path | None = None) -> Path:
    """The jllm checkout: the nearest parent holding both ``pom.xml`` and ``jllm-cli/``."""
    here = (start or Path(__file__)).resolve()
    for p in (here, *here.parents):
        if (p / "pom.xml").is_file() and (p / "jllm-cli").is_dir():
            return p
    raise FileNotFoundError(f"no jllm checkout above {here}")


def results_dir(root: Path | None = None) -> Path:
    return (root or repo_root()) / "eval" / "results"


def find_jar(root: Path | None = None, env: dict[str, str] | None = None) -> Path:
    """``JLLM_CLI_JAR`` when set, else the newest ``jllm-cli/target/jllm-cli-*-jllm.jar``."""
    env = os.environ if env is None else env
    explicit = env.get("JLLM_CLI_JAR")
    if explicit:
        p = Path(explicit)
        if not p.is_file():
            raise FileNotFoundError(f"JLLM_CLI_JAR points to a missing file: {p}")
        return p
    target = (root or repo_root()) / "jllm-cli" / "target"
    jars = sorted(glob.glob(str(target / "jllm-cli-*-jllm.jar")), key=os.path.getmtime)
    if not jars:
        raise FileNotFoundError(
            f"no jllm-cli-*-jllm.jar in {target}: build it (mvn -pl jllm-cli -am package) or set JLLM_CLI_JAR"
        )
    return Path(jars[-1])


def git_commit(root: Path | None = None) -> str:
    """HEAD of the checkout, with ``-dirty`` when tracked files changed; ``unknown`` without git."""
    cwd = root or repo_root()
    try:
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=cwd, capture_output=True, text=True, check=True
        ).stdout.strip()
        dirty = subprocess.run(
            ["git", "status", "--porcelain", "--untracked-files=no"], cwd=cwd, capture_output=True, text=True, check=True
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"
    return head + ("-dirty" if dirty else "")


def install_script() -> str:
    """Text of ``install.sh``: packaged copy in a wheel, the source tree file otherwise."""
    packaged = resources.files(__package__).joinpath("install.sh")
    if packaged.is_file():
        return packaged.read_text(encoding="utf-8")
    return (Path(__file__).resolve().parent.parent / "install.sh").read_text(encoding="utf-8")


def install_script_path() -> Path:
    packaged = Path(__file__).resolve().parent / "install.sh"
    return packaged if packaged.is_file() else Path(__file__).resolve().parent.parent / "install.sh"


@dataclass(frozen=True)
class JllmParams:
    """The CLI settings of one run (everything except the per-task prompt and workdir)."""

    limits: Limits
    provider: str | None = None
    model: str | None = None
    base_url: str | None = None
    context_tokens: int | None = None
    max_tokens: int | None = None
    small_model: bool = False
    system_prompt_file: str | None = None  # path inside the container
    pricing: dict[str, float] = field(default_factory=dict)  # input/output/cache-read/cache-write per million
    extra_args: tuple[str, ...] = ()

    def cli_args(self, workdir: str, json_result: str) -> list[str]:
        """Arguments after the launcher, without ``--prompt``."""
        args = ["--mode", "auto", "--sandbox", "none", "--workdir", workdir]
        if self.provider:
            args += ["--provider", self.provider]
        if self.model:
            args += ["--model", self.model]
        if self.base_url:
            args += ["--base-url", self.base_url]
        if self.context_tokens:
            args += ["--context-tokens", str(self.context_tokens)]
        if self.max_tokens:
            args += ["--max-tokens", str(self.max_tokens)]
        if self.small_model:
            args.append("--small-model")
        if self.system_prompt_file:
            args += ["--system-prompt-file", self.system_prompt_file]
        for key in ("input", "output", "cache-read", "cache-write"):
            if key in self.pricing:
                args += [f"--pricing.{key}-per-million", repr(float(self.pricing[key]))]
        args += ["--max-turns", str(self.limits.max_turns), "--timeout", str(self.limits.timeout_sec)]
        args += ["--json-result", json_result]
        args += list(self.extra_args)
        return args

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["extra_args"] = list(self.extra_args)
        return d


def pricing_from_env(env: dict[str, str] | None = None) -> dict[str, float]:
    """``PRICE_INPUT``, ``PRICE_OUTPUT``, ``PRICE_CACHE_READ``, ``PRICE_CACHE_WRITE`` (USD per million tokens)."""
    env = os.environ if env is None else env
    out: dict[str, float] = {}
    for var, key in (
        ("PRICE_INPUT", "input"),
        ("PRICE_OUTPUT", "output"),
        ("PRICE_CACHE_READ", "cache-read"),
        ("PRICE_CACHE_WRITE", "cache-write"),
    ):
        v = env.get(var, "").strip()
        if v:
            out[key] = float(v)
    return out
