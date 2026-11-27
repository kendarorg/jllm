import socket

import pytest

from jllm_eval_common import dockerhost


def test_candidates_order():
    assert dockerhost.candidates({"DOCKER_HOST": " tcp://h:2375 "}, unix=True) == [
        "tcp://h:2375", dockerhost.LOCALHOST_TCP, dockerhost.UNIX_SOCKET]
    assert dockerhost.candidates({}, unix=False) == [dockerhost.LOCALHOST_TCP]
    assert dockerhost.candidates({"DOCKER_HOST": dockerhost.LOCALHOST_TCP}, unix=False) == [dockerhost.LOCALHOST_TCP]


def test_reachable_tcp():
    with socket.socket() as srv:
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        port = srv.getsockname()[1]
        assert dockerhost.reachable(f"tcp://127.0.0.1:{port}")
    assert not dockerhost.reachable(f"tcp://127.0.0.1:{port}")
    assert not dockerhost.reachable("unix:///no/such/docker.sock")
    assert dockerhost.reachable("ssh://user@h")
    assert not dockerhost.reachable("")


def test_select_falls_through(monkeypatch):
    up = {"tcp://localhost:2375"}
    monkeypatch.setattr(dockerhost, "reachable", lambda u: u in up)
    assert dockerhost.select({"DOCKER_HOST": "tcp://down:2375"}, unix=True) == "tcp://localhost:2375"
    up.clear()
    with pytest.raises(SystemExit, match="tcp://down:2375, tcp://localhost:2375, unix:///var/run/docker.sock"):
        dockerhost.select({"DOCKER_HOST": "tcp://down:2375"}, unix=True)
