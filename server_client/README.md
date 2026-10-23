# 🎵 Alaktra Music - Self-Hosted Server Package

This folder contains everything needed to self-host your own **Alaktra Music Server** on **Windows**, **Linux**, or a **Raspberry Pi**. It includes an interactive **1-Click Launcher** with **automatic Tailscale IP detection**, letting you stream your personal music library anywhere in the world on your Android phone with zero port-forwarding!

---

## ⚡ 1-Click Quick Start

### 🪟 Windows Users:
1. Make sure you have **Python 3.10+** installed from [python.org](https://www.python.org/downloads/) (check the box *"Add Python to PATH"* during install).
2. Simply double-click **`start_windows.bat`**.
3. The launcher will open! Click the big green **▶ START SERVER** button.
4. Click **📋 Copy App URL** to get your connection link.

---

### 🐧 Linux & Raspberry Pi Users:
1. Open a terminal in this folder.
2. Run:
   ```bash
   chmod +x start_linux.sh
   ./start_linux.sh
   ```
3. If running on a desktop, the modern Dark Mode GUI opens. If running on a headless server or over SSH, it automatically runs in an interactive colored terminal menu!
4. Press **Ctrl+C** anytime to stop.

---

## 🌐 Streaming Anywhere in the World with Tailscale (Recommended)

Tailscale creates a free, secure, private network between your PC and your phone. You **do not** need a static IP, a public IP, or router port-forwarding.

### Step 1: Install Tailscale on your Host Computer
- **Windows:** Download & install from [tailscale.com/download/windows](https://tailscale.com/download/windows)
- **Linux / Raspberry Pi:** Run:
  ```bash
  curl -fsSL https://tailscale.com/install.sh | sh
  sudo tailscale up
  ```
- Sign in with your Google, Microsoft, or GitHub account.

### Step 2: Install Tailscale on your Android Phone
- Download **Tailscale** from the Google Play Store.
- Sign in using the **exact same account** as your computer.
- Flip the switch to **Active**.

### Step 3: Connect Alaktra Music App
1. Open the **Alaktra Server Launcher** on your computer.
2. The launcher will automatically detect your Tailscale IP (e.g. `100.85.12.34`) and display:
   ```
   http://100.85.12.34:8000
   ```
3. Click **📋 Copy App URL**.
4. Open the **Alaktra Music** app on your phone, navigate to **Settings ⚙️ -> Server Configuration**, and paste the URL.
5. That's it! You can now stream, sync playlists, and download songs on mobile data (4G/5G) or any Wi-Fi network worldwide.

---

## 🎶 Uploading Songs to your Server

The server includes a built-in web portal for easily uploading your audio files and metadata:

1. In the launcher, click **🌐 Open Web Upload Portal** (or visit `http://localhost:8000` in any web browser).
2. Drag and drop your audio files (`.mp3`, `.flac`, `.m4a`, `.wav`).
3. The server automatically:
   - Extracts ID3 title, artist, and album tags.
   - Extracts embedded album artwork.
   - Generates the dynamic Material 3 dominant theme color palette for each song!
4. The songs will immediately show up in your Android player!

---

## ⚙️ Optional Configuration (.env)

The `.env` file contains server configuration:
- `PORT=8000`: Port the server listens on.
- `HOST=0.0.0.0`: Binds to all network interfaces so Tailscale, local LAN, and localhost all work.
- `GMAIL_USER` & `GMAIL_APP_PASSWORD`: (Optional) If you want the server to send verification codes via email. If not set, OTP codes are logged directly in the launcher output window.

---

## 🍓 Running as a Background Service on Linux / Raspberry Pi (Optional)

If you want the server to automatically start when your Raspberry Pi or Linux box boots up:

1. Create a systemd service file:
   ```bash
   sudo nano /etc/systemd/system/alaktra.service
   ```
2. Paste the following (replace `/path/to/server_client` and `youruser`):
   ```ini
   [Unit]
   Description=Alaktra Music Streaming Server
   After=network.target tailscaled.service

   [Service]
   Type=simple
   User=youruser
   WorkingDirectory=/path/to/server_client
   ExecStart=/usr/bin/python3 -m uvicorn main:app --host 0.0.0.0 --port 8000
   Restart=always

   [Install]
   WantedBy=multi-user.target
   ```
3. Enable and start:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable alaktra.service
   sudo systemctl start alaktra.service
   ```

---

## 📁 Folder Structure

- `launcher.py` - Cross-platform 1-click launcher with Tailscale auto-detection & GUI/CLI.
- `start_windows.bat` - 1-click double-clickable script for Windows.
- `start_linux.sh` - 1-click executable script for Linux & Raspberry Pi.
- `main.py` - FastAPI server application entrypoint.
- `database.py` - SQLite database engine and file storage paths.
- `index.html` - Web upload portal.
- `migrate_art.html` - Artwork & color palette migration utility.
- `requirements.txt` - Python package requirements.
- `routes/` - API endpoints for authentication, songs, streaming, playlists, history, and pins.
- `covers/` - Stored song cover artwork images.
- `music/` - Stored audio tracks.
- `pins/` - Stored playlist & artist pin banners.
