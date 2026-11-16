from fastapi import APIRouter, Request, HTTPException
from pydantic import BaseModel
from typing import List, Optional
from datetime import datetime, timezone
import os
import re
import traceback
from database import get_db, rollup_and_clean_listen_times

router = APIRouter(prefix="/history", tags=["history"])

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
MIN_MS_FOR_PLAY = 30_000


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


def canonical_artist_id(artist_name: Optional[str]) -> str:
    cleaned = " ".join((artist_name or "").strip().lower().split())
    return cleaned if cleaned else "unknown artist"


def build_cover_url(base_url: str, cover_path: Optional[str]) -> str:
    if not cover_path:
        return ""
    normalized = cover_path.replace(os.sep, "/").lstrip("/")
    if normalized.startswith("http://") or normalized.startswith("https://"):
        return normalized
    return f"{base_url}/{normalized}"


# ---------------- DATA MODELS ----------------

class HistoryEventDto(BaseModel):
    song_id: int
    listened_seconds: int = 0
    ms_played: Optional[int] = None
    played_at: int  # Epoch timestamp in milliseconds (when track started)
    local_date: Optional[str] = None  # Optional YYYY-MM-DD in device local timezone


class HistorySyncRequest(BaseModel):
    user_id: int
    events: List[HistoryEventDto] = []


# ---------------- 1. GET SOUND CAPSULE DATA (UNIFIED REAL SERVER FEED) ----------------
# Combines listening_history PlayEvents + songs artwork/metadata + daily & monthly listen-time tables.

@router.get("/sound-capsule/{user_id}")
def get_sound_capsule_data(user_id: int, request: Request):
    conn = get_db()
    try:
        rollup_and_clean_listen_times(conn)
        base_url = get_base_url(request)

        history_rows = conn.execute("""
            SELECT lh.song_id, lh.listened_seconds, lh.ms_played, lh.played_at,
                   s.title, s.artist, s.cover_path, s.duration,
                   a.id AS primary_artist_id,
                   a.name AS primary_artist_name,
                   a.avatar_path AS primary_artist_avatar
            FROM listening_history lh
            LEFT JOIN songs s ON lh.song_id = s.id
            LEFT JOIN song_artists sa ON sa.song_id = s.id AND sa.role = 'primary'
            LEFT JOIN artists a ON a.id = sa.artist_id
            WHERE lh.user_id = ?
            ORDER BY lh.played_at ASC
        """, (user_id,)).fetchall()

        events = []
        months_with_events = set()

        for r in history_rows:
            song_id = r["song_id"]
            raw_ms = r["ms_played"] if ("ms_played" in r.keys() and r["ms_played"] is not None) else 0
            raw_sec = r["listened_seconds"] or 0
            dur_sec = float(r["duration"]) if r["duration"] else 0.0

            if raw_ms and raw_ms > 0:
                ms_played = int(raw_ms)
            elif raw_sec > 0:
                ms_played = int(raw_sec) * 1000
            elif dur_sec > 0:
                ms_played = int(round(dur_sec * 1000))
            else:
                ms_played = 0

            if ms_played <= 0:
                continue

            played_at = int(r["played_at"] or 0)
            if played_at <= 0:
                continue

            try:
                dt = datetime.fromtimestamp(played_at / 1000.0, tz=timezone.utc)
                months_with_events.add(f"{dt.year:04d}-{dt.month:02d}")
            except Exception:
                pass

            prim_id = r["primary_artist_id"]
            prim_name = (r["primary_artist_name"] or r["artist"] or "Unknown Artist").strip() or "Unknown Artist"
            track_title = (r["title"] or "Unknown Song").strip() or "Unknown Song"
            cover_url = build_cover_url(base_url, r["cover_path"]) or None
            artist_art_url = build_cover_url(base_url, r["primary_artist_avatar"]) or cover_url

            events.append({
                "song_id": song_id,
                "track_id": str(song_id),
                "track_title": track_title,
                "artist_id": str(prim_id) if prim_id is not None else canonical_artist_id(prim_name),
                "artist_name": prim_name,
                "artwork_url": cover_url,
                "artist_artwork_url": artist_art_url,
                "duration": dur_sec,
                "started_at_millis": played_at,
                "ms_played": ms_played
            })

        # Fetch active-month daily listen time rows
        daily_rows = conn.execute("""
            SELECT date, seconds
            FROM user_daily_listen_time
            WHERE user_id = ?
            ORDER BY date ASC
        """, (user_id,)).fetchall()

        daily_listen_time = [
            {
                "date": r["date"],
                "seconds": int(r["seconds"] or 0),
                "minutes": round((r["seconds"] or 0) / 60.0)
            }
            for r in daily_rows
        ]

        # Fetch monthly listen time rows across all recorded months
        monthly_rows = conn.execute("""
            SELECT year, month, total_seconds
            FROM user_monthly_listen_time
            WHERE user_id = ?
            ORDER BY year ASC, month ASC
        """, (user_id,)).fetchall()

        monthly_listen_time = [
            {
                "year": int(r["year"]),
                "month": int(r["month"]),
                "total_seconds": int(r["total_seconds"] or 0),
                "minutes": round((r["total_seconds"] or 0) / 60.0)
            }
            for r in monthly_rows
        ]

        # Reconcile legacy server months that have rolled-up seconds in user_monthly_listen_time
        # (or user_daily_listen_time) when listening_history had no rows for that month, using
        # the user's real played songs from user_song_plays if available.
        available_months = set(months_with_events)
        for d in daily_listen_time:
            if d["seconds"] > 0 and len(d["date"]) >= 7:
                available_months.add(d["date"][:7])

        for m in monthly_listen_time:
            ym_str = f"{m['year']:04d}-{m['month']:02d}"
            if m["total_seconds"] > 0:
                available_months.add(ym_str)
                if ym_str not in months_with_events:
                    top_year_song = conn.execute("""
                        SELECT usp.song_id, s.title, s.artist, s.cover_path, s.duration,
                               a.id AS primary_artist_id,
                               a.name AS primary_artist_name,
                               a.avatar_path AS primary_artist_avatar
                        FROM user_song_plays usp
                        INNER JOIN songs s ON usp.song_id = s.id
                        LEFT JOIN song_artists sa ON sa.song_id = s.id AND sa.role = 'primary'
                        LEFT JOIN artists a ON a.id = sa.artist_id
                        WHERE usp.user_id = ? AND usp.year = ?
                        ORDER BY usp.play_count DESC
                        LIMIT 1
                    """, (user_id, m["year"])).fetchone()
                    if top_year_song:
                        dt_mid = datetime(m["year"], m["month"], 15, 12, 0, 0, tzinfo=timezone.utc)
                        synth_ts = int(dt_mid.timestamp() * 1000)
                        prim_id = top_year_song["primary_artist_id"]
                        prim_name = (top_year_song["primary_artist_name"] or top_year_song["artist"] or "Unknown Artist").strip() or "Unknown Artist"
                        cover_url = build_cover_url(base_url, top_year_song["cover_path"]) or None
                        artist_art_url = build_cover_url(base_url, top_year_song["primary_artist_avatar"]) or cover_url
                        events.append({
                            "song_id": top_year_song["song_id"],
                            "track_id": str(top_year_song["song_id"]),
                            "track_title": (top_year_song["title"] or "Unknown Song").strip() or "Unknown Song",
                            "artist_id": str(prim_id) if prim_id is not None else canonical_artist_id(prim_name),
                            "artist_name": prim_name,
                            "artwork_url": cover_url,
                            "artist_artwork_url": artist_art_url,
                            "duration": float(top_year_song["duration"]) if top_year_song["duration"] else 0.0,
                            "started_at_millis": synth_ts,
                            "ms_played": int(m["total_seconds"]) * 1000
                        })
                        months_with_events.add(ym_str)

        events.sort(key=lambda item: item["started_at_millis"])

        return {
            "user_id": user_id,
            "events": events,
            "daily_listen_time": daily_listen_time,
            "monthly_listen_time": monthly_listen_time,
            "available_months": sorted(available_months, reverse=True)
        }
    finally:
        conn.close()


# ---------------- 2. SYNC HISTORY FROM CLIENT ----------------
# Idempotent per (user_id, song_id, played_at): updates ms_played & listened_seconds in-place,
# adds only delta seconds to user_daily_listen_time, and counts plays >= 30s once per session.

@router.post("/sync")
def sync_history(data: HistorySyncRequest):
    if not data.user_id:
        raise HTTPException(status_code=400, detail="User ID is required")

    conn = get_db()
    synced_count = 0
    try:
        conn.execute("PRAGMA foreign_keys = OFF")

        for ev in data.events:
            try:
                try:
                    ev_dt = datetime.fromtimestamp(ev.played_at / 1000.0, tz=timezone.utc)
                except Exception:
                    ev_dt = datetime.now(timezone.utc)

                if ev.local_date and DATE_RE.match(ev.local_date):
                    date_str = ev.local_date
                    year = int(date_str[:4])
                else:
                    date_str = ev_dt.strftime("%Y-%m-%d")
                    year = ev_dt.year

                raw_sec = max(0, int(ev.listened_seconds or 0))
                if ev.ms_played is not None:
                    effective_ms = max(0, int(ev.ms_played))
                    effective_sec = max(raw_sec, int(round(effective_ms / 1000.0)))
                else:
                    effective_sec = raw_sec
                    effective_ms = effective_sec * 1000

                existing = conn.execute("""
                    SELECT id, listened_seconds, ms_played
                    FROM listening_history
                    WHERE user_id = ? AND song_id = ? AND played_at = ?
                """, (data.user_id, ev.song_id, ev.played_at)).fetchone()

                if existing:
                    prev_sec = int(existing["listened_seconds"] or 0)
                    prev_ms_col = existing["ms_played"] if ("ms_played" in existing.keys() and existing["ms_played"] is not None) else 0
                    prev_ms = int(prev_ms_col) if int(prev_ms_col) > 0 else (prev_sec * 1000)

                    new_sec = max(prev_sec, effective_sec)
                    new_ms = max(prev_ms, effective_ms)
                    delta_sec = max(0, new_sec - prev_sec)
                    crossed_play_threshold = (prev_ms < MIN_MS_FOR_PLAY <= new_ms)

                    if new_sec != prev_sec or new_ms != prev_ms:
                        conn.execute("""
                            UPDATE listening_history
                            SET listened_seconds = ?, ms_played = ?
                            WHERE id = ?
                        """, (new_sec, new_ms, existing["id"]))
                else:
                    delta_sec = effective_sec
                    crossed_play_threshold = (effective_ms >= MIN_MS_FOR_PLAY)
                    conn.execute("""
                        INSERT INTO listening_history (user_id, song_id, listened_seconds, ms_played, played_at)
                        VALUES (?, ?, ?, ?, ?)
                    """, (data.user_id, ev.song_id, effective_sec, effective_ms, ev.played_at))

                # A. Update daily listen time with delta seconds only (no double counting)
                if delta_sec > 0:
                    conn.execute("""
                        INSERT INTO user_daily_listen_time (user_id, date, seconds, updated_at)
                        VALUES (?, ?, ?, datetime('now'))
                        ON CONFLICT(user_id, date) DO UPDATE SET
                            seconds = seconds + EXCLUDED.seconds,
                            updated_at = datetime('now')
                    """, (data.user_id, date_str, delta_sec))

                # B. Increment yearly song play count once when >= 30s threshold is reached
                if crossed_play_threshold:
                    conn.execute("""
                        INSERT INTO user_song_plays (user_id, song_id, play_count, year, last_played_at)
                        VALUES (?, ?, 1, ?, datetime('now'))
                        ON CONFLICT(user_id, song_id, year) DO UPDATE SET
                            play_count = play_count + 1,
                            last_played_at = datetime('now')
                    """, (data.user_id, ev.song_id, year))

                synced_count += 1
            except Exception as ev_err:
                print(f"Warning: Failed to process history event for song {ev.song_id}: {ev_err}")

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
