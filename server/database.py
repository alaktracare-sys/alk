import sqlite3
import os
import re

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(BASE_DIR, "music.db")
MUSIC_DIR = os.path.join(BASE_DIR, "music")
COVER_DIR = os.path.join(BASE_DIR, "covers")
PIN_DIR = os.path.join(BASE_DIR, "pins")
ARTIST_DIR = os.path.join(BASE_DIR, "artist_media")

os.makedirs(MUSIC_DIR, exist_ok=True)
os.makedirs(COVER_DIR, exist_ok=True)
os.makedirs(PIN_DIR, exist_ok=True)
os.makedirs(ARTIST_DIR, exist_ok=True)

def get_db():
    # timeout + busy_timeout: wait for a competing writer instead of failing
    # instantly with "database is locked" (the default wait is only 5 s).
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 30000")
    return conn

def init_db():
    conn = get_db()
    try:
        # WAL lets readers and one writer work at the same time (the API, the
        # admin console and history sync all share music.db). Persistent.
        try:
            conn.execute("PRAGMA journal_mode = WAL")
        except sqlite3.DatabaseError:
            pass
        _init_db_schema(conn)
        conn.commit()
    finally:
        conn.close()


def _init_db_schema(conn):
    cursor = conn.cursor()

    # Users Table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            email TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            otp_code TEXT,
            otp_expiry TEXT,
            is_verified INTEGER DEFAULT 0,
            created_at TEXT DEFAULT (datetime('now'))
        )
    """)

    # Songs Table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS songs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            artist TEXT NOT NULL,
            audio_path TEXT NOT NULL,
            cover_path TEXT NOT NULL,
            dominant_color TEXT DEFAULT NULL,
            etag TEXT,
            duration REAL DEFAULT 0.0,
            created_at TEXT DEFAULT (datetime('now')),
            updated_at TEXT DEFAULT (datetime('now'))
        )
    """)
    try:
        cursor.execute("ALTER TABLE songs ADD COLUMN dominant_color TEXT DEFAULT NULL")
    except Exception:
        pass

    # Playlists Table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS playlists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            name TEXT NOT NULL,
            is_system INTEGER DEFAULT 0,
            is_fuse INTEGER DEFAULT 0,
            fuse_id INTEGER DEFAULT NULL,
            created_at TEXT DEFAULT (datetime('now')),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        )
    """)
    try:
        cursor.execute("ALTER TABLE playlists ADD COLUMN is_fuse INTEGER DEFAULT 0")
    except Exception:
        pass
    try:
        cursor.execute("ALTER TABLE playlists ADD COLUMN fuse_id INTEGER DEFAULT NULL")
    except Exception:
        pass

    # Playlist Songs Table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS playlist_songs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            playlist_id INTEGER NOT NULL,
            song_id INTEGER NOT NULL,
            created_at TEXT DEFAULT (datetime('now')),
            FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
            FOREIGN KEY (song_id) REFERENCES songs(id) ON DELETE CASCADE,
            UNIQUE(playlist_id, song_id)
        )
    """)

    # Fuse 5-Minute Temporary Invites Table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS fuse_invites (
            code TEXT PRIMARY KEY,
            host_user_id INTEGER NOT NULL,
            host_username TEXT NOT NULL,
            created_at_ms INTEGER NOT NULL,
            expires_at_ms INTEGER NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            fuse_id INTEGER DEFAULT NULL
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_fuse_invites_expiry ON fuse_invites(status, expires_at_ms)")

    # Server-Curated Fuse (Spotify Blend) Table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS fuses (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user1_id INTEGER NOT NULL,
            user2_id INTEGER NOT NULL,
            user1_name TEXT NOT NULL,
            user2_name TEXT NOT NULL,
            name TEXT NOT NULL,
            match_score INTEGER NOT NULL DEFAULT 0,
            last_refreshed_date TEXT NOT NULL,
            created_at TEXT DEFAULT (datetime('now')),
            updated_at TEXT DEFAULT (datetime('now'))
        )
    """)

    # Server-Decided Fuse Songs Table with Contributor Attribution
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS fuse_songs (
            fuse_id INTEGER NOT NULL,
            song_id INTEGER NOT NULL,
            position INTEGER NOT NULL,
            contributor TEXT NOT NULL DEFAULT 'both',
            PRIMARY KEY (fuse_id, song_id),
            FOREIGN KEY (fuse_id) REFERENCES fuses(id) ON DELETE CASCADE,
            FOREIGN KEY (song_id) REFERENCES songs(id) ON DELETE CASCADE
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_fuse_songs_order ON fuse_songs(fuse_id, position ASC)")

    # User Daily Listen Time Table (kept till the end of the month)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS user_daily_listen_time (
            user_id INTEGER NOT NULL,
            date TEXT NOT NULL,
            seconds INTEGER DEFAULT 0,
            updated_at TEXT DEFAULT (datetime('now')),
            PRIMARY KEY (user_id, date)
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_daily_listen_date ON user_daily_listen_time(user_id, date)")

    # User Song Plays Table: play count per song per user per year (kept till end of year)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS user_song_plays (
            user_id INTEGER NOT NULL,
            song_id INTEGER NOT NULL,
            play_count INTEGER DEFAULT 0,
            year INTEGER NOT NULL,
            last_played_at TEXT DEFAULT (datetime('now')),
            PRIMARY KEY (user_id, song_id, year)
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_song_plays_user_year ON user_song_plays(user_id, year)")

    # User Monthly Listen Time Table: aggregated on last day of month, kept till end of year
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS user_monthly_listen_time (
            user_id INTEGER NOT NULL,
            year INTEGER NOT NULL,
            month INTEGER NOT NULL,
            total_seconds INTEGER DEFAULT 0,
            updated_at TEXT DEFAULT (datetime('now')),
            PRIMARY KEY (user_id, year, month)
        )
    """)

    # Server Listening History Table (synced playback history events for cross-device retrieval & Sound Capsule)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS listening_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            song_id INTEGER NOT NULL,
            listened_seconds INTEGER DEFAULT 0,
            ms_played INTEGER DEFAULT 0,
            played_at INTEGER NOT NULL,
            created_at TEXT DEFAULT (datetime('now')),
            UNIQUE(user_id, song_id, played_at)
        )
    """)
    try:
        cursor.execute("ALTER TABLE listening_history ADD COLUMN ms_played INTEGER DEFAULT 0")
    except Exception:
        pass
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_server_history_user ON listening_history(user_id, played_at DESC)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_server_history_user_asc ON listening_history(user_id, played_at ASC)")

    # Location Pins Table (expires at the end of the month)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS location_pins (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            song_id INTEGER NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            image_path TEXT NOT NULL,
            year INTEGER NOT NULL,
            month INTEGER NOT NULL,
            created_at TEXT DEFAULT (datetime('now')),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY (song_id) REFERENCES songs(id) ON DELETE CASCADE
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_location_pins_time ON location_pins(year, month)")

    # Segregated Artist Catalog Profiles Table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS artists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT UNIQUE NOT NULL COLLATE NOCASE,
            bio TEXT DEFAULT '',
            avatar_path TEXT DEFAULT NULL,
            banner_path TEXT DEFAULT NULL,
            is_verified INTEGER DEFAULT 0,
            claimed_by_user_id INTEGER UNIQUE DEFAULT NULL,
            created_at TEXT DEFAULT (datetime('now')),
            updated_at TEXT DEFAULT (datetime('now')),
            FOREIGN KEY (claimed_by_user_id) REFERENCES users(id) ON DELETE SET NULL
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_artists_name ON artists(name COLLATE NOCASE)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_artists_claimed_user ON artists(claimed_by_user_id)")

    # Device Tokens for Push Notifications
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS user_device_tokens (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER DEFAULT NULL,
            fcm_token TEXT UNIQUE NOT NULL,
            device_info TEXT DEFAULT '',
            created_at TEXT DEFAULT (datetime('now')),
            updated_at TEXT DEFAULT (datetime('now'))
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_device_tokens_user ON user_device_tokens(user_id)")

    # Sent Notifications Log Table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS sent_notifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            message TEXT NOT NULL,
            recipient_type TEXT DEFAULT 'all',
            target_user_ids TEXT DEFAULT NULL,
            target_usernames TEXT DEFAULT NULL,
            sent_count INTEGER DEFAULT 0,
            created_at TEXT DEFAULT (datetime('now'))
        )
    """)
    try:
        cursor.execute("ALTER TABLE sent_notifications ADD COLUMN target_user_ids TEXT DEFAULT NULL")
    except Exception:
        pass
    try:
        cursor.execute("ALTER TABLE sent_notifications ADD COLUMN target_usernames TEXT DEFAULT NULL")
    except Exception:
        pass

    # Multi-Artist Song Allotment Table ('primary' vs 'featured')
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS song_artists (
            song_id INTEGER NOT NULL,
            artist_id INTEGER NOT NULL,
            role TEXT NOT NULL DEFAULT 'primary',
            position INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (song_id, artist_id),
            FOREIGN KEY (song_id) REFERENCES songs(id) ON DELETE CASCADE,
            FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_song_artists_artist ON song_artists(artist_id, role)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_song_artists_song_pos ON song_artists(song_id, position ASC)")

    # Artist Profile Claims Table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS artist_claims (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            artist_id INTEGER DEFAULT NULL,
            requested_name TEXT NOT NULL,
            note TEXT DEFAULT '',
            status TEXT NOT NULL DEFAULT 'pending',
            created_at TEXT DEFAULT (datetime('now')),
            reviewed_at TEXT DEFAULT NULL,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_artist_claims_user ON artist_claims(user_id, status)")
    # Temporary Real-Time Synchronized Listening Clubs
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS clubs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code TEXT UNIQUE NOT NULL,
            name TEXT NOT NULL,
            owner_user_id INTEGER NOT NULL,
            owner_username TEXT NOT NULL,
            current_song_id INTEGER DEFAULT NULL,
            is_playing INTEGER DEFAULT 0,
            position_ms INTEGER DEFAULT 0,
            updated_at_ms INTEGER NOT NULL,
            revision INTEGER DEFAULT 0,
            created_at TEXT DEFAULT (datetime('now'))
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_clubs_code ON clubs(code)")
    try:
        cursor.execute("ALTER TABLE clubs ADD COLUMN revision INTEGER DEFAULT 0")
    except Exception:
        pass

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS club_members (
            club_id INTEGER NOT NULL,
            user_id INTEGER NOT NULL,
            device_id TEXT NOT NULL DEFAULT 'default',
            username TEXT NOT NULL,
            last_active_ms INTEGER NOT NULL,
            PRIMARY KEY (club_id, user_id, device_id),
            FOREIGN KEY (club_id) REFERENCES clubs(id) ON DELETE CASCADE
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_club_members_active ON club_members(club_id, last_active_ms)")
    try:
        cursor.execute("ALTER TABLE club_members ADD COLUMN device_id TEXT DEFAULT 'default'")
    except Exception:
        pass

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS club_songs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            club_id INTEGER NOT NULL,
            song_id INTEGER NOT NULL,
            added_by_user_id INTEGER NOT NULL,
            added_by_username TEXT NOT NULL,
            position INTEGER NOT NULL,
            added_at TEXT DEFAULT (datetime('now')),
            FOREIGN KEY (club_id) REFERENCES clubs(id) ON DELETE CASCADE,
            FOREIGN KEY (song_id) REFERENCES songs(id) ON DELETE CASCADE,
            UNIQUE(club_id, song_id)
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_club_songs_pos ON club_songs(club_id, position ASC)")

    # Public Song Requests Table (submitted from alaktracare.in)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS song_requests (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            song_name TEXT NOT NULL,
            artist_name TEXT NOT NULL,
            requester_name TEXT DEFAULT '',
            note TEXT DEFAULT '',
            status TEXT NOT NULL DEFAULT 'pending',
            created_at TEXT DEFAULT (datetime('now'))
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_song_requests_status ON song_requests(status, id DESC)")

    # Daily Personalized Random Playlists (5 playlists x 30 songs per user per day)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS daily_playlists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            playlist_date TEXT NOT NULL,
            slot_index INTEGER NOT NULL,
            name TEXT NOT NULL,
            subtitle TEXT NOT NULL DEFAULT '',
            accent_hex TEXT NOT NULL DEFAULT '#00E599',
            created_at TEXT DEFAULT (datetime('now')),
            UNIQUE(user_id, playlist_date, slot_index),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_daily_playlists_user_date ON daily_playlists(user_id, playlist_date, slot_index ASC)")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS daily_playlist_songs (
            daily_playlist_id INTEGER NOT NULL,
            song_id INTEGER NOT NULL,
            position INTEGER NOT NULL,
            PRIMARY KEY (daily_playlist_id, song_id),
            FOREIGN KEY (daily_playlist_id) REFERENCES daily_playlists(id) ON DELETE CASCADE,
            FOREIGN KEY (song_id) REFERENCES songs(id) ON DELETE CASCADE
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_daily_playlist_songs_pos ON daily_playlist_songs(daily_playlist_id, position ASC)")

    migrate_existing_song_artists(conn)


_ARTIST_SPLIT_RE = re.compile(
    r"\s*(?:,|&|\bfeat(?:\.|\b)|\bft(?:\.|\b)|\bfeaturing\b|\bx\b(?=\s+[A-Z0-9]))\s*",
    flags=re.IGNORECASE,
)


def split_artist_credits(raw_artist_str: str):
    """Splits a legacy credit string (e.g. 'Tegi Pannu, Money Musik') into ordered unique artist names."""
    if not raw_artist_str:
        return ["Unknown Artist"]
    parts = [p.strip() for p in _ARTIST_SPLIT_RE.split(str(raw_artist_str)) if p and p.strip()]
    seen = set()
    ordered = []
    for p in parts:
        norm = " ".join(p.split())
        key = norm.lower()
        if norm and key not in seen:
            seen.add(key)
            ordered.append(norm)
    return ordered if ordered else ["Unknown Artist"]


def build_asset_url(base_url: str = "https://api.alaktracare.in", rel_path: str = ""):
    if not rel_path:
        return ""
    if not base_url:
        base_url = "https://api.alaktracare.in"
    norm = str(rel_path).replace(os.sep, "/").lstrip("/")
    if norm.startswith("http://") or norm.startswith("https://"):
        return norm
    return f"{base_url.rstrip('/')}/{norm}"


def get_or_create_artist(conn, name: str, is_verified: int = 1) -> int:
    clean_name = " ".join((name or "").strip().split())
    if not clean_name:
        clean_name = "Unknown Artist"
    existing = conn.execute(
        "SELECT id FROM artists WHERE name = ? COLLATE NOCASE",
        (clean_name,)
    ).fetchone()
    if existing:
        return int(existing["id"])
    cur = conn.execute(
        """
        INSERT INTO artists (name, bio, is_verified, created_at, updated_at)
        VALUES (?, '', ?, datetime('now'), datetime('now'))
        """,
        (clean_name, is_verified)
    )
    return int(cur.lastrowid)


def sync_song_display_artist(conn, song_id: int) -> str:
    """Rebuilds songs.artist display credit from ordered song_artists rows so legacy clients stay in sync."""
    rows = conn.execute(
        """
        SELECT a.name
        FROM song_artists sa
        JOIN artists a ON a.id = sa.artist_id
        WHERE sa.song_id = ?
        ORDER BY CASE WHEN sa.role = 'primary' THEN 0 ELSE 1 END, sa.position ASC
        """,
        (song_id,)
    ).fetchall()
    if not rows:
        return "Unknown Artist"
    display = ", ".join(r["name"] for r in rows if r["name"])
    conn.execute(
        "UPDATE songs SET artist = ?, updated_at = datetime('now') WHERE id = ?",
        (display, song_id)
    )
    return display


def allot_song_artists(
    conn,
    song_id: int,
    primary_artist_id: int = None,
    primary_artist_name: str = None,
    featured_artist_ids=None,
    featured_artist_names=None,
    fallback_artist_string: str = None,
) -> str:
    """
    Assigns 1 primary artist + 0..N featured collaborating artists to song_id,
    replacing any prior song_artists rows for that song and updating songs.artist.
    """
    resolved_primary_id = None
    if primary_artist_id is not None and int(primary_artist_id) > 0:
        row = conn.execute("SELECT id FROM artists WHERE id = ?", (int(primary_artist_id),)).fetchone()
        if row:
            resolved_primary_id = int(row["id"])

    if resolved_primary_id is None and primary_artist_name and str(primary_artist_name).strip():
        resolved_primary_id = get_or_create_artist(conn, str(primary_artist_name))

    parsed_fallback_names = []
    if resolved_primary_id is None:
        parsed_fallback_names = split_artist_credits(fallback_artist_string or "")
        resolved_primary_id = get_or_create_artist(conn, parsed_fallback_names[0])

    featured_ids = []
    seen_ids = {resolved_primary_id}

    for fid in (featured_artist_ids or []):
        try:
            fid_int = int(fid)
        except (TypeError, ValueError):
            continue
        if fid_int > 0 and fid_int not in seen_ids:
            exists = conn.execute("SELECT id FROM artists WHERE id = ?", (fid_int,)).fetchone()
            if exists:
                seen_ids.add(fid_int)
                featured_ids.append(fid_int)

    extra_names = list(featured_artist_names or [])
    if not featured_artist_ids and not extra_names and len(parsed_fallback_names) > 1:
        extra_names = parsed_fallback_names[1:]

    for fname in extra_names:
        for split_name in split_artist_credits(fname):
            if split_name.lower() == "unknown artist" and len(extra_names) == 0:
                continue
            aid = get_or_create_artist(conn, split_name)
            if aid not in seen_ids:
                seen_ids.add(aid)
                featured_ids.append(aid)

    conn.execute("DELETE FROM song_artists WHERE song_id = ?", (song_id,))
    conn.execute(
        "INSERT INTO song_artists (song_id, artist_id, role, position) VALUES (?, ?, 'primary', 0)",
        (song_id, resolved_primary_id)
    )
    for idx, fid in enumerate(featured_ids, start=1):
        conn.execute(
            "INSERT OR IGNORE INTO song_artists (song_id, artist_id, role, position) VALUES (?, ?, 'featured', ?)",
            (song_id, fid, idx)
        )

    return sync_song_display_artist(conn, song_id)


def migrate_existing_song_artists(conn):
    """Non-destructively backfills relational artists + song_artists for any song missing allotments."""
    unmapped = conn.execute(
        """
        SELECT s.id, s.artist
        FROM songs s
        LEFT JOIN song_artists sa ON sa.song_id = s.id
        WHERE sa.song_id IS NULL
        """
    ).fetchall()
    for s in unmapped:
        allot_song_artists(
            conn,
            song_id=int(s["id"]),
            fallback_artist_string=s["artist"] or "Unknown Artist",
        )


def attach_artists_to_song_dicts(conn, song_dicts: list, base_url: str = "https://api.alaktracare.in") -> list:
    """Batch-enriches serialized song dicts with primary_artist_id and ordered artists credits."""
    if not base_url:
        base_url = "https://api.alaktracare.in"
    if not song_dicts:
        return song_dicts

    song_ids = [int(s["id"]) for s in song_dicts if s.get("id") is not None]
    if not song_ids:
        return song_dicts

    placeholders = ",".join("?" for _ in song_ids)
    rows = conn.execute(
        f"""
        SELECT
            sa.song_id,
            sa.artist_id,
            sa.role,
            sa.position,
            a.name,
            a.avatar_path,
            a.is_verified,
            (
                SELECT s2.cover_path
                FROM song_artists sa2
                JOIN songs s2 ON s2.id = sa2.song_id
                WHERE sa2.artist_id = a.id
                ORDER BY CASE WHEN sa2.role = 'primary' THEN 0 ELSE 1 END, s2.id DESC
                LIMIT 1
            ) AS fallback_cover_path
        FROM song_artists sa
        JOIN artists a ON a.id = sa.artist_id
        WHERE sa.song_id IN ({placeholders})
        ORDER BY sa.song_id ASC, CASE WHEN sa.role = 'primary' THEN 0 ELSE 1 END, sa.position ASC
        """,
        song_ids,
    ).fetchall()

    by_song = {}
    for r in rows:
        sid = int(r["song_id"])
        avatar_rel = r["avatar_path"] or r["fallback_cover_path"] or ""
        credit = {
            "id": int(r["artist_id"]),
            "name": r["name"],
            "role": r["role"] or "primary",
            "position": int(r["position"] or 0),
            "avatar_url": build_asset_url(base_url, avatar_rel) if avatar_rel else "",
            "is_verified": bool(int(r["is_verified"] or 0) == 1),
        }
        by_song.setdefault(sid, []).append(credit)

    for s in song_dicts:
        sid = int(s["id"])
        credits = by_song.get(sid, [])
        primary = next((c for c in credits if c["role"] == "primary"), credits[0] if credits else None)
        s["primary_artist_id"] = primary["id"] if primary else None
        s["primary_artist_name"] = primary["name"] if primary else s.get("artist", "Unknown Artist")
        s["primary_artist_avatar_url"] = primary["avatar_url"] if primary else s.get("cover_url", "")
        s["artists"] = credits
    return song_dicts

import calendar
from datetime import datetime, date

def is_last_day_of_month(target_date: date = None) -> bool:
    if target_date is None:
        target_date = datetime.utcnow().date()
    _, last_day = calendar.monthrange(target_date.year, target_date.month)
    return target_date.day == last_day

def rollup_and_clean_listen_times(conn=None):
    """
    1. Snapshots the running month's total into user_monthly_listen_time while keeping current-month
       user_daily_listen_time intact so Today / Day-by-Day queries stay accurate.
    2. For strictly past months (ym < current_ym), rolls up user_daily_listen_time into
       user_monthly_listen_time and deletes the past month's per-day rows.
       NOTE: User song plays (user_song_plays), session events (listening_history), and monthly
       listen-time summaries (user_monthly_listen_time) are preserved for Sound Capsule's multi-month feed.
    """
    close_conn = False
    if conn is None:
        conn = get_db()
        close_conn = True

    try:
        today = datetime.utcnow().date()
        current_year = today.year
        current_month = today.month
        current_ym = f"{current_year:04d}-{current_month:02d}"

        month_rows = conn.execute("""
            SELECT DISTINCT user_id, substr(date, 1, 7) as ym
            FROM user_daily_listen_time
        """).fetchall()

        for row in month_rows:
            user_id = row["user_id"]
            ym = row["ym"]
            if not ym or len(ym) < 7:
                continue

            is_past_month = ym < current_ym
            is_current_month = ym == current_ym
            if not is_past_month and not is_current_month:
                continue

            try:
                parts = ym.split("-")
                y = int(parts[0])
                m = int(parts[1])
            except ValueError:
                continue

            tot_row = conn.execute("""
                SELECT SUM(seconds) as total_sec
                FROM user_daily_listen_time
                WHERE user_id = ? AND substr(date, 1, 7) = ?
            """, (user_id, ym)).fetchone()

            month_total = tot_row["total_sec"] if tot_row and tot_row["total_sec"] else 0

            if is_past_month:
                if month_total > 0:
                    conn.execute("""
                        INSERT INTO user_monthly_listen_time (user_id, year, month, total_seconds, updated_at)
                        VALUES (?, ?, ?, ?, datetime('now'))
                        ON CONFLICT(user_id, year, month) DO UPDATE SET
                            total_seconds = MAX(user_monthly_listen_time.total_seconds, EXCLUDED.total_seconds),
                            updated_at = datetime('now')
                    """, (user_id, y, m, month_total))

                conn.execute("""
                    DELETE FROM user_daily_listen_time
                    WHERE user_id = ? AND substr(date, 1, 7) = ?
                """, (user_id, ym))
            elif is_current_month and month_total > 0:
                conn.execute("""
                    INSERT INTO user_monthly_listen_time (user_id, year, month, total_seconds, updated_at)
                    VALUES (?, ?, ?, ?, datetime('now'))
                    ON CONFLICT(user_id, year, month) DO UPDATE SET
                        total_seconds = MAX(user_monthly_listen_time.total_seconds, EXCLUDED.total_seconds),
                        updated_at = datetime('now')
                """, (user_id, y, m, month_total))

        conn.commit()
    except Exception as e:
        print(f"Error rolling up listen times: {e}")
    finally:
        if close_conn:
            conn.close()


if __name__ == "__main__":
    init_db()
