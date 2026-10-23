#!/usr/bin/env python3
"""
============================================================
 Alaktra Music - 1-Click Server Launcher (Windows & Linux)
============================================================
Automatically detects your Tailscale IP, verifies dependencies,
and hosts your self-hosted Alaktra Music streaming server with a
single button.

Features:
- Instant 1-Click Server Start / Stop
- Auto-detects Tailscale IP (100.x.y.z) & Local Network IP
- Generates exact connection URL for the Alaktra Android app
- 1-Click 'Copy App URL' button
- 1-Click 'Open Web Upload Portal' in browser
- Live real-time server log streaming
- Graceful GUI (Tkinter) or Headless Console CLI fallback
============================================================
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

# Ensure current folder is working directory
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
os.chdir(SCRIPT_DIR)
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

DEFAULT_PORT = 8000

# -------------------------------------------------------------
# 1. Tailscale & Network IP Detection
# -------------------------------------------------------------
def get_tailscale_ip():
    """Detects active Tailscale IPv4 address (100.64.0.0/10 range)."""
    # 1. Try Tailscale CLI directly
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
            # Tailscale IPv4 matches 100.x.y.z
            match = re.search(r"\b100\.(?:[6-9]\d|1[0-1]\d|12[0-7])\.\d{1,3}\.\d{1,3}\b", out)
            if match:
                return match.group(0)
        except Exception:
            pass

    # 2. Try network interface inspection via system tools
    if platform.system() == "Windows":
        try:
            out = subprocess.check_output(["ipconfig"], timeout=3, stderr=subprocess.DEVNULL).decode("utf-8", errors="ignore")
            # Look for 100.x.y.z under any adapter
            match = re.search(r"\b100\.(?:[6-9]\d|1[0-1]\d|12[0-7])\.\d{1,3}\.\d{1,3}\b", out)
            if match:
                return match.group(0)
        except Exception:
            pass
    else:
        try:
            out = subprocess.check_output(["ip", "-4", "addr"], timeout=2, stderr=subprocess.DEVNULL).decode("utf-8", errors="ignore")
            match = re.search(r"\b100\.(?:[6-9]\d|1[0-1]\d|12[0-7])\.\d{1,3}\.\d{1,3}\b", out)
            if match:
                return match.group(0)
        except Exception:
            pass

    return None


def get_lan_ip():
    """Detects local LAN IPv4 address (e.g., 192.168.x.x, 10.x.x.x)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        # Using a dummy UDP connect doesn't actually send packets
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127."):
            return ip
    except Exception:
        pass
    return "127.0.0.1"


def get_best_server_url(port=DEFAULT_PORT):
    """Returns (ip, url, is_tailscale)."""
    ts_ip = get_tailscale_ip()
    if ts_ip:
        return ts_ip, f"http://{ts_ip}:{port}", True
    lan_ip = get_lan_ip()
    return lan_ip, f"http://{lan_ip}:{port}", False


# -------------------------------------------------------------
# 2. Dependency Checking & Installation
# -------------------------------------------------------------
REQUIRED_MODULES = ["fastapi", "uvicorn", "mutagen", "PIL", "bcrypt"]

def check_missing_dependencies():
    missing = []
    for mod in REQUIRED_MODULES:
        try:
            __import__(mod)
        except ImportError:
            missing.append(mod)
    return missing


def install_requirements(log_callback=print):
    """Installs required packages from requirements.txt."""
    req_file = os.path.join(SCRIPT_DIR, "requirements.txt")
    if not os.path.exists(req_file):
        log_callback("⚠️ requirements.txt not found. Skipping auto-install.")
        return False
    log_callback("📦 Installing missing dependencies (this only happens once)...")
    try:
        cmd = [sys.executable, "-m", "pip", "install", "-r", req_file]
        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1
        )
        for line in process.stdout:
            log_callback(line.rstrip())
        process.wait()
        return process.returncode == 0
    except Exception as e:
        log_callback(f"❌ Error during pip install: {e}")
        return False


# -------------------------------------------------------------
# 3. Server Controller
# -------------------------------------------------------------
class ServerManager:
    def __init__(self, port=DEFAULT_PORT, log_callback=None):
        self.port = port
        self.process = None
        self.is_running = False
        self.log_callback = log_callback or print

    def log(self, text):
        if self.log_callback:
            self.log_callback(text)

    def start(self):
        if self.is_running:
            return

        missing = check_missing_dependencies()
        if missing:
            self.log(f"⚠️ Missing packages: {', '.join(missing)}")
            self.log("🔧 Auto-installing required packages now...")
            success = install_requirements(self.log)
            if not success:
                self.log("❌ Failed to install dependencies. Please run 'pip install -r requirements.txt' manually.")
                return False

        self.log("🚀 Starting Alaktra Music Server...")
        self.log(f"📁 Root directory: {SCRIPT_DIR}")

        # Set environment variables
        env = os.environ.copy()
        env["HOST"] = "0.0.0.0"
        env["PORT"] = str(self.port)
        env["PYTHONUNBUFFERED"] = "1"

        cmd = [
            sys.executable, "-m", "uvicorn",
            "main:app",
            "--host", "0.0.0.0",
            "--port", str(self.port),
            "--app-dir", SCRIPT_DIR
        ]

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

            # Background thread to capture uvicorn output
            def stream_output():
                for line in self.process.stdout:
                    self.log(line.rstrip())
                self.is_running = False
                self.log("⏹ Server process stopped.")

            threading.Thread(target=stream_output, daemon=True).start()
            return True
        except Exception as e:
            self.log(f"❌ Failed to launch server: {e}")
            self.is_running = False
            return False

    def stop(self):
        if not self.is_running or not self.process:
            return
        self.log("⏹ Stopping server...")
        try:
            if platform.system() == "Windows":
                subprocess.call(["taskkill", "/F", "/T", "/PID", str(self.process.pid)], stderr=subprocess.DEVNULL)
            else:
                self.process.terminate()
                try:
                    self.process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    self.process.kill()
        except Exception as e:
            self.log(f"⚠️ Notice when stopping: {e}")
        self.is_running = False
        self.log("✅ Server stopped successfully.")


# -------------------------------------------------------------
# 4. Graphical User Interface (Tkinter)
# -------------------------------------------------------------
def run_gui():
    import tkinter as tk
    from tkinter import ttk, messagebox

    root = tk.Tk()
    root.title("Alaktra Music - Server Launcher")
    root.geometry("680x580")
    root.minsize(580, 480)

    # Modern Dark Theme Colors
    BG_DARK = "#0d1117"
    BG_CARD = "#161b22"
    BG_CARD_BORDER = "#30363d"
    ACCENT_CYAN = "#00e5ff"
    ACCENT_GREEN = "#2ea043"
    ACCENT_RED = "#da3633"
    TEXT_PRIMARY = "#f0f6fc"
    TEXT_MUTED = "#8b949e"
    TEXT_URL = "#58a6ff"

    root.configure(bg=BG_DARK)

    # State variables
    port_var = tk.StringVar(value=str(DEFAULT_PORT))
    ip_display_var = tk.StringVar(value="Detecting...")
    url_display_var = tk.StringVar(value="Detecting...")
    status_var = tk.StringVar(value="● STOPPED")
    is_ts_var = tk.BooleanVar(value=False)

    server_manager = ServerManager(port=DEFAULT_PORT)

    def append_log(line):
        def _append():
            log_text.config(state="normal")
            log_text.insert(tk.END, line + "\n")
            log_text.see(tk.END)
            log_text.config(state="disabled")
        root.after(0, _append)

    server_manager.log_callback = append_log

    def refresh_ip():
        try:
            p = int(port_var.get())
        except ValueError:
            p = DEFAULT_PORT
        ip, url, is_ts = get_best_server_url(p)
        is_ts_var.set(is_ts)
        if is_ts:
            ip_display_var.set(f"Tailscale IP: {ip}  (Global Remote Access)")
            url_display_var.set(url)
            ip_badge.config(text="TAILSCALE DETECTED", bg="#1f6feb", fg="#ffffff")
        else:
            ip_display_var.set(f"Local LAN IP: {ip}  (Same Wi-Fi Only)")
            url_display_var.set(url)
            ip_badge.config(text="LOCAL NETWORK ONLY", bg="#6e7681", fg="#ffffff")

    def toggle_server():
        if server_manager.is_running:
            server_manager.stop()
            btn_start.config(text="▶  START SERVER", bg=ACCENT_GREEN, activebackground="#3fb950")
            status_label.config(text="● STOPPED", fg=ACCENT_RED)
        else:
            try:
                p = int(port_var.get())
                server_manager.port = p
            except ValueError:
                messagebox.showerror("Invalid Port", "Port must be an integer, e.g. 8000")
                return

            refresh_ip()
            success = server_manager.start()
            if success:
                btn_start.config(text="⏹  STOP SERVER", bg=ACCENT_RED, activebackground="#f85149")
                status_label.config(text="● ONLINE", fg=ACCENT_GREEN)

    def copy_url():
        url = url_display_var.get()
        root.clipboard_clear()
        root.clipboard_append(url)
        root.update()
        btn_copy.config(text="✓ Copied!")
        root.after(1500, lambda: btn_copy.config(text="📋 Copy App URL"))

    def open_browser():
        try:
            p = int(port_var.get())
        except ValueError:
            p = DEFAULT_PORT
        webbrowser.open(f"http://localhost:{p}")

    def on_closing():
        if server_manager.is_running:
            server_manager.stop()
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_closing)

    # ---------------- UI Layout ----------------
    # Header Card
    header_frame = tk.Frame(root, bg=BG_DARK, pady=12, padx=20)
    header_frame.pack(fill=tk.X)

    title_label = tk.Label(
        header_frame,
        text="🎵 Alaktra Music Server Launcher",
        font=("Segoe UI", 16, "bold"),
        fg=TEXT_PRIMARY,
        bg=BG_DARK
    )
    title_label.pack(side=tk.LEFT)

    status_label = tk.Label(
        header_frame,
        textvariable=status_var,
        font=("Segoe UI", 11, "bold"),
        fg=ACCENT_RED,
        bg=BG_DARK
    )
    status_label.pack(side=tk.RIGHT)

    # Server Info Card
    card_frame = tk.Frame(root, bg=BG_CARD, bd=1, relief=tk.SOLID, padx=16, pady=14)
    card_frame.pack(fill=tk.X, padx=20, pady=6)

    # Row 1: Detected IP & Badge
    row1 = tk.Frame(card_frame, bg=BG_CARD)
    row1.pack(fill=tk.X, pady=(0, 8))

    ip_label = tk.Label(
        row1,
        textvariable=ip_display_var,
        font=("Segoe UI", 11, "bold"),
        fg=TEXT_PRIMARY,
        bg=BG_CARD
    )
    ip_label.pack(side=tk.LEFT)

    ip_badge = tk.Label(
        row1,
        text="CHECKING...",
        font=("Segoe UI", 8, "bold"),
        bg="#30363d",
        fg="#ffffff",
        padx=8,
        pady=2
    )
    ip_badge.pack(side=tk.RIGHT)

    # Row 2: Android App URL
    row2 = tk.Frame(card_frame, bg=BG_CARD)
    row2.pack(fill=tk.X, pady=(0, 10))

    url_hint = tk.Label(
        row2,
        text="Android App URL:",
        font=("Segoe UI", 9),
        fg=TEXT_MUTED,
        bg=BG_CARD
    )
    url_hint.pack(anchor="w")

    url_val = tk.Label(
        row2,
        textvariable=url_display_var,
        font=("Consolas", 13, "bold"),
        fg=ACCENT_CYAN,
        bg=BG_CARD,
        cursor="hand2"
    )
    url_val.pack(anchor="w", pady=(2, 0))

    # Row 3: Action Buttons inside Card
    row3 = tk.Frame(card_frame, bg=BG_CARD)
    row3.pack(fill=tk.X, pady=(6, 0))

    btn_copy = tk.Button(
        row3,
        text="📋 Copy App URL",
        font=("Segoe UI", 9, "bold"),
        bg="#21262d",
        fg=TEXT_PRIMARY,
        activebackground="#30363d",
        activeforeground=TEXT_PRIMARY,
        bd=1,
        relief=tk.FLAT,
        padx=12,
        pady=5,
        cursor="hand2",
        command=copy_url
    )
    btn_copy.pack(side=tk.LEFT, padx=(0, 8))

    btn_portal = tk.Button(
        row3,
        text="🌐 Open Web Upload Portal",
        font=("Segoe UI", 9),
        bg="#21262d",
        fg=TEXT_PRIMARY,
        activebackground="#30363d",
        activeforeground=TEXT_PRIMARY,
        bd=1,
        relief=tk.FLAT,
        padx=12,
        pady=5,
        cursor="hand2",
        command=open_browser
    )
    btn_portal.pack(side=tk.LEFT, padx=(0, 8))

    btn_refresh = tk.Button(
        row3,
        text="🔄 Refresh IP",
        font=("Segoe UI", 9),
        bg="#21262d",
        fg=TEXT_PRIMARY,
        activebackground="#30363d",
        activeforeground=TEXT_PRIMARY,
        bd=1,
        relief=tk.FLAT,
        padx=10,
        pady=5,
        cursor="hand2",
        command=refresh_ip
    )
    btn_refresh.pack(side=tk.LEFT)

    # Big 1-Button Start / Stop
    btn_start = tk.Button(
        root,
        text="▶  START SERVER",
        font=("Segoe UI", 13, "bold"),
        bg=ACCENT_GREEN,
        fg="#ffffff",
        activebackground="#3fb950",
        activeforeground="#ffffff",
        bd=0,
        relief=tk.FLAT,
        pady=12,
        cursor="hand2",
        command=toggle_server
    )
    btn_start.pack(fill=tk.X, padx=20, pady=12)

    # Server Logs Section
    log_header = tk.Label(
        root,
        text="Server Output & Streaming Logs:",
        font=("Segoe UI", 9),
        fg=TEXT_MUTED,
        bg=BG_DARK
    )
    log_header.pack(anchor="w", padx=20, pady=(4, 2))

    log_frame = tk.Frame(root, bg="#010409", bd=1, relief=tk.SOLID)
    log_frame.pack(fill=tk.BOTH, expand=True, padx=20, pady=(0, 16))

    scrollbar = tk.Scrollbar(log_frame)
    scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

    log_text = tk.Text(
        log_frame,
        bg="#010409",
        fg="#c9d1d9",
        insertbackground="#ffffff",
        font=("Consolas", 9),
        wrap=tk.WORD,
        yscrollcommand=scrollbar.set,
        bd=0,
        padx=8,
        pady=8
    )
    log_text.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
    scrollbar.config(command=log_text.yview)

    # Initial log message & IP detection
    append_log("Alaktra Music Server Launcher initialized.")
    append_log("Click 'START SERVER' to host on your network.")
    refresh_ip()

    root.mainloop()


# -------------------------------------------------------------
# 5. Console CLI Fallback (Headless / SSH / Raspberry Pi)
# -------------------------------------------------------------
def run_cli():
    print("=" * 60)
    print(" 🎵  ALAKTRA MUSIC SERVER - 1-CLICK LAUNCHER (CLI)")
    print("=" * 60)

    ip, url, is_ts = get_best_server_url(DEFAULT_PORT)
    print("\n🔍 Detecting Network Interfaces...")
    if is_ts:
        print(f" ✅ Tailscale IP Detected: \033[1;32m{ip}\033[0m")
        print("    (Stream anywhere from mobile data or external Wi-Fi!)")
    else:
        print(f" ℹ️  Local LAN IP: \033[1;33m{ip}\033[0m")
        print("    (Stream within your home Wi-Fi. Start Tailscale for global remote access)")

    print(f"\n📱 Android App URL to paste: \033[1;36m{url}\033[0m")
    print(f"🌐 Web Upload Portal:       \033[1;36mhttp://localhost:{DEFAULT_PORT}\033[0m")
    print("-" * 60)

    missing = check_missing_dependencies()
    if missing:
        print(f"⚠️  Missing dependencies: {', '.join(missing)}")
        print("📦 Installing required dependencies automatically...")
        install_requirements()

    server = ServerManager(port=DEFAULT_PORT, log_callback=lambda l: print(f"[Server] {l}"))

    def handle_sigint(sig, frame):
        print("\n\n⏹ Stopping server...")
        server.stop()
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_sigint)

    print("\n▶  Starting server now on 0.0.0.0:8000...")
    success = server.start()
    if not success:
        print("❌ Could not start server. Please check errors above.")
        return

    print("✅ Server is RUNNING! Press Ctrl+C at any time to stop.\n")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        handle_sigint(None, None)


# -------------------------------------------------------------
# 6. Entry Point
# -------------------------------------------------------------
if __name__ == "__main__":
    # Check if graphical display is available
    has_display = True
    if platform.system() != "Windows" and not os.environ.get("DISPLAY"):
        has_display = False

    if has_display:
        try:
            import tkinter
            run_gui()
        except ImportError:
            print("⚠️ Tkinter not installed in this environment. Falling back to Console CLI...")
            run_cli()
        except Exception as e:
            print(f"⚠️ Could not open GUI ({e}). Falling back to Console CLI...")
            run_cli()
    else:
        run_cli()
