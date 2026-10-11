from fastapi import APIRouter, Form, Request, HTTPException
from fastapi.responses import Response, JSONResponse
from database import get_db
import os
import hashlib

router = APIRouter(prefix="/playlists", tags=["playlists"])

def get_base_url(request: Request) -> str:
    env_host = os.getenv("HOST_URL")
    if env_host:
        return env_host.rstrip("/")
    if request:
        host = request.headers.get("host")
        proto = request.headers.get("x-forwarded-proto", request.url.scheme)
        if host:
            return f"{proto}://{host}"
    return "http://100.65.126.106:8000"


# =========================
# CREATE PLAYLIST
# =========================
@router.post("/create")
def create_playlist(
    user_id: int = Form(...),
    name: str = Form(...)
):
    db = get_db()
    base_name = name.strip()
    if not base_name:
        base_name = "New Playlist"

    final_name = base_name
    counter = 1

    # Auto-number duplicate names
    while True:
        existing = db.execute(
            "SELECT 1 FROM playlists WHERE user_id=? AND name=?",
            (user_id, final_name)
        ).fetchone()

        if not existing:
            break

        final_name = f"{base_name} ({counter})"
        counter += 1

    cursor = db.execute(
        "INSERT INTO playlists(user_id, name, is_system) VALUES (?, ?, 0)",
        (user_id, final_name)
    )
    db.commit()
    playlist_id = cursor.lastrowid

    return {
        "success": True,
        "id": playlist_id,
        "name": final_name
    }


# =========================
# ADD SONG TO PLAYLIST
# =========================
@router.post("/add-song")
def add_song_to_playlist(
    playlist_id: int = Form(...),
    song_id: int = Form(...)
):
    db = get_db()
    try:
        db.execute(
            "INSERT INTO playlist_songs(playlist_id, song_id) VALUES (?, ?)",
            (playlist_id, song_id)
        )
        db.commit()
    except Exception:
        # Ignore duplicate insertion if already in playlist
        pass

    return {"success": True}


# =========================
# REMOVE SONG FROM PLAYLIST
# =========================
@router.post("/remove-song")
def remove_song_from_playlist(
    playlist_id: int = Form(...),
    song_id: int = Form(...)
):
    db = get_db()
    db.execute(
        "DELETE FROM playlist_songs WHERE playlist_id=? AND song_id=?",
        (playlist_id, song_id)
    )
    db.commit()
    return {"success": True}


# =========================
# GET SONGS IN PLAYLIST
# =========================
@router.get("/{playlist_id}/songs")
def get_playlist_songs(playlist_id: int, request: Request):
    db = get_db()
    songs = db.execute(
        """
        SELECT songs.*
        FROM songs
        JOIN playlist_songs
        ON songs.id = playlist_songs.song_id
        WHERE playlist_songs.playlist_id = ?
        ORDER BY playlist_songs.id DESC
        """,
        (playlist_id,)
    ).fetchall()

    base_url = get_base_url(request)

    pl_sig = f"pl_{playlist_id}_{len(songs)}_{songs[0]['id'] if songs else 0}"
    etag = f'"{hashlib.md5(pl_sig.encode()).hexdigest()}"'
    client_etag = request.headers.get("if-none-match")
    if client_etag and client_etag.strip('"') == etag.strip('"'):
        return Response(status_code=304, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})

    data = [
        {
            "id": s["id"],
            "title": s["title"],
            "artist": s["artist"],
            "audio_url": f"{base_url}/songs/{s['id']}/stream",
            "cover_url": f"{base_url}/{s['cover_path'].replace(os.sep, '/')}",
            "duration": float(s["duration"]) if s["duration"] else 0.0,
        }
        for s in songs
    ]
    return JSONResponse(content=data, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})


# =========================
# GET USER PLAYLISTS
# =========================
@router.get("/{user_id}")
def get_playlists(user_id: int, request: Request):
    db = get_db()
    playlists = db.execute(
        """
        SELECT *
        FROM playlists
        WHERE user_id=?
        ORDER BY is_system DESC, id DESC
        """,
        (user_id,)
    ).fetchall()

    user_pl_sig = f"user_{user_id}_{len(playlists)}_{playlists[0]['id'] if playlists else 0}"
    etag = f'"{hashlib.md5(user_pl_sig.encode()).hexdigest()}"'
    client_etag = request.headers.get("if-none-match")
    if client_etag and client_etag.strip('"') == etag.strip('"'):
        return Response(status_code=304, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})

    data = [
        {
            "id": p["id"],
            "user_id": p["user_id"],
            "name": p["name"],
            "is_system": p["is_system"]
        }
        for p in playlists
    ]
    return JSONResponse(content=data, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})


# =========================
# DELETE PLAYLIST
# =========================
@router.post("/delete")
def delete_playlist(
    playlist_id: int = Form(...)
):
    db = get_db()

    playlist = db.execute(
        "SELECT is_system FROM playlists WHERE id=?",
        (playlist_id,)
    ).fetchone()

    if playlist and playlist["is_system"] == 1:
        return {
            "success": False,
            "error": "Cannot delete system playlist"
        }

    db.execute(
        "DELETE FROM playlist_songs WHERE playlist_id=?",
        (playlist_id,)
    )
    db.execute(
        "DELETE FROM playlists WHERE id=?",
        (playlist_id,)
    )
    db.commit()

    return {"success": True}


# =========================
# RENAME PLAYLIST
# =========================
@router.post("/rename")
def rename_playlist(
    playlist_id: int = Form(...),
    user_id: int = Form(...),
    name: str = Form(...)
):
    db = get_db()

    playlist = db.execute(
        "SELECT is_system FROM playlists WHERE id=?",
        (playlist_id,)
    ).fetchone()

    if playlist and playlist["is_system"] == 1:
        return {
            "success": False,
            "error": "Cannot rename system playlist"
        }

    base_name = name.strip()
    final_name = base_name
    counter = 1

    while True:
        existing = db.execute(
            """
            SELECT 1 FROM playlists
            WHERE user_id=? AND name=? AND id!=?
            """,
            (user_id, final_name, playlist_id)
        ).fetchone()

        if not existing:
            break

        final_name = f"{base_name} ({counter})"
        counter += 1

    db.execute(
        "UPDATE playlists SET name=? WHERE id=?",
        (final_name, playlist_id)
    )
    db.commit()

    return {
        "success": True,
        "name": final_name
    }


# =========================
# GET LIKED PLAYLIST ID
# =========================
@router.get("/liked/{user_id}")
def get_liked_playlist(user_id: int):
    db = get_db()

    playlist = db.execute(
        """
        SELECT id FROM playlists
        WHERE user_id=? AND is_system=1
        """,
        (user_id,)
    ).fetchone()

    if not playlist:
        # Auto-create if not yet generated
        cursor = db.execute(
            """
            INSERT INTO playlists (user_id, name, is_system)
            VALUES (?, 'Liked Songs', 1)
            """,
            (user_id,)
        )
        db.commit()
        return {"playlist_id": cursor.lastrowid}

    return {"playlist_id": playlist["id"]}
