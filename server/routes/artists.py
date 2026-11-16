from fastapi import APIRouter, UploadFile, File, Form, Query, Request, HTTPException
from pydantic import BaseModel
from typing import Optional
from datetime import datetime, timedelta
import os
import uuid
import io

from database import (
    get_db,
    ARTIST_DIR,
    build_asset_url,
    get_or_create_artist,
    sync_song_display_artist,
    attach_artists_to_song_dicts,
)

router = APIRouter(prefix="/artists", tags=["artists"])


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


def _save_sanitized_artist_image(raw_bytes: bytes, filename: str, prefix: str) -> str:
    ext = os.path.splitext(filename or "")[1].lower()
    if ext not in (".jpg", ".jpeg", ".png", ".webp"):
        ext = ".jpg"
    out_name = f"{prefix}_{uuid.uuid4().hex}{ext}"
    abs_path = os.path.join(ARTIST_DIR, out_name)

    # Strip EXIF/GPS metadata while preserving upright orientation via Pillow when available
    saved = False
    try:
        from PIL import Image, ImageOps
        img = ImageOps.exif_transpose(Image.open(io.BytesIO(raw_bytes)))
        buf = io.BytesIO()
        if ext == ".png":
            img.save(buf, format="PNG")
        elif ext == ".webp":
            img.save(buf, format="WEBP", quality=92)
        else:
            if img.mode not in ("RGB", "L"):
                img = img.convert("RGB")
            img.save(buf, format="JPEG", quality=92)
        with open(abs_path, "wb") as f:
            f.write(buf.getvalue())
        saved = True
    except Exception:
        pass

    if not saved:
        with open(abs_path, "wb") as f:
            f.write(raw_bytes)

    return f"artist-media/{out_name}"


def _serialize_artist_row(conn, row, base_url: str) -> dict:
    artist_id = int(row["id"])

    # Fallback avatar/banner from artist's songs when custom images haven't been uploaded yet
    cover_rows = conn.execute(
        """
        SELECT s.cover_path
        FROM song_artists sa
        JOIN songs s ON s.id = sa.song_id
        WHERE sa.artist_id = ?
        ORDER BY CASE WHEN sa.role = 'primary' THEN 0 ELSE 1 END, s.id DESC
        LIMIT 2
        """,
        (artist_id,)
    ).fetchall()
    fallback_avatar = cover_rows[0]["cover_path"] if cover_rows else ""
    fallback_banner = (
        cover_rows[1]["cover_path"]
        if len(cover_rows) > 1
        else (cover_rows[0]["cover_path"] if cover_rows else "")
    )

    avatar_rel = row["avatar_path"] or fallback_avatar
    banner_rel = row["banner_path"] or fallback_banner

    song_count_row = conn.execute(
        "SELECT COUNT(DISTINCT song_id) AS cnt FROM song_artists WHERE artist_id = ?",
        (artist_id,)
    ).fetchone()
    song_count = int(song_count_row["cnt"] or 0) if song_count_row else 0

    # Aggregate total plays across user_song_plays + listening_history (>= 30s)
    usp_row = conn.execute(
        """
        SELECT COALESCE(SUM(usp.play_count), 0) AS plays
        FROM user_song_plays usp
        JOIN song_artists sa ON sa.song_id = usp.song_id
        WHERE sa.artist_id = ?
        """,
        (artist_id,)
    ).fetchone()
    lh_row = conn.execute(
        """
        SELECT COUNT(*) AS plays
        FROM listening_history lh
        JOIN song_artists sa ON sa.song_id = lh.song_id
        WHERE sa.artist_id = ?
          AND (COALESCE(lh.ms_played, 0) >= 30000 OR COALESCE(lh.listened_seconds, 0) >= 30)
        """,
        (artist_id,)
    ).fetchone()
    total_plays = max(
        int(usp_row["plays"] or 0) if usp_row else 0,
        int(lh_row["plays"] or 0) if lh_row else 0,
    )

    thirty_days_ago_ms = int((datetime.utcnow() - timedelta(days=30)).timestamp() * 1000)
    ml_row = conn.execute(
        """
        SELECT COUNT(DISTINCT lh.user_id) AS listeners
        FROM listening_history lh
        JOIN song_artists sa ON sa.song_id = lh.song_id
        WHERE sa.artist_id = ? AND lh.played_at >= ?
        """,
        (artist_id, thirty_days_ago_ms)
    ).fetchone()
    monthly_listeners = int(ml_row["listeners"] or 0) if ml_row else 0

    claimed_uid = row["claimed_by_user_id"]
    claimed_username = None
    if claimed_uid is not None:
        u_row = conn.execute("SELECT username FROM users WHERE id = ?", (int(claimed_uid),)).fetchone()
        if u_row:
            claimed_username = u_row["username"]

    return {
        "id": artist_id,
        "name": row["name"],
        "bio": row["bio"] or "",
        "avatar_url": build_asset_url(base_url, avatar_rel) if avatar_rel else "",
        "banner_url": build_asset_url(base_url, banner_rel) if banner_rel else "",
        "has_custom_avatar": bool(row["avatar_path"]),
        "has_custom_banner": bool(row["banner_path"]),
        "is_verified": bool(int(row["is_verified"] or 0) == 1),
        "claimed_by_user_id": int(claimed_uid) if claimed_uid is not None else None,
        "claimed_by_username": claimed_username,
        "song_count": song_count,
        "total_plays": total_plays,
        "monthly_listeners": monthly_listeners,
    }


def _song_row_to_base_dict(s, base_url: str) -> dict:
    return {
        "id": int(s["id"]),
        "title": s["title"],
        "artist": s["artist"],
        "audio_url": f"{base_url}/songs/{s['id']}/stream",
        "cover_url": build_asset_url(base_url, s["cover_path"]),
        "dominant_color": s["dominant_color"] or "#00E599",
        "duration": float(s["duration"]) if s["duration"] else 0.0,
    }


# ---------------- 1. LIST / SEARCH ARTISTS ----------------

@router.get("")
def list_artists(
    request: Request,
    q: str = Query("", description="Optional artist search query"),
    unclaimed_only: bool = Query(False),
    limit: int = Query(100, ge=1, le=500),
):
    conn = get_db()
    try:
        base_url = get_base_url(request)
        clean_q = q.strip()
        sql = "SELECT * FROM artists WHERE 1=1"
        params = []
        if clean_q:
            sql += " AND name LIKE ?"
            params.append(f"%{clean_q}%")
        if unclaimed_only:
            sql += " AND claimed_by_user_id IS NULL"
        sql += " ORDER BY is_verified DESC, name COLLATE NOCASE ASC LIMIT ?"
        params.append(limit)

        rows = conn.execute(sql, params).fetchall()
        return [_serialize_artist_row(conn, r, base_url) for r in rows]
    finally:
        conn.close()


# ---------------- 2. RESOLVE ARTIST BY NAME ----------------

@router.get("/by-name")
def get_artist_by_name(request: Request, name: str = Query(...)):
    conn = get_db()
    try:
        base_url = get_base_url(request)
        clean = " ".join((name or "").strip().split())
        if not clean:
            raise HTTPException(status_code=400, detail="Artist name is required")
        row = conn.execute(
            "SELECT * FROM artists WHERE name = ? COLLATE NOCASE",
            (clean,)
        ).fetchone()
        if not row:
            # Try primary credit prefix if full string had collaborators
            first_part = clean.split(",")[0].strip()
            row = conn.execute(
                "SELECT * FROM artists WHERE name = ? COLLATE NOCASE",
                (first_part,)
            ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Artist not found")
        return _serialize_artist_row(conn, row, base_url)
    finally:
        conn.close()


# ---------------- 3. USER ARTIST SEGREGATION STATUS ----------------

@router.get("/user-status/{user_id}")
def get_user_artist_status(user_id: int, request: Request):
    conn = get_db()
    try:
        base_url = get_base_url(request)
        claimed_row = conn.execute(
            "SELECT * FROM artists WHERE claimed_by_user_id = ?",
            (user_id,)
        ).fetchone()
        claimed_artist = _serialize_artist_row(conn, claimed_row, base_url) if claimed_row else None

        pending_row = conn.execute(
            """
            SELECT * FROM artist_claims
            WHERE user_id = ? AND status = 'pending'
            ORDER BY id DESC LIMIT 1
            """,
            (user_id,)
        ).fetchone()
        pending_claim = None
        if pending_row:
            pending_claim = {
                "id": int(pending_row["id"]),
                "user_id": int(pending_row["user_id"]),
                "artist_id": int(pending_row["artist_id"]) if pending_row["artist_id"] is not None else None,
                "requested_name": pending_row["requested_name"],
                "note": pending_row["note"] or "",
                "status": pending_row["status"],
                "created_at": pending_row["created_at"] or "",
            }

        return {
            "user_id": user_id,
            "is_artist": claimed_artist is not None,
            "claimed_artist": claimed_artist,
            "pending_claim": pending_claim,
        }
    finally:
        conn.close()


# ---------------- 4. LIST REGISTERED USERS (FOR PORTAL LINKING) ----------------

@router.get("/users")
def list_listener_users_for_portal():
    conn = get_db()
    try:
        rows = conn.execute(
            """
            SELECT u.id, u.username, u.email, u.is_verified,
                   a.id AS claimed_artist_id, a.name AS claimed_artist_name
            FROM users u
            LEFT JOIN artists a ON a.claimed_by_user_id = u.id
            WHERE u.is_verified = 1
            ORDER BY u.username COLLATE NOCASE ASC
            """
        ).fetchall()
        return [
            {
                "id": int(r["id"]),
                "username": r["username"],
                "email": r["email"],
                "claimed_artist_id": int(r["claimed_artist_id"]) if r["claimed_artist_id"] is not None else None,
                "claimed_artist_name": r["claimed_artist_name"],
            }
            for r in rows
        ]
    finally:
        conn.close()


# ---------------- 5. ARTIST CLAIMS WORKFLOW ----------------

class SubmitClaimRequest(BaseModel):
    user_id: int
    artist_id: Optional[int] = None
    requested_name: str
    note: str = ""


@router.post("/claims")
def submit_artist_claim(req: SubmitClaimRequest):
    conn = get_db()
    try:
        user = conn.execute("SELECT id, username FROM users WHERE id = ?", (req.user_id,)).fetchone()
        if not user:
            raise HTTPException(status_code=404, detail="Listener account not found")

        already_claimed = conn.execute(
            "SELECT id, name FROM artists WHERE claimed_by_user_id = ?",
            (req.user_id,)
        ).fetchone()
        if already_claimed:
            raise HTTPException(
                status_code=400,
                detail=f"Your account already manages artist profile '{already_claimed['name']}'."
            )

        existing_pending = conn.execute(
            "SELECT id FROM artist_claims WHERE user_id = ? AND status = 'pending'",
            (req.user_id,)
        ).fetchone()
        if existing_pending:
            raise HTTPException(status_code=400, detail="You already have a pending artist claim awaiting review.")

        clean_name = " ".join((req.requested_name or "").strip().split())
        target_artist_id = req.artist_id if (req.artist_id and req.artist_id > 0) else None

        if target_artist_id is not None:
            art = conn.execute("SELECT * FROM artists WHERE id = ?", (target_artist_id,)).fetchone()
            if not art:
                raise HTTPException(status_code=404, detail="Selected artist profile not found")
            if art["claimed_by_user_id"] is not None:
                raise HTTPException(status_code=400, detail="This artist profile has already been claimed.")
            clean_name = art["name"]
        else:
            if not clean_name:
                raise HTTPException(status_code=400, detail="Please enter your artist/stage name.")
            # If an unclaimed catalog artist already has this exact name, link the claim to it automatically
            match = conn.execute(
                "SELECT * FROM artists WHERE name = ? COLLATE NOCASE",
                (clean_name,)
            ).fetchone()
            if match:
                if match["claimed_by_user_id"] is not None:
                    raise HTTPException(status_code=400, detail="An artist with this name is already claimed.")
                target_artist_id = int(match["id"])
                clean_name = match["name"]

        cur = conn.execute(
            """
            INSERT INTO artist_claims (user_id, artist_id, requested_name, note, status, created_at)
            VALUES (?, ?, ?, ?, 'pending', datetime('now'))
            """,
            (req.user_id, target_artist_id, clean_name, (req.note or "").strip())
        )
        conn.commit()
        claim_id = int(cur.lastrowid)
        row = conn.execute("SELECT * FROM artist_claims WHERE id = ?", (claim_id,)).fetchone()
        return {
            "success": True,
            "claim": {
                "id": claim_id,
                "user_id": int(row["user_id"]),
                "artist_id": int(row["artist_id"]) if row["artist_id"] is not None else None,
                "requested_name": row["requested_name"],
                "note": row["note"] or "",
                "status": row["status"],
                "created_at": row["created_at"] or "",
            }
        }
    finally:
        conn.close()


@router.get("/claims")
def list_artist_claims(status: Optional[str] = Query(None)):
    conn = get_db()
    try:
        sql = """
            SELECT c.*, u.username, u.email, a.name AS existing_artist_name
            FROM artist_claims c
            JOIN users u ON u.id = c.user_id
            LEFT JOIN artists a ON a.id = c.artist_id
        """
        params = []
        if status:
            sql += " WHERE c.status = ?"
            params.append(status.strip().lower())
        sql += " ORDER BY CASE WHEN c.status = 'pending' THEN 0 ELSE 1 END, c.id DESC"
        rows = conn.execute(sql, params).fetchall()
        return [
            {
                "id": int(r["id"]),
                "user_id": int(r["user_id"]),
                "username": r["username"],
                "email": r["email"],
                "artist_id": int(r["artist_id"]) if r["artist_id"] is not None else None,
                "existing_artist_name": r["existing_artist_name"],
                "requested_name": r["requested_name"],
                "note": r["note"] or "",
                "status": r["status"],
                "created_at": r["created_at"] or "",
                "reviewed_at": r["reviewed_at"],
            }
            for r in rows
        ]
    finally:
        conn.close()


@router.post("/claims/{claim_id}/review")
def review_artist_claim(
    claim_id: int,
    request: Request,
    action: str = Form(...),
):
    conn = get_db()
    try:
        base_url = get_base_url(request)
        claim = conn.execute("SELECT * FROM artist_claims WHERE id = ?", (claim_id,)).fetchone()
        if not claim:
            raise HTTPException(status_code=404, detail="Claim not found")

        clean_action = action.strip().lower()
        if clean_action not in ("approve", "reject"):
            raise HTTPException(status_code=400, detail="Action must be 'approve' or 'reject'")

        if clean_action == "reject":
            conn.execute(
                "UPDATE artist_claims SET status = 'rejected', reviewed_at = datetime('now') WHERE id = ?",
                (claim_id,)
            )
            conn.commit()
            return {"success": True, "status": "rejected"}

        user_id = int(claim["user_id"])
        # Clear any old claim ownership on this user first just in case
        conn.execute("UPDATE artists SET claimed_by_user_id = NULL WHERE claimed_by_user_id = ?", (user_id,))

        target_artist_id = claim["artist_id"]
        if target_artist_id is not None:
            art = conn.execute("SELECT * FROM artists WHERE id = ?", (int(target_artist_id),)).fetchone()
            if not art:
                target_artist_id = get_or_create_artist(conn, claim["requested_name"])
        else:
            target_artist_id = get_or_create_artist(conn, claim["requested_name"])

        conn.execute(
            """
            UPDATE artists
            SET claimed_by_user_id = ?, is_verified = 1, updated_at = datetime('now')
            WHERE id = ?
            """,
            (user_id, int(target_artist_id))
        )
        conn.execute(
            """
            UPDATE artist_claims
            SET status = 'approved', artist_id = ?, reviewed_at = datetime('now')
            WHERE id = ?
            """,
            (int(target_artist_id), claim_id)
        )
        conn.commit()
        updated_art = conn.execute("SELECT * FROM artists WHERE id = ?", (int(target_artist_id),)).fetchone()
        return {
            "success": True,
            "status": "approved",
            "artist": _serialize_artist_row(conn, updated_art, base_url),
        }
    finally:
        conn.close()


# ---------------- 6. CREATE & UPDATE ARTIST PROFILES ----------------

@router.post("")
async def create_artist_profile(
    request: Request,
    name: str = Form(...),
    bio: str = Form(""),
    is_verified: int = Form(0),
    claimed_by_user_id: Optional[str] = Form(None),
    avatar: Optional[UploadFile] = File(None),
    banner: Optional[UploadFile] = File(None),
):
    conn = get_db()
    try:
        base_url = get_base_url(request)
        clean_name = " ".join((name or "").strip().split())
        if not clean_name:
            raise HTTPException(status_code=400, detail="Artist name is required")

        dup = conn.execute("SELECT id FROM artists WHERE name = ? COLLATE NOCASE", (clean_name,)).fetchone()
        if dup:
            raise HTTPException(status_code=400, detail=f"Artist '{clean_name}' already exists.")

        avatar_path = None
        banner_path = None
        if avatar and avatar.filename:
            raw = await avatar.read()
            if raw:
                avatar_path = _save_sanitized_artist_image(raw, avatar.filename, "avatar")
        if banner and banner.filename:
            raw = await banner.read()
            if raw:
                banner_path = _save_sanitized_artist_image(raw, banner.filename, "banner")

        uid_val = None
        if claimed_by_user_id and str(claimed_by_user_id).strip().isdigit():
            uid_int = int(str(claimed_by_user_id).strip())
            if uid_int > 0:
                conn.execute("UPDATE artists SET claimed_by_user_id = NULL WHERE claimed_by_user_id = ?", (uid_int,))
                uid_val = uid_int

        cur = conn.execute(
            """
            INSERT INTO artists (name, bio, avatar_path, banner_path, is_verified, claimed_by_user_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
            """,
            (clean_name, (bio or "").strip(), avatar_path, banner_path, 1 if int(is_verified or 0) else 0, uid_val)
        )
        conn.commit()
        row = conn.execute("SELECT * FROM artists WHERE id = ?", (int(cur.lastrowid),)).fetchone()
        return {"success": True, "artist": _serialize_artist_row(conn, row, base_url)}
    finally:
        conn.close()


@router.post("/{artist_id}/update")
async def update_artist_profile(
    artist_id: int,
    request: Request,
    name: Optional[str] = Form(None),
    bio: Optional[str] = Form(None),
    is_verified: Optional[int] = Form(None),
    claimed_by_user_id: Optional[str] = Form(None),
    requester_user_id: Optional[int] = Form(None),
    avatar: Optional[UploadFile] = File(None),
    banner: Optional[UploadFile] = File(None),
):
    conn = get_db()
    try:
        base_url = get_base_url(request)
        existing = conn.execute("SELECT * FROM artists WHERE id = ?", (artist_id,)).fetchone()
        if not existing:
            raise HTTPException(status_code=404, detail="Artist not found")

        # Enforce strict user segregation when called from in-app Artist Studio
        if requester_user_id is not None:
            owner_uid = existing["claimed_by_user_id"]
            if owner_uid is None or int(owner_uid) != int(requester_user_id):
                raise HTTPException(
                    status_code=403,
                    detail="Only the verified user who claimed this artist profile can edit it."
                )

        new_name = existing["name"]
        name_changed = False
        if name is not None and " ".join(name.strip().split()):
            candidate = " ".join(name.strip().split())
            dup = conn.execute(
                "SELECT id FROM artists WHERE name = ? COLLATE NOCASE AND id != ?",
                (candidate, artist_id)
            ).fetchone()
            if dup:
                raise HTTPException(status_code=400, detail=f"Another artist already uses '{candidate}'.")
            if candidate != existing["name"]:
                new_name = candidate
                name_changed = True

        new_bio = (bio.strip() if bio is not None else (existing["bio"] or ""))
        new_verified = int(existing["is_verified"] or 0)
        if is_verified is not None and requester_user_id is None:
            new_verified = 1 if int(is_verified) else 0

        new_claimed_uid = existing["claimed_by_user_id"]
        if claimed_by_user_id is not None and requester_user_id is None:
            raw_uid = str(claimed_by_user_id).strip()
            if raw_uid == "" or raw_uid == "0" or raw_uid.lower() == "none":
                new_claimed_uid = None
            elif raw_uid.isdigit():
                uid_int = int(raw_uid)
                conn.execute(
                    "UPDATE artists SET claimed_by_user_id = NULL WHERE claimed_by_user_id = ? AND id != ?",
                    (uid_int, artist_id)
                )
                new_claimed_uid = uid_int

        new_avatar = existing["avatar_path"]
        new_banner = existing["banner_path"]
        if avatar and avatar.filename:
            raw = await avatar.read()
            if raw:
                new_avatar = _save_sanitized_artist_image(raw, avatar.filename, "avatar")
        if banner and banner.filename:
            raw = await banner.read()
            if raw:
                new_banner = _save_sanitized_artist_image(raw, banner.filename, "banner")

        conn.execute(
            """
            UPDATE artists
            SET name = ?, bio = ?, avatar_path = ?, banner_path = ?,
                is_verified = ?, claimed_by_user_id = ?, updated_at = datetime('now')
            WHERE id = ?
            """,
            (new_name, new_bio, new_avatar, new_banner, new_verified, new_claimed_uid, artist_id)
        )

        if name_changed:
            song_rows = conn.execute(
                "SELECT DISTINCT song_id FROM song_artists WHERE artist_id = ?",
                (artist_id,)
            ).fetchall()
            for sr in song_rows:
                sync_song_display_artist(conn, int(sr["song_id"]))

        conn.commit()
        updated = conn.execute("SELECT * FROM artists WHERE id = ?", (artist_id,)).fetchone()
        return {"success": True, "artist": _serialize_artist_row(conn, updated, base_url)}
    finally:
        conn.close()


# ---------------- 7. FULL ARTIST PROFILE DETAIL + DISCOGRAPHY ----------------

@router.get("/{artist_id}")
def get_artist_detail(artist_id: int, request: Request):
    conn = get_db()
    try:
        base_url = get_base_url(request)
        art_row = conn.execute("SELECT * FROM artists WHERE id = ?", (artist_id,)).fetchone()
        if not art_row:
            raise HTTPException(status_code=404, detail="Artist not found")

        artist_dto = _serialize_artist_row(conn, art_row, base_url)

        primary_rows = conn.execute(
            """
            SELECT s.*
            FROM songs s
            JOIN song_artists sa ON sa.song_id = s.id
            WHERE sa.artist_id = ? AND sa.role = 'primary'
            ORDER BY s.id DESC
            """,
            (artist_id,)
        ).fetchall()

        featured_rows = conn.execute(
            """
            SELECT s.*
            FROM songs s
            JOIN song_artists sa ON sa.song_id = s.id
            WHERE sa.artist_id = ? AND sa.role = 'featured'
            ORDER BY s.id DESC
            """,
            (artist_id,)
        ).fetchall()

        primary_songs = attach_artists_to_song_dicts(
            conn, [_song_row_to_base_dict(r, base_url) for r in primary_rows], base_url
        )
        featured_songs = attach_artists_to_song_dicts(
            conn, [_song_row_to_base_dict(r, base_url) for r in featured_rows], base_url
        )

        # Combine deduplicated catalog for Play / Shuffle & Popular Songs ranking
        all_map = {}
        for s in primary_songs + featured_songs:
            if s["id"] not in all_map:
                all_map[s["id"]] = s
        all_songs = list(all_map.values())

        # Compute play counts per song to rank Popular Songs accurately
        play_counts = {}
        if all_songs:
            sids = [s["id"] for s in all_songs]
            ph = ",".join("?" for _ in sids)
            usp = conn.execute(
                f"SELECT song_id, COALESCE(SUM(play_count), 0) AS cnt FROM user_song_plays WHERE song_id IN ({ph}) GROUP BY song_id",
                sids
            ).fetchall()
            lh = conn.execute(
                f"""
                SELECT song_id, COUNT(*) AS cnt
                FROM listening_history
                WHERE song_id IN ({ph})
                  AND (COALESCE(ms_played, 0) >= 30000 OR COALESCE(listened_seconds, 0) >= 30)
                GROUP BY song_id
                """,
                sids
            ).fetchall()
            for r in usp:
                play_counts[int(r["song_id"])] = int(r["cnt"] or 0)
            for r in lh:
                sid = int(r["song_id"])
                play_counts[sid] = max(play_counts.get(sid, 0), int(r["cnt"] or 0))

        for s in all_songs:
            s["play_count"] = play_counts.get(s["id"], 0)
        for s in primary_songs:
            s["play_count"] = play_counts.get(s["id"], 0)
        for s in featured_songs:
            s["play_count"] = play_counts.get(s["id"], 0)

        popular_songs = sorted(
            all_songs,
            key=lambda x: (x.get("play_count", 0), x["id"]),
            reverse=True
        )[:5]

        return {
            "artist": artist_dto,
            "popular_songs": popular_songs,
            "primary_songs": primary_songs,
            "featured_songs": featured_songs,
            "all_songs": all_songs,
        }
    finally:
        conn.close()
