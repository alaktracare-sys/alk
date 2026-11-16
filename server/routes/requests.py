import time
import threading
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel
from typing import Optional
from database import get_db

router = APIRouter(prefix="/song-requests", tags=["song-requests"])

_RATE_LOCK = threading.Lock()
_IP_HITS: dict[str, list[float]] = {}
RATE_WINDOW_SEC = 600  # 10 minutes
RATE_MAX_HITS = 15


class SongRequestSubmit(BaseModel):
    song_name: str
    artist_name: str
    requester_name: Optional[str] = ""
    note: Optional[str] = ""


def _client_ip(request: Request) -> str:
    cf_ip = request.headers.get("cf-connecting-ip")
    if cf_ip:
        return cf_ip.strip()
    xff = request.headers.get("x-forwarded-for")
    if xff:
        return xff.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


@router.post("/submit")
def submit_song_request(payload: SongRequestSubmit, request: Request):
    song_name = " ".join((payload.song_name or "").strip().split())
    artist_name = " ".join((payload.artist_name or "").strip().split())
    requester_name = " ".join((payload.requester_name or "").strip().split())
    note = " ".join((payload.note or "").strip().split())

    if not song_name:
        raise HTTPException(status_code=400, detail="Please enter the song name.")
    if not artist_name:
        raise HTTPException(status_code=400, detail="Please enter the artist name.")
    if len(song_name) > 120:
        raise HTTPException(status_code=400, detail="Song name is too long (max 120 characters).")
    if len(artist_name) > 120:
        raise HTTPException(status_code=400, detail="Artist name is too long (max 120 characters).")
    if len(requester_name) > 80:
        requester_name = requester_name[:80]
    if len(note) > 300:
        note = note[:300]

    ip = _client_ip(request)
    now = time.time()
    with _RATE_LOCK:
        hits = [t for t in _IP_HITS.get(ip, []) if now - t < RATE_WINDOW_SEC]
        if len(hits) >= RATE_MAX_HITS:
            raise HTTPException(
                status_code=429,
                detail="Too many requests sent recently. Please try again in a few minutes."
            )
        hits.append(now)
        _IP_HITS[ip] = hits

    conn = get_db()
    try:
        cursor = conn.cursor()

        # Check if an identical pending request was just submitted recently
        existing = cursor.execute(
            """
            SELECT id, status FROM song_requests
            WHERE LOWER(song_name) = LOWER(?)
              AND LOWER(artist_name) = LOWER(?)
              AND status = 'pending'
            ORDER BY id DESC LIMIT 1
            """,
            (song_name, artist_name),
        ).fetchone()

        if existing:
            return {
                "success": True,
                "duplicate": True,
                "id": existing["id"],
                "song_name": song_name,
                "artist_name": artist_name,
                "message": "This song has already been requested and is in the queue!"
            }

        cursor.execute(
            """
            INSERT INTO song_requests (song_name, artist_name, requester_name, note, status)
            VALUES (?, ?, ?, ?, 'pending')
            """,
            (song_name, artist_name, requester_name, note),
        )
        req_id = cursor.lastrowid
        conn.commit()
        return {
            "success": True,
            "duplicate": False,
            "id": req_id,
            "song_name": song_name,
            "artist_name": artist_name,
            "message": f"Requested '{song_name}' by {artist_name}!"
        }
    finally:
        conn.close()


@router.get("/public")
def get_public_song_requests():
    conn = get_db()
    try:
        cursor = conn.cursor()
        rows = cursor.execute(
            """
            SELECT id, song_name, artist_name, requester_name, status, created_at
            FROM song_requests
            WHERE status IN ('pending', 'fulfilled')
            ORDER BY id DESC
            LIMIT 25
            """
        ).fetchall()

        pending_cnt = cursor.execute(
            "SELECT COUNT(*) FROM song_requests WHERE status = 'pending'"
        ).fetchone()[0]
        fulfilled_cnt = cursor.execute(
            "SELECT COUNT(*) FROM song_requests WHERE status = 'fulfilled'"
        ).fetchone()[0]

        return {
            "items": [dict(r) for r in rows],
            "counts": {
                "pending": pending_cnt,
                "fulfilled": fulfilled_cnt
            }
        }
    finally:
        conn.close()
