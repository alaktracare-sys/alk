#!/usr/bin/env python3
"""
============================================================
 Alaktra Music - Standalone Admin Console & 1-Click Launcher
============================================================
Allows the admin to open the Admin Manager WITHOUT pre-launching
the streaming server. The admin logs in with credentials, views
database metrics directly, and turns the streaming server ON/OFF.

Default Admin Credentials:
  Username: admin
  Password: admin123
"""

import os
import sys
import re
import socket
import platform
import subprocess
import threading
import time
import webbrowser
import signal
import json
import sqlite3
import shutil
from http.server import HTTPServer, SimpleHTTPRequestHandler

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
os.chdir(SCRIPT_DIR)
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

DEFAULT_PORT = 8000
ADMIN_PORT = 8080

ADMIN_USER = os.getenv("ADMIN_USERNAME", "admin")
ADMIN_PASS = os.getenv("ADMIN_PASSWORD", "admin123")

DB_PATH = os.path.join(SCRIPT_DIR, "music.db")
MUSIC_DIR = os.path.join(SCRIPT_DIR, "music")
COVER_DIR = os.path.join(SCRIPT_DIR, "covers")
PIN_DIR = os.path.join(SCRIPT_DIR, "pins")
ARTIST_DIR = os.path.join(SCRIPT_DIR, "artist_media")

# -------------------------------------------------------------
# 1. Tailscale & Network IP Detection
# -------------------------------------------------------------
def get_tailscale_ip():
    tailscale_bins = ["tailscale"]
    if platform.system() == "Windows":
        tailscale_bins += [
            r"C:\Program Files\Tailscale\tailscale.exe",
            r"C:\Program Files (x86)\Tailscale\tailscale.exe",
            os.path.expandvars(r"%LOCALAPPDATA%\Tailscale\tailscale.exe")
        ]
    else:
        tailscale_bins += [
            "/usr/bin/tailscale",
            "/usr/local/bin/tailscale",
            "/snap/bin/tailscale"
        ]

    for bin_path in tailscale_bins:
        try:
            out = subprocess.check_output(
                [bin_path, "ip", "-4"],
                timeout=2,
                stderr=subprocess.DEVNULL
            ).decode("utf-8", errors="ignore").strip()
            match = re.search(r"\b100\.(?:[6-9]\d|1[0-1]\d|12[0-7])\.\d{1,3}\.\d{1,3}\b", out)
            if match:
                return match.group(0)
        except Exception:
            pass
    return None

def get_lan_ip():
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

def get_best_server_url(port=DEFAULT_PORT):
    ts_ip = get_tailscale_ip()
    if ts_ip:
        return ts_ip, f"http://{ts_ip}:{port}", True
    lan_ip = get_lan_ip()
    return lan_ip, f"http://{lan_ip}:{port}", False


# -------------------------------------------------------------
# 2. Direct Database & System Storage Metrics
# -------------------------------------------------------------
def get_dir_size(path):
    if not os.path.exists(path):
        return 0
    total = 0
    for root, dirs, files in os.walk(path):
        for f in files:
            fp = os.path.join(root, f)
            if os.path.isfile(fp):
                total += os.path.getsize(fp)
    return total

def format_size(bytes_val):
    if bytes_val < 1024 * 1024:
        return f"{bytes_val / 1024:.1f} KB"
    elif bytes_val < 1024 * 1024 * 1024:
        return f"{bytes_val / (1024 * 1024):.1f} MB"
    else:
        return f"{bytes_val / (1024 * 1024 * 1024):.2f} GB"

def get_offline_db_stats():
    if not os.path.exists(DB_PATH):
        return {
            "status": "offline_no_db",
            "total_songs": 0, "total_users": 0, "total_plays": 0,
            "storage": {"used": "0 MB", "total": "512 GB", "music": "0 MB", "covers": "0 MB", "pins": "0 MB", "db": "0 MB"},
            "recent_songs": [], "users": [], "playlists": [], "clubs": []
        }

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()

    total_songs = cursor.execute("SELECT COUNT(*) FROM songs").fetchone()[0] if os.path.exists(DB_PATH) else 0
    total_users = cursor.execute("SELECT COUNT(*) FROM users").fetchone()[0] if os.path.exists(DB_PATH) else 0
    
    try:
        total_plays = cursor.execute("SELECT COUNT(*) FROM listening_history").fetchone()[0]
    except Exception:
        total_plays = 0

    music_size = get_dir_size(MUSIC_DIR)
    cover_size = get_dir_size(COVER_DIR)
    pin_size = get_dir_size(PIN_DIR) + get_dir_size(ARTIST_DIR)
    db_size = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
    total_storage = music_size + cover_size + pin_size + db_size

    try:
        total_disk, used_disk, free_disk = shutil.disk_usage(SCRIPT_DIR)
    except Exception:
        total_disk, used_disk, free_disk = 512 * (1024**3), total_storage, 512 * (1024**3) - total_storage

    recent_songs = []
    try:
        rows = cursor.execute("SELECT id, title, artist, duration, created_at FROM songs ORDER BY id DESC LIMIT 50").fetchall()
        for r in rows:
            recent_songs.append({
                "id": r["id"],
                "title": r["title"],
                "artist": r["artist"],
                "duration": f"{int(r['duration'] // 60)}:{int(r['duration'] % 60):02d}" if r["duration"] else "3:30",
                "added": r["created_at"] or "Just now"
            })
    except Exception:
        pass

    users_list = []
    try:
        rows = cursor.execute("SELECT id, username, email, is_verified, created_at FROM users ORDER BY id DESC LIMIT 50").fetchall()
        for r in rows:
            users_list.append({
                "id": r["id"],
                "username": r["username"],
                "email": r["email"],
                "is_verified": bool(r["is_verified"]),
                "created_at": r["created_at"] or "Recent"
            })
    except Exception:
        pass

    playlists_list = []
    try:
        rows = cursor.execute("""
            SELECT p.id, p.name, p.is_system, p.is_fuse, u.username, 
            (SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlist_id = p.id) as song_count,
            p.created_at
            FROM playlists p
            LEFT JOIN users u ON p.user_id = u.id
            ORDER BY p.id DESC LIMIT 50
        """).fetchall()
        for r in rows:
            p_type = "Blend" if r["is_fuse"] else ("System" if r["is_system"] else "Custom")
            playlists_list.append({
                "id": r["id"],
                "name": r["name"],
                "owner": r["username"] or "System",
                "song_count": r["song_count"],
                "type": p_type,
                "created_at": r["created_at"] or "Recent"
            })
    except Exception:
        pass

    clubs_list = []
    try:
        rows = cursor.execute("""
            SELECT c.id, c.code, c.name, c.created_at, u.username as owner_name,
            (SELECT COUNT(*) FROM club_members cm WHERE cm.club_id = c.id) as member_count,
            (SELECT COUNT(*) FROM club_songs cs WHERE cs.club_id = c.id) as song_count
            FROM clubs c
            LEFT JOIN users u ON c.owner_id = u.id
            ORDER BY c.id DESC LIMIT 50
        """).fetchall()
        for r in rows:
            clubs_list.append({
                "id": r["id"],
                "code": r["code"],
                "name": r["name"],
                "owner": r["owner_name"] or "Host",
                "member_count": r["member_count"],
                "song_count": r["song_count"],
                "is_live": True
            })
    except Exception:
        pass

    conn.close()

    return {
        "status": "ready",
        "total_songs": total_songs,
        "total_users": total_users,
        "total_plays": total_plays,
        "storage": {
            "used": format_size(total_storage),
            "total": format_size(total_disk),
            "music": format_size(music_size),
            "covers": format_size(cover_size),
            "pins": format_size(pin_size),
            "db": format_size(db_size),
            "used_bytes": total_storage,
            "total_bytes": total_disk
        },
        "recent_songs": recent_songs,
        "users": users_list,
        "playlists": playlists_list,
        "clubs": clubs_list
    }


# -------------------------------------------------------------
# 3. Server Controller Subprocess
# -------------------------------------------------------------
class ServerManager:
    def __init__(self, port=DEFAULT_PORT):
        self.port = port
        self.process = None
        self.is_running = False

    def start(self):
        if self.is_running:
            return True

        cmd = [
            sys.executable, "-m", "uvicorn",
            "main:app",
            "--host", "0.0.0.0",
            "--port", str(self.port),
            "--app-dir", SCRIPT_DIR
        ]

        env = os.environ.copy()
        env["HOST"] = "0.0.0.0"
        env["PORT"] = str(self.port)
        env["PYTHONUNBUFFERED"] = "1"

        try:
            self.process = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                cwd=SCRIPT_DIR,
                env=env
            )
            self.is_running = True

            def monitor():
                if self.process:
                    for line in self.process.stdout:
                        pass
                self.is_running = False

            threading.Thread(target=monitor, daemon=True).start()
            return True
        except Exception as e:
            print(f"❌ Error starting server: {e}")
            self.is_running = False
            return False

    def stop(self):
        if not self.is_running or not self.process:
            self.is_running = False
            return True

        try:
            if platform.system() == "Windows":
                subprocess.call(["taskkill", "/F", "/T", "/PID", str(self.process.pid)], stderr=subprocess.DEVNULL)
            else:
                self.process.terminate()
                try:
                    self.process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    self.process.kill()
        except Exception:
            pass

        self.is_running = False
        self.process = None
        return True

server_manager = ServerManager(port=DEFAULT_PORT)


# -------------------------------------------------------------
# 4. Standalone Admin Control HTTP Handler
# -------------------------------------------------------------
class AdminManagerHTTPHandler(SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        return

    def do_GET(self):
        path = self.path.split('?')[0]
        if path in ["/", "/admin", "/admin/"]:
            admin_file = os.path.join(SCRIPT_DIR, "admin.html")
            if os.path.exists(admin_file):
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                with open(admin_file, "rb") as f:
                    self.wfile.write(f.read())
            else:
                self.send_error(404, "admin.html not found")
            return

        if path == "/admin/api/server/status":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            ip, url, is_ts = get_best_server_url(DEFAULT_PORT)
            data = {
                "running": server_manager.is_running,
                "port": DEFAULT_PORT,
                "host": "0.0.0.0",
                "tailscale_ip": ip if is_ts else None,
                "lan_ip": get_lan_ip(),
                "app_url": url,
                "admin_user": ADMIN_USER
            }
            self.wfile.write(json.dumps(data).encode("utf-8"))
            return

        if path == "/admin/api/stats":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            stats = get_offline_db_stats()
            self.wfile.write(json.dumps(stats).encode("utf-8"))
            return

        super().do_GET()

    def do_POST(self):
        path = self.path.split('?')[0]
        content_length = int(self.headers.get('Content-Length', 0))
        body_bytes = self.rfile.read(content_length) if content_length > 0 else b""

        if path == "/admin/api/login":
            try:
                body = json.loads(body_bytes.decode("utf-8"))
                u = body.get("username", "").strip()
                p = body.get("password", "").strip()
                if u == ADMIN_USER and p == ADMIN_PASS:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps({"success": True, "token": "admin-token-offline", "username": ADMIN_USER}).encode("utf-8"))
                    return
            except Exception:
                pass
            self.send_response(401)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"success": False, "detail": "Invalid Admin credentials"}).encode("utf-8"))
            return

        if path == "/admin/api/server/start":
            success = server_manager.start()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"success": success, "running": server_manager.is_running}).encode("utf-8"))
            return

        if path == "/admin/api/server/stop":
            success = server_manager.stop()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"success": success, "running": server_manager.is_running}).encode("utf-8"))
            return

        self.send_error(404, "Not Found")


def start_admin_web_server(port=ADMIN_PORT):
    try:
        httpd = HTTPServer(("0.0.0.0", port), AdminManagerHTTPHandler)
        print(f"🛡️ Standalone Admin Control Panel running on http://localhost:{port}/admin")
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        return True
    except Exception as e:
        print(f"⚠️ Could not start Admin web server on port {port}: {e}")
        return False


# -------------------------------------------------------------
# 5. Entry Point & Browser Auto-Open
# -------------------------------------------------------------
if __name__ == "__main__":
    print("=" * 60)
    print(" 🛡️  ALAKTRA MUSIC - STANDALONE ADMIN MANAGER")
    print("=" * 60)
    print(f" Default Admin Credentials: Username: '{ADMIN_USER}' | Password: '{ADMIN_PASS}'")
    print("-" * 60)

    start_admin_web_server(ADMIN_PORT)

    admin_url = f"http://localhost:{ADMIN_PORT}/admin"
    print(f"\n🌐 Opening Standalone Admin Console in browser: {admin_url}")
    try:
        webbrowser.open(admin_url)
    except Exception:
        pass

    print("\n✅ Admin Manager is READY!")
    print("   Open browser to login, check database metrics, and turn server ON/OFF.")
    print("   Press Ctrl+C to exit Admin Manager.\n")

    def handle_sig(sig, frame):
        print("\n⏹ Shutting down Admin Manager...")
        server_manager.stop()
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_sig)

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        handle_sig(None, None)
