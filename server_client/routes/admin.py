from fastapi import APIRouter, HTTPException, Depends, Header
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from pydantic import BaseModel
import os
import sqlite3
import shutil
import subprocess
import sys
import platform
import uuid

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB_PATH = os.path.join(BASE_DIR, "music.db")
MUSIC_DIR = os.path.join(BASE_DIR, "music")
COVER_DIR = os.path.join(BASE_DIR, "covers")
PIN_DIR = os.path.join(BASE_DIR, "pins")
ARTIST_DIR = os.path.join(BASE_DIR, "artist_media")

ADMIN_USER = os.getenv("ADMIN_USERNAME", "admin")
ADMIN_PASS = os.getenv("ADMIN_PASSWORD", "admin123")

# Session tokens store
ACTIVE_SESSIONS = set()

router = APIRouter()

class LoginRequest(BaseModel):
    username: str
    password: str

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

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

@router.get("/admin", response_class=HTMLResponse)
@router.get("/admin/", response_class=HTMLResponse)
def serve_admin_console():
    admin_path = os.path.join(BASE_DIR, "admin.html")
    if os.path.exists(admin_path):
        return FileResponse(admin_path)
    return "<h3>Admin Console (admin.html) not found</h3>"

@router.post("/admin/api/login")
def admin_login(req: LoginRequest):
    if req.username.strip() == ADMIN_USER and req.password.strip() == ADMIN_PASS:
        token = f"admin-token-{uuid.uuid4().hex[:12]}"
        ACTIVE_SESSIONS.add(token)
        return {"success": True, "token": token, "username": ADMIN_USER}
    return JSONResponse(status_code=401, content={"success": False, "detail": "Invalid Admin credentials"})

@router.get("/admin/api/server/status")
def get_server_status():
    port = int(os.getenv("PORT", 8000))
    host = os.getenv("HOST", "0.0.0.0")
    
    return {
        "running": True,
        "port": port,
        "host": host,
        "admin_user": ADMIN_USER
    }

@router.post("/admin/api/server/stop")
def stop_server():
    def shutdown():
        import time
        time.sleep(0.5)
        os._exit(0)
    
    import threading
    threading.Thread(target=shutdown, daemon=True).start()
    return {"success": True, "message": "Server shutdown initiated"}

@router.get("/admin/api/stats")
def get_admin_stats():
    conn = get_db()
    cursor = conn.cursor()
    
    # Counts
    try:
        total_songs = cursor.execute("SELECT COUNT(*) FROM songs").fetchone()[0]
    except Exception:
        total_songs = 0

    try:
        total_users = cursor.execute("SELECT COUNT(*) FROM users").fetchone()[0]
    except Exception:
        total_users = 0

    try:
        total_plays = cursor.execute("SELECT COUNT(*) FROM listening_history").fetchone()[0]
    except Exception:
        total_plays = 0

    # Storage Breakdown
    music_size = get_dir_size(MUSIC_DIR)
    cover_size = get_dir_size(COVER_DIR)
    pin_size = get_dir_size(PIN_DIR) + get_dir_size(ARTIST_DIR)
    db_size = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0

    total_storage = music_size + cover_size + pin_size + db_size

    # Disk total/free info
    try:
        total_disk, used_disk, free_disk = shutil.disk_usage(BASE_DIR)
    except Exception:
        total_disk, used_disk, free_disk = 512 * (1024**3), total_storage, 512 * (1024**3) - total_storage

    # Songs List
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

    # Users List
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

    # Playlists List
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

    # Clubs List
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
        "status": "online",
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
