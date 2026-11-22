from fastapi import APIRouter, Form, Query, Request
from fastapi.responses import Response, JSONResponse
from database import get_db, attach_artists_to_song_dicts
from routes.fuse import refresh_fuse_if_needed, format_fuse_songs_payload, cleanup_expired_fuse_invites
from datetime import datetime
import os
import hashlib
import random
import re

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
# DAILY PERSONALIZED MIXES (5 x 30 SONGS / DAY)
# =========================
DAILY_MIX_COUNT = 5
DAILY_MIX_SONG_LIMIT = 30
_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

_DAILY_MIX_THEMES = [
    ("Neon Drift", "#00E599"),
    ("Midnight Echo", "#00D2FF"),
    ("Velvet Horizon", "#8B5CF6"),
    ("Solar Pulse", "#F59E0B"),
    ("Emerald Tide", "#10B981"),
    ("Aurora Haze", "#EC4899"),
    ("Prism Wave", "#06B6D4"),
    ("Crimson Static", "#FF5277"),
    ("Starlight Run", "#6366F1"),
    ("Obsidian Flow", "#14B8A6"),
]


def _resolve_target_date(date_param: str | None) -> str:
    if date_param and _DATE_RE.match(date_param.strip()):
        return date_param.strip()
    return datetime.now().strftime("%Y-%m-%d")


def _serialize_song_row(s, base_url: str) -> dict:
    return {
        "id": s["id"],
        "title": s["title"],
        "artist": s["artist"],
        "audio_url": f"{base_url}/songs/{s['id']}/stream",
        "cover_url": f"{base_url}/{s['cover_path'].replace(os.sep, '/')}",
        "dominant_color": s["dominant_color"] or "#00E599",
        "duration": float(s["duration"]) if s["duration"] else 0.0,
    }


def _build_mix_subtitle(selected_songs) -> str:
    seen = []
    seen_lower = set()
    for s in selected_songs:
        raw_artist = (s["artist"] or "").split(",")[0].strip()
        if raw_artist and raw_artist.lower() not in ("unknown artist", "") and raw_artist.lower() not in seen_lower:
            seen_lower.add(raw_artist.lower())
            seen.append(raw_artist)
            if len(seen) >= 2:
                break
    if len(seen) >= 2:
        return f"Feat. {seen[0]}, {seen[1]} & more"
    if len(seen) == 1:
        return f"Feat. {seen[0]} & more"
    return "Fresh 30-track daily mix"


def _delete_stale_daily_playlists(db, user_id: int, keep_date: str | None = None):
    if keep_date:
        old_rows = db.execute(
            "SELECT id FROM daily_playlists WHERE user_id = ? AND playlist_date != ?",
            (user_id, keep_date),
        ).fetchall()
    else:
        old_rows = db.execute(
            "SELECT id FROM daily_playlists WHERE user_id = ?",
            (user_id,),
        ).fetchall()

    old_ids = [int(r["id"]) for r in old_rows]
    if old_ids:
        placeholders = ",".join("?" for _ in old_ids)
        db.execute(f"DELETE FROM daily_playlist_songs WHERE daily_playlist_id IN ({placeholders})", old_ids)
        db.execute(f"DELETE FROM daily_playlists WHERE id IN ({placeholders})", old_ids)


def _generate_and_save_daily_playlists(db, user_id: int, target_date: str):
    # Remove any previous day's (or partial today's) daily playlists before saving the new 5
    _delete_stale_daily_playlists(db, user_id, keep_date=None)

    all_songs = db.execute("SELECT * FROM songs ORDER BY id ASC").fetchall()
    rng = random.SystemRandom()
    themes = rng.sample(_DAILY_MIX_THEMES, DAILY_MIX_COUNT)
    target_per_playlist = min(DAILY_MIX_SONG_LIMIT, len(all_songs))

    used_counts = {int(s["id"]): 0 for s in all_songs}
    previous_sets = []

    for slot_idx in range(1, DAILY_MIX_COUNT + 1):
        mix_name, accent_hex = themes[slot_idx - 1]
        chosen = []
        if target_per_playlist > 0:
            if len(all_songs) <= DAILY_MIX_SONG_LIMIT:
                chosen = list(all_songs)
                rng.shuffle(chosen)
            else:
                # Prefer less-used songs with randomized jitter so all 5 mixes stay diverse and unique per user
                best_chosen = None
                best_overlap = None
                for _attempt in range(4):
                    jittered = sorted(
                        all_songs,
                        key=lambda s: (used_counts[int(s["id"])] * 0.65) + rng.random(),
                    )
                    candidate = jittered[:target_per_playlist]
                    rng.shuffle(candidate)
                    cand_ids = {int(s["id"]) for s in candidate}
                    max_ov = max((len(cand_ids.intersection(prev)) for prev in previous_sets), default=0)
                    if best_chosen is None or max_ov < best_overlap:
                        best_chosen = candidate
                        best_overlap = max_ov
                    if max_ov <= target_per_playlist // 2:
                        break
                chosen = best_chosen or rng.sample(all_songs, target_per_playlist)
                previous_sets.append({int(s["id"]) for s in chosen})
                for s in chosen:
                    used_counts[int(s["id"])] += 1

        subtitle = _build_mix_subtitle(chosen)
        cur = db.execute(
            """
            INSERT INTO daily_playlists (user_id, playlist_date, slot_index, name, subtitle, accent_hex)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (user_id, target_date, slot_idx, mix_name, subtitle, accent_hex),
        )
        dp_id = int(cur.lastrowid)
        for pos, song_row in enumerate(chosen):
            db.execute(
                """
                INSERT INTO daily_playlist_songs (daily_playlist_id, song_id, position)
                VALUES (?, ?, ?)
                """,
                (dp_id, int(song_row["id"]), pos),
            )

    db.commit()


def _ensure_daily_playlists_for_user(db, user_id: int, target_date: str):
    # Always delete any expired previous-day playlists saved for this user
    _delete_stale_daily_playlists(db, user_id, keep_date=target_date)

    existing = db.execute(
        """
        SELECT *
        FROM daily_playlists
        WHERE user_id = ? AND playlist_date = ?
        ORDER BY slot_index ASC
        """,
        (user_id, target_date),
    ).fetchall()

    need_regen = len(existing) != DAILY_MIX_COUNT
    if not need_regen:
        catalog_total = db.execute("SELECT COUNT(*) AS cnt FROM songs").fetchone()["cnt"]
        if catalog_total > 0:
            for dp in existing:
                cnt_row = db.execute(
                    "SELECT COUNT(*) AS cnt FROM daily_playlist_songs WHERE daily_playlist_id = ?",
                    (int(dp["id"]),),
                ).fetchone()
                if (cnt_row["cnt"] or 0) == 0:
                    need_regen = True
                    break

    if need_regen:
        _generate_and_save_daily_playlists(db, user_id, target_date)
        existing = db.execute(
            """
            SELECT *
            FROM daily_playlists
            WHERE user_id = ? AND playlist_date = ?
            ORDER BY slot_index ASC
            """,
            (user_id, target_date),
        ).fetchall()
    else:
        db.commit()

    return existing


@router.get("/daily/item/{daily_playlist_id}/songs")
def get_daily_playlist_songs(daily_playlist_id: int, request: Request):
    db = get_db()
    try:
        dp = db.execute("SELECT * FROM daily_playlists WHERE id = ?", (daily_playlist_id,)).fetchone()
        if not dp:
            return JSONResponse(status_code=404, content={"detail": "Daily playlist not found"})

        base_url = get_base_url(request)
        songs = db.execute(
            """
            SELECT songs.*
            FROM songs
            JOIN daily_playlist_songs dps ON dps.song_id = songs.id
            WHERE dps.daily_playlist_id = ?
            ORDER BY dps.position ASC
            """,
            (daily_playlist_id,),
        ).fetchall()

        data = [_serialize_song_row(s, base_url) for s in songs]
        attach_artists_to_song_dicts(db, data, base_url)
        return JSONResponse(content=data, headers={"Cache-Control": "private, max-age=60"})
    finally:
        db.close()


@router.get("/daily/{user_id}")
def get_daily_playlists(
    user_id: int,
    request: Request,
    date: str | None = Query(default=None),
):
    db = get_db()
    try:
        user_exists = db.execute("SELECT id FROM users WHERE id = ?", (user_id,)).fetchone()
        if not user_exists:
            return JSONResponse(status_code=404, content={"detail": "User not found"})

        target_date = _resolve_target_date(date)
        daily_rows = _ensure_daily_playlists_for_user(db, user_id, target_date)
        base_url = get_base_url(request)

        # Fetch all songs for these 5 daily playlists in one batch
        dp_ids = [int(r["id"]) for r in daily_rows]
        songs_by_dp = {dp_id: [] for dp_id in dp_ids}
        all_song_dicts = []
        if dp_ids:
            placeholders = ",".join("?" for _ in dp_ids)
            joined = db.execute(
                f"""
                SELECT dps.daily_playlist_id, dps.position, s.*
                FROM daily_playlist_songs dps
                JOIN songs s ON s.id = dps.song_id
                WHERE dps.daily_playlist_id IN ({placeholders})
                ORDER BY dps.daily_playlist_id ASC, dps.position ASC
                """,
                dp_ids,
            ).fetchall()
            for r in joined:
                dp_id = int(r["daily_playlist_id"])
                s_dict = _serialize_song_row(r, base_url)
                songs_by_dp[dp_id].append(s_dict)
                all_song_dicts.append(s_dict)

            if all_song_dicts:
                attach_artists_to_song_dicts(db, all_song_dicts, base_url)

        sig = f"daily_{user_id}_{target_date}_{'_'.join(str(r['id']) + ':' + str(len(songs_by_dp.get(int(r['id']), []))) for r in daily_rows)}"
        etag = f'"{hashlib.md5(sig.encode()).hexdigest()}"'
        client_etag = request.headers.get("if-none-match")
        if client_etag and client_etag.strip('"') == etag.strip('"'):
            return Response(status_code=304, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})

        payload = []
        for r in daily_rows:
            dp_id = int(r["id"])
            pl_songs = songs_by_dp.get(dp_id, [])
            covers = []
            for s in pl_songs:
                cu = s.get("cover_url") or ""
                if cu and cu not in covers:
                    covers.append(cu)
                if len(covers) >= 4:
                    break
            payload.append({
                "id": dp_id,
                "slot_index": int(r["slot_index"]),
                "date": r["playlist_date"],
                "name": r["name"],
                "subtitle": r["subtitle"] or "",
                "accent_color": r["accent_hex"] or "#00E599",
                "song_count": len(pl_songs),
                "preview_covers": covers,
                "songs": pl_songs,
            })

        return JSONResponse(content=payload, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})
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
