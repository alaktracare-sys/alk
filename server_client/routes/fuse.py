from fastapi import APIRouter, Form, Request, HTTPException
from database import get_db, attach_artists_to_song_dicts
from datetime import datetime
import hashlib
import os
import random
import secrets
import time

router = APIRouter(prefix="/fuse", tags=["fuse"])

FUSE_TTL_MS = 5 * 60 * 1000  # 5 minutes
MAX_FUSE_SONGS = 30
ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


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


def now_millis() -> int:
    return int(time.time() * 1000)


def today_utc_str() -> str:
    return datetime.utcnow().strftime("%Y-%m-%d")


def cleanup_expired_fuse_invites(db, current_ms: int = None):
    if current_ms is None:
        current_ms = now_millis()
    db.execute(
        "DELETE FROM fuse_invites WHERE status = 'pending' AND expires_at_ms <= ?",
        (current_ms,)
    )
    db.commit()


def resolve_username(db, user_id: int, fallback_name: str = "") -> str:
    row = db.execute("SELECT username FROM users WHERE id = ?", (user_id,)).fetchone()
    if row and row["username"] and str(row["username"]).strip():
        return str(row["username"]).strip()
    if fallback_name and fallback_name.strip():
        return fallback_name.strip()
    return f"User {user_id}"


def _collect_user_library_profile(db, user_id: int):
    rows = db.execute(
        """
        SELECT
            s.id AS song_id,
            s.artist AS artist,
            MAX(CASE WHEN p.is_system = 1 THEN 1 ELSE 0 END) AS is_liked
        FROM playlist_songs ps
        JOIN playlists p ON p.id = ps.playlist_id
        JOIN songs s ON s.id = ps.song_id
        WHERE p.user_id = ?
          AND COALESCE(p.is_fuse, 0) = 0
          AND p.name NOT LIKE '__FUSE_PENDING__|%'
        GROUP BY s.id
        """,
        (user_id,)
    ).fetchall()

    play_rows = db.execute(
        """
        SELECT song_id, SUM(play_count) AS total_plays
        FROM user_song_plays
        WHERE user_id = ?
        GROUP BY song_id
        """,
        (user_id,)
    ).fetchall()
    plays_by_song = {r["song_id"]: int(r["total_plays"] or 0) for r in play_rows}

    song_weights = {}
    artists = set()
    for r in rows:
        sid = int(r["song_id"])
        liked_bonus = 3 if int(r["is_liked"] or 0) == 1 else 1
        play_bonus = min(plays_by_song.get(sid, 0), 25)
        song_weights[sid] = liked_bonus + play_bonus
        artist_norm = (r["artist"] or "").strip().lower()
        if artist_norm and artist_norm != "unknown artist":
            artists.add(artist_norm)

    if not song_weights and plays_by_song:
        for sid, pcount in plays_by_song.items():
            song_weights[int(sid)] = max(1, min(pcount, 25))
            s_row = db.execute("SELECT artist FROM songs WHERE id = ?", (sid,)).fetchone()
            if s_row and s_row["artist"]:
                a_norm = s_row["artist"].strip().lower()
                if a_norm and a_norm != "unknown artist":
                    artists.add(a_norm)

    return song_weights, artists


def curate_fuse_tracks(db, user1_id: int, user2_id: int, limit: int = MAX_FUSE_SONGS):
    u1_weights, u1_artists = _collect_user_library_profile(db, user1_id)
    u2_weights, u2_artists = _collect_user_library_profile(db, user2_id)

    u1_ids = set(u1_weights.keys())
    u2_ids = set(u2_weights.keys())
    common_ids = u1_ids.intersection(u2_ids)
    shared_artists = u1_artists.intersection(u2_artists)

    if not u1_ids and not u2_ids:
        match_score = 50
    elif user1_id == user2_id:
        match_score = 99
    else:
        smaller_lib = max(1, min(len(u1_ids), len(u2_ids)))
        song_overlap_ratio = len(common_ids) / float(smaller_lib)
        smaller_artists = max(1, min(len(u1_artists) or 1, len(u2_artists) or 1))
        artist_overlap_ratio = len(shared_artists) / float(smaller_artists)
        raw_score = (song_overlap_ratio * 68.0) + (artist_overlap_ratio * 24.0) + (8.0 if common_ids else 4.0)
        if common_ids:
            match_score = max(58, min(99, int(round(45 + raw_score * 0.55))))
        elif shared_artists:
            match_score = max(42, min(78, int(round(36 + artist_overlap_ratio * 42))))
        else:
            match_score = 35

    seed_key = f"{min(user1_id, user2_id)}:{max(user1_id, user2_id)}:{today_utc_str()}"
    rng = random.Random(int(hashlib.md5(seed_key.encode()).hexdigest()[:12], 16))

    common_scored = []
    for sid in common_ids:
        combined_score = u1_weights.get(sid, 1) + u2_weights.get(sid, 1) + rng.random() * 0.5
        common_scored.append((combined_score, sid))
    common_scored.sort(reverse=True)

    selected = []
    seen_ids = set()

    for _, sid in common_scored[:limit]:
        selected.append((sid, "both"))
        seen_ids.add(sid)

    if len(selected) < limit and user1_id != user2_id:
        def rank_exclusive(user_weights, exclude_ids):
            candidates = []
            for sid, w in user_weights.items():
                if sid in exclude_ids:
                    continue
                s_row = db.execute("SELECT artist FROM songs WHERE id = ?", (sid,)).fetchone()
                if not s_row:
                    continue
                artist_norm = (s_row["artist"] or "").strip().lower()
                shared_artist_boost = 4.0 if artist_norm in shared_artists else 0.0
                candidates.append((w + shared_artist_boost + rng.random() * 0.4, sid))
            candidates.sort(reverse=True)
            return [sid for _, sid in candidates]

        u1_exclusive = rank_exclusive(u1_weights, seen_ids)
        u2_exclusive = rank_exclusive(u2_weights, seen_ids)

        i1, i2 = 0, 0
        turn_u1 = True
        while len(selected) < limit and (i1 < len(u1_exclusive) or i2 < len(u2_exclusive)):
            if turn_u1 and i1 < len(u1_exclusive):
                sid = u1_exclusive[i1]
                i1 += 1
                if sid not in seen_ids:
                    selected.append((sid, "user1"))
                    seen_ids.add(sid)
            elif not turn_u1 and i2 < len(u2_exclusive):
                sid = u2_exclusive[i2]
                i2 += 1
                if sid not in seen_ids:
                    selected.append((sid, "user2"))
                    seen_ids.add(sid)
            elif i1 < len(u1_exclusive):
                sid = u1_exclusive[i1]
                i1 += 1
                if sid not in seen_ids:
                    selected.append((sid, "user1"))
                    seen_ids.add(sid)
            elif i2 < len(u2_exclusive):
                sid = u2_exclusive[i2]
                i2 += 1
                if sid not in seen_ids:
                    selected.append((sid, "user2"))
                    seen_ids.add(sid)
            turn_u1 = not turn_u1

    return selected[:limit], match_score


def refresh_fuse_if_needed(db, fuse_id: int, force: bool = False):
    fuse_row = db.execute("SELECT * FROM fuses WHERE id = ?", (fuse_id,)).fetchone()
    if not fuse_row:
        return None

    today = today_utc_str()
    existing_count_row = db.execute(
        "SELECT COUNT(*) AS cnt FROM fuse_songs WHERE fuse_id = ?",
        (fuse_id,)
    ).fetchone()
    has_songs = (existing_count_row["cnt"] or 0) > 0

    if not force and fuse_row["last_refreshed_date"] == today and has_songs:
        return fuse_row

    curated, match_score = curate_fuse_tracks(
        db,
        user1_id=int(fuse_row["user1_id"]),
        user2_id=int(fuse_row["user2_id"]),
        limit=MAX_FUSE_SONGS
    )

    db.execute("DELETE FROM fuse_songs WHERE fuse_id = ?", (fuse_id,))
    for idx, (song_id, contributor) in enumerate(curated):
        db.execute(
            """
            INSERT OR REPLACE INTO fuse_songs (fuse_id, song_id, position, contributor)
            VALUES (?, ?, ?, ?)
            """,
            (fuse_id, song_id, idx, contributor)
        )

    db.execute(
        """
        UPDATE fuses
        SET match_score = ?, last_refreshed_date = ?, updated_at = datetime('now')
        WHERE id = ?
        """,
        (match_score, today, fuse_id)
    )
    db.commit()
    return db.execute("SELECT * FROM fuses WHERE id = ?", (fuse_id,)).fetchone()


def format_fuse_songs_payload(db, fuse_row, base_url: str):
    fuse_id = int(fuse_row["id"])
    rows = db.execute(
        """
        SELECT s.*, fs.contributor, fs.position
        FROM fuse_songs fs
        JOIN songs s ON s.id = fs.song_id
        WHERE fs.fuse_id = ?
        ORDER BY fs.position ASC
        """,
        (fuse_id,)
    ).fetchall()

    data = [
        {
            "id": r["id"],
            "title": r["title"],
            "artist": r["artist"],
            "audio_url": f"{base_url}/songs/{r['id']}/stream",
            "cover_url": f"{base_url}/{r['cover_path'].replace(os.sep, '/')}",
            "dominant_color": r["dominant_color"] or "#00E599",
            "duration": float(r["duration"]) if r["duration"] else 0.0,
            "fuse_contributor": r["contributor"] or "both",
            "downloadable": False,
        }
        for r in rows
    ]
    return attach_artists_to_song_dicts(db, data, base_url)


def _ensure_user_fuse_playlist(db, user_id: int, fuse_id: int, fuse_name: str) -> int:
    existing = db.execute(
        "SELECT id FROM playlists WHERE user_id = ? AND fuse_id = ? AND is_fuse = 1",
        (user_id, fuse_id)
    ).fetchone()
    if existing:
        db.execute(
            "UPDATE playlists SET name = ? WHERE id = ?",
            (fuse_name, existing["id"])
        )
        return int(existing["id"])

    cur = db.execute(
        """
        INSERT INTO playlists (user_id, name, is_system, is_fuse, fuse_id)
        VALUES (?, ?, 0, 1, ?)
        """,
        (user_id, fuse_name, fuse_id)
    )
    return int(cur.lastrowid)


@router.post("/create")
def create_fuse_invite(
    user_id: int = Form(...),
    username: str = Form("")
):
    db = get_db()
    try:
        current_ms = now_millis()
        cleanup_expired_fuse_invites(db, current_ms)

        db.execute(
            "DELETE FROM fuse_invites WHERE host_user_id = ? AND status = 'pending'",
            (user_id,)
        )

        host_name = resolve_username(db, user_id, username)
        expires_ms = current_ms + FUSE_TTL_MS

        code = ""
        for _ in range(20):
            candidate = "".join(secrets.choice(ALPHABET) for _ in range(6))
            exists = db.execute("SELECT 1 FROM fuse_invites WHERE code = ?", (candidate,)).fetchone()
            if not exists:
                code = candidate
                break
        if not code:
            raise HTTPException(status_code=500, detail="Unable to generate Fuse code")

        db.execute(
            """
            INSERT INTO fuse_invites (code, host_user_id, host_username, created_at_ms, expires_at_ms, status)
            VALUES (?, ?, ?, ?, ?, 'pending')
            """,
            (code, user_id, host_name, current_ms, expires_ms)
        )
        db.commit()

        return {
            "success": True,
            "code": code,
            "host_user_id": user_id,
            "host_name": host_name,
            "created_at_ms": current_ms,
            "expires_at_ms": expires_ms
        }
    finally:
        db.close()


@router.post("/cancel")
def cancel_fuse_invite(
    code: str = Form(...),
    user_id: int = Form(0)
):
    db = get_db()
    try:
        clean_code = code.strip().upper()
        if user_id > 0:
            db.execute(
                "DELETE FROM fuse_invites WHERE code = ? AND host_user_id = ? AND status = 'pending'",
                (clean_code, user_id)
            )
        else:
            db.execute(
                "DELETE FROM fuse_invites WHERE code = ? AND status = 'pending'",
                (clean_code,)
            )
        db.commit()
        return {"success": True}
    finally:
        db.close()


@router.get("/status/{code}")
def get_fuse_invite_status(code: str, request: Request):
    db = get_db()
    try:
        current_ms = now_millis()
        clean_code = code.strip().upper()
        invite = db.execute(
            "SELECT * FROM fuse_invites WHERE code = ?",
            (clean_code,)
        ).fetchone()

        if not invite:
            return {"status": "expired"}

        if invite["status"] == "pending":
            if current_ms >= int(invite["expires_at_ms"]):
                db.execute("DELETE FROM fuse_invites WHERE code = ?", (clean_code,))
                db.commit()
                return {"status": "expired"}
            return {
                "status": "waiting",
                "code": clean_code,
                "expires_at_ms": int(invite["expires_at_ms"]),
                "remaining_ms": max(0, int(invite["expires_at_ms"]) - current_ms)
            }

        if invite["status"] == "joined" and invite["fuse_id"]:
            fuse_id = int(invite["fuse_id"])
            fuse_row = refresh_fuse_if_needed(db, fuse_id, force=False)
            if not fuse_row:
                return {"status": "expired"}

            host_pl = db.execute(
                "SELECT id, name FROM playlists WHERE user_id = ? AND fuse_id = ? AND is_fuse = 1",
                (int(invite["host_user_id"]), fuse_id)
            ).fetchone()
            playlist_id = int(host_pl["id"]) if host_pl else 0
            base_url = get_base_url(request)
            songs = format_fuse_songs_payload(db, fuse_row, base_url)

            return {
                "status": "joined",
                "playlist_id": playlist_id,
                "fuse_id": fuse_id,
                "name": fuse_row["name"],
                "user1_name": fuse_row["user1_name"],
                "user2_name": fuse_row["user2_name"],
                "match_score": int(fuse_row["match_score"]),
                "is_fuse": True,
                "editable": False,
                "downloadable": False,
                "songs": songs
            }

        return {"status": "expired"}
    finally:
        db.close()


@router.post("/join")
def join_fuse_invite(
    request: Request,
    code: str = Form(...),
    user_id: int = Form(...),
    username: str = Form("")
):
    db = get_db()
    try:
        current_ms = now_millis()
        clean_code = code.strip().upper()
        invite = db.execute(
            "SELECT * FROM fuse_invites WHERE code = ?",
            (clean_code,)
        ).fetchone()

        if not invite:
            cleanup_expired_fuse_invites(db, current_ms)
            raise HTTPException(status_code=404, detail="Invalid or expired Fuse code.")

        if invite["status"] != "pending":
            raise HTTPException(status_code=409, detail="This Fuse code has already been used.")

        if current_ms >= int(invite["expires_at_ms"]):
            db.execute("DELETE FROM fuse_invites WHERE code = ?", (clean_code,))
            db.commit()
            raise HTTPException(status_code=410, detail="This Fuse expired after 5 minutes and was deleted.")

        user1_id = int(invite["host_user_id"])
        user2_id = int(user_id)
        user1_name = resolve_username(db, user1_id, invite["host_username"])
        user2_name = resolve_username(db, user2_id, username)
        fuse_name = f"{user1_name} fuse {user2_name}"

        existing_fuse = db.execute(
            """
            SELECT id FROM fuses
            WHERE (user1_id = ? AND user2_id = ?) OR (user1_id = ? AND user2_id = ?)
            """,
            (user1_id, user2_id, user2_id, user1_id)
        ).fetchone()

        today = today_utc_str()
        if existing_fuse:
            fuse_id = int(existing_fuse["id"])
            db.execute(
                """
                UPDATE fuses
                SET user1_id = ?, user2_id = ?, user1_name = ?, user2_name = ?, name = ?, updated_at = datetime('now')
                WHERE id = ?
                """,
                (user1_id, user2_id, user1_name, user2_name, fuse_name, fuse_id)
            )
        else:
            cur = db.execute(
                """
                INSERT INTO fuses (user1_id, user2_id, user1_name, user2_name, name, match_score, last_refreshed_date)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                """,
                (user1_id, user2_id, user1_name, user2_name, fuse_name, today)
            )
            fuse_id = int(cur.lastrowid)

        fuse_row = refresh_fuse_if_needed(db, fuse_id, force=True)

        _ensure_user_fuse_playlist(db, user1_id, fuse_id, fuse_name)
        joiner_playlist_id = _ensure_user_fuse_playlist(db, user2_id, fuse_id, fuse_name)

        db.execute(
            "UPDATE fuse_invites SET status = 'joined', fuse_id = ? WHERE code = ?",
            (fuse_id, clean_code)
        )
        db.commit()

        base_url = get_base_url(request)
        songs = format_fuse_songs_payload(db, fuse_row, base_url)

        return {
            "success": True,
            "playlist_id": joiner_playlist_id,
            "fuse_id": fuse_id,
            "name": fuse_name,
            "user1_name": user1_name,
            "user2_name": user2_name,
            "match_score": int(fuse_row["match_score"]),
            "is_fuse": True,
            "editable": False,
            "downloadable": False,
            "songs": songs
        }
    finally:
        db.close()


@router.get("/playlist/{playlist_id}")
def get_fuse_playlist(playlist_id: int, request: Request):
    db = get_db()
    try:
        pl = db.execute(
            "SELECT * FROM playlists WHERE id = ?",
            (playlist_id,)
        ).fetchone()
        if not pl or int(pl["is_fuse"] or 0) != 1 or not pl["fuse_id"]:
            raise HTTPException(status_code=404, detail="Fuse playlist not found")

        fuse_row = refresh_fuse_if_needed(db, int(pl["fuse_id"]), force=False)
        if not fuse_row:
            raise HTTPException(status_code=404, detail="Fuse record not found")

        base_url = get_base_url(request)
        songs = format_fuse_songs_payload(db, fuse_row, base_url)

        return {
            "playlist_id": int(pl["id"]),
            "fuse_id": int(fuse_row["id"]),
            "name": fuse_row["name"],
            "user1_name": fuse_row["user1_name"],
            "user2_name": fuse_row["user2_name"],
            "match_score": int(fuse_row["match_score"]),
            "last_refreshed_date": fuse_row["last_refreshed_date"],
            "is_fuse": True,
            "editable": False,
            "downloadable": False,
            "songs": songs
        }
    finally:
        db.close()
