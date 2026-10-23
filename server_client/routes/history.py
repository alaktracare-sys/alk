from fastapi import APIRouter, Request, HTTPException
from pydantic import BaseModel
from typing import List, Optional
from datetime import datetime, timezone
import os
import traceback
from database import get_db, rollup_and_clean_listen_times

router = APIRouter(prefix="/history", tags=["history"])

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


# ---------------- DATA MODELS ----------------

class HistoryEventDto(BaseModel):
    song_id: int
    listened_seconds: int = 0
    played_at: int  # Epoch timestamp in milliseconds


class HistorySyncRequest(BaseModel):
    user_id: int
    events: List[HistoryEventDto] = []


# ---------------- 1. GET TODAY'S LISTEN TIME ----------------
# Requirement: "get user listen time for today (save till the end of this month)."
# Requirement: "do not show the user the seconds in the history only hr and min."

@router.get("/today/{user_id}")
def get_today_listen_time(user_id: int):
    conn = get_db()
    try:
        # Check and perform any month-end or past-month rollup
        rollup_and_clean_listen_times(conn)

        today_str = datetime.utcnow().strftime("%Y-%m-%d")
        row = conn.execute("""
            SELECT seconds FROM user_daily_listen_time
            WHERE user_id = ? AND date = ?
        """, (user_id, today_str)).fetchone()

        today_sec = row["seconds"] if row and row["seconds"] else 0
        hrs = today_sec // 3600
        mins = (today_sec % 3600) // 60
        formatted = f"{hrs}h {mins}m" if hrs > 0 else f"{mins}m"

        return {
            "user_id": user_id,
            "date": today_str,
            "today_seconds": today_sec,
            "formatted": formatted,
            "hours": hrs,
            "minutes": mins
        }
    finally:
        conn.close()


# ---------------- 2. GET USER SONG PLAYS (PLAY COUNTS) ----------------
# Requirement: "get users plays like which song they played how many times not how much just the no of times. (save till end of year)."

@router.get("/plays/{user_id}")
def get_user_song_plays(user_id: int, request: Request, year: Optional[int] = None):
    if year is None:
        year = datetime.utcnow().year

    conn = get_db()
    try:
        rollup_and_clean_listen_times(conn)
        base_url = get_base_url(request)

        rows = conn.execute("""
            SELECT usp.song_id, usp.play_count, usp.year, usp.last_played_at,
                   s.title, s.artist, s.cover_path, s.duration
            FROM user_song_plays usp
            LEFT JOIN songs s ON usp.song_id = s.id
            WHERE usp.user_id = ? AND usp.year = ?
            ORDER BY usp.play_count DESC
        """, (user_id, year)).fetchall()

        data = [
            {
                "song_id": r["song_id"],
                "title": r["title"] or "Unknown Song",
                "artist": r["artist"] or "Unknown Artist",
                "cover_url": f"{base_url}/{r['cover_path'].replace(os.sep, '/')}" if r["cover_path"] else "",
                "play_count": r["play_count"],
                "year": r["year"],
                "last_played_at": r["last_played_at"]
            }
            for r in rows
        ]
        return data
    finally:
        conn.close()


# ---------------- 3. GET MONTHLY LISTEN TIME ----------------
# Requirement: "on the last day of month calculate the to total listen time of the user and save it till the end of year and also delete the per day listen time for that month after saving the total."

@router.get("/monthly/{user_id}")
def get_user_monthly_listen_time(user_id: int, year: Optional[int] = None):
    if year is None:
        year = datetime.utcnow().year

    conn = get_db()
    try:
        rollup_and_clean_listen_times(conn)

        rows = conn.execute("""
            SELECT year, month, total_seconds
            FROM user_monthly_listen_time
            WHERE user_id = ? AND year = ?
            ORDER BY month ASC
        """, (user_id, year)).fetchall()

        data = []
        for r in rows:
            tot = r["total_seconds"] or 0
            hrs = tot // 3600
            mins = (tot % 3600) // 60
            data.append({
                "year": r["year"],
                "month": r["month"],
                "total_seconds": tot,
                "formatted": f"{hrs}h {mins}m" if hrs > 0 else f"{mins}m",
                "hours": hrs,
                "minutes": mins
            })
        return data
    finally:
        conn.close()


# ---------------- 4. SYNC HISTORY FROM CLIENT ----------------
# Requirement: "make a way to send the history to sync after every 6 hrs and it the 6 hr mark is missed then whenever the user is online."

@router.post("/sync")
def sync_history(data: HistorySyncRequest):
    if not data.user_id:
        raise HTTPException(status_code=400, detail="User ID is required")

    conn = get_db()
    synced_count = 0
    try:
        # Prevent any foreign key issues if user or songs table is being populated or modified
        conn.execute("PRAGMA foreign_keys = OFF")

        for ev in data.events:
            try:
                try:
                    ev_dt = datetime.fromtimestamp(ev.played_at / 1000.0, tz=timezone.utc)
                except Exception:
                    ev_dt = datetime.now(timezone.utc)

                date_str = ev_dt.strftime("%Y-%m-%d")
                year = ev_dt.year
                listened_sec = max(0, int(ev.listened_seconds))

                # A. Update daily listen time (saved till end of this month)
                if listened_sec > 0:
                    conn.execute("""
                        INSERT INTO user_daily_listen_time (user_id, date, seconds, updated_at)
                        VALUES (?, ?, ?, datetime('now'))
                        ON CONFLICT(user_id, date) DO UPDATE SET
                            seconds = seconds + EXCLUDED.seconds,
                            updated_at = datetime('now')
                    """, (data.user_id, date_str, listened_sec))

                # B. Increment user song play count for this year (saved till end of year)
                conn.execute("""
                    INSERT INTO user_song_plays (user_id, song_id, play_count, year, last_played_at)
                    VALUES (?, ?, 1, ?, datetime('now'))
                    ON CONFLICT(user_id, song_id, year) DO UPDATE SET
                        play_count = play_count + 1,
                        last_played_at = datetime('now')
                """, (data.user_id, ev.song_id, year))

                # C. Save event into server listening_history (for fetching when logging in)
                conn.execute("""
                    INSERT OR IGNORE INTO listening_history (user_id, song_id, listened_seconds, played_at)
                    VALUES (?, ?, ?, ?)
                """, (data.user_id, ev.song_id, listened_sec, ev.played_at))

                synced_count += 1
            except Exception as ev_err:
                print(f"Warning: Failed to process history event for song {ev.song_id}: {ev_err}")

        # Check and process monthly rollups and year cleanup
        try:
            rollup_and_clean_listen_times(conn)
        except Exception as rollup_err:
            print(f"Warning: Rollup failed during sync: {rollup_err}")

        conn.commit()
    except Exception as e:
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Failed to sync history: {str(e)}")
    finally:
        conn.close()

    return {
        "success": True,
        "message": f"Successfully synced {synced_count} history events",
        "synced_count": synced_count
    }


# ---------------- 5. FETCH HISTORY ON LOGIN ----------------
# Requirement: "if the user logging in is already registered try fetching history data from the server."

@router.get("/{user_id}")
def get_user_history(user_id: int, request: Request, limit: int = 100):
    conn = get_db()
    try:
        base_url = get_base_url(request)
        rows = conn.execute("""
            SELECT lh.song_id, lh.listened_seconds, lh.played_at,
                   s.title, s.artist, s.audio_path, s.cover_path, s.duration
            FROM listening_history lh
            LEFT JOIN songs s ON lh.song_id = s.id
            WHERE lh.user_id = ?
            ORDER BY lh.played_at DESC
            LIMIT ?
        """, (user_id, limit)).fetchall()

        data = [
            {
                "song_id": r["song_id"],
                "title": r["title"] or "Unknown Song",
                "artist": r["artist"] or "Unknown Artist",
                "audio_url": f"{base_url}/songs/{r['song_id']}/stream" if r["song_id"] else "",
                "cover_url": f"{base_url}/{r['cover_path'].replace(os.sep, '/')}" if r["cover_path"] else "",
                "duration": float(r["duration"]) if r["duration"] else 0.0,
                "listened_seconds": r["listened_seconds"] or 0,
                "played_at": r["played_at"]
            }
            for r in rows
        ]
        return data
    finally:
        conn.close()


# ---------------- 6. MANUAL / SCHEDULED ROLLUP TRIGGER ----------------

@router.post("/rollup")
def trigger_monthly_rollup():
    conn = get_db()
    try:
        rollup_and_clean_listen_times(conn)
        return {
            "success": True,
            "message": "Monthly rollup and yearly cleanup completed successfully"
        }
    finally:
        conn.close()
