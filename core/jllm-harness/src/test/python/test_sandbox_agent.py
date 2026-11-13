"""Tests of sandbox-agent.py.

Run from the repository root:
    python -m unittest jllm-harness/src/test/python/test_sandbox_agent.py -v

The daemon runs as a subprocess on a random local port with a temporary workspace; `sh` must be on the PATH.
"""
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

AGENT = (Path(__file__).resolve().parents[2] / "main" / "resources" / "org" / "kendar" / "llm" / "harness"
         / "sandbox" / "sandbox-agent.py")
TOKEN = "a" * 64


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Base(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.ws = os.path.realpath(cls.tmp.name)
        cls.port = free_port()
        env = dict(os.environ, JLLM_TOKEN=TOKEN, JLLM_PORT=str(cls.port), JLLM_BIND="127.0.0.1",
                   JLLM_WORKSPACE=cls.ws)
        env.pop("JLLM_SCRATCH", None)
        cls.proc = subprocess.Popen([sys.executable, str(AGENT)], env=env, stderr=subprocess.DEVNULL)
        for _ in range(100):
            try:
                cls.call("GET", "/health")
                return
            except Exception:
                time.sleep(0.1)
        raise RuntimeError("daemon did not start")

    @classmethod
    def tearDownClass(cls):
        cls.proc.kill()
        cls.proc.wait()
        cls.tmp.cleanup()

    @classmethod
    def call(cls, method, path, body=None, token=TOKEN, raw=False):
        data = body if isinstance(body, bytes) else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request("http://127.0.0.1:%d%s" % (cls.port, path), data=data, method=method)
        if token is not None:
            req.add_header("X-Jllm-Token", token)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                payload = r.read()
                return r.status, cls._decode(payload, raw)
        except urllib.error.HTTPError as e:
            return e.code, cls._decode(e.read(), raw)

    @staticmethod
    def _decode(payload, raw):
        if raw:
            return payload
        if payload[:1] in (b"{", b"["):
            return json.loads(payload)
        return payload.decode()

    def run_cmd(self, command, timeout=30, cwd=None):
        code, r = self.call("POST", "/exec", {"command": command, "cwd": cwd or self.ws, "timeoutSeconds": timeout})
        self.assertEqual(200, code, r)
        code, res = self.call("GET", "/exec/" + r["id"] + "?wait=25")
        self.assertEqual(200, code)
        return r["id"], res


class TokenTest(Base):
    def test_no_token_is_401_and_empty(self):
        for method, path in [("GET", "/health"), ("POST", "/exec"), ("GET", "/file?path=x"), ("GET", "/nothing")]:
            code, body = self.call(method, path, token=None, body={} if method == "POST" else None, raw=True)
            self.assertEqual(401, code, path)
            self.assertEqual(b"", body, path)

    def test_wrong_token_is_401_and_nothing_runs(self):
        marker = os.path.join(self.ws, "should-not-exist")
        code, body = self.call("POST", "/exec", {"command": "touch " + marker, "cwd": self.ws},
                               token="b" * 64, raw=True)
        self.assertEqual((401, b""), (code, body))
        time.sleep(0.3)
        self.assertFalse(os.path.exists(marker))

    def test_right_token(self):
        self.assertEqual(200, self.call("GET", "/health")[0])

    def raw_request(self, head):
        with socket.create_connection(("127.0.0.1", self.port), timeout=10) as s:
            s.sendall(head.encode())
            data = b""
            while True:
                chunk = s.recv(4096)  # the daemon closes the connection: no wait for the announced body
                if not chunk:
                    return data
                data += chunk

    def test_401_does_not_read_the_announced_body(self):
        started = time.time()
        resp = self.raw_request("POST /exec HTTP/1.1\r\nHost: x\r\nContent-Length: 99999999999\r\n\r\n")
        self.assertTrue(resp.startswith(b"HTTP/1.1 401"), resp)
        self.assertLess(time.time() - started, 5)

    def test_oversized_body_is_413_without_reading_it(self):
        resp = self.raw_request("PUT /file?path=big HTTP/1.1\r\nHost: x\r\nX-Jllm-Token: %s\r\n"
                                "Content-Length: 99999999999\r\n\r\n" % TOKEN)
        self.assertTrue(resp.startswith(b"HTTP/1.1 413"), resp)


class ExecTest(Base):
    def test_output_and_exit_code(self):
        _, res = self.run_cmd("echo hi; echo err 1>&2; exit 3")
        self.assertEqual(3, res["exitCode"])
        self.assertIn("hi", res["stdout"])
        self.assertIn("err", res["stdout"])
        self.assertFalse(res["timedOut"])

    def test_token_not_in_command_environment(self):
        _, res = self.run_cmd("env")
        self.assertNotIn(TOKEN, res["stdout"])
        self.assertNotIn("JLLM_TOKEN", res["stdout"])

    def test_cwd_and_fresh_shell(self):
        os.makedirs(os.path.join(self.ws, "sub"), exist_ok=True)
        _, res = self.run_cmd("pwd", cwd=os.path.join(self.ws, "sub"))
        self.assertTrue(res["stdout"].strip().endswith("sub"))
        self.run_cmd("export FOO=bar; cd /")
        _, res = self.run_cmd('echo "[$FOO]"')
        self.assertIn("[]", res["stdout"])

    def test_files_persist_between_commands(self):
        self.run_cmd("echo one > persisted.txt")
        _, res = self.run_cmd("cat persisted.txt")
        self.assertIn("one", res["stdout"])

    def test_timeout_kills(self):
        started = time.time()
        _, res = self.run_cmd("sleep 30", timeout=1)
        self.assertTrue(res["timedOut"])
        self.assertLess(time.time() - started, 15)

    def test_delete_kills(self):
        code, r = self.call("POST", "/exec", {"command": "sleep 30", "cwd": self.ws, "timeoutSeconds": 60})
        self.assertEqual({"running": True}, self.call("GET", "/exec/" + r["id"])[1])
        self.assertEqual(200, self.call("DELETE", "/exec/" + r["id"])[0])
        self.assertEqual(404, self.call("GET", "/exec/" + r["id"])[0])

    LONG = "i=1; while [ $i -le 20000 ]; do echo line-$i; i=$((i+1)); done"

    def test_long_output_keeps_tail_and_capped_log(self):
        _, res = self.run_cmd(self.LONG)
        self.assertEqual(0, res["exitCode"])
        self.assertTrue(res["stdout"].rstrip().endswith("line-20000"))
        self.assertLessEqual(len(res["stdout"].encode()), 50 * 1024)
        self.assertGreater(res["omittedLines"], 0)
        self.assertTrue(res["logPath"].startswith(os.path.join(self.ws, ".jllm", "scratchpad")))
        log = Path(res["logPath"]).read_bytes()
        self.assertTrue(log.startswith(b"line-1\n"))
        self.assertTrue(log.endswith(b"[log truncated at 51200 bytes]\n"), log[-80:])
        self.assertLessEqual(len(log), 50 * 1024 + 100)

    def test_log_cap_from_the_request(self):
        code, r = self.call("POST", "/exec", {"command": self.LONG, "cwd": self.ws, "timeoutSeconds": 60,
                                              "logMaxBytes": 0})
        self.assertEqual(200, code, r)
        res = self.call("GET", "/exec/" + r["id"] + "?wait=25")[1]
        self.assertEqual(20000, len(Path(res["logPath"]).read_text().splitlines()))

    def test_returns_when_the_shell_exits_although_a_background_job_holds_the_output(self):
        started = time.time()
        eid, res = self.run_cmd("sleep 20 & echo started", timeout=60)
        self.assertLess(time.time() - started, 15)
        self.assertEqual(0, res["exitCode"])
        self.assertFalse(res["timedOut"])
        self.assertIn("started", res["stdout"])
        self.assertEqual(200, self.call("DELETE", "/exec/" + eid)[0])  # the harness releases it: job killed


class FileTest(Base):
    def test_write_read_append_stat(self):
        self.assertEqual(200, self.call("PUT", "/file?path=d/a.txt", b"hello")[0])
        self.assertEqual(200, self.call("PUT", "/file?path=d/a.txt&append=1", b" world")[0])
        self.assertEqual(b"hello world", self.call("GET", "/file?path=d/a.txt", raw=True)[1])
        self.assertEqual(b"world", self.call("GET", "/file?path=d/a.txt&offset=6", raw=True)[1])
        st = self.call("GET", "/stat?path=d/a.txt")[1]
        self.assertEqual(("file", 11), (st["type"], st["size"]))
        self.assertFalse(self.call("GET", "/stat?path=nope")[1]["exists"])
        self.assertEqual("dir", self.call("GET", "/stat?path=d")[1]["type"])

    def test_missing_is_404(self):
        self.assertEqual(404, self.call("GET", "/file?path=missing.txt")[0])

    def test_jail(self):
        for p in ["../x", "a/../../x", "/etc/passwd", "/"]:
            self.assertEqual(400, self.call("GET", "/file?path=" + p)[0], p)
            self.assertEqual(400, self.call("PUT", "/file?path=" + p, b"x")[0], p)

    @unittest.skipUnless(os.name == "posix", "needs symlinks")
    def test_symlink_escape(self):
        os.symlink("/etc", os.path.join(self.ws, "link-out"))
        self.assertEqual(400, self.call("GET", "/file?path=link-out/passwd")[0])

    def test_glob(self):
        for f in ["g/A.java", "g/sub/B.java", "g/sub/c.txt", "Top.java"]:
            self.call("PUT", "/file?path=" + f, b"x")
        hits = self.call("GET", "/glob?pattern=**/*.java")[1].split("\n")
        self.assertIn("Top.java", hits)
        self.assertIn("g/sub/B.java", hits)
        self.assertNotIn("g/sub/c.txt", hits)
        self.assertEqual(["g/A.java"], self.call("GET", "/glob?pattern=g/*.java")[1].split("\n"))

    def test_glob_classes_cannot_leak_into_the_regex(self):
        for f in ["gc/a1.txt", "gc/b2.txt", "gc/x.txt", "secret/key.txt"]:
            self.call("PUT", "/file?path=" + f, b"x")
        q = urllib.parse.quote
        self.assertEqual("gc/a1.txt", self.call("GET", "/glob?pattern=" + q("gc/[a]1.txt"))[1])
        self.assertEqual(["gc/a1.txt", "gc/b2.txt"],
                         self.call("GET", "/glob?pattern=" + q("gc/[a-b][0-9].txt"))[1].split("\n"))
        self.assertEqual("gc/x.txt", self.call("GET", "/glob?pattern=" + q("gc/[!ab]*.txt"))[1])
        self.assertEqual("", self.call("GET", "/glob?pattern=" + q("gc/[.*]"))[1])
        for bad in ["gc/[]{][a],**", "gc/[a/b]", "{a,b"]:
            code, body = self.call("GET", "/glob?pattern=" + q(bad))
            self.assertEqual(400, code, (bad, body))

    def test_grep(self):
        self.call("PUT", "/file?path=gr/x.txt", b"alpha\nbeta needle\n")
        self.call("PUT", "/file?path=gr/y.md", b"needle again\n")
        out = self.call("POST", "/grep", {"pattern": "needle", "path": "gr"})[1].split("\n")
        self.assertEqual(["gr/x.txt:2:beta needle", "gr/y.md:1:needle again"], out)
        out = self.call("POST", "/grep", {"pattern": "needle", "path": "gr", "glob": "**/*.md"})[1]
        self.assertEqual("gr/y.md:1:needle again", out)
        self.assertEqual(400, self.call("POST", "/grep", {"pattern": "("})[0])

    def test_grep_glob_is_relative_to_path(self):
        self.call("PUT", "/file?path=gp/a.java", b"needle\n")
        self.call("PUT", "/file?path=gp/sub/b.java", b"needle\n")
        self.call("PUT", "/file?path=gp/c.txt", b"needle\n")
        # relative to the searched path: "*.java" is a top-level file of gp only
        out = self.call("POST", "/grep", {"pattern": "needle", "path": "gp", "glob": "*.java"})[1]
        self.assertEqual("gp/a.java:1:needle", out)
        out = self.call("POST", "/grep", {"pattern": "needle", "path": "gp", "glob": "sub/*.java"})[1]
        self.assertEqual("gp/sub/b.java:1:needle", out)
        # relative to the work dir: still accepted
        out = self.call("POST", "/grep", {"pattern": "needle", "path": "gp", "glob": "gp/*.java"})[1]
        self.assertEqual("gp/a.java:1:needle", out)
        # a single file: the glob matches its name
        out = self.call("POST", "/grep", {"pattern": "needle", "path": "gp/c.txt", "glob": "*.txt"})[1]
        self.assertEqual("gp/c.txt:1:needle", out)

    def test_scratch_root(self):
        self.assertEqual(200, self.call("PUT", "/file?root=scratch&path=a1/n.txt", b"s")[0])
        self.assertTrue(os.path.exists(os.path.join(self.ws, ".jllm", "scratchpad", "a1", "n.txt")))
        self.assertEqual(400, self.call("GET", "/file?root=scratch&path=../../x")[0])


class BrowseTest(Base):
    def test_list(self):
        for f in ["ls/b.txt", "ls/a.txt", "ls/.hidden", "ls/dir/x"]:
            self.call("PUT", "/file?path=" + f, b"x")
        self.assertEqual("a.txt\nb.txt\ndir/", self.call("GET", "/list?path=ls")[1])
        self.assertIn(".hidden", self.call("GET", "/list?path=ls&all=1")[1])
        j = self.call("GET", "/list?path=ls&format=json")[1]
        self.assertEqual(["a.txt", "b.txt", "dir"], [e["name"] for e in j["entries"]])
        self.assertTrue(j["entries"][2]["dir"])
        self.assertIn("a.txt", self.call("GET", "/list?path=ls&long=1")[1])
        self.assertEqual("a.txt", self.call("GET", "/list?path=ls/a.txt")[1])
        self.assertEqual(404, self.call("GET", "/list?path=ls/none")[0])

    def test_list_cap(self):
        self.run_cmd("mkdir many && cd many && i=0; while [ $i -lt 1100 ]; do : > f$i; i=$((i+1)); done")
        out = self.call("GET", "/list?path=many")[1].split("\n")
        self.assertEqual(1001, len(out))
        self.assertEqual("... 100 more", out[-1])

    def test_tree_and_download(self):
        self.call("PUT", "/file?path=tr/a/b/c.txt", b"deep")
        t = self.call("GET", "/tree?path=tr&depth=2")[1]
        self.assertIn("a/", t)
        self.assertIn("b/", t)
        self.assertNotIn("c.txt", t)
        self.assertIn("c.txt", self.call("GET", "/tree?path=tr&depth=3")[1])
        self.assertEqual(b"deep", self.call("GET", "/download?path=tr/a/b/c.txt", raw=True)[1])


class ModifyTest(Base):
    def test_mkdir_move_delete(self):
        self.assertEqual(200, self.call("POST", "/mkdir?path=m/sub")[0])
        self.assertEqual(409, self.call("POST", "/mkdir?path=m/sub")[0])
        self.call("PUT", "/file?path=m/sub/f.txt", b"x")
        self.assertEqual(200, self.call("POST", "/move?from=m/sub/f.txt&to=m/g.txt")[0])
        self.assertEqual(409, self.call("POST", "/move?from=m/g.txt&to=m/sub")[0])
        self.assertEqual(404, self.call("GET", "/file?path=m/sub/f.txt")[0])
        self.assertEqual(200, self.call("DELETE", "/file?path=m")[0])
        self.assertFalse(self.call("GET", "/stat?path=m")[1]["exists"])

    def test_cannot_delete_root_or_escape(self):
        self.assertEqual(400, self.call("DELETE", "/file?path=.")[0])
        self.assertEqual(400, self.call("DELETE", "/file?path=../x")[0])
        self.assertEqual(400, self.call("POST", "/move?from=a&to=../b")[0])


class BackupTest(Base):
    def test_backup_and_setup_round_trip(self):
        import io
        import zipfile
        self.call("PUT", "/file?path=bk/a.txt", b"alpha")
        self.call("PUT", "/file?path=bk/sub/b.txt", b"beta")
        self.call("PUT", "/file?root=scratch&path=main/tmp.txt", b"scratch")
        self.call("PUT", "/file?path=.jllm/history/h.txt", b"history")
        self.call("PUT", "/file?path=.jllm/checkpoints/c/1/created.txt", b"x")
        self.run_cmd("printf '#!/bin/sh' > bk/run.sh && chmod +x bk/run.sh")
        code, data = self.call("GET", "/backup", raw=True)
        self.assertEqual(200, code)
        names = zipfile.ZipFile(io.BytesIO(data)).namelist()
        self.assertIn("bk/a.txt", names)
        self.assertIn(".jllm/checkpoints/c/1/created.txt", names)
        self.assertFalse([n for n in names if "scratchpad" in n or "history" in n], names)
        self.assertEqual(200, self.call("DELETE", "/file?path=bk")[0])
        code, res = self.call("POST", "/setup", data)
        self.assertEqual(200, code, res)
        self.assertEqual(b"beta", self.call("GET", "/file?path=bk/sub/b.txt", raw=True)[1])
        if os.name == "posix":
            self.assertTrue(os.access(os.path.join(self.ws, "bk", "run.sh"), os.X_OK))

    def test_setup_refuses_traversal(self):
        import io
        import zipfile
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("../evil.txt", "x")
        self.assertEqual(400, self.call("POST", "/setup", buf.getvalue())[0])
        self.assertFalse(os.path.exists(os.path.join(os.path.dirname(self.ws), "evil.txt")))

    def test_backup_needs_token(self):
        self.assertEqual(401, self.call("GET", "/backup", token=None, raw=True)[0])


if __name__ == "__main__":
    unittest.main()
