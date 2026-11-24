#!/usr/bin/env python3
"""
==========================================================================
 Alaktra Music - Admin Console server  (default: http://localhost:8080)
==========================================================================
Standalone and standard-library only, so it needs no venv and no pip
packages to start. It talks to music.db directly, which means the console
works whether or not the streaming API (port 8000) is running.

  * Serves admin.html and its JSON API on ADMIN_PORT (default 8080)
  * Starts / stops the FastAPI server (and the Cloudflare tunnel)
  * Captures their output for the Logs page
  * Token-based login (ADMIN_USERNAME / ADMIN_PASSWORD from .env)

Run it through launcher.py (or `python admin_server.py`).
"""
from __future__ import annotations

import atexit
import collections
import hashlib
import hmac
import json
import mimetypes
import os
import platform
import re
import secrets
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse
import urllib.request
import uuid
from contextlib import closing
from datetime import datetime, timedelta
from email.parser import BytesParser
from email.policy import HTTP as HTTP_POLICY
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)


# ----------------------------------------------------------------- config --
def load_env(path: str | None = None) -> None:
    """Load server/.env (same rules as main.py: real environment wins)."""
    path = path or os.path.join(BASE_DIR, ".env")
    if not os.path.exists(path):
        return
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))
    except Exception as e:  # pragma: no cover
        print(f"[warn] could not read .env: {e}")


load_env()

import database  # noqa: E402  (standard library only)
from database import DB_PATH, MUSIC_DIR, COVER_DIR, PIN_DIR, ARTIST_DIR  # noqa: E402

DEFAULT_PASSWORD = "admin123"
ADMIN_USER = os.getenv("ADMIN_USERNAME", "admin")
ADMIN_PASS = os.getenv("ADMIN_PASSWORD", DEFAULT_PASSWORD)
USING_DEFAULT_PASSWORD = ADMIN_PASS == DEFAULT_PASSWORD
ADMIN_PORT = int(os.getenv("ADMIN_PORT", "8080"))
# With the factory password the console only listens on this machine.
# Set a real ADMIN_PASSWORD in .env to make it reachable from your LAN/VPN.
ADMIN_HOST = os.getenv("ADMIN_HOST") or ("127.0.0.1" if USING_DEFAULT_PASSWORD else "0.0.0.0")

API_PORT = int(os.getenv("PORT", "8000"))
_h = os.getenv("HOST", "0.0.0.0")
API_HOST = _h if re.fullmatch(r"[0-9a-fA-F:.]+", _h or "") else "0.0.0.0"

MAX_UPLOAD = int(os.getenv("ADMIN_MAX_UPLOAD_MB", "300")) * 1024 * 1024
SESSION_TTL = 12 * 3600
KEEP_API_ON_EXIT = os.getenv("KEEP_API_ON_EXIT", "0") == "1"
PIN_RADIUS_M = 50  # matches routes/pins.py

ADMIN_HTML = os.path.join(BASE_DIR, "admin.html")
SETTINGS_FILE = os.path.join(BASE_DIR, "admin_settings.json")

AUDIO_EXT = {".mp3", ".flac", ".m4a", ".wav", ".ogg", ".aac", ".opus", ".wma"}
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp"}


class ApiError(Exception):
    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status, self.detail = status, detail


# ------------------------------------------------------------------- logs --
LOGS: collections.deque = collections.deque(maxlen=4000)
_log_seq = 0
_log_lock = threading.Lock()
_last_level = {"api": "info", "tunnel": "info", "admin": "info"}
_ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


def _guess_level(src: str, msg: str) -> str:
    m = msg.lstrip()
    up = msg.upper()
    if (up.startswith("ERROR") or " ERR " in up or "TRACEBACK" in up or "EXCEPTION" in up
            or "OPERATIONALERROR" in up or re.search(r'"\s5\d\d\b', msg) or re.search(r"\bERR\b", up)):
        return "err"
    if m != msg or m.startswith(("File ", "sqlite3.", "raise ", "^")):  # traceback continuation
        if _last_level.get(src) == "err":
            return "err"
    if up.startswith("WARN") or " WARN" in up or re.search(r'"\s4\d\d\b', msg):
        return "warn"
    return "info"


def add_log(src: str, msg: str, level: str | None = None) -> None:
    global _log_seq
    msg = _ANSI.sub("", str(msg)).rstrip("\r\n")
    if not msg.strip() or "/health" in msg or "/admin/api/" in msg:
        return
    level = level or _guess_level(src, msg)
    _last_level[src] = level
    with _log_lock:
        _log_seq += 1
        LOGS.append({"id": _log_seq, "t": time.strftime("%H:%M:%S"), "ts": int(time.time() * 1000),
                     "src": src, "level": level, "msg": msg.strip() if len(msg) < 600 else msg[:600] + "…"})


# --------------------------------------------------------------- db utils --
def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=30000")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def q_all(sql: str, args=()) -> list[dict]:
    with closing(db()) as c:
        return [dict(r) for r in c.execute(sql, args).fetchall()]


def q_one(sql: str, args=()) -> dict | None:
    with closing(db()) as c:
        r = c.execute(sql, args).fetchone()
        return dict(r) if r else None


def q_val(sql: str, args=(), default=0):
    try:
        with closing(db()) as c:
            r = c.execute(sql, args).fetchone()
            return default if r is None or r[0] is None else r[0]
    except sqlite3.OperationalError:
        return default


def like(s: str) -> str:
    return "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"


def to_int(v, default=0) -> int:
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def paging(q: dict, default_per: int = 15):
    cap = 5000 if q.get("export") else 100
    page = max(1, to_int(q.get("page"), 1))
    per = min(cap, max(1, to_int(q.get("per"), default_per)))
    return page, per, (page - 1) * per


def ensure_db() -> None:
    database.init_db()  # creates every table if music.db is new / empty
    with closing(db()) as c:
        try:
            c.execute("PRAGMA journal_mode=WAL")
        except sqlite3.DatabaseError:
            pass
        c.execute("CREATE INDEX IF NOT EXISTS idx_history_song ON listening_history(song_id)")
        c.execute("CREATE INDEX IF NOT EXISTS idx_history_played ON listening_history(played_at)")
        c.commit()


# ------------------------------------------------------------ small utils --
_size_cache: dict = {}


def dir_size(path: str, ttl: float = 30.0) -> int:
    now = time.time()
    hit = _size_cache.get(path)
    if hit and now - hit[0] < ttl:
        return hit[1]
    total = 0
    if os.path.isdir(path):
        for root, _d, files in os.walk(path):
            for f in files:
                try:
                    total += os.path.getsize(os.path.join(root, f))
                except OSError:
                    pass
    _size_cache[path] = (now, total)
    return total


def fmt_size(n: float) -> str:
    n = float(n or 0)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{n:.0f} {unit}" if unit == "B" else (f"{n:.1f} {unit}" if n < 100 else f"{n:.0f} {unit}")
        n /= 1024
    return "0 B"


def fmt_dur(sec: float) -> str:
    sec = int(sec or 0)
    h, m = divmod(sec // 60, 60)
    return f"{h}h {m:02d}m" if h else f"{m}m {sec % 60:02d}s"


def mmss(sec) -> str:
    sec = int(float(sec or 0))
    return f"{sec // 60}:{sec % 60:02d}"


def media_url(rel: str | None, folder: str) -> str:
    rel = (rel or "").strip().replace("\\", "/").lstrip("/")
    if not rel:
        return ""
    return "/" + rel if rel.startswith(folder + "/") else f"/{folder}/{rel}"


def safe_unlink(rel_or_name: str | None, base_dir: str) -> None:
    """Delete a stored file, but only if it really lives inside base_dir."""
    if not rel_or_name:
        return
    name = rel_or_name.replace("\\", "/")
    cand = os.path.realpath(os.path.join(BASE_DIR, name)) if "/" in name else os.path.realpath(os.path.join(base_dir, name))
    root = os.path.realpath(base_dir) + os.sep
    if cand.startswith(root) and os.path.isfile(cand):
        try:
            os.remove(cand)
        except OSError:
            pass


def lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127."):
            return ip
    except Exception:
        pass
    return "127.0.0.1"


def day_start_ms(days_ago: int = 0) -> int:
    d = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0) - timedelta(days=days_ago)
    return int(d.timestamp() * 1000)


def pct_change(cur: float, prev: float) -> str:
    if not prev:
        return "+100%" if cur else "0%"
    p = round((cur - prev) / prev * 100)
    return f"{'+' if p >= 0 else '−'}{abs(p)}%"


# --------------------------------------------------------------- settings --
_settings_lock = threading.Lock()
_VALID_CF_PROTOCOLS = {"http2", "quic", "auto"}
_VALID_CF_EDGE_IPS = {"auto", "4", "6"}

def _norm_cf_protocol(val: str) -> str:
    v = str(val or "").strip().lower()
    return v if v in _VALID_CF_PROTOCOLS else "http2"

def _norm_cf_edge_ip(val: str) -> str:
    v = str(val or "").strip().lower()
    return v if v in _VALID_CF_EDGE_IPS else "auto"

SETTINGS = {
    "cf_tunnel_name": os.getenv("CF_TUNNEL_NAME", "") or "alaktra",
    "cf_host_domain": os.getenv("CF_HOST_DOMAIN", "api.alaktracare.in"),
    "cf_tunnel_protocol": _norm_cf_protocol(os.getenv("CF_TUNNEL_PROTOCOL", "http2")),
    "cf_edge_ip_version": _norm_cf_edge_ip(os.getenv("CF_EDGE_IP_VERSION", "auto")),
    "tunnel_enabled": True,
}
try:
    with open(SETTINGS_FILE, "r", encoding="utf-8") as _f:
        _loaded = json.load(_f)
        for _k, _v in _loaded.items():
            if _k not in SETTINGS:
                continue
            if _k == "cf_tunnel_protocol":
                SETTINGS[_k] = _norm_cf_protocol(_v)
            elif _k == "cf_edge_ip_version":
                SETTINGS[_k] = _norm_cf_edge_ip(_v)
            else:
                SETTINGS[_k] = _v
except Exception:
    pass


def save_settings() -> None:
    with _settings_lock:
        try:
            with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
                json.dump(SETTINGS, f, indent=2)
        except OSError as e:
            add_log("admin", f"Could not save admin_settings.json: {e}", "warn")


# --------------------------------------------------------- process control --
_NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))
_alive_cache = (0.0, False)


def probe_api() -> bool:
    try:
        with _NO_PROXY.open(f"http://127.0.0.1:{API_PORT}/health", timeout=0.8) as r:
            return r.status == 200
    except Exception:
        return False


def api_alive() -> bool:
    global _alive_cache
    if "PROCS" in globals() and PROCS.api_managed():
        return PROCS.api.poll() is None
    now = time.time()
    if now - _alive_cache[0] < 5.0:
        return _alive_cache[1]
    ok = port_in_use(API_PORT)
    _alive_cache = (now, ok)
    return ok


def port_in_use(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.3):
            return True
    except OSError:
        return False


def server_python() -> str:
    names = (["venv/Scripts/python.exe", ".venv/Scripts/python.exe"] if os.name == "nt"
             else ["venv/bin/python", ".venv/bin/python"])
    for n in names:
        p = os.path.join(BASE_DIR, n)
        if os.path.isfile(p):
            return p
    return sys.executable


def kill_tree(proc: subprocess.Popen) -> None:
    if proc.poll() is not None:
        return
    try:
        if os.name == "nt":
            subprocess.call(["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
            try:
                proc.wait(timeout=6)
            except subprocess.TimeoutExpired:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
    except (ProcessLookupError, PermissionError, OSError):
        try:
            proc.kill()
        except Exception:
            pass


def kill_process_on_port(port: int) -> bool:
    """Force stop any external process listening on the specified port."""
    stopped = False
    if os.name == "nt":
        try:
            out = subprocess.check_output(f"netstat -ano | findstr :{port}", shell=True, text=True)
            pids = set()
            for line in out.strip().splitlines():
                parts = line.split()
                if len(parts) >= 5 and "LISTENING" in line:
                    pids.add(parts[-1])
            for pid in pids:
                subprocess.call(["taskkill", "/F", "/PID", pid], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                stopped = True
        except Exception:
            pass
    else:
        try:
            res = subprocess.run(["fuser", "-k", f"{port}/tcp"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if res.returncode == 0:
                stopped = True
        except Exception:
            pass

        if not stopped:
            try:
                out = subprocess.check_output(["lsof", "-t", f"-i:{port}"], text=True)
                for pid_str in out.strip().splitlines():
                    pid = int(pid_str.strip())
                    if pid != os.getpid():
                        os.kill(pid, signal.SIGKILL)
                        stopped = True
            except Exception:
                pass

        if not stopped:
            try:
                out = subprocess.check_output(["ps", "aux"], text=True)
                for line in out.splitlines():
                    if "main.py" in line or "uvicorn" in line:
                        parts = line.split()
                        if len(parts) >= 2:
                            pid = int(parts[1])
                            if pid != os.getpid():
                                os.kill(pid, signal.SIGKILL)
                                stopped = True
            except Exception:
                pass

    return stopped


class Processes:
    def __init__(self):
        self.api: subprocess.Popen | None = None
        self.tunnel: subprocess.Popen | None = None
        self.api_started = 0.0
        self.tunnel_started = 0.0
        self.lock = threading.RLock()
        self._ext_tunnel = (0.0, False)
        self._ext_warned = False
        self._shutting_down = False
        self._tunnel_restarts = 0
        self._tunnel_restart_timer: threading.Timer | None = None

    # -- helpers
    def _spawn(self, src: str, cmd: list[str]) -> subprocess.Popen:
        env = dict(os.environ, PYTHONUNBUFFERED="1")
        kw = {"start_new_session": True} if os.name != "nt" else {"creationflags": 0x00000200}  # NEW_PROCESS_GROUP
        proc = subprocess.Popen(cmd, cwd=BASE_DIR, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, bufsize=1, errors="replace", env=env, **kw)

        def pump():
            for line in proc.stdout:  # type: ignore[union-attr]
                add_log(src, line)
            code = proc.wait()
            add_log(src, f"{'API server' if src == 'api' else 'Cloudflare tunnel'} exited (code {code})",
                    "info" if code in (0, -15, 143) else "err")
            if src == "tunnel":
                self._maybe_schedule_tunnel_restart(proc, code)

        threading.Thread(target=pump, daemon=True, name=f"pump-{src}").start()
        return proc

    def _maybe_schedule_tunnel_restart(self, exited_proc: subprocess.Popen, code: int) -> None:
        with self.lock:
            if self._shutting_down or not SETTINGS.get("tunnel_enabled", True):
                return
            if self.tunnel is not exited_proc:
                return
            if self._tunnel_restart_timer is not None:
                self._tunnel_restart_timer.cancel()
                self._tunnel_restart_timer = None
            uptime = time.time() - self.tunnel_started if self.tunnel_started else 0.0
            if uptime > 30.0:
                self._tunnel_restarts = 0
            delay = min(30, 2 * (2 ** min(self._tunnel_restarts, 4)))
            self._tunnel_restarts += 1
            add_log("admin", f"Tunnel exited unexpectedly (code {code}); restarting in {delay}s…", "warn")

            def _retry():
                with self.lock:
                    self._tunnel_restart_timer = None
                    if self._shutting_down or not SETTINGS.get("tunnel_enabled", True):
                        return
                    if not self.tunnel_managed() and not self.tunnel_external():
                        self.ensure_tunnel()

            t = threading.Timer(delay, _retry)
            t.daemon = True
            t.start()
            self._tunnel_restart_timer = t

    def api_managed(self) -> bool:
        return self.api is not None and self.api.poll() is None

    def tunnel_managed(self) -> bool:
        return self.tunnel is not None and self.tunnel.poll() is None

    def tunnel_external(self) -> bool:
        if os.name == "nt" or self.tunnel_managed():
            return False
        now = time.time()
        if now - self._ext_tunnel[0] < 5:
            return self._ext_tunnel[1]
        ok = False
        try:
            ok = subprocess.run(["pgrep", "-x", "cloudflared"], capture_output=True, timeout=1).returncode == 0
        except Exception:
            pass
        self._ext_tunnel = (now, ok)
        return ok

    def ensure_tunnel(
        self,
        tunnel_name: str = "",
        protocol: str = "",
        edge_ip_version: str = "",
        force_restart: bool = False,
    ) -> str:
        with self.lock:
            changed = False
            if tunnel_name and tunnel_name != SETTINGS["cf_tunnel_name"]:
                SETTINGS["cf_tunnel_name"] = tunnel_name
                changed = True
            if protocol:
                norm_proto = _norm_cf_protocol(protocol)
                if norm_proto != SETTINGS["cf_tunnel_protocol"]:
                    SETTINGS["cf_tunnel_protocol"] = norm_proto
                    changed = True
            if edge_ip_version:
                norm_edge = _norm_cf_edge_ip(edge_ip_version)
                if norm_edge != SETTINGS["cf_edge_ip_version"]:
                    SETTINGS["cf_edge_ip_version"] = norm_edge
                    changed = True
            if changed:
                save_settings()

            name = SETTINGS["cf_tunnel_name"]
            proto = _norm_cf_protocol(SETTINGS.get("cf_tunnel_protocol", "http2"))
            edge_ip = _norm_cf_edge_ip(SETTINGS.get("cf_edge_ip_version", "auto"))

            if (force_restart or changed) and self.tunnel_managed():
                old = self.tunnel
                self.tunnel = None
                if self._tunnel_restart_timer is not None:
                    self._tunnel_restart_timer.cancel()
                    self._tunnel_restart_timer = None
                if old is not None:
                    kill_tree(old)

            if self.tunnel_managed():
                return "already running"
            if self.tunnel_external():
                if not self._ext_warned:
                    self._ext_warned = True
                    add_log("admin", "Using externally started cloudflared process (ensure protocol: http2 is configured to prevent QUIC UDP timeouts).", "info")
                return "already running"
            if not shutil.which("cloudflared"):
                add_log("admin", "cloudflared not found in PATH - tunnel not started.", "warn")
                return "cloudflared is not installed"

            if self._tunnel_restart_timer is not None:
                self._tunnel_restart_timer.cancel()
                self._tunnel_restart_timer = None

            cmd = ["cloudflared", "tunnel", "run"]
            if proto in ("http2", "quic"):
                cmd += ["--protocol", proto]
            if edge_ip in ("4", "6"):
                cmd += ["--edge-ip-version", edge_ip]
            cmd.append(name)

            add_log("admin", f"Starting Cloudflare tunnel ({name}, protocol={proto}, edge_ip={edge_ip}) for admin & API routing", "info")
            self.tunnel_started = time.time()
            self.tunnel = self._spawn("tunnel", cmd)
            return "started"

    # -- actions
    def start(
        self,
        tunnel: bool,
        tunnel_name: str,
        host: str,
        tunnel_protocol: str = "",
        edge_ip_version: str = "",
    ) -> dict:
        global _alive_cache
        with self.lock:
            out: dict = {}
            tunnel_changed = (
                (bool(tunnel_name) and tunnel_name != SETTINGS["cf_tunnel_name"])
                or (bool(tunnel_protocol) and _norm_cf_protocol(tunnel_protocol) != SETTINGS["cf_tunnel_protocol"])
                or (bool(edge_ip_version) and _norm_cf_edge_ip(edge_ip_version) != SETTINGS["cf_edge_ip_version"])
            )
            if tunnel_name:
                SETTINGS["cf_tunnel_name"] = tunnel_name
            if host:
                SETTINGS["cf_host_domain"] = host
            if tunnel_protocol:
                SETTINGS["cf_tunnel_protocol"] = _norm_cf_protocol(tunnel_protocol)
            if edge_ip_version:
                SETTINGS["cf_edge_ip_version"] = _norm_cf_edge_ip(edge_ip_version)
            SETTINGS["tunnel_enabled"] = bool(tunnel)
            save_settings()

            if self.api_managed() or probe_api():
                out["api"] = "already running"
            else:
                if port_in_use(API_PORT):
                    raise ApiError(409, f"Port {API_PORT} is already used by another program.")
                cmd = [
                    server_python(), "-m", "uvicorn", "main:app",
                    "--host", API_HOST, "--port", str(API_PORT),
                    "--timeout-keep-alive", "95",
                ]
                add_log("admin", f"Starting API: {' '.join(cmd)}", "info")
                self.api = self._spawn("api", cmd)
                self.api_started = time.time()
                deadline = time.time() + 8
                while time.time() < deadline:
                    time.sleep(0.25)
                    if self.api.poll() is not None:
                        tail = [l["msg"] for l in list(LOGS)[-12:] if l["src"] == "api"]
                        raise ApiError(500, "API failed to start: " + (tail[-1] if tail else "see Logs"))
                    if probe_api():
                        break
                _alive_cache = (0.0, False)
                out["api"] = "started" if probe_api() else "starting"

            if tunnel:
                out["tunnel"] = self.ensure_tunnel(
                    tunnel_name=tunnel_name,
                    protocol=tunnel_protocol,
                    edge_ip_version=edge_ip_version,
                    force_restart=tunnel_changed,
                )
            return out

    def stop(self) -> dict:
        global _alive_cache
        with self.lock:
            stopped = []
            # Keep the Cloudflare tunnel alive so admin.alaktracare.in stays reachable;
            # only stop the FastAPI server here (tunnel exits cleanly on Ctrl+C / shutdown).
            if self.api is not None and self.api.poll() is None:
                kill_tree(self.api)
                stopped.append("api")
            _alive_cache = (0.0, False)
            if probe_api():
                if kill_process_on_port(API_PORT):
                    stopped.append("api (external)")
                    _alive_cache = (0.0, False)
                else:
                    raise ApiError(409, "The API was started outside this console (terminal or systemd), "
                                        "so it can't be stopped from here.")
            return {"stopped": stopped}

    def shutdown(self) -> None:
        self._shutting_down = True
        with self.lock:
            if self._tunnel_restart_timer is not None:
                self._tunnel_restart_timer.cancel()
                self._tunnel_restart_timer = None
        if KEEP_API_ON_EXIT:
            return
        for p in (self.tunnel, self.api):
            if p is not None and p.poll() is None:
                kill_tree(p)

    def status(self) -> dict:
        managed = self.api_managed()
        alive = api_alive()
        t_managed, t_ext = self.tunnel_managed(), self.tunnel_external()
        host = SETTINGS["cf_host_domain"]
        tunnel_on = t_managed or t_ext
        return {
            "api": {"running": alive, "managed": managed, "port": API_PORT,
                    "pid": self.api.pid if managed else None,
                    "uptime": int(time.time() - self.api_started) if managed else None},
            "tunnel": {"installed": bool(shutil.which("cloudflared")), "running": tunnel_on,
                       "managed": t_managed, "name": SETTINGS["cf_tunnel_name"], "host": host,
                       "protocol": SETTINGS["cf_tunnel_protocol"],
                       "edge_ip_version": SETTINGS["cf_edge_ip_version"],
                       "enabled": SETTINGS["tunnel_enabled"]},
            "admin": {"port": ADMIN_PORT, "host": ADMIN_HOST},
            "lan_ip": lan_ip(),
            "app_url": f"https://{host}" if (tunnel_on and host) else f"http://{lan_ip()}:{API_PORT}",
        }


PROCS = Processes()


# ------------------------------------------------------------------- auth --
SESSIONS: dict[str, float] = {}
_fails: dict[str, list] = {}
_sess_lock = threading.Lock()


def check_login(ip: str, user: str, pw: str) -> str:
    now = time.time()
    with _sess_lock:
        recent = [t for t in _fails.get(ip, []) if now - t < 300]
        _fails[ip] = recent
        if len(recent) >= 5:
            raise ApiError(429, "Too many failed attempts. Wait a few minutes and try again.")
    ok_u = hmac.compare_digest(user.encode(), ADMIN_USER.encode())
    ok_p = hmac.compare_digest(pw.encode(), ADMIN_PASS.encode())
    if not (ok_u and ok_p):
        with _sess_lock:
            _fails[ip].append(now)
        add_log("admin", f"Failed admin login from {ip}", "warn")
        time.sleep(0.6)
        raise ApiError(401, "Invalid username or password")
    token = secrets.token_urlsafe(32)
    with _sess_lock:
        for t in [t for t, exp in SESSIONS.items() if exp < now]:
            SESSIONS.pop(t, None)
        SESSIONS[token] = now + SESSION_TTL
        _fails.pop(ip, None)
    add_log("admin", f"Admin signed in from {ip}", "info")
    return token


def valid_token(token: str | None) -> bool:
    if not token:
        return False
    with _sess_lock:
        exp = SESSIONS.get(token)
        if exp and exp > time.time():
            return True
        SESSIONS.pop(token, None)
        return False


# ------------------------------------------------------------ API: session --
class Ctx:
    def __init__(self, q: dict, body: dict | None, form=None, ip: str = "", token: str | None = None):
        self.q, self.body, self.form, self.ip, self.token = q, body or {}, form, ip, token


def api_login(ctx: Ctx):
    token = check_login(ctx.ip, str(ctx.body.get("username", "")).strip(), str(ctx.body.get("password", "")).strip())
    return {"success": True, "token": token, "username": ADMIN_USER, "default_password": USING_DEFAULT_PASSWORD}


def api_logout(ctx: Ctx):
    with _sess_lock:
        SESSIONS.pop(ctx.token or "", None)
    return {"success": True}


def api_session(ctx: Ctx):
    return {"username": ADMIN_USER, "default_password": USING_DEFAULT_PASSWORD}


# ------------------------------------------------------------- API: server --
def api_server_status(ctx: Ctx):
    s = PROCS.status()
    s["settings"] = dict(SETTINGS)
    return s


def api_server_start(ctx: Ctx):
    b = ctx.body
    res = PROCS.start(
        bool(b.get("tunnel", SETTINGS["tunnel_enabled"])),
        str(b.get("cf_tunnel_name", "")).strip(),
        str(b.get("cf_host_domain", "")).strip(),
        str(b.get("cf_tunnel_protocol", "")).strip(),
        str(b.get("cf_edge_ip_version", "")).strip(),
    )
    add_log("admin", f"Start requested -> {res}", "info")
    return {"success": True, **res}


def api_server_stop(ctx: Ctx):
    res = PROCS.stop()
    add_log("admin", f"Stop requested -> {res}", "info")
    return {"success": True, **res}


# ----------------------------------------------------------- API: overview --
def daily_counts(sql: str, days: int, args=()) -> list[int]:
    """sql must return (day 'YYYY-MM-DD', n). Result is oldest->newest, zero-filled."""
    got = {r["d"]: r["n"] for r in q_all(sql, args)}
    out = []
    for i in range(days - 1, -1, -1):
        out.append(int(got.get((datetime.now() - timedelta(days=i)).strftime("%Y-%m-%d"), 0)))
    return out


def api_overview(ctx: Ctx):
    now_ms = int(time.time() * 1000)
    total_songs = q_val("SELECT COUNT(*) FROM songs")
    total_users = q_val("SELECT COUNT(*) FROM users")
    total_artists = q_val("SELECT COUNT(*) FROM artists")
    total_playlists = q_val("SELECT COUNT(*) FROM playlists")
    plays_total = q_val("SELECT COUNT(*) FROM listening_history")
    plays_today = q_val("SELECT COUNT(*) FROM listening_history WHERE played_at >= ?", (day_start_ms(0),))
    plays_yday = q_val("SELECT COUNT(*) FROM listening_history WHERE played_at >= ? AND played_at < ?",
                       (day_start_ms(1), day_start_ms(0)))
    active_24h = q_val("SELECT COUNT(DISTINCT user_id) FROM listening_history WHERE played_at >= ?", (now_ms - 86400000,))
    songs_week = q_val("SELECT COUNT(*) FROM songs WHERE created_at >= datetime('now','-7 days')")
    users_week = q_val("SELECT COUNT(*) FROM users WHERE created_at >= datetime('now','-7 days')")

    sparks = {
        "songs": daily_counts("SELECT date(created_at,'localtime') d, COUNT(*) n FROM songs "
                              "WHERE created_at >= datetime('now','-8 days') GROUP BY d", 7),
        "users": daily_counts("SELECT date(created_at,'localtime') d, COUNT(*) n FROM users "
                              "WHERE created_at >= datetime('now','-8 days') GROUP BY d", 7),
        "plays": daily_counts("SELECT date(played_at/1000,'unixepoch','localtime') d, COUNT(*) n FROM listening_history "
                              "WHERE played_at >= ? GROUP BY d", 7, (day_start_ms(6),)),
    }

    music, covers = dir_size(MUSIC_DIR), dir_size(COVER_DIR)
    media = dir_size(PIN_DIR) + dir_size(ARTIST_DIR)
    dbs = sum(os.path.getsize(p) for p in (DB_PATH, DB_PATH + "-wal", DB_PATH + "-shm") if os.path.exists(p))
    used = music + covers + media + dbs
    try:
        disk_total, disk_used, disk_free = shutil.disk_usage(BASE_DIR)
    except OSError:
        disk_total = disk_used = disk_free = 0

    def share(x):
        return round(x / used * 100) if used else 0

    storage = {
        "disk_total": fmt_size(disk_total), "disk_used": fmt_size(disk_used), "disk_free": fmt_size(disk_free),
        "disk_pct": round(disk_used / disk_total * 100) if disk_total else 0,
        "app_used": fmt_size(used), "app_pct_of_disk": round(used / disk_total * 100, 1) if disk_total else 0,
        "parts": [
            {"label": "Music", "size": fmt_size(music), "pct": share(music)},
            {"label": "Covers", "size": fmt_size(covers), "pct": share(covers)},
            {"label": "Pins & artist media", "size": fmt_size(media), "pct": share(media)},
            {"label": "music.db", "size": fmt_size(dbs), "pct": share(dbs)},
        ],
    }

    recent = []
    for r in q_all("SELECT id,title,artist,cover_path,dominant_color,duration,created_at FROM songs ORDER BY id DESC LIMIT 5"):
        recent.append({"id": r["id"], "title": r["title"], "artist": r["artist"], "cover_url": media_url(r["cover_path"], "covers"),
                       "color": r["dominant_color"] or "", "duration": mmss(r["duration"]), "added": r["created_at"]})

    ev = []
    for r in q_all("SELECT username, created_at FROM users ORDER BY id DESC LIMIT 5"):
        ev.append({"kind": "user", "who": r["username"], "what": "joined", "at": r["created_at"]})
    for r in q_all("SELECT title, created_at FROM songs ORDER BY id DESC LIMIT 5"):
        ev.append({"kind": "song", "who": r["title"], "what": "was uploaded", "at": r["created_at"]})
    for r in q_all("SELECT name, owner_username, created_at FROM clubs ORDER BY id DESC LIMIT 5"):
        ev.append({"kind": "club", "who": r["owner_username"], "what": f"created club “{r['name']}”", "at": r["created_at"]})
    for r in q_all("SELECT u.username, p.created_at FROM location_pins p LEFT JOIN users u ON u.id=p.user_id ORDER BY p.id DESC LIMIT 5"):
        ev.append({"kind": "pin", "who": r["username"] or "Someone", "what": "dropped a pin", "at": r["created_at"]})
    for r in q_all("SELECT name, created_at FROM fuses ORDER BY id DESC LIMIT 5"):
        ev.append({"kind": "blend", "who": r["name"], "what": "blend created", "at": r["created_at"]})
    try:
        for r in q_all("SELECT song_name, artist_name, requester_name, created_at FROM song_requests ORDER BY id DESC LIMIT 5"):
            who = r["requester_name"] or "Listener"
            ev.append({"kind": "request", "who": who, "what": f"requested “{r['song_name']}” by {r['artist_name']}", "at": r["created_at"]})
    except sqlite3.OperationalError:
        pass
    ev.sort(key=lambda e: e["at"] or "", reverse=True)

    st = PROCS.status()
    cutoff = (time.time() - 3600) * 1000
    pending_requests = q_val("SELECT COUNT(*) FROM song_requests WHERE status='pending'")
    notices = {"claims": q_val("SELECT COUNT(*) FROM artist_claims WHERE status='pending'"),
               "requests": pending_requests,
               "errors": sum(1 for l in list(LOGS) if l["level"] == "err" and l["src"] != "admin" and l["ts"] >= cutoff)}
    try:
        journal = q_val("PRAGMA journal_mode", default="?")
    except Exception:
        journal = "?"
    return {
        "cards": {
            "songs": {"value": total_songs, "delta": f"+{songs_week}", "note": "this week", "spark": sparks["songs"]},
            "users": {"value": active_24h, "label": "Active users", "delta": f"{total_users}", "note": "registered", "spark": sparks["users"],
                      "new_week": users_week},
            "plays": {"value": plays_today, "delta": pct_change(plays_today, plays_yday), "note": "vs yesterday", "spark": sparks["plays"]},
            "storage": {"value": fmt_size(used), "delta": f"{storage['app_pct_of_disk']}%", "note": "of disk", "pct": storage["app_pct_of_disk"]},
        },
        "totals": {"songs": total_songs, "users": total_users, "artists": total_artists, "playlists": total_playlists,
                   "plays": plays_total, "requests_pending": pending_requests},
        "storage": storage,
        "recent": recent,
        "activity": ev[:8],
        "services": {"api": st["api"], "tunnel": st["tunnel"], "db": {"size": fmt_size(dbs), "journal": str(journal).upper()}},
        "notices": notices,
    }


def api_activity(ctx: Ctx):
    rng = ctx.q.get("range", "30d")
    if rng == "1y":
        first = datetime.now().replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        months = []
        y, m = first.year, first.month
        for _ in range(12):
            months.append((y, m))
            m -= 1
            if m == 0:
                y, m = y - 1, 12
        months.reverse()
        start = datetime(months[0][0], months[0][1], 1)
        rows = q_all("SELECT strftime('%Y-%m', played_at/1000,'unixepoch','localtime') d, SUM(listened_seconds) s "
                     "FROM listening_history WHERE played_at >= ? GROUP BY d", (int(start.timestamp() * 1000),))
        got = {r["d"]: r["s"] or 0 for r in rows}
        labels = [datetime(y, m, 1).strftime("%b") for y, m in months]
        values = [round(got.get(f"{y:04d}-{m:02d}", 0) / 60) for y, m in months]
    else:
        days = 7 if rng == "7d" else 30
        rows = q_all("SELECT date(played_at/1000,'unixepoch','localtime') d, SUM(listened_seconds) s "
                     "FROM listening_history WHERE played_at >= ? GROUP BY d", (day_start_ms(days - 1),))
        got = {r["d"]: r["s"] or 0 for r in rows}
        dates = [datetime.now() - timedelta(days=i) for i in range(days - 1, -1, -1)]
        labels = [d.strftime("%b %-d") if os.name != "nt" else d.strftime("%b %d") for d in dates]
        values = [round(got.get(d.strftime("%Y-%m-%d"), 0) / 60) for d in dates]
    return {"range": rng, "labels": labels, "values": values, "total": sum(values)}


# ------------------------------------------------------------- API: lists --
def api_suggestions(ctx: Ctx):
    kind = ctx.q.get("type", "artist").lower()
    query = ctx.q.get("q", "").strip()
    if kind == "song":
        sql = "SELECT DISTINCT title, artist FROM songs"
        args = []
        if query:
            sql += " WHERE title LIKE ? ESCAPE '\\'"
            args.append(like(query))
        sql += " ORDER BY id DESC LIMIT 15"
        rows = q_all(sql, args)
        return {"items": [{"title": r["title"], "artist": r["artist"] or ""} for r in rows]}
    else:  # artist
        sql = "SELECT id, name, is_verified FROM artists"
        args = []
        if query:
            sql += " WHERE name LIKE ? ESCAPE '\\'"
            args.append(like(query))
        sql += " ORDER BY is_verified DESC, name COLLATE NOCASE ASC LIMIT 15"
        rows = q_all(sql, args)
        return {"items": [{"id": r["id"], "name": r["name"], "verified": bool(r["is_verified"])} for r in rows]}


def list_songs(ctx: Ctx):
    q, flt = ctx.q.get("q", "").strip(), ctx.q.get("filter", "all")
    page, per, off = paging(ctx.q)
    where, args = [], []
    if q:
        where.append("(s.title LIKE ? ESCAPE '\\' OR s.artist LIKE ? ESCAPE '\\')")
        args += [like(q), like(q)]
    if flt == "recent":
        where.append("s.created_at >= datetime('now','-7 days')")
    elif flt == "nocover":
        where.append("(s.cover_path IS NULL OR TRIM(s.cover_path)='')")
    W = ("WHERE " + " AND ".join(where)) if where else ""
    order = "COALESCE(p.c,0) DESC, s.id DESC" if flt == "most" else "s.id DESC"
    total = q_val(f"SELECT COUNT(*) FROM songs s {W}", args)
    rows = q_all(
        f"SELECT s.id,s.title,s.artist,s.cover_path,s.audio_path,s.dominant_color,s.duration,s.created_at,COALESCE(p.c,0) AS plays "
        f"FROM songs s LEFT JOIN (SELECT song_id, COUNT(*) c FROM listening_history GROUP BY song_id) p ON p.song_id=s.id "
        f"{W} ORDER BY {order} LIMIT ? OFFSET ?", args + [per, off])
    song_ids = [r["id"] for r in rows]
    artists_map = {}
    if song_ids:
        placeholders = ",".join("?" for _ in song_ids)
        sa_rows = q_all(
            f"SELECT sa.song_id, sa.role, a.name FROM song_artists sa "
            f"JOIN artists a ON a.id = sa.artist_id "
            f"WHERE sa.song_id IN ({placeholders}) "
            f"ORDER BY sa.song_id, CASE WHEN sa.role='primary' THEN 0 ELSE 1 END, sa.position ASC",
            song_ids
        )
        for sa in sa_rows:
            sid = sa["song_id"]
            if sid not in artists_map:
                artists_map[sid] = {"main": "", "features": []}
            if sa["role"] == "primary" and not artists_map[sid]["main"]:
                artists_map[sid]["main"] = sa["name"]
            else:
                artists_map[sid]["features"].append(sa["name"])
    items = []
    for r in rows:
        try:
            size = os.path.getsize(os.path.join(BASE_DIR, (r["audio_path"] or "").replace("\\", "/")))
        except OSError:
            size = 0
        art_info = artists_map.get(r["id"])
        if art_info and art_info["main"]:
            main_artist = art_info["main"]
            feature_artists = ", ".join(art_info["features"])
        else:
            splits = database.split_artist_credits(r["artist"] or "")
            main_artist = splits[0] if splits else (r["artist"] or "")
            feature_artists = ", ".join(splits[1:]) if len(splits) > 1 else ""
        items.append({"id": r["id"], "title": r["title"], "artist": r["artist"],
                      "main_artist": main_artist, "feature_artists": feature_artists,
                      "cover_url": media_url(r["cover_path"], "covers"),
                      "color": r["dominant_color"] or "", "duration": mmss(r["duration"]), "plays": r["plays"],
                      "size": fmt_size(size) if size else "—", "added": r["created_at"]})
    kpis = [["Total songs", q_val("SELECT COUNT(*) FROM songs")],
            ["Total duration", fmt_dur(q_val("SELECT SUM(duration) FROM songs"))],
            ["Library size", fmt_size(dir_size(MUSIC_DIR))],
            ["Plays · 30 days", q_val("SELECT COUNT(*) FROM listening_history WHERE played_at >= ?", (day_start_ms(30),))]]
    return {"items": items, "total": total, "page": page, "per": per, "kpis": kpis}


def list_artists(ctx: Ctx):
    q, flt = ctx.q.get("q", "").strip(), ctx.q.get("filter", "all")
    page, per, off = paging(ctx.q, 12)
    where, args = [], []
    if q:
        where.append("a.name LIKE ? ESCAPE '\\'")
        args.append(like(q))
    if flt == "claimed":
        where.append("a.claimed_by_user_id IS NOT NULL")
    elif flt == "unclaimed":
        where.append("a.claimed_by_user_id IS NULL")
    W = ("WHERE " + " AND ".join(where)) if where else ""
    total = q_val(f"SELECT COUNT(*) FROM artists a {W}", args)
    rows = q_all(
        f"SELECT a.id,a.name,a.bio,a.avatar_path,a.banner_path,a.is_verified,a.claimed_by_user_id,u.username AS claimed_by,a.created_at,"
        f"(SELECT COUNT(*) FROM song_artists sa WHERE sa.artist_id=a.id) AS songs,"
        f"(SELECT COUNT(*) FROM song_artists sa JOIN listening_history h ON h.song_id=sa.song_id WHERE sa.artist_id=a.id) AS plays "
        f"FROM artists a LEFT JOIN users u ON u.id=a.claimed_by_user_id {W} ORDER BY songs DESC, a.name COLLATE NOCASE LIMIT ? OFFSET ?",
        args + [per, off])
    items = [{"id": r["id"], "name": r["name"], "bio": r["bio"] or "",
              "avatar_url": media_url(r["avatar_path"], "artist-media"),
              "banner_url": media_url(r["banner_path"], "artist-media"),
              "verified": bool(r["is_verified"]), "claimed": r["claimed_by_user_id"] is not None,
              "claimed_by_user_id": r["claimed_by_user_id"], "claimed_by": r["claimed_by"],
              "songs": r["songs"], "plays": r["plays"], "created": r["created_at"]} for r in rows]
    n_art = q_val("SELECT COUNT(*) FROM artists")
    n_claimed = q_val("SELECT COUNT(*) FROM artists WHERE claimed_by_user_id IS NOT NULL")
    n_links = q_val("SELECT COUNT(*) FROM song_artists")
    claims = q_all("SELECT c.id,c.requested_name,c.note,c.created_at,u.username FROM artist_claims c "
                   "LEFT JOIN users u ON u.id=c.user_id WHERE c.status='pending' ORDER BY c.id DESC LIMIT 20")
    all_users = q_all("SELECT id, username, email FROM users ORDER BY username COLLATE NOCASE")
    users_list = [{"id": u["id"], "username": u["username"], "email": u["email"]} for u in all_users]
    kpis = [["Artists", n_art], ["Claimed", n_claimed], ["Unclaimed", n_art - n_claimed],
            ["Avg. songs", round(n_links / n_art, 1) if n_art else 0]]
    return {"items": items, "total": total, "page": page, "per": per, "kpis": kpis, "claims": claims, "users": users_list}


def list_users(ctx: Ctx):
    q, flt = ctx.q.get("q", "").strip(), ctx.q.get("filter", "all")
    page, per, off = paging(ctx.q)
    where, args = [], []
    if q:
        where.append("(u.username LIKE ? ESCAPE '\\' OR u.email LIKE ? ESCAPE '\\')")
        args += [like(q), like(q)]
    if flt == "verified":
        where.append("u.is_verified=1")
    elif flt == "pending":
        where.append("u.is_verified=0")
    W = ("WHERE " + " AND ".join(where)) if where else ""
    total = q_val(f"SELECT COUNT(*) FROM users u {W}", args)
    rows = q_all(
        f"SELECT u.id,u.username,u.email,u.is_verified,u.created_at,"
        f"(SELECT COUNT(*) FROM playlists p WHERE p.user_id=u.id) AS playlists,"
        f"(SELECT MAX(played_at) FROM listening_history h WHERE h.user_id=u.id) AS last_ms "
        f"FROM users u {W} ORDER BY u.id DESC LIMIT ? OFFSET ?", args + [per, off])
    items = [{"id": r["id"], "username": r["username"], "email": r["email"], "verified": bool(r["is_verified"]),
              "playlists": r["playlists"], "last_ms": r["last_ms"], "joined": r["created_at"]} for r in rows]
    kpis = [["Total users", q_val("SELECT COUNT(*) FROM users")],
            ["Verified", q_val("SELECT COUNT(*) FROM users WHERE is_verified=1")],
            ["Pending OTP", q_val("SELECT COUNT(*) FROM users WHERE is_verified=0")],
            ["Active today", q_val("SELECT COUNT(DISTINCT user_id) FROM listening_history WHERE played_at >= ?", (day_start_ms(0),))]]
    return {"items": items, "total": total, "page": page, "per": per, "kpis": kpis}


def list_playlists(ctx: Ctx):
    q, flt = ctx.q.get("q", "").strip(), ctx.q.get("filter", "all")
    page, per, off = paging(ctx.q)
    where, args = [], []
    if q:
        where.append("(p.name LIKE ? ESCAPE '\\' OR u.username LIKE ? ESCAPE '\\')")
        args += [like(q), like(q)]
    if flt == "blends":
        where.append("p.is_fuse=1")
    elif flt == "system":
        where.append("p.is_fuse=0 AND p.is_system=1")
    elif flt == "custom":
        where.append("p.is_fuse=0 AND p.is_system=0")
    W = ("WHERE " + " AND ".join(where)) if where else ""
    total = q_val(f"SELECT COUNT(*) FROM playlists p LEFT JOIN users u ON u.id=p.user_id {W}", args)
    rows = q_all(
        f"SELECT p.id,p.name,p.is_system,p.is_fuse,p.created_at,u.username,"
        f"(SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlist_id=p.id) AS songs "
        f"FROM playlists p LEFT JOIN users u ON u.id=p.user_id {W} ORDER BY p.id DESC LIMIT ? OFFSET ?", args + [per, off])
    items = [{"id": r["id"], "name": r["name"], "owner": r["username"] or "System", "songs": r["songs"],
              "type": "Blend" if r["is_fuse"] else ("System" if r["is_system"] else "Custom"), "created": r["created_at"]}
             for r in rows]
    kpis = [["Playlists", q_val("SELECT COUNT(*) FROM playlists")],
            ["System", q_val("SELECT COUNT(*) FROM playlists WHERE is_system=1 AND is_fuse=0")],
            ["Custom", q_val("SELECT COUNT(*) FROM playlists WHERE is_system=0 AND is_fuse=0")],
            ["Blends", q_val("SELECT COUNT(*) FROM playlists WHERE is_fuse=1")]]
    return {"items": items, "total": total, "page": page, "per": per, "kpis": kpis}


def list_pins(ctx: Ctx):
    q, flt = ctx.q.get("q", "").strip(), ctx.q.get("filter", "all")
    page, per, off = paging(ctx.q, 10)
    now = datetime.utcnow()
    where, args = [], []
    if q:
        where.append("(u.username LIKE ? ESCAPE '\\' OR s.title LIKE ? ESCAPE '\\')")
        args += [like(q), like(q)]
    if flt == "active":
        where.append("p.year=? AND p.month=?")
        args += [now.year, now.month]
    elif flt == "expired":
        where.append("(p.year < ? OR (p.year=? AND p.month < ?))")
        args += [now.year, now.year, now.month]
    W = ("WHERE " + " AND ".join(where)) if where else ""
    base = "FROM location_pins p LEFT JOIN users u ON u.id=p.user_id LEFT JOIN songs s ON s.id=p.song_id"
    total = q_val(f"SELECT COUNT(*) {base} {W}", args)
    rows = q_all(f"SELECT p.id,p.latitude,p.longitude,p.image_path,p.year,p.month,p.created_at,u.username,s.title AS song,s.artist "
                 f"{base} {W} ORDER BY p.id DESC LIMIT ? OFFSET ?", args + [per, off])
    items = [{"id": r["id"], "lat": r["latitude"], "lon": r["longitude"],
              "image_url": media_url((r["image_path"] or "").replace("pins/", "", 1) if (r["image_path"] or "").startswith("pins/") else r["image_path"], "pins"),
              "user": r["username"] or "deleted user", "song": r["song"] or "deleted song", "artist": r["artist"] or "",
              "month": f"{r['year']}-{int(r['month']):02d}", "created": r["created_at"]} for r in rows]
    map_pts = [{"id": r["id"], "lat": r["latitude"], "lon": r["longitude"], "label": r["song"] or "pin", "user": r["username"] or ""}
               for r in q_all("SELECT p.id,p.latitude,p.longitude,u.username,s.title AS song FROM location_pins p "
                              "LEFT JOIN users u ON u.id=p.user_id LEFT JOIN songs s ON s.id=p.song_id ORDER BY p.id DESC LIMIT 300")]
    nxt = (now.replace(day=28) + timedelta(days=4)).replace(day=1)
    days_left = (nxt.date() - now.date()).days
    kpis = [["Active pins", q_val("SELECT COUNT(*) FROM location_pins WHERE year=? AND month=?", (now.year, now.month))],
            ["Contributors", q_val("SELECT COUNT(DISTINCT user_id) FROM location_pins")],
            ["Resets in", f"{days_left} d"], ["Radius", f"{PIN_RADIUS_M} m"]]
    return {"items": items, "total": total, "page": page, "per": per, "kpis": kpis, "map": map_pts}


def list_clubs(ctx: Ctx):
    q, flt = ctx.q.get("q", "").strip(), ctx.q.get("filter", "all")
    page, per, off = paging(ctx.q, 12)
    now_ms = int(time.time() * 1000)
    live_since = now_ms - 120_000  # a club is "live" if someone was active in the last 2 minutes
    inner = ("SELECT c.id,c.code,c.name,c.owner_username,c.is_playing,c.created_at,s.title AS song,s.artist AS song_artist,"
             "(SELECT COUNT(DISTINCT m.user_id) FROM club_members m WHERE m.club_id=c.id) AS members,"
             "(SELECT COUNT(DISTINCT m.user_id) FROM club_members m WHERE m.club_id=c.id AND m.last_active_ms>=?) AS active,"
             "(SELECT COUNT(*) FROM club_songs cs WHERE cs.club_id=c.id) AS tracks "
             "FROM clubs c LEFT JOIN songs s ON s.id=c.current_song_id")
    where, args = [], [live_since]
    if q:
        where.append("(name LIKE ? ESCAPE '\\' OR code LIKE ? ESCAPE '\\' OR owner_username LIKE ? ESCAPE '\\')")
        args += [like(q)] * 3
    if flt == "live":
        where.append("active > 0")
    elif flt == "idle":
        where.append("active = 0")
    W = ("WHERE " + " AND ".join(where)) if where else ""
    total = q_val(f"SELECT COUNT(*) FROM ({inner}) {W}", args)
    rows = q_all(f"SELECT * FROM ({inner}) {W} ORDER BY active DESC, id DESC LIMIT ? OFFSET ?", args + [per, off])
    names: dict[int, list] = {}
    if rows:
        ids = ",".join(str(int(r["id"])) for r in rows)
        for m in q_all(f"SELECT club_id, username FROM club_members WHERE club_id IN ({ids}) GROUP BY club_id, user_id ORDER BY MAX(last_active_ms) DESC"):
            names.setdefault(m["club_id"], []).append(m["username"])
    items = [{"id": r["id"], "code": r["code"], "name": r["name"], "owner": r["owner_username"], "live": r["active"] > 0,
              "playing": bool(r["is_playing"]) and r["active"] > 0, "song": r["song"] or "", "song_artist": r["song_artist"] or "",
              "members": r["members"], "active": r["active"], "tracks": r["tracks"], "member_names": names.get(r["id"], [])[:5],
              "created": r["created_at"]} for r in rows]
    n_clubs = q_val("SELECT COUNT(*) FROM clubs")
    kpis = [["Live now", q_val("SELECT COUNT(DISTINCT club_id) FROM club_members WHERE last_active_ms >= ?", (live_since,))],
            ["Listeners", q_val("SELECT COUNT(*) FROM (SELECT DISTINCT club_id, user_id FROM club_members WHERE last_active_ms >= ?)", (live_since,))],
            ["Created today", q_val("SELECT COUNT(*) FROM clubs WHERE created_at >= date('now')")],
            ["Avg. size", round(q_val("SELECT COUNT(*) FROM (SELECT DISTINCT club_id, user_id FROM club_members)") / n_clubs, 1) if n_clubs else 0]]
    return {"items": items, "total": total, "page": page, "per": per, "kpis": kpis}


def list_blends(ctx: Ctx):
    q, flt = ctx.q.get("q", "").strip(), ctx.q.get("filter", "all")
    page, per, off = paging(ctx.q, 12)
    today = datetime.utcnow().strftime("%Y-%m-%d")
    week_ago = (datetime.utcnow() - timedelta(days=7)).strftime("%Y-%m-%d")
    where, args = [], []
    if q:
        where.append("(f.name LIKE ? ESCAPE '\\' OR f.user1_name LIKE ? ESCAPE '\\' OR f.user2_name LIKE ? ESCAPE '\\')")
        args += [like(q)] * 3
    if flt == "fresh":
        where.append("f.last_refreshed_date = ?")
        args.append(today)
    elif flt == "stale":
        where.append("f.last_refreshed_date < ?")
        args.append(week_ago)
    W = ("WHERE " + " AND ".join(where)) if where else ""
    total = q_val(f"SELECT COUNT(*) FROM fuses f {W}", args)
    rows = q_all(f"SELECT f.id,f.name,f.user1_name,f.user2_name,f.match_score,f.last_refreshed_date,f.created_at,"
                 f"(SELECT COUNT(*) FROM fuse_songs fs WHERE fs.fuse_id=f.id) AS songs "
                 f"FROM fuses f {W} ORDER BY f.id DESC LIMIT ? OFFSET ?", args + [per, off])
    items = [{"id": r["id"], "name": r["name"], "user1": r["user1_name"], "user2": r["user2_name"], "score": r["match_score"],
              "songs": r["songs"], "refreshed": r["last_refreshed_date"], "created": r["created_at"]} for r in rows]
    kpis = [["Blends", q_val("SELECT COUNT(*) FROM fuses")],
            ["Avg. match", f"{round(q_val('SELECT AVG(match_score) FROM fuses'))}%"],
            ["Pending invites", q_val("SELECT COUNT(*) FROM fuse_invites WHERE status='pending' AND expires_at_ms > ?", (int(time.time() * 1000),))],
            ["Refreshed today", q_val("SELECT COUNT(*) FROM fuses WHERE last_refreshed_date=?", (today,))]]
    return {"items": items, "total": total, "page": page, "per": per, "kpis": kpis}


def api_logs(ctx: Ctx):
    since = to_int(ctx.q.get("since"), 0)
    with _log_lock:
        items = [l for l in LOGS if l["id"] > since]
        last = _log_seq
    if since == 0:
        items = items[-400:]
    return {"items": items[-800:], "last": last}


# ------------------------------------------------------------ API: actions --
def _need_id(ctx: Ctx) -> int:
    i = to_int(ctx.body.get("id"), 0)
    if i <= 0:
        raise ApiError(400, "Missing id")
    return i


def _clean_color(c) -> str | None:
    c = (c or "").strip().upper()
    if not c:
        return None
    if not c.startswith("#"):
        c = "#" + c
    if not re.fullmatch(r"#[0-9A-F]{6}", c):
        raise ApiError(400, "Color must look like #00E599")
    return c


def song_edit(ctx: Ctx):
    i = _need_id(ctx)
    title = str(ctx.body.get("title", "")).strip()
    main_artist = str(ctx.body.get("main_artist") or ctx.body.get("artist") or "").strip()
    raw_feats = str(ctx.body.get("feature_artists", "")).strip()
    feat_list = [x.strip() for x in raw_feats.split(",") if x.strip()] if raw_feats else []
    color = _clean_color(ctx.body.get("dominant_color"))
    if not title:
        raise ApiError(400, "Title can't be empty")
    with closing(db()) as c:
        row = c.execute("SELECT artist FROM songs WHERE id=?", (i,)).fetchone()
        if not row:
            raise ApiError(404, "Song not found")
        c.execute("UPDATE songs SET title=?, dominant_color=?, updated_at=datetime('now') WHERE id=?", (title, color, i))
        if main_artist:
            database.allot_song_artists(c, i, primary_artist_name=main_artist, featured_artist_names=feat_list)
        c.commit()
    add_log("admin", f"Edited song #{i} “{title}”")
    return {"success": True}


def song_delete(ctx: Ctx):
    i = _need_id(ctx)
    with closing(db()) as c:
        row = c.execute("SELECT title,audio_path,cover_path FROM songs WHERE id=?", (i,)).fetchone()
        if not row:
            raise ApiError(404, "Song not found")
        pins = [r["image_path"] for r in c.execute("SELECT image_path FROM location_pins WHERE song_id=?", (i,))]
        c.execute("DELETE FROM songs WHERE id=?", (i,))
        c.commit()
        for col, folder, base in (("audio_path", "music", MUSIC_DIR), ("cover_path", "covers", COVER_DIR)):
            rel = row[col]
            if rel and not c.execute(f"SELECT 1 FROM songs WHERE {col}=? LIMIT 1", (rel,)).fetchone():
                safe_unlink(rel, base)
    for p in pins:
        safe_unlink(p, PIN_DIR)
    _size_cache.clear()
    add_log("admin", f"Deleted song #{i} “{row['title']}” and its files")
    return {"success": True}


_helpers = None


def _upload_helpers():
    global _helpers
    if _helpers:
        return _helpers
    dur = san = None
    try:
        from routes import songs as S  # needs the venv (fastapi); gives identical behaviour to POST /songs/upload
        dur, san = S.calculate_duration, S.sanitize_and_inject_color_metadata
    except Exception as e:
        add_log("admin", f"Upload: using built-in helpers ({type(e).__name__}). Run the launcher from the venv for full cover cleaning.", "warn")

    def fallback_dur(path):
        try:
            from mutagen import File as MF
            f = MF(path)
            return round(float(f.info.length), 2)
        except Exception:
            return 0.0

    _helpers = (dur or fallback_dur, san or (lambda b, ext, color: b))
    return _helpers


def song_upload(ctx: Ctx):
    f, files = ctx.form
    title = (f.get("title") or "").strip()
    main_artist = (f.get("main_artist") or f.get("artist") or "").strip() or "Unknown Artist"
    raw_feats = (f.get("feature_artists") or "").strip()
    feat_list = [x.strip() for x in raw_feats.split(",") if x.strip()] if raw_feats else []
    color = _clean_color(f.get("color")) or "#00E599"
    if not title:
        raise ApiError(400, "Song title is required")
    if "audio" not in files or not files["audio"][1]:
        raise ApiError(400, "Choose an audio file")
    if "cover" not in files or not files["cover"][1]:
        raise ApiError(400, "Choose a cover image (the app needs one for every song)")
    a_name, a_bytes = files["audio"]
    c_name, c_bytes = files["cover"]
    a_ext, c_ext = os.path.splitext(a_name)[1].lower(), os.path.splitext(c_name)[1].lower()
    if a_ext not in AUDIO_EXT:
        raise ApiError(400, f"Unsupported audio type “{a_ext or '?'}”. Use mp3, flac, m4a, wav, ogg, aac or opus.")
    if c_ext not in IMAGE_EXT:
        raise ApiError(400, f"Unsupported cover type “{c_ext or '?'}”. Use jpg, png or webp.")
    calc_duration, sanitize = _upload_helpers()

    os.makedirs(MUSIC_DIR, exist_ok=True)
    os.makedirs(COVER_DIR, exist_ok=True)
    a_file, c_file = f"{uuid.uuid4()}{a_ext}", f"{uuid.uuid4()}{c_ext}"
    a_path, c_path = os.path.join(MUSIC_DIR, a_file), os.path.join(COVER_DIR, c_file)
    try:
        with open(a_path, "wb") as fh:
            fh.write(a_bytes)
        with open(c_path, "wb") as fh:
            fh.write(sanitize(c_bytes, c_ext, color))
        etag = hashlib.md5(a_bytes).hexdigest()
        duration = calc_duration(a_path)
        with closing(db()) as c:
            cur = c.execute("INSERT INTO songs (title, artist, audio_path, cover_path, dominant_color, etag, duration, updated_at) "
                            "VALUES (?,?,?,?,?,?,?, datetime('now'))",
                            (title, main_artist, f"music/{a_file}", f"covers/{c_file}", color, etag, duration))
            sid = int(cur.lastrowid)
            display = database.allot_song_artists(c, sid, primary_artist_name=main_artist, featured_artist_names=feat_list)
            c.commit()
    except Exception:
        safe_unlink(f"music/{a_file}", MUSIC_DIR)
        safe_unlink(f"covers/{c_file}", COVER_DIR)
        raise
    _size_cache.clear()
    add_log("admin", f"Uploaded song #{sid} “{title}” by {display or main_artist} ({fmt_size(len(a_bytes))})")
    return {"success": True, "id": sid, "title": title, "artist": display}


def _extract_artist_params(ctx: Ctx):
    if ctx.form:
        fields, files = ctx.form
        name = " ".join((fields.get("name") or "").split())
        bio = str(fields.get("bio") or "").strip()
        ver = fields.get("verified")
        verified = 1 if ver in ("1", "true", "on", True, 1) else 0
        user_id_raw = fields.get("claimed_by_user_id") or fields.get("user_id")
        user_id = int(user_id_raw) if user_id_raw and str(user_id_raw).isdigit() else None
        avatar_file = files.get("avatar") or files.get("avatar_path")
        banner_file = files.get("banner") or files.get("banner_path")
        aid = int(fields.get("id")) if fields.get("id") and str(fields.get("id")).isdigit() else None
    else:
        b = ctx.body or {}
        name = " ".join(str(b.get("name", "")).split())
        bio = str(b.get("bio", "")).strip()
        verified = 1 if b.get("verified") else 0
        user_id_raw = b.get("claimed_by_user_id") or b.get("user_id")
        user_id = int(user_id_raw) if user_id_raw and str(user_id_raw).isdigit() else None
        avatar_file = None
        banner_file = None
        aid = int(b.get("id")) if b.get("id") and str(b.get("id")).isdigit() else None
    return aid, name, bio, verified, user_id, avatar_file, banner_file


def _save_artist_media(file_tuple, prefix: str) -> str | None:
    if not file_tuple or not file_tuple[1]:
        return None
    fn, file_bytes = file_tuple
    ext = os.path.splitext(fn)[1].lower()
    if ext not in IMAGE_EXT:
        raise ApiError(400, f"Invalid image type '{ext}'. Use JPG, PNG or WEBP.")
    os.makedirs(ARTIST_DIR, exist_ok=True)
    filename = f"{prefix}_{uuid.uuid4().hex}{ext}"
    filepath = os.path.join(ARTIST_DIR, filename)
    with open(filepath, "wb") as f:
        f.write(file_bytes)
    return f"artist-media/{filename}"


def artist_create(ctx: Ctx):
    aid, name, bio, verified, user_id, avatar_file, banner_file = _extract_artist_params(ctx)
    if not name:
        raise ApiError(400, "Artist name is compulsory")

    avatar_rel = _save_artist_media(avatar_file, "avatar")
    banner_rel = _save_artist_media(banner_file, "banner")

    with closing(db()) as c:
        if c.execute("SELECT 1 FROM artists WHERE name=? COLLATE NOCASE", (name,)).fetchone():
            raise ApiError(409, "An artist with that name already exists")
        if user_id:
            c.execute("UPDATE artists SET claimed_by_user_id=NULL WHERE claimed_by_user_id=?", (user_id,))
        c.execute("""
            INSERT INTO artists (name, bio, avatar_path, banner_path, is_verified, claimed_by_user_id)
            VALUES (?, ?, ?, ?, ?, ?)
        """, (name, bio, avatar_rel, banner_rel, verified, user_id))
        c.commit()
    add_log("admin", f"Created artist “{name}”")
    return {"success": True}


def artist_edit(ctx: Ctx):
    aid, name, bio, verified, user_id, avatar_file, banner_file = _extract_artist_params(ctx)
    if not aid:
        aid = _need_id(ctx)
    if not name:
        raise ApiError(400, "Artist name is compulsory")

    new_avatar = _save_artist_media(avatar_file, "avatar")
    new_banner = _save_artist_media(banner_file, "banner")

    with closing(db()) as c:
        old = c.execute("SELECT name, avatar_path, banner_path FROM artists WHERE id=?", (aid,)).fetchone()
        if not old:
            raise ApiError(404, "Artist not found")
        if user_id:
            c.execute("UPDATE artists SET claimed_by_user_id=NULL WHERE claimed_by_user_id=? AND id!=?", (user_id, aid))
        try:
            c.execute("""
                UPDATE artists SET name=?, bio=?, is_verified=?, claimed_by_user_id=?,
                avatar_path=COALESCE(?, avatar_path), banner_path=COALESCE(?, banner_path),
                updated_at=datetime('now') WHERE id=?
            """, (name, bio, verified, user_id, new_avatar, new_banner, aid))
        except sqlite3.IntegrityError:
            raise ApiError(409, "Another artist already uses that name")

        if old["name"] != name:
            for r in c.execute("SELECT song_id FROM song_artists WHERE artist_id=?", (aid,)).fetchall():
                database.sync_song_display_artist(c, r["song_id"])
        c.commit()

        if new_avatar and old["avatar_path"] and old["avatar_path"] != new_avatar:
            safe_unlink(old["avatar_path"], ARTIST_DIR)
        if new_banner and old["banner_path"] and old["banner_path"] != new_banner:
            safe_unlink(old["banner_path"], ARTIST_DIR)

    add_log("admin", f"Edited artist #{aid} “{name}”")
    return {"success": True}


def artist_delete(ctx: Ctx):
    i = _need_id(ctx)
    with closing(db()) as c:
        row = c.execute("SELECT name FROM artists WHERE id=?", (i,)).fetchone()
        if not row:
            raise ApiError(404, "Artist not found")
        n = c.execute("SELECT COUNT(*) FROM song_artists WHERE artist_id=?", (i,)).fetchone()[0]
        if n:
            raise ApiError(409, f"“{row['name']}” still has {n} song(s). Delete or re-credit those songs first "
                                "(otherwise the server would re-create the artist on its next start).")
        c.execute("DELETE FROM artists WHERE id=?", (i,))
        c.commit()
    add_log("admin", f"Deleted artist “{row['name']}”")
    return {"success": True}


def claim_review(ctx: Ctx):
    i = _need_id(ctx)
    action = str(ctx.body.get("action", "")).lower()
    if action not in ("approve", "reject"):
        raise ApiError(400, "action must be approve or reject")
    with closing(db()) as c:
        claim = c.execute("SELECT * FROM artist_claims WHERE id=?", (i,)).fetchone()
        if not claim:
            raise ApiError(404, "Claim not found")
        if action == "reject":
            c.execute("UPDATE artist_claims SET status='rejected', reviewed_at=datetime('now') WHERE id=?", (i,))
        else:
            uid = int(claim["user_id"])
            c.execute("UPDATE artists SET claimed_by_user_id=NULL WHERE claimed_by_user_id=?", (uid,))
            target = claim["artist_id"]
            if target is None or not c.execute("SELECT 1 FROM artists WHERE id=?", (int(target),)).fetchone():
                target = database.get_or_create_artist(c, claim["requested_name"])
            c.execute("UPDATE artists SET claimed_by_user_id=?, is_verified=1, updated_at=datetime('now') WHERE id=?", (uid, int(target)))
            c.execute("UPDATE artist_claims SET status='approved', artist_id=?, reviewed_at=datetime('now') WHERE id=?", (int(target), i))
        c.commit()
    add_log("admin", f"Artist claim #{i} {action}d")
    return {"success": True}


def user_edit(ctx: Ctx):
    i = _need_id(ctx)
    username, email = str(ctx.body.get("username", "")).strip(), str(ctx.body.get("email", "")).strip()
    if not username or not email or "@" not in email:
        raise ApiError(400, "Enter a username and a valid email")
    with closing(db()) as c:
        try:
            cur = c.execute("UPDATE users SET username=?, email=?, is_verified=? WHERE id=?",
                            (username, email, 1 if ctx.body.get("verified") else 0, i))
        except sqlite3.IntegrityError:
            raise ApiError(409, "That username or email is already taken")
        if cur.rowcount == 0:
            raise ApiError(404, "User not found")
        c.commit()
    add_log("admin", f"Edited user #{i} @{username}")
    return {"success": True}


def _try(c, sql, args=()):
    try:
        c.execute(sql, args)
    except sqlite3.OperationalError:
        pass


def user_delete(ctx: Ctx):
    i = _need_id(ctx)
    with closing(db()) as c:
        u = c.execute("SELECT username FROM users WHERE id=?", (i,)).fetchone()
        if not u:
            raise ApiError(404, "User not found")
        pin_imgs = [r["image_path"] for r in c.execute("SELECT image_path FROM location_pins WHERE user_id=?", (i,))]
        # tables that have no FOREIGN KEY to users, so they would otherwise be orphaned
        for tbl in ("listening_history", "user_daily_listen_time", "user_song_plays", "user_monthly_listen_time"):
            _try(c, f"DELETE FROM {tbl} WHERE user_id=?", (i,))
        _try(c, "DELETE FROM club_members WHERE user_id=?", (i,))
        _try(c, "DELETE FROM clubs WHERE owner_user_id=?", (i,))
        _try(c, "DELETE FROM fuse_invites WHERE host_user_id=?", (i,))
        for f in c.execute("SELECT id FROM fuses WHERE user1_id=? OR user2_id=?", (i, i)).fetchall():
            _try(c, "DELETE FROM playlists WHERE fuse_id=?", (f["id"],))
            _try(c, "DELETE FROM fuses WHERE id=?", (f["id"],))
        c.execute("DELETE FROM users WHERE id=?", (i,))  # cascades playlists, pins, claims
        c.commit()
    for p in pin_imgs:
        safe_unlink(p, PIN_DIR)
    _size_cache.clear()
    add_log("admin", f"Deleted user #{i} @{u['username']} and their data")
    return {"success": True}


def playlist_edit(ctx: Ctx):
    i = _need_id(ctx)
    name = str(ctx.body.get("name", "")).strip()
    if not name:
        raise ApiError(400, "Playlist name can't be empty")
    with closing(db()) as c:
        if c.execute("UPDATE playlists SET name=? WHERE id=?", (name, i)).rowcount == 0:
            raise ApiError(404, "Playlist not found")
        c.commit()
    add_log("admin", f"Renamed playlist #{i} to “{name}”")
    return {"success": True}


def playlist_delete(ctx: Ctx):
    i = _need_id(ctx)
    with closing(db()) as c:
        if c.execute("DELETE FROM playlists WHERE id=?", (i,)).rowcount == 0:
            raise ApiError(404, "Playlist not found")
        c.commit()
    add_log("admin", f"Deleted playlist #{i}")
    return {"success": True}


def pin_delete(ctx: Ctx):
    i = _need_id(ctx)
    with closing(db()) as c:
        row = c.execute("SELECT image_path FROM location_pins WHERE id=?", (i,)).fetchone()
        if not row:
            raise ApiError(404, "Pin not found")
        c.execute("DELETE FROM location_pins WHERE id=?", (i,))
        c.commit()
    safe_unlink(row["image_path"], PIN_DIR)
    add_log("admin", f"Deleted pin #{i}")
    return {"success": True}


def club_delete(ctx: Ctx):
    i = _need_id(ctx)
    with closing(db()) as c:
        if c.execute("DELETE FROM clubs WHERE id=?", (i,)).rowcount == 0:
            raise ApiError(404, "Club not found")
        c.commit()
    add_log("admin", f"Closed club #{i}")
    return {"success": True}


def blend_delete(ctx: Ctx):
    i = _need_id(ctx)
    with closing(db()) as c:
        if not c.execute("SELECT 1 FROM fuses WHERE id=?", (i,)).fetchone():
            raise ApiError(404, "Blend not found")
        _try(c, "DELETE FROM playlists WHERE fuse_id=?", (i,))  # the users' copies of the blend playlist
        c.execute("DELETE FROM fuses WHERE id=?", (i,))
        c.commit()
    add_log("admin", f"Deleted blend #{i}")
    return {"success": True}


def list_song_requests(ctx: Ctx):
    q, flt = ctx.q.get("q", "").strip(), ctx.q.get("filter", "all")
    page, per, off = paging(ctx.q)
    where, args = [], []
    if q:
        where.append("(song_name LIKE ? ESCAPE '\\' OR artist_name LIKE ? ESCAPE '\\' OR requester_name LIKE ? ESCAPE '\\' OR note LIKE ? ESCAPE '\\')")
        args += [like(q)] * 4
    if flt in ("pending", "fulfilled", "rejected"):
        where.append("status=?")
        args.append(flt)
    W = ("WHERE " + " AND ".join(where)) if where else ""
    total = q_val(f"SELECT COUNT(*) FROM song_requests {W}", args)
    rows = q_all(
        f"SELECT id, song_name, artist_name, requester_name, note, status, created_at "
        f"FROM song_requests {W} ORDER BY CASE WHEN status='pending' THEN 0 ELSE 1 END, id DESC LIMIT ? OFFSET ?",
        args + [per, off],
    )
    items = [{
        "id": r["id"],
        "song_name": r["song_name"],
        "artist_name": r["artist_name"],
        "requester_name": r["requester_name"] or "",
        "note": r["note"] or "",
        "status": r["status"] or "pending",
        "created": r["created_at"],
    } for r in rows]
    kpis = [
        ["Total requests", q_val("SELECT COUNT(*) FROM song_requests")],
        ["Pending", q_val("SELECT COUNT(*) FROM song_requests WHERE status='pending'")],
        ["Added", q_val("SELECT COUNT(*) FROM song_requests WHERE status='fulfilled'")],
        ["This week", q_val("SELECT COUNT(*) FROM song_requests WHERE created_at >= datetime('now','-7 days')")],
    ]
    return {"items": items, "total": total, "page": page, "per": per, "kpis": kpis}


def request_update_status(ctx: Ctx):
    i = _need_id(ctx)
    status = str((ctx.body or {}).get("status") or "").strip().lower()
    if status not in ("pending", "fulfilled", "rejected"):
        raise ApiError(400, "Invalid status (expected pending, fulfilled, or rejected)")
    with closing(db()) as c:
        cur = c.execute("UPDATE song_requests SET status=? WHERE id=?", (status, i))
        if cur.rowcount == 0:
            raise ApiError(404, "Song request not found")
        c.commit()
    add_log("admin", f"Marked song request #{i} as {status}")
    return {"success": True, "id": i, "status": status}


def request_delete(ctx: Ctx):
    i = _need_id(ctx)
    with closing(db()) as c:
        if c.execute("DELETE FROM song_requests WHERE id=?", (i,)).rowcount == 0:
            raise ApiError(404, "Song request not found")
        c.commit()
    add_log("admin", f"Deleted song request #{i}")
    return {"success": True}


def list_notifications(ctx: Ctx) -> list:
    with closing(db()) as c:
        cur = c.cursor()
        cur.execute("SELECT id, title, message, recipient_type, target_user_ids, target_usernames, sent_count, created_at FROM sent_notifications ORDER BY id DESC LIMIT 50")
        rows = cur.fetchall()
        return [dict(r) for r in rows]


def api_send_notification(ctx: Ctx) -> dict:
    body = ctx.body or {}
    title = (body.get("title") or "").strip()
    message = (body.get("message") or "").strip()
    recipient_type = (body.get("recipient_type") or "all").strip().lower()
    target_user_ids_raw = body.get("target_user_ids")
    target_usernames_raw = body.get("target_usernames")

    if not title or not message:
        raise ApiError(400, "Title and message are required")

    target_user_ids_list = []
    if isinstance(target_user_ids_raw, list):
        for uid in target_user_ids_raw:
            try:
                target_user_ids_list.append(int(uid))
            except (ValueError, TypeError):
                pass
    elif isinstance(target_user_ids_raw, (int, str)) and str(target_user_ids_raw).strip():
        for uid in str(target_user_ids_raw).split(","):
            try:
                target_user_ids_list.append(int(uid.strip()))
            except (ValueError, TypeError):
                pass

    target_usernames_list = []
    if isinstance(target_usernames_raw, list):
        target_usernames_list = [str(u).strip() for u in target_usernames_raw if str(u).strip()]
    elif isinstance(target_usernames_raw, str) and target_usernames_raw.strip():
        target_usernames_list = [u.strip() for u in target_usernames_raw.split(",") if u.strip()]

    target_user_ids_str = ("," + ",".join(str(u) for u in target_user_ids_list) + ",") if target_user_ids_list else ""
    target_usernames_str = ", ".join(target_usernames_list) if target_usernames_list else ""

    with closing(db()) as c:
        cur = c.cursor()
        if recipient_type == "selected" and target_user_ids_list:
            placeholders = ",".join("?" for _ in target_user_ids_list)
            cur.execute(f"SELECT COUNT(*) AS c FROM user_device_tokens WHERE user_id IN ({placeholders})", target_user_ids_list)
            row = cur.fetchone()
            token_count = row["c"] if row else 0
            if token_count == 0:
                sent_count = len(target_user_ids_list)
            else:
                sent_count = token_count
        else:
            recipient_type = "all"
            cur.execute("SELECT COUNT(*) AS c FROM user_device_tokens")
            row = cur.fetchone()
            token_count = row["c"] if row else 0
            sent_count = token_count

        cur.execute(
            """INSERT INTO sent_notifications
               (title, message, recipient_type, target_user_ids, target_usernames, sent_count)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (title, message, recipient_type, target_user_ids_str, target_usernames_str, sent_count)
        )
        c.commit()
        desc = f"Targeted: '{title}' to user(s): {target_usernames_str}" if recipient_type == "selected" else f"Broadcast: '{title}' to {sent_count} device(s)"
        add_log("admin", f"Push notification sent ({desc})", "info")
        return {
            "status": "success",
            "sent_count": sent_count,
            "recipient_type": recipient_type,
            "target_usernames": target_usernames_str,
            "title": title,
            "message": message
        }


# ---------------------------------------------------------------- routing --
ROUTES = {
    ("POST", "/login"): (api_login, False),
    ("GET", "/ping"): (lambda ctx: {"ok": True}, False),
    ("POST", "/logout"): (api_logout, True),
    ("GET", "/session"): (api_session, True),
    ("GET", "/server/status"): (api_server_status, True),
    ("POST", "/server/start"): (api_server_start, True),
    ("POST", "/server/stop"): (api_server_stop, True),
    ("GET", "/overview"): (api_overview, True),
    ("GET", "/activity"): (api_activity, True),
    ("GET", "/songs"): (list_songs, True),
    ("GET", "/catalog/suggestions"): (api_suggestions, True),
    ("GET", "/artists"): (list_artists, True),
    ("GET", "/users"): (list_users, True),
    ("GET", "/playlists"): (list_playlists, True),
    ("GET", "/pins"): (list_pins, True),
    ("GET", "/clubs"): (list_clubs, True),
    ("GET", "/blends"): (list_blends, True),
    ("GET", "/requests"): (list_song_requests, True),
    ("GET", "/notifications"): (list_notifications, True),
    ("POST", "/notifications/send"): (api_send_notification, True),
    ("GET", "/logs"): (api_logs, True),
    ("POST", "/songs/upload"): (song_upload, True),
    ("POST", "/songs/edit"): (song_edit, True),
    ("POST", "/songs/delete"): (song_delete, True),
    ("POST", "/artists/create"): (artist_create, True),
    ("POST", "/artists/edit"): (artist_edit, True),
    ("POST", "/artists/delete"): (artist_delete, True),
    ("POST", "/artists/claims/review"): (claim_review, True),
    ("POST", "/users/edit"): (user_edit, True),
    ("POST", "/users/delete"): (user_delete, True),
    ("POST", "/playlists/edit"): (playlist_edit, True),
    ("POST", "/playlists/delete"): (playlist_delete, True),
    ("POST", "/pins/delete"): (pin_delete, True),
    ("POST", "/clubs/delete"): (club_delete, True),
    ("POST", "/blends/delete"): (blend_delete, True),
    ("POST", "/requests/status"): (request_update_status, True),
    ("POST", "/requests/delete"): (request_delete, True),
}

STATIC_DIRS = {"/covers/": COVER_DIR, "/pins/": PIN_DIR, "/artist-media/": ARTIST_DIR}


def parse_multipart(content_type: str, body: bytes):
    raw = b"MIME-Version: 1.0\r\nContent-Type: " + content_type.encode("latin-1") + b"\r\n\r\n" + body
    msg = BytesParser(policy=HTTP_POLICY).parsebytes(raw)
    fields, files = {}, {}
    if not msg.is_multipart():
        return fields, files
    for part in msg.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if not name:
            continue
        data = part.get_payload(decode=True) or b""
        fn = part.get_filename()
        if fn is not None:
            files[name] = (fn, data)
        else:
            fields[name] = data.decode("utf-8", "replace")
    return fields, files


class Handler(BaseHTTPRequestHandler):
    server_version = "AlaktraAdmin/2.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):  # keep the terminal quiet; admin actions are logged explicitly
        pass

    # -- output
    def _send(self, status: int, body: bytes, ctype: str, extra: dict | None = None, head: bool = False):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if not head:
            self.wfile.write(body)

    def _json(self, status: int, obj, head: bool = False):
        self._send(status, json.dumps(obj, default=str).encode(), "application/json; charset=utf-8",
                   {"Cache-Control": "no-store"}, head)

    # -- verbs
    def do_GET(self):
        self._dispatch("GET")

    def do_HEAD(self):
        self._dispatch("HEAD")

    def do_POST(self):
        self._dispatch("POST")

    def _dispatch(self, method: str):
        parsed = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(parsed.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}
        try:
            if path.startswith("/admin/api/"):
                return self._api("GET" if method == "HEAD" else method, path[len("/admin/api"):], q)
            if method in ("GET", "HEAD"):
                return self._static(path, method == "HEAD")
            self._json(404, {"detail": "Not found"})
        except ApiError as e:
            self._json(e.status, {"detail": e.detail})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            add_log("admin", f"{method} {path} failed: {type(e).__name__}: {e}", "err")
            traceback.print_exc()
            try:
                self._json(500, {"detail": f"{type(e).__name__}: {e}"})
            except Exception:
                pass

    def _api(self, method: str, sub: str, q: dict):
        route = ROUTES.get((method, sub))
        if not route:
            raise ApiError(404, "Unknown endpoint")
        fn, needs_auth = route
        auth = self.headers.get("Authorization", "")
        token = auth[7:].strip() if auth.lower().startswith("bearer ") else None
        if needs_auth and not valid_token(token):
            raise ApiError(401, "Please sign in again")
        body, form = None, None
        if method == "POST":
            n = to_int(self.headers.get("Content-Length"), 0)
            if n > MAX_UPLOAD:
                self.close_connection = True
                raise ApiError(413, f"File too large (limit {MAX_UPLOAD // 1048576} MB, set ADMIN_MAX_UPLOAD_MB in .env)")
            raw = self.rfile.read(n) if n else b""
            ctype = self.headers.get("Content-Type", "")
            if ctype.startswith("multipart/form-data"):
                form = parse_multipart(ctype, raw)
            elif raw:
                try:
                    body = json.loads(raw.decode("utf-8"))
                except ValueError:
                    raise ApiError(400, "Invalid JSON")
                if not isinstance(body, dict):
                    raise ApiError(400, "Expected a JSON object")
        ctx = Ctx(q, body, form, self.client_address[0], token)
        self._json(200, fn(ctx))

    def _static(self, path: str, head: bool):
        if path in ("/", "/admin", "/admin/", "/index.html"):
            if not os.path.isfile(ADMIN_HTML):
                raise ApiError(404, "admin.html not found next to admin_server.py")
            with open(ADMIN_HTML, "rb") as f:
                return self._send(200, f.read(), "text/html; charset=utf-8", {"Cache-Control": "no-cache"}, head)
        if path == "/favicon.ico":
            return self._send(204, b"", "image/x-icon", head=head)
        for prefix, folder in STATIC_DIRS.items():
            if path.startswith(prefix):
                root = os.path.realpath(folder) + os.sep
                target = os.path.realpath(os.path.join(folder, path[len(prefix):]))
                if not target.startswith(root) or not os.path.isfile(target):
                    raise ApiError(404, "Not found")
                ctype = mimetypes.guess_type(target)[0] or "application/octet-stream"
                with open(target, "rb") as f:
                    return self._send(200, f.read(), ctype, {"Cache-Control": "public, max-age=3600"}, head)
        raise ApiError(404, "Not found")


class AdminHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def create_server() -> AdminHTTPServer:
    ensure_db()
    return AdminHTTPServer((ADMIN_HOST, ADMIN_PORT), Handler)


atexit.register(PROCS.shutdown)


def main(open_browser: bool = False, start_api: bool = False) -> None:
    try:
        httpd = create_server()
    except OSError as e:
        print(f"\n❌ Could not start the admin console on {ADMIN_HOST}:{ADMIN_PORT}: {e}")
        print("   Is it already running? Change the port with ADMIN_PORT in .env.")
        sys.exit(1)

    add_log("admin", f"Admin console listening on {ADMIN_HOST}:{ADMIN_PORT}")
    print("=" * 62)
    print(" 🛡️  ALAKTRA MUSIC - ADMIN CONSOLE")
    print("=" * 62)
    print(f" Local : http://localhost:{ADMIN_PORT}")
    if ADMIN_HOST not in ("127.0.0.1", "localhost"):
        print(f" LAN   : http://{lan_ip()}:{ADMIN_PORT}")
    print(f" Login : {ADMIN_USER} / {'(your ADMIN_PASSWORD)' if not USING_DEFAULT_PASSWORD else DEFAULT_PASSWORD}")
    if USING_DEFAULT_PASSWORD:
        print("\n ⚠️  Factory password in use, so the console only listens on this")
        print("    computer. Set ADMIN_PASSWORD in .env to change it and to allow")
        print("    access from your LAN / Tailscale.")
    print(f"\n API server (port {API_PORT}) is NOT started automatically - press")
    print(" 'Start server' in the Admin Console (or pass --start-api).")
    if SETTINGS["tunnel_enabled"] and shutil.which("cloudflared"):
        t_res = PROCS.ensure_tunnel()
        print(f" Tunnel: https://admin.alaktracare.in ({t_res})")
    print(" Press Ctrl+C to quit.\n")

    if start_api:
        try:
            print(" ▶ Starting API:", PROCS.start(SETTINGS["tunnel_enabled"], "", ""))
        except ApiError as e:
            print(" ⚠️  API start failed:", e.detail)

    if open_browser:
        try:
            import webbrowser
            threading.Timer(0.8, lambda: webbrowser.open(f"http://localhost:{ADMIN_PORT}")).start()
        except Exception:
            pass

    def stop(_sig=None, _frm=None):
        print("\n⏹  Shutting down admin console…")
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, stop)
    try:
        signal.signal(signal.SIGTERM, stop)
    except (ValueError, AttributeError):
        pass
    try:
        httpd.serve_forever(poll_interval=0.5)
    finally:
        httpd.server_close()
        PROCS.shutdown()


if __name__ == "__main__":
    main(open_browser="--no-browser" not in sys.argv, start_api="--start-api" in sys.argv)
