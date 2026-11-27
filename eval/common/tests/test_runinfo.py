import os
import time

import pytest

from jllm_eval_common.budget import Limits
from jllm_eval_common.runinfo import (
    JllmParams,
    find_jar,
    install_script,
    pricing_from_env,
    repo_root,
)


def fake_repo(tmp_path):
    (tmp_path / "pom.xml").write_text("<project/>")
    (tmp_path / "jllm-cli" / "target").mkdir(parents=True)
    return tmp_path


def test_repo_root_found_from_nested_dir(tmp_path):
    root = fake_repo(tmp_path)
    nested = root / "eval" / "x"
    nested.mkdir(parents=True)
    assert repo_root(nested) == root.resolve()


def test_find_jar_prefers_env(tmp_path):
    jar = tmp_path / "my.jar"
    jar.write_bytes(b"x")
    assert find_jar(tmp_path, env={"JLLM_CLI_JAR": str(jar)}) == jar
    with pytest.raises(FileNotFoundError):
        find_jar(tmp_path, env={"JLLM_CLI_JAR": str(tmp_path / "nope.jar")})


def test_find_jar_picks_newest_shaded_jar(tmp_path):
    root = fake_repo(tmp_path)
    target = root / "jllm-cli" / "target"
    old = target / "jllm-cli-1.0.0-jllm.jar"
    new = target / "jllm-cli-1.1.0-jllm.jar"
    (target / "jllm-cli-1.1.0.jar").write_bytes(b"thin")
    old.write_bytes(b"o")
    new.write_bytes(b"n")
    now = time.time()
    os.utime(old, (now - 100, now - 100))
    os.utime(new, (now, now))
    assert find_jar(root, env={}) == new
    for j in target.iterdir():
        j.unlink()
    with pytest.raises(FileNotFoundError):
        find_jar(root, env={})


def test_cli_args():
    p = JllmParams(
        limits=Limits(max_turns=50, timeout_sec=1800),
        provider="anthropic",
        model="claude-sonnet-5-5",
        pricing={"input": 2.0, "output": 10.0},
        small_model=True,
        extra_args=("--context-strategy", "compaction"),
    )
    args = p.cli_args("/testbed", "/tmp/r.json")
    assert args[:6] == ["--mode", "auto", "--sandbox", "none", "--workdir", "/testbed"]
    s = " ".join(args)
    assert "--provider anthropic --model claude-sonnet-5-5" in s
    assert "--small-model" in args
    assert "--pricing.input-per-million 2.0 --pricing.output-per-million 10.0" in s
    assert "--max-turns 50 --timeout 1800 --json-result /tmp/r.json" in s
    assert args[-2:] == ["--context-strategy", "compaction"]
    assert "--prompt" not in args and "--api-key" not in args


def test_pricing_from_env():
    assert pricing_from_env({"PRICE_INPUT": "2", "PRICE_CACHE_READ": " 0.1 ", "PRICE_OUTPUT": ""}) == {
        "input": 2.0,
        "cache-read": 0.1,
    }


def test_install_script_is_posix_sh_with_pinned_jre():
    text = install_script()
    assert text.startswith("#!/bin/sh")
    for asset in ("x64_linux", "aarch64_linux", "x64_alpine-linux", "aarch64_mac"):
        assert f"OpenJDK21U-jre_{asset}_hotspot_21.0.13.1_2.tar.gz" in text
    assert "\r\n" not in text
