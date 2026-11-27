"""Which docker daemon the runners use, in one place. Order, first one that accepts a connection wins:

1. ``DOCKER_HOST``, when set;
2. ``tcp://localhost:2375`` (for instance dockerd in WSL listening on localhost);
3. the unix socket ``/var/run/docker.sock``, on Linux and macOS only.

``python -m jllm_eval_common.dockerhost`` prints the chosen endpoint (exit 1 and the tried ones on stderr when none
answers); the run scripts export it as ``DOCKER_HOST`` so every docker client they start talks to the same daemon.
"""

from __future__ import annotations

import os
import socket
import sys
from typing import Mapping
from urllib.parse import urlsplit

LOCALHOST_TCP = "tcp://localhost:2375"
UNIX_SOCKET = "unix:///var/run/docker.sock"
CONNECT_TIMEOUT_SEC = 0.5


def is_unix() -> bool:
    return os.name != "nt"


def candidates(env: Mapping[str, str], unix: bool) -> list[str]:
    out: list[str] = []
    host = (env.get("DOCKER_HOST") or "").strip()
    if host:
        out.append(host)
    if LOCALHOST_TCP not in out:
        out.append(LOCALHOST_TCP)
    if unix and UNIX_SOCKET not in out:
        out.append(UNIX_SOCKET)
    return out


def reachable(uri: str) -> bool:
    """True when something accepts a connection at ``uri``. ``tcp``/``http(s)`` and ``unix`` are probed; other schemes
    (``ssh``, ``npipe``) cannot be probed here and count as reachable: the docker client decides."""
    try:
        u = urlsplit(uri.strip())
    except ValueError:
        return False
    scheme = u.scheme.lower()
    if scheme in ("tcp", "http", "https"):
        if not u.hostname:
            return False
        try:
            with socket.create_connection((u.hostname, u.port or 2375), timeout=CONNECT_TIMEOUT_SEC):
                return True
        except (OSError, ValueError):
            return False
    if scheme == "unix":
        if not u.path or not os.path.exists(u.path) or not hasattr(socket, "AF_UNIX"):
            return False
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
                s.settimeout(CONNECT_TIMEOUT_SEC)
                s.connect(u.path)
                return True
        except OSError:
            return False
    return bool(scheme)


def select(env: Mapping[str, str] | None = None, unix: bool | None = None) -> str:
    """The first reachable candidate; SystemExit listing the tried ones when none answers."""
    tried = candidates(os.environ if env is None else env, is_unix() if unix is None else unix)
    for uri in tried:
        if reachable(uri):
            return uri
    raise SystemExit("no docker daemon reachable (tried " + ", ".join(tried) + ")")


def main() -> int:
    try:
        print(select())
    except SystemExit as e:
        print(e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
