#!/usr/bin/env python3
"""jllm sandbox daemon: a small HTTP server that runs the agent's tools inside the container.

Standard library only. Configuration (environment):
  JLLM_TOKEN      required. Every request must carry it in the header X-Jllm-Token.
  JLLM_PORT       listen port (default 8080)
  JLLM_BIND       listen address (default 0.0.0.0; the host publishes the port on 127.0.0.1 only)
  JLLM_WORKSPACE  work root (default /workspace)
  JLLM_SCRATCH    scratch root (default <workspace>/.jllm/scratchpad)
  JLLM_LOG_MAX_BYTES  cap of a command's spill log when the request gives none (default 51200; 0: unlimited)
  JLLM_EXTERNAL_LINKS  none (default), read or readwrite: what a link that leaves the root does (files.externalLinks)
  JLLM_ALIAS      tests only: absolute paths under this prefix (e.g. /workspace) are mapped onto the workspace

Endpoints: see docs/guides/sandbox.md. A request with a missing or wrong token gets an empty 401 and nothing is executed.
"""
import errno
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
SCRATCH = os.environ.get("JLLM_SCRATCH", os.path.join(WORKSPACE, ".jllm", "scratchpad"))
ALIAS = os.environ.get("JLLM_ALIAS", "")
# What the file tools do with a link that leaves the root: none (default), read or readwrite
EXTERNAL_LINKS = (os.environ.get("JLLM_EXTERNAL_LINKS") or "none").strip().lower()
if EXTERNAL_LINKS not in ("none", "read", "readwrite"):
    EXTERNAL_LINKS = "none"

CAPTURE_LIMIT = 50 * 1024      # bytes of command output kept in memory
LOG_MAX_BYTES = int(os.environ.get("JLLM_LOG_MAX_BYTES") or 50 * 1024)  # spill log cap (0: unlimited)
READER_GRACE = 2.0             # seconds the output is still read after the shell exited
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


def check_external(shown, write):
    """A link leaves the root: refused unless JLLM_EXTERNAL_LINKS allows this access (read: reads only)."""
    if EXTERNAL_LINKS == "none":
        raise HttpError(400, "%s is a link that leaves the work dir. Files outside the work dir are off-limits for "
                             "every tool, the shell included: tell the user you cannot access it, do not try another "
                             "way." % shown)
    if write and EXTERNAL_LINKS != "readwrite":
        raise HttpError(400, "%s is a link that leaves the work dir: it can be read but not written, because the "
                             "setting files.externalLinks is read (readwrite allows writes through such links). Tell "
                             "the user; do not try another way to change it." % shown)


def resolve(root, path, must_exist=False, write=False):
    """Resolves path (relative to root, or absolute) and refuses anything that ends outside root, symlinks included.

    A link leaving the root is followed only as JLLM_EXTERNAL_LINKS allows (write: the access changes something)."""
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
    if candidate != real_root and not candidate.startswith(real_root + os.sep):
        raise HttpError(400, "path escapes the root: " + p)
    if real_existing != real_root and not real_existing.startswith(real_root + os.sep):
        check_external(p, write)
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
                out.append(glob_class(pattern[i + 1:j]))
                i = j
        else:
            out.append(re.escape(c))
        i += 1
    try:
        return re.compile("^" + "".join(out) + "$")
    except re.error as e:
        raise HttpError(400, "bad glob: %s" % e)


def glob_class(body):
    """A [..] class as a regex class built from escaped characters, so nothing in it can leak into the rest of the
    regex. [!..] negates; a-z ranges are kept; / is refused (a class never crosses a path segment)."""
    neg = body.startswith("!")
    if neg:
        body = body[1:]
    if not body:
        raise HttpError(400, "bad glob: empty character class")
    if "/" in body:
        raise HttpError(400, "bad glob: '/' in a character class")
    parts, k = [], 0
    while k < len(body):
        if k + 2 < len(body) and body[k + 1] == "-":
            lo, hi = body[k], body[k + 2]
            if lo > hi:
                raise HttpError(400, "bad glob: range %s-%s" % (lo, hi))
            parts.append(re.escape(lo) + "-" + re.escape(hi))
            k += 3
        else:
            parts.append(re.escape(body[k]))
            k += 1
    return "[" + ("^/" if neg else "") + "".join(parts) + "]"


def _inside(real_root, real):
    return real == real_root or real.startswith(real_root + os.sep)


def walk_files(start, root, entered=None):
    """Regular files under start, as paths relative to root.

    A link to a file is a file when its target is inside the root, or JLLM_EXTERNAL_LINKS allows reading outside. A
    link to a directory is not entered when its target is inside the root (it is walked where it lies); one leaving the
    root is entered once, when reads outside are allowed, unless it is an ancestor of the root."""
    real_root = os.path.realpath(root)
    follow = EXTERNAL_LINKS != "none"
    if entered is None:
        entered = set()
        real_start = os.path.realpath(start)
        if not _inside(real_root, real_start):
            entered.add(real_start)
    for dirpath, dirnames, filenames in os.walk(start, followlinks=False):
        dirnames.sort()
        if follow:
            for d in list(dirnames):
                full = os.path.join(dirpath, d)
                if not os.path.islink(full):
                    continue
                real = os.path.realpath(full)
                if (os.path.isdir(real) and not _inside(real_root, real) and not _inside(real, real_root)
                        and real not in entered):
                    entered.add(real)
                    yield from walk_files(full, root, entered)
        for f in sorted(filenames):
            full = os.path.join(dirpath, f)
            if not os.path.isfile(full):
                continue
            if os.path.islink(full) and not (follow or _inside(real_root, os.path.realpath(full))):
                continue
            yield full, os.path.relpath(full, real_root).replace(os.sep, "/")


# ── commands ──────────────────────────────────────────────────────────────────

class Exec:
    def __init__(self, command, cwd, timeout, log_dir, log_max=None):
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
        self.log_max = LOG_MAX_BYTES if log_max is None else log_max
        self.logged = 0
        self.log_truncated = False
        self.detached = False  # the shell exited but a background job holds the pipe: later output is discarded
        self.reader_done = threading.Event()
        env = {k: v for k, v in os.environ.items() if not k.startswith("JLLM_")}
        self.proc = subprocess.Popen(
            ["sh", "-c", command], cwd=cwd, env=env, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            **({"start_new_session": True} if os.name == "posix" else {}))
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._wait, daemon=True).start()
        threading.Thread(target=self._watch, daemon=True).start()

    def _open_log(self):
        os.makedirs(self.log_dir, exist_ok=True)
        with LOG_LOCK:
            n = 1
            while os.path.exists(os.path.join(self.log_dir, "bash-%d.log" % n)):
                n += 1
            self.log_path = os.path.join(self.log_dir, "bash-%d.log" % n)
            self.log_file = open(self.log_path, "wb")

    def _write_log(self, data):
        if self.log_truncated or self.log_file is None:
            return
        room = self.log_max - self.logged if self.log_max > 0 else len(data)
        if len(data) <= room:
            self.log_file.write(data)
            self.logged += len(data)
            return
        self.log_file.write(data[:room])
        self.logged += room
        self.log_file.write(b"\n[log truncated at %d bytes]\n" % self.log_max)
        self.log_truncated = True
        self._close_log()

    def _close_log(self):
        if self.log_file is not None and not self.log_file.closed:
            self.log_file.close()

    def _read(self):
        try:
            while True:
                chunk = self.proc.stdout.read1(8192)
                if not chunk:
                    break
                with self.lock:
                    if self.detached:
                        continue  # keep draining so the background job does not block, but discard
                    self.newlines += chunk.count(b"\n")
                    if self.log_path is None and self.total + len(chunk) > CAPTURE_LIMIT:
                        self._open_log()
                        self._write_log(self.ring)
                    self._write_log(chunk)
                    self.ring = (self.ring + chunk)[-CAPTURE_LIMIT:]
                    self.total += len(chunk)
        except (OSError, ValueError):
            pass
        finally:
            with self.lock:
                self._close_log()
            self.reader_done.set()

    def _wait(self):
        """Finishes when the shell exits, not when the pipe closes: a background job (cmd &) that inherited the
        output would otherwise keep the command running until its timeout. Its later output is discarded; the job
        itself is killed with the process group when the harness releases the command (DELETE /exec/{id})."""
        code = self.proc.wait()
        self.reader_done.wait(READER_GRACE)
        with self.lock:
            if not self.reader_done.is_set():
                self.detached = True
                self._close_log()
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
    log_max = body.get("logMaxBytes")
    ex = Exec(command, cwd, timeout, log_dir, None if log_max is None else max(0, int(log_max)))
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


# open-in-root: no model-supplied path is opened by name. Every component is opened relative to the descriptor of its
# parent with O_NOFOLLOW, so a component swapped for a link after the check cannot lead outside the root. A link whose
# target stays inside the root is followed by restarting the walk on the target's components (at most 40 hops).

MAX_LINK_HOPS = 40
O_DIRECTORY = getattr(os, "O_DIRECTORY", 0)
O_NOFOLLOW = getattr(os, "O_NOFOLLOW", 0)
# Linux, macOS: openat and friends. Windows (the daemon runs there for development only): no directory descriptors,
# the path is checked by resolve() and opened by name.
HAS_DIRFD = bool(O_NOFOLLOW and O_DIRECTORY and os.open in os.supports_dir_fd and os.stat in os.supports_dir_fd
                 and os.rename in os.supports_dir_fd and os.mkdir in os.supports_dir_fd
                 and os.unlink in os.supports_dir_fd and os.rmdir in os.supports_dir_fd)


def _components(real_root, candidate):
    r = os.path.relpath(candidate, real_root)
    return [] if r == "." else r.split(os.sep)


def walk_parent(real_root, comps, create=False, follow_final=True, write=False):
    """Opens the directory holding the last of comps (names below real_root) without following links.

    Returns (dir_fd, name); the caller closes dir_fd. A link to somewhere inside the root is followed (follow_final:
    also at the last component). A link leaving the root is followed only as JLLM_EXTERNAL_LINKS allows (write: the
    access changes something); the walk then restarts from "/" and is no longer confined. A link swapped in on the way
    is refused with 400."""
    if not comps:
        raise HttpError(400, "path is the root")
    shown = "/".join(comps)
    hops = 0
    base = real_root
    while True:
        fd = os.open(base, os.O_RDONLY | O_DIRECTORY)
        restart_base = base
        restart = None
        try:
            for i, name in enumerate(comps):
                last = i == len(comps) - 1
                try:
                    st = os.stat(name, dir_fd=fd, follow_symlinks=False)
                except FileNotFoundError:
                    st = None
                if st is not None and statmod.S_ISLNK(st.st_mode) and (not last or follow_final):
                    hops += 1
                    if hops > MAX_LINK_HOPS:
                        raise HttpError(400, "too many levels of links: " + shown)
                    at = os.path.join(base, *comps[:i])
                    target = os.path.normpath(os.path.join(at, os.readlink(name, dir_fd=fd)))
                    if target == real_root or target.startswith(real_root + os.sep):
                        restart_base = real_root
                        restart = _components(real_root, target) + comps[i + 1:]
                    else:
                        check_external(shown, write)
                        restart_base = os.sep
                        restart = _components(os.sep, target) + comps[i + 1:]
                    if not restart:
                        raise HttpError(400, "path is the root")
                    break
                if last:
                    result = (fd, name)
                    fd = None  # owned by the caller
                    return result
                if st is None:
                    if not create:
                        raise HttpError(404, "not found: " + shown)
                    try:
                        os.mkdir(name, dir_fd=fd)
                    except FileExistsError:
                        pass
                try:
                    nxt = os.open(name, os.O_RDONLY | O_DIRECTORY | O_NOFOLLOW, dir_fd=fd)
                except OSError:
                    raise HttpError(400, "path escapes the root, or is not a directory: " + shown)
                os.close(fd)
                fd = nxt
        finally:
            if fd is not None:
                os.close(fd)
        comps = restart
        base = restart_base


def open_in_root(root, path, flags, mode=0o666, create_dirs=False):
    """Opens a file under root without following a link that leaves it (unless JLLM_EXTERNAL_LINKS allows it for this
    access); returns the file descriptor."""
    write = bool(flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT)) or create_dirs
    if not HAS_DIRFD:
        p = resolve(root, path, write=write)
        if create_dirs:
            os.makedirs(os.path.dirname(p), exist_ok=True)
        try:
            return os.open(p, flags | getattr(os, "O_BINARY", 0), mode)
        except FileNotFoundError:
            raise HttpError(404, "not found: " + path)
    real_root = os.path.realpath(root)
    fd, name = walk_parent(real_root, _components(real_root, resolve(root, path, write=write)), create_dirs,
                           write=write)
    try:
        return os.open(name, flags | O_NOFOLLOW, mode, dir_fd=fd)
    except FileNotFoundError:
        raise HttpError(404, "not found: " + path)
    except OSError as e:
        if e.errno == errno.ELOOP:
            raise HttpError(400, "path escapes the root: " + path)
        raise
    finally:
        os.close(fd)


def read_file(root, path, offset, limit):
    p = resolve(root, path, must_exist=True)
    if not HAS_DIRFD and not os.path.isfile(p):
        raise HttpError(404, "not a file: " + path)
    fd = open_in_root(root, path, os.O_RDONLY)
    with os.fdopen(fd, "rb") as f:
        if not statmod.S_ISREG(os.fstat(f.fileno()).st_mode):
            raise HttpError(404, "not a file: " + path)
        f.seek(max(0, offset))
        return f.read(limit if limit >= 0 else -1)


def write_file(root, path, data, append):
    p = resolve(root, path, write=True)
    if not HAS_DIRFD:
        if os.path.isdir(p):
            raise HttpError(400, "is a directory: " + path)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "ab" if append else "wb") as f:
            f.write(data)
        return {"path": rel(root, p), "size": os.path.getsize(p)}
    real_root = os.path.realpath(root)
    fd, name = walk_parent(real_root, _components(real_root, p), create=True, write=True)
    try:
        try:
            old = os.stat(name, dir_fd=fd, follow_symlinks=False)
        except FileNotFoundError:
            old = None
        if old is not None and statmod.S_ISDIR(old.st_mode):
            raise HttpError(400, "is a directory: " + path)
        if append or (old is not None and old.st_nlink > 1):
            # appended, or shared through hard links: written in place, so every link keeps seeing the content
            flags = os.O_WRONLY | os.O_CREAT | O_NOFOLLOW | (os.O_APPEND if append else os.O_TRUNC)
            with os.fdopen(os.open(name, flags, 0o666, dir_fd=fd), "ab" if append else "wb") as f:
                f.write(data)
        else:
            # a temporary file next to it, moved over it relative to the directory descriptor: never half written
            tmp = ".%s.%s.tmp" % (name, uuid.uuid4().hex)
            tfd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | O_NOFOLLOW, 0o666, dir_fd=fd)
            try:
                with os.fdopen(tfd, "wb") as f:
                    f.write(data)
                    f.flush()
                    os.fsync(f.fileno())
                if old is not None and statmod.S_ISREG(old.st_mode):
                    os.chmod(tmp, statmod.S_IMODE(old.st_mode), dir_fd=fd, follow_symlinks=False)
                os.rename(tmp, name, src_dir_fd=fd, dst_dir_fd=fd)
            except BaseException:
                try:
                    os.unlink(tmp, dir_fd=fd)
                except OSError:
                    pass
                raise
        size = os.stat(name, dir_fd=fd, follow_symlinks=False).st_size
    finally:
        os.close(fd)
    return {"path": rel(root, p), "size": size}


def _remove_tree(parent_fd, name):
    """Deletes name (relative to parent_fd): a link is removed, never entered; directories are walked by descriptor."""
    st = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    if not statmod.S_ISDIR(st.st_mode):
        os.unlink(name, dir_fd=parent_fd)
        return
    dfd = os.open(name, os.O_RDONLY | O_DIRECTORY | O_NOFOLLOW, dir_fd=parent_fd)
    try:
        with os.scandir(dfd) as it:
            children = [e.name for e in it]
        for child in children:
            _remove_tree(dfd, child)
    finally:
        os.close(dfd)
    os.rmdir(name, dir_fd=parent_fd)


def delete_path(root, path):
    p = resolve(root, path, must_exist=True, write=True)
    real_root = os.path.realpath(root)
    if p == real_root:
        raise HttpError(400, "cannot delete the root")
    if not HAS_DIRFD:
        if os.path.isdir(p) and not os.path.islink(p):
            shutil.rmtree(p)
        else:
            os.remove(p)
        return {"deleted": rel(root, p)}
    fd, name = walk_parent(real_root, _components(real_root, p), follow_final=False, write=True)
    try:
        _remove_tree(fd, name)
    except FileNotFoundError:
        raise HttpError(404, "not found: " + path)
    finally:
        os.close(fd)
    return {"deleted": rel(root, p)}


def make_dir(root, path):
    p = resolve(root, path, write=True)
    if not HAS_DIRFD:
        if os.path.lexists(p):
            raise HttpError(409, "already exists: " + path)
        os.makedirs(p)
        return {"path": rel(root, p)}
    real_root = os.path.realpath(root)
    fd, name = walk_parent(real_root, _components(real_root, p), create=True, follow_final=False, write=True)
    try:
        try:
            os.mkdir(name, dir_fd=fd)
        except FileExistsError:
            raise HttpError(409, "already exists: " + path)
    finally:
        os.close(fd)
    return {"path": rel(root, p)}


def move_path(root, src, dst):
    d = resolve(root, dst, write=True)
    s = resolve(root, src, must_exist=True, write=True)
    if s == os.path.realpath(root):
        raise HttpError(400, "cannot move the root")
    if not HAS_DIRFD:
        if os.path.lexists(d):
            raise HttpError(409, "already exists: " + dst)
        os.makedirs(os.path.dirname(d), exist_ok=True)
        os.rename(s, d)
        return {"path": rel(root, d)}
    real_root = os.path.realpath(root)
    sfd, sname = walk_parent(real_root, _components(real_root, s), follow_final=False, write=True)
    try:
        dfd, dname = walk_parent(real_root, _components(real_root, d), create=True, follow_final=False, write=True)
        try:
            if _exists_at(dfd, dname):
                raise HttpError(409, "already exists: " + dst)
            os.rename(sname, dname, src_dir_fd=sfd, dst_dir_fd=dfd)
        finally:
            os.close(dfd)
    finally:
        os.close(sfd)
    return {"path": rel(root, d)}


def _exists_at(dir_fd, name):
    try:
        os.stat(name, dir_fd=dir_fd, follow_symlinks=False)
        return True
    except FileNotFoundError:
        return False


# ── workspace backup / setup (remote docker: the host cannot mount the project) ─

EXEC_MANIFEST = ".jllm-exec"
# harness-private data that is never copied: the scratchpad is disposable, the history lives on the host
BACKUP_EXCLUDE = (".jllm/scratchpad", ".jllm/history")


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
        base = os.path.dirname(start)
    else:
        files = walk_files(start, root)
        base = start

    def glob_ok(r_):
        return grx.match(r_) or (gtop is not None and gtop.match(r_))

    for full, r in files:
        # the glob matches relative to the searched path; one written relative to the root is accepted too
        if grx is not None and not (glob_ok(os.path.relpath(full, base).replace(os.sep, "/")) or glob_ok(r)):
            continue
        try:
            if os.path.getsize(full) > MAX_FILE_BYTES:
                continue
            with os.fdopen(open_in_root(root, full, os.O_RDONLY), "r", encoding="utf-8") as f:
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
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _body(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self.close_connection = True
            raise HttpError(400, "bad Content-Length")
        if n < 0 or n > MAX_BODY:
            self.close_connection = True  # the body is not read: the connection cannot be reused
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
            # nothing of an unauthenticated request is read, its body included: answer and close the connection
            self.log_message('"%s %s" 401', self.command, urlparse(self.path).path)
            self.close_connection = True
            self.send_response(401)
            self.send_header("Content-Length", "0")
            self.send_header("Connection", "close")
            self.end_headers()
            return
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
