from fastapi import APIRouter, Form, Request
from fastapi.responses import Response, JSONResponse
from database import get_db, attach_artists_to_song_dicts
from routes.fuse import refresh_fuse_if_needed, format_fuse_songs_payload, cleanup_expired_fuse_invites
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
    return "https://api.alaktracare.in"


# =========================
# CREATE PLAYLIST
# =========================
@router.post("/create")
def create_playlist(
    user_id: int = Form(...),
    name: str = Form(...)
):
    db = get_db()
    try:
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
            "INSERT INTO playlists(user_id, name, is_system, is_fuse) VALUES (?, ?, 0, 0)",
            (user_id, final_name)
        )
        db.commit()
        playlist_id = cursor.lastrowid

        return {
            "success": True,
            "id": playlist_id,
            "name": final_name
        }
    finally:
        db.close()


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
        pl = db.execute("SELECT is_fuse FROM playlists WHERE id=?", (playlist_id,)).fetchone()
        if pl and int(pl["is_fuse"] or 0) == 1:
            return JSONResponse(
                status_code=403,
                content={"success": False, "error": "Fuse playlists are curated by the server and cannot be edited."}
            )

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
    finally:
        db.close()


# =========================
# REMOVE SONG FROM PLAYLIST
# =========================
@router.post("/remove-song")
def remove_song_from_playlist(
    playlist_id: int = Form(...),
    song_id: int = Form(...)
):
    db = get_db()
    try:
        pl = db.execute("SELECT is_fuse FROM playlists WHERE id=?", (playlist_id,)).fetchone()
        if pl and int(pl["is_fuse"] or 0) == 1:
            return JSONResponse(
                status_code=403,
                content={"success": False, "error": "Fuse playlists are curated by the server and cannot be edited."}
            )

        db.execute(
            "DELETE FROM playlist_songs WHERE playlist_id=? AND song_id=?",
            (playlist_id, song_id)
        )
        db.commit()
        return {"success": True}
    finally:
        db.close()


# =========================
# GET SONGS IN PLAYLIST
# =========================
@router.get("/{playlist_id}/songs")
def get_playlist_songs(playlist_id: int, request: Request):
    db = get_db()
    try:
        pl = db.execute("SELECT * FROM playlists WHERE id=?", (playlist_id,)).fetchone()
        base_url = get_base_url(request)

        # Server-curated Fuse playlist (Spotify Blend mode)
        if pl and int(pl["is_fuse"] or 0) == 1 and pl["fuse_id"]:
            fuse_row = refresh_fuse_if_needed(db, int(pl["fuse_id"]), force=False)
            if fuse_row:
                data = format_fuse_songs_payload(db, fuse_row, base_url)
                pl_sig = f"fuse_{playlist_id}_{fuse_row['last_refreshed_date']}_{len(data)}_{data[0]['id'] if data else 0}"
                etag = f'"{hashlib.md5(pl_sig.encode()).hexdigest()}"'
                client_etag = request.headers.get("if-none-match")
                if client_etag and client_etag.strip('"') == etag.strip('"'):
                    return Response(status_code=304, headers={"ETag": etag, "Cache-Control": "private, max-age=30"})
                return JSONResponse(content=data, headers={"ETag": etag, "Cache-Control": "private, max-age=30"})

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

        max_song_up = max((str(s["updated_at"] or "") for s in songs), default="")
        pl_sig = f"pl_{playlist_id}_{len(songs)}_{songs[0]['id'] if songs else 0}_{max_song_up}"
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
                "dominant_color": s["dominant_color"] or "#00E599",
                "duration": float(s["duration"]) if s["duration"] else 0.0,
            }
            for s in songs
        ]
        attach_artists_to_song_dicts(db, data, base_url)
        return JSONResponse(content=data, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})
    finally:
        db.close()


# =========================
# GET USER PLAYLISTS
# =========================
@router.get("/{user_id}")
def get_playlists(user_id: int, request: Request):
    db = get_db()
    try:
        cleanup_expired_fuse_invites(db)

        playlists = db.execute(
            """
            SELECT
                p.*,
                f.match_score AS fuse_match_score,
                f.user1_name AS fuse_user1_name,
                f.user2_name AS fuse_user2_name,
                CASE
                    WHEN COALESCE(p.is_fuse, 0) = 1 AND p.fuse_id IS NOT NULL
                        THEN (SELECT COUNT(*) FROM fuse_songs fs WHERE fs.fuse_id = p.fuse_id)
                    ELSE (SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlist_id = p.id)
                END AS computed_song_count
            FROM playlists p
            LEFT JOIN fuses f ON f.id = p.fuse_id
            WHERE p.user_id = ?
            ORDER BY p.is_system DESC, COALESCE(p.is_fuse, 0) DESC, p.id DESC
            """,
            (user_id,)
        ).fetchall()

        sig_parts = [f"{p['id']}:{p['name']}:{p['computed_song_count']}" for p in playlists]
        user_pl_sig = f"user_{user_id}_{len(playlists)}_{'|'.join(sig_parts)}"
        etag = f'"{hashlib.md5(user_pl_sig.encode()).hexdigest()}"'
        client_etag = request.headers.get("if-none-match")
        if client_etag and client_etag.strip('"') == etag.strip('"'):
            return Response(status_code=304, headers={"ETag": etag, "Cache-Control": "private, max-age=15"})

        data = [
            {
                "id": p["id"],
                "user_id": p["user_id"],
                "name": p["name"],
                "is_system": p["is_system"],
                "is_fuse": int(p["is_fuse"] or 0),
                "fuse_id": p["fuse_id"],
                "song_count": int(p["computed_song_count"] or 0),
                "match_score": int(p["fuse_match_score"] or 0),
                "user1_name": p["fuse_user1_name"] or "",
                "user2_name": p["fuse_user2_name"] or "",
                "editable": int(p["is_fuse"] or 0) == 0 and int(p["is_system"] or 0) == 0,
                "downloadable": int(p["is_fuse"] or 0) == 0,
            }
            for p in playlists
        ]
        return JSONResponse(content=data, headers={"ETag": etag, "Cache-Control": "private, max-age=15"})
    finally:
        db.close()


# =========================
# DELETE PLAYLIST
# =========================
@router.post("/delete")
def delete_playlist(
    playlist_id: int = Form(...)
):
    db = get_db()
    try:
        playlist = db.execute(
            "SELECT is_system, is_fuse FROM playlists WHERE id=?",
            (playlist_id,)
        ).fetchone()

        if playlist and playlist["is_system"] == 1:
            return {
                "success": False,
                "error": "Cannot delete system playlist"
            }

        if playlist and int(playlist["is_fuse"] or 0) == 1:
            return JSONResponse(
                status_code=403,
                content={"success": False, "error": "Fuse playlists cannot be edited or deleted."}
            )

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
    finally:
        db.close()


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
    try:
        playlist = db.execute(
            "SELECT is_system, is_fuse FROM playlists WHERE id=?",
            (playlist_id,)
        ).fetchone()

        if playlist and playlist["is_system"] == 1:
            return {
                "success": False,
                "error": "Cannot rename system playlist"
            }

        if playlist and int(playlist["is_fuse"] or 0) == 1:
            return JSONResponse(
                status_code=403,
                content={"success": False, "error": "Fuse playlists cannot be renamed."}
            )

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
    finally:
        db.close()


# =========================
# GET LIKED PLAYLIST ID
# =========================
@router.get("/liked/{user_id}")
def get_liked_playlist(user_id: int):
    db = get_db()
    try:
        playlist = db.execute(
            """
            SELECT id FROM playlists
            WHERE user_id=? AND is_system=1
            """,
            (user_id,)
        ).fetchone()

        if not playlist:
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
    finally:
        db.close()
