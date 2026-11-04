#!/usr/bin/env python3
"""jllm sandbox daemon: a small HTTP server that runs the agent's tools inside the container.

Standard library only. Configuration (environment):
  JLLM_TOKEN      required. Every request must carry it in the header X-Jllm-Token.
  JLLM_PORT       listen port (default 8080)
  JLLM_BIND       listen address (default 0.0.0.0; the host publishes the port on 127.0.0.1 only)
  JLLM_WORKSPACE  work root (default /workspace)
  JLLM_SCRATCH    scratch root (default <workspace>/.kendhar/scratchpad)
  JLLM_ALIAS      tests only: absolute paths under this prefix (e.g. /workspace) are mapped onto the workspace

Endpoints: see docs/SANDBOX.md. A request with a missing or wrong token gets an empty 401 and nothing is executed.
"""
import hmac
import json
import os
import re
import shutil
import signal
import stat as statmod
import subprocess
import sys
import threading
import time
import tempfile
import uuid
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

TOKEN = os.environ.get("JLLM_TOKEN", "")
WORKSPACE = os.environ.get("JLLM_WORKSPACE", "/workspace")
SCRATCH = os.environ.get("JLLM_SCRATCH", os.path.join(WORKSPACE, ".kendhar", "scratchpad"))
ALIAS = os.environ.get("JLLM_ALIAS", "")

CAPTURE_LIMIT = 50 * 1024      # bytes of command output kept in memory
MAX_FILE_BYTES = 1_000_000     # grep skips larger files
MAX_RESULTS = 200              # glob / grep hits
MAX_ENTRIES = 1000             # list / tree entries
MAX_BODY = 256 * 1024 * 1024
MAX_FINISHED = 200             # finished commands kept for polling


class HttpError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


# ── paths ─────────────────────────────────────────────────────────────────────

def unalias(p):
    """Test aid (JLLM_ALIAS): maps an absolute path under the alias, e.g. /workspace, onto the real workspace."""
    if ALIAS and (p == ALIAS or p.startswith(ALIAS + "/")):
        return WORKSPACE + p[len(ALIAS):]
    return p


def root_of(name):
    if name in (None, "", "workspace"):
        return WORKSPACE
    if name == "scratch":
        os.makedirs(SCRATCH, exist_ok=True)
        return SCRATCH
    raise HttpError(400, "unknown root")


def resolve(root, path, must_exist=False):
    """Resolves path (relative to root, or absolute) and refuses anything that ends outside root, symlinks included."""
    real_root = os.path.realpath(root)
    p = unalias(path or ".")
    candidate = p if os.path.isabs(p) else os.path.join(real_root, p)
    candidate = os.path.normpath(candidate)
    # resolve symlinks of the deepest existing ancestor
    existing = candidate
    while existing and not os.path.lexists(existing):
        parent = os.path.dirname(existing)
        if parent == existing:
            break
        existing = parent
    real_existing = os.path.realpath(existing)
    if real_existing != real_root and not real_existing.startswith(real_root + os.sep):
        raise HttpError(400, "path escapes the root: " + p)
    if candidate != real_root and not candidate.startswith(real_root + os.sep):
        raise HttpError(400, "path escapes the root: " + p)
    if must_exist and not os.path.lexists(candidate):
        raise HttpError(404, "not found: " + p)
    return candidate


def rel(root, path):
    return os.path.relpath(path, os.path.realpath(root)).replace(os.sep, "/")


# ── glob ──────────────────────────────────────────────────────────────────────

def glob_regex(pattern):
    """Java-like glob: * and ? stay inside a segment, ** crosses segments, {a,b} alternatives, [..] classes."""
    out, i, n, depth = [], 0, len(pattern), 0
    while i < n:
        c = pattern[i]
        if c == "*":
            if i + 1 < n and pattern[i + 1] == "*":
                i += 2
                if i < n and pattern[i] == "/":
                    out.append("(?:.*/)?")
                    i += 1
                else:
                    out.append(".*")
                continue
            out.append("[^/]*")
        elif c == "?":
            out.append("[^/]")
        elif c == "{":
            out.append("(?:")
            depth += 1
        elif c == "}" and depth:
            out.append(")")
            depth -= 1
        elif c == "," and depth:
            out.append("|")
        elif c == "[":
            j = pattern.find("]", i + 1)
            if j < 0:
                out.append(re.escape(c))
            else:
                cls = pattern[i + 1:j]
                if cls.startswith("!"):
                    cls = "^" + cls[1:]
                out.append("[" + cls.replace("\\", "\\\\") + "]")
                i = j
        else:
            out.append(re.escape(c))
        i += 1
    return re.compile("^" + "".join(out) + "$")


def walk_files(start, root):
    """Regular files under start, as paths relative to root; symlinks are not followed."""
    real_root = os.path.realpath(root)
    for dirpath, dirnames, filenames in os.walk(start, followlinks=False):
        dirnames.sort()
        for f in sorted(filenames):
            full = os.path.join(dirpath, f)
            if os.path.isfile(full) and not os.path.islink(full):
                yield full, os.path.relpath(full, real_root).replace(os.sep, "/")


# ── commands ──────────────────────────────────────────────────────────────────

class Exec:
    def __init__(self, command, cwd, timeout, log_dir):
        self.id = uuid.uuid4().hex
        self.command = command
        self.timeout = timeout
        self.log_dir = log_dir
        self.lock = threading.Lock()
        self.done = threading.Event()
        self.timed_out = False
        self.killed = False
        self.exit_code = None
        self.ring = b""
        self.total = 0
        self.newlines = 0
        self.log_path = None
        self.log_file = None
        env = {k: v for k, v in os.environ.items() if not k.startswith("JLLM_")}
        self.proc = subprocess.Popen(
            ["sh", "-c", command], cwd=cwd, env=env, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            **({"start_new_session": True} if os.name == "posix" else {}))
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._watch, daemon=True).start()

    def _open_log(self):
        os.makedirs(self.log_dir, exist_ok=True)
        with LOG_LOCK:
            n = 1
            while os.path.exists(os.path.join(self.log_dir, "bash-%d.log" % n)):
                n += 1
            self.log_path = os.path.join(self.log_dir, "bash-%d.log" % n)
            self.log_file = open(self.log_path, "wb")

    def _read(self):
        try:
            while True:
                chunk = self.proc.stdout.read1(8192)
                if not chunk:
                    break
                with self.lock:
                    self.newlines += chunk.count(b"\n")
                    if self.log_file is None and self.total + len(chunk) > CAPTURE_LIMIT:
                        self._open_log()
                        self.log_file.write(self.ring)
                    if self.log_file is not None:
                        self.log_file.write(chunk)
                    self.ring = (self.ring + chunk)[-CAPTURE_LIMIT:]
                    self.total += len(chunk)
        finally:
            code = self.proc.wait()
            with self.lock:
                if self.log_file is not None:
                    self.log_file.close()
                self.exit_code = -1 if (self.timed_out or self.killed) else code
            self.done.set()

    def _watch(self):
        if not self.done.wait(self.timeout):
            self.timed_out = True
            self.kill()

    def kill(self):
        self.killed = not self.timed_out
        try:
            if hasattr(os, "killpg"):
                os.killpg(self.proc.pid, signal.SIGKILL)
            else:  # development on Windows only; the container is always Linux
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(self.proc.pid)], capture_output=True)
        except (ProcessLookupError, PermissionError, OSError):
            pass

    def result(self):
        with self.lock:
            text = self.ring.decode("utf-8", errors="replace")
            omitted = 0
            if self.total > CAPTURE_LIMIT:
                nl = text.find("\n")
                if nl >= 0:
                    text = text[nl + 1:]
                omitted = max(0, self.newlines - text.count("\n"))
            return {"running": False, "stdout": text, "exitCode": self.exit_code, "timedOut": self.timed_out,
                    "omittedLines": omitted, "logPath": self.log_path}


LOG_LOCK = threading.Lock()
EXECS = {}
EXECS_LOCK = threading.Lock()


def start_exec(body):
    command = body.get("command")
    if not isinstance(command, str) or not command:
        raise HttpError(400, "command is required")
    cwd = resolve(WORKSPACE, body.get("cwd") or WORKSPACE)
    if not os.path.isdir(cwd):
        raise HttpError(400, "cwd is not a directory")
    timeout = max(1, min(int(body.get("timeoutSeconds") or 120), 3600))
    log_dir = resolve(WORKSPACE, body.get("logDir") or SCRATCH)
    ex = Exec(command, cwd, timeout, log_dir)
    with EXECS_LOCK:
        EXECS[ex.id] = ex
        finished = [k for k, v in EXECS.items() if v.done.is_set()]
        for k in finished[:-MAX_FINISHED]:
            del EXECS[k]
    return {"id": ex.id}


def get_exec(eid, wait):
    ex = EXECS.get(eid)
    if ex is None:
        raise HttpError(404, "unknown command")
    if wait > 0:
        ex.done.wait(wait)
    return ex.result() if ex.done.is_set() else {"running": True}


def kill_exec(eid):
    ex = EXECS.get(eid)
    if ex is None:
        raise HttpError(404, "unknown command")
    ex.kill()
    ex.done.wait(5)
    with EXECS_LOCK:
        EXECS.pop(eid, None)
    return {"killed": True}


# ── file primitives ───────────────────────────────────────────────────────────

def stat_info(root, path):
    p = resolve(root, path)
    try:
        st = os.lstat(p)
    except FileNotFoundError:
        return {"exists": False, "path": rel(root, p)}
    mode = st.st_mode
    if statmod.S_ISLNK(mode):
        kind = "link"
        real = os.path.realpath(p)
        if os.path.isdir(real):
            kind = "linkdir"
    elif statmod.S_ISDIR(mode):
        kind = "dir"
    elif statmod.S_ISREG(mode):
        kind = "file"
    else:
        kind = "other"
    return {"exists": True, "path": rel(root, p), "type": kind, "size": st.st_size, "mtime": int(st.st_mtime * 1000),
            "mode": statmod.filemode(mode)}


def read_file(root, path, offset, limit):
    p = resolve(root, path, must_exist=True)
    if not os.path.isfile(p):
        raise HttpError(404, "not a file: " + path)
    with open(p, "rb") as f:
        f.seek(max(0, offset))
        return f.read(limit if limit >= 0 else -1)


def write_file(root, path, data, append):
    p = resolve(root, path)
    if os.path.isdir(p):
        raise HttpError(400, "is a directory: " + path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "ab" if append else "wb") as f:
        f.write(data)
    return {"path": rel(root, p), "size": os.path.getsize(p)}


def delete_path(root, path):
    p = resolve(root, path, must_exist=True)
    if p == os.path.realpath(root):
        raise HttpError(400, "cannot delete the root")
    if os.path.isdir(p) and not os.path.islink(p):
        shutil.rmtree(p)
    else:
        os.remove(p)
    return {"deleted": rel(root, p)}


def make_dir(root, path):
    p = resolve(root, path)
    if os.path.lexists(p):
        raise HttpError(409, "already exists: " + path)
    os.makedirs(p)
    return {"path": rel(root, p)}


def move_path(root, src, dst):
    d = resolve(root, dst)
    s = resolve(root, src, must_exist=True)
    if s == os.path.realpath(root):
        raise HttpError(400, "cannot move the root")
    if os.path.lexists(d):
        raise HttpError(409, "already exists: " + dst)
    os.makedirs(os.path.dirname(d), exist_ok=True)
    os.rename(s, d)
    return {"path": rel(root, d)}


# ── workspace backup / setup (remote docker: the host cannot mount the project) ─

EXEC_MANIFEST = ".jllm-exec"
# harness-private data that is never copied: the scratchpad is disposable, the history lives on the host
BACKUP_EXCLUDE = (".kendhar/scratchpad", ".kendhar/history")


def excluded(rel_path):
    return any(rel_path == e or rel_path.startswith(e + "/") for e in BACKUP_EXCLUDE)


def do_backup():
    """The whole workspace as a zip (regular files and directories; symlinks are skipped)."""
    tmp = tempfile.SpooledTemporaryFile(max_size=64 * 1024 * 1024)
    execs = []
    real = os.path.realpath(WORKSPACE)
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for dirpath, dirnames, filenames in os.walk(real, followlinks=False):
            r = os.path.relpath(dirpath, real).replace(os.sep, "/")
            r = "" if r == "." else r
            dirnames[:] = sorted(d for d in dirnames if not excluded((r + "/" + d).lstrip("/")))
            if r:
                z.writestr(r + "/", "")
            for f in sorted(filenames):
                full = os.path.join(dirpath, f)
                rp = (r + "/" + f).lstrip("/")
                if excluded(rp) or os.path.islink(full) or not os.path.isfile(full):
                    continue
                z.write(full, rp)
                if os.stat(full).st_mode & 0o111:
                    execs.append(rp)
        z.writestr(EXEC_MANIFEST, "\n".join(execs))
    tmp.seek(0)
    return tmp.read()


def do_setup(data):
    """Extracts a zip into the workspace (overwriting); refuses names that would leave it."""
    real = os.path.realpath(WORKSPACE)
    count = 0
    with zipfile.ZipFile(tempfile.SpooledTemporaryFile(max_size=64 * 1024 * 1024)
                         if not data else __import__("io").BytesIO(data)) as z:
        names = z.namelist()
        execs = set(z.read(EXEC_MANIFEST).decode().split("\n")) if EXEC_MANIFEST in names else set()
        for info in z.infolist():
            name = info.filename
            if name == EXEC_MANIFEST:
                continue
            target = os.path.normpath(os.path.join(real, name))
            if os.path.isabs(name) or (target != real and not target.startswith(real + os.sep)) or excluded(name.rstrip("/")):
                raise HttpError(400, "bad entry in archive: " + name)
            if name.endswith("/"):
                os.makedirs(target, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            if os.path.islink(target):
                os.remove(target)
            with z.open(info) as src, open(target, "wb") as dst:
                shutil.copyfileobj(src, dst)
            if name in execs:
                os.chmod(target, 0o755)
            count += 1
    return {"files": count}


def do_glob(root, pattern):
    if not pattern:
        raise HttpError(400, "pattern is required")
    rx = glob_regex(pattern)
    top = glob_regex(pattern[3:]) if pattern.startswith("**/") else None
    real_root = os.path.realpath(root)
    hits = []
    for _, r in walk_files(real_root, root):
        if rx.match(r) or (top is not None and top.match(r)):
            hits.append(r)
    hits.sort()
    return hits[:MAX_RESULTS * 5]


def do_grep(body):
    root = root_of(body.get("root"))
    try:
        rx = re.compile(body.get("pattern") or "")
    except re.error as e:
        raise HttpError(400, "bad pattern: %s" % e)
    start = resolve(root, body.get("path") or ".", must_exist=True)
    gl = body.get("glob")
    grx = glob_regex(gl) if gl else None
    gtop = glob_regex(gl[3:]) if gl and gl.startswith("**/") else None
    limit = int(body.get("maxResults") or MAX_RESULTS)
    hits = []
    if os.path.isfile(start):
        files = [(start, rel(root, start))]
    else:
        files = walk_files(start, root)
    for full, r in files:
        if grx is not None and not (grx.match(r) or (gtop is not None and gtop.match(r))):
            continue
        try:
            if os.path.getsize(full) > MAX_FILE_BYTES:
                continue
            with open(full, "r", encoding="utf-8") as f:
                for i, line in enumerate(f, 1):
                    line = line.rstrip("\r\n")
                    if rx.search(line):
                        hits.append("%s:%d:%s" % (r, i, line))
                        if len(hits) >= limit:
                            return hits
        except (UnicodeDecodeError, OSError):
            continue
    return hits


# ── browse ────────────────────────────────────────────────────────────────────

def entry_of(full, name):
    try:
        st = os.lstat(full)
    except OSError:
        return {"name": name, "dir": False, "link": False, "size": 0, "mtime": 0, "mode": "?????????"}
    link = statmod.S_ISLNK(st.st_mode)
    e = {"name": name, "dir": (os.path.isdir(full) and not link), "link": link, "size": st.st_size,
         "mtime": int(st.st_mtime * 1000), "mode": statmod.filemode(st.st_mode)}
    if link:
        try:
            e["target"] = os.readlink(full)
        except OSError:
            pass
    return e


def do_list(q):
    root = root_of(q.get("root"))
    p = resolve(root, q.get("path") or ".", must_exist=True)
    show_all = q.get("all") == "1"
    if os.path.isdir(p) and not os.path.islink(p):
        names = sorted(os.listdir(p))
        if not show_all:
            names = [n for n in names if not n.startswith(".")]
        extra = max(0, len(names) - MAX_ENTRIES)
        entries = [entry_of(os.path.join(p, n), n) for n in names[:MAX_ENTRIES]]
    else:
        entries, extra = [entry_of(p, os.path.basename(p))], 0
    if q.get("format") == "json":
        return {"path": rel(root, p), "entries": entries, "more": extra}
    lines = []
    for e in entries:
        label = e["name"] + ("/" if e["dir"] else "")
        if q.get("long") == "1":
            tgt = " -> " + e["target"] if "target" in e else ""
            label = "%s %10d %s %s%s" % (e["mode"], e["size"], time.strftime(
                "%Y-%m-%d %H:%M", time.localtime(e["mtime"] / 1000)), label, tgt)
        lines.append(label)
    if extra:
        lines.append("... %d more" % extra)
    return "\n".join(lines)


def do_tree(q):
    root = root_of(q.get("root"))
    p = resolve(root, q.get("path") or ".", must_exist=True)
    depth = max(1, min(int(q.get("depth") or 2), 10))
    lines, count = [rel(root, p) if p != os.path.realpath(root) else "."], [0]

    def walk(d, prefix, level):
        if level > depth:
            return
        try:
            names = sorted(os.listdir(d))
        except OSError:
            return
        for n in names:
            if count[0] >= MAX_ENTRIES:
                return
            full = os.path.join(d, n)
            is_dir = os.path.isdir(full) and not os.path.islink(full)
            lines.append(prefix + n + ("/" if is_dir else ""))
            count[0] += 1
            if is_dir:
                walk(full, prefix + "  ", level + 1)

    walk(p, "  ", 1)
    if count[0] >= MAX_ENTRIES:
        lines.append("... more entries not shown")
    return "\n".join(lines)


# ── http ──────────────────────────────────────────────────────────────────────

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "jllm-sandbox"

    def log_message(self, fmt, *args):  # never logs headers, so never the token
        sys.stderr.write("%s %s\n" % (self.log_date_time_string(), fmt % args))

    def _authorized(self):
        got = self.headers.get("X-Jllm-Token") or ""
        return bool(TOKEN) and hmac.compare_digest(got.encode(), TOKEN.encode())

    def _send(self, code, body=b"", ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        if body:
            self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_BODY:
            raise HttpError(413, "body too large")
        return self.rfile.read(n) if n else b""

    def _json(self):
        raw = self._body()
        try:
            return json.loads(raw or b"{}")
        except ValueError:
            raise HttpError(400, "bad json")

    def _dispatch(self):
        if not self._authorized():
            self.log_message('"%s %s" 401', self.command, urlparse(self.path).path)
            self.rfile.read(int(self.headers.get("Content-Length") or 0))
            return self._send(401)
        try:
            u = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(u.query, keep_blank_values=True).items()}
            self._route(self.command, u.path, q)
        except HttpError as e:
            self._send(e.code, {"error": e.message})
        except (ValueError, KeyError) as e:
            self._send(400, {"error": "bad request: %s" % e})
        except Exception as e:  # noqa: BLE001 - a tool error must not kill the daemon
            self._send(500, {"error": "%s: %s" % (type(e).__name__, e)})

    do_GET = do_POST = do_PUT = do_DELETE = _dispatch

    def _route(self, method, path, q):
        if path == "/health" and method == "GET":
            return self._send(200, {"ok": True})
        if path == "/exec" and method == "POST":
            return self._send(200, start_exec(self._json()))
        if path.startswith("/exec/"):
            eid = path[len("/exec/"):]
            if method == "GET":
                return self._send(200, get_exec(eid, min(float(q.get("wait") or 0), 25)))
            if method == "DELETE":
                return self._send(200, kill_exec(eid))
        root = root_of(q.get("root"))
        if path == "/file" and method == "GET":
            data = read_file(root, q.get("path"), int(q.get("offset") or 0), int(q.get("limit") or -1))
            return self._send(200, data, "application/octet-stream")
        if path == "/file" and method == "PUT":
            return self._send(200, write_file(root, q.get("path"), self._body(), q.get("append") == "1"))
        if path == "/file" and method == "DELETE":
            return self._send(200, delete_path(root, q.get("path")))
        if path == "/mkdir" and method == "POST":
            return self._send(200, make_dir(root, q.get("path")))
        if path == "/move" and method == "POST":
            return self._send(200, move_path(root, q.get("from"), q.get("to")))
        if path == "/backup" and method == "GET":
            return self._send(200, do_backup(), "application/zip")
        if path == "/setup" and method == "POST":
            return self._send(200, do_setup(self._body()))
        if path == "/stat" and method == "GET":
            return self._send(200, stat_info(root, q.get("path")))
        if path == "/glob" and method == "GET":
            return self._send(200, "\n".join(do_glob(root, q.get("pattern"))), "text/plain; charset=utf-8")
        if path == "/grep" and method == "POST":
            return self._send(200, "\n".join(do_grep(self._json())), "text/plain; charset=utf-8")
        if path == "/list" and method == "GET":
            out = do_list(q)
            return self._send(200, out, "application/json" if q.get("format") == "json" else "text/plain; charset=utf-8")
        if path == "/tree" and method == "GET":
            return self._send(200, do_tree(q), "text/plain; charset=utf-8")
        if path == "/download" and method == "GET":
            data = read_file(root, q.get("path"), 0, -1)
            return self._send(200, data, "application/octet-stream")
        raise HttpError(404, "no such endpoint")


def main():
    if not TOKEN:
        sys.stderr.write("JLLM_TOKEN is required\n")
        sys.exit(2)
    os.makedirs(WORKSPACE, exist_ok=True)
    port = int(os.environ.get("JLLM_PORT", "8080"))
    bind = os.environ.get("JLLM_BIND", "0.0.0.0")
    srv = ThreadingHTTPServer((bind, port), Handler)
    srv.daemon_threads = True
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=srv.shutdown, daemon=True).start())
    sys.stderr.write("jllm sandbox daemon on %s:%d, workspace %s\n" % (bind, srv.server_address[1], WORKSPACE))
    srv.serve_forever()


if __name__ == "__main__":
    main()
