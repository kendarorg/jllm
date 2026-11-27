"""Runs install.sh with a fake java 21 on PATH (no download, no network)."""

import os
import shutil
import subprocess

import pytest

from jllm_eval_common.runinfo import install_script_path

SH = shutil.which("sh")
pytestmark = pytest.mark.skipif(SH is None, reason="no POSIX sh")


def posix(p):
    return str(p).replace("\\", "/")


def run_install(tmp_path, java_version_line):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    java = bin_dir / "java"
    java.write_text(f"#!/bin/sh\necho '{java_version_line}' >&2\n", encoding="utf-8", newline="\n")
    java.chmod(0o755)
    jar = tmp_path / "jllm-cli-1.0-jllm.jar"
    jar.write_bytes(b"PK")
    dest = tmp_path / "opt"
    env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ.get("PATH", ""))
    proc = subprocess.run(
        [SH, posix(install_script_path()), posix(jar), posix(dest)], capture_output=True, text=True, env=env
    )
    return proc, dest


def test_uses_installed_java_21(tmp_path):
    proc, dest = run_install(tmp_path, 'openjdk version "21.0.5" 2024-10-15')
    assert proc.returncode == 0, proc.stderr + proc.stdout
    assert "using installed java 21" in proc.stdout
    assert (dest / "jllm.jar").read_bytes() == b"PK"
    launcher = (dest / "jllm").read_text(encoding="utf-8")
    assert "-jar" in launcher and "jllm.jar" in launcher
    assert not (dest / "jre").exists()


def test_missing_jar_fails(tmp_path):
    proc = subprocess.run([SH, posix(install_script_path()), posix(tmp_path / "none.jar")], capture_output=True, text=True)
    assert proc.returncode != 0
    assert "jar not found" in proc.stderr
