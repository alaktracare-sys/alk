import sqlite3
import os

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(BASE_DIR, "music.db")
MUSIC_DIR = os.path.join(BASE_DIR, "music")
COVER_DIR = os.path.join(BASE_DIR, "covers")
PIN_DIR = os.path.join(BASE_DIR, "pins")

os.makedirs(MUSIC_DIR, exist_ok=True)
os.makedirs(COVER_DIR, exist_ok=True)
os.makedirs(PIN_DIR, exist_ok=True)

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn

def init_db():
    conn = get_db()
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
            created_at TEXT DEFAULT (datetime('now')),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        )
    """)

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

    # Server Listening History Table (synced playback history events for cross-device retrieval)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS listening_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            song_id INTEGER NOT NULL,
            listened_seconds INTEGER DEFAULT 0,
            played_at INTEGER NOT NULL,
            created_at TEXT DEFAULT (datetime('now')),
            UNIQUE(user_id, song_id, played_at)
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_server_history_user ON listening_history(user_id, played_at DESC)")

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

    conn.commit()
    conn.close()

import calendar
from datetime import datetime, date

def is_last_day_of_month(target_date: date = None) -> bool:
    if target_date is None:
        target_date = datetime.utcnow().date()
    _, last_day = calendar.monthrange(target_date.year, target_date.month)
    return target_date.day == last_day

def rollup_and_clean_listen_times(conn=None):
    """
    1. On the last day of the month (or for any past months):
       Calculates the total listen time of the user for that month,
       saves it into user_monthly_listen_time,
       and deletes ONLY the per-day listen times (user_daily_listen_time) for that month after saving the total.
       NOTE: Only the times are deleted; the number of plays (user_song_plays) are NOT deleted.
    2. Keeps song plays preserved and prunes past years' monthly totals.
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
        today_is_last_day = is_last_day_of_month(today)

        # 1. Check months present in user_daily_listen_time
        month_rows = conn.execute("""
            SELECT DISTINCT user_id, substr(date, 1, 7) as ym
            FROM user_daily_listen_time
        """).fetchall()

        for row in month_rows:
            user_id = row["user_id"]
            ym = row["ym"]
            if not ym or len(ym) < 7:
                continue

            # Rollup if month is in the past, or if it is current month and today is last day of month
            should_rollup = (ym < current_ym) or (ym == current_ym and today_is_last_day)
            if not should_rollup:
                continue

            try:
                parts = ym.split("-")
                y = int(parts[0])
                m = int(parts[1])
            except ValueError:
                continue

            # Calculate total listen time of the user for that month
            tot_row = conn.execute("""
                SELECT SUM(seconds) as total_sec
                FROM user_daily_listen_time
                WHERE user_id = ? AND substr(date, 1, 7) = ?
            """, (user_id, ym)).fetchone()

            month_total = tot_row["total_sec"] if tot_row and tot_row["total_sec"] else 0

            if month_total > 0:
                conn.execute("""
                    INSERT INTO user_monthly_listen_time (user_id, year, month, total_seconds, updated_at)
                    VALUES (?, ?, ?, ?, datetime('now'))
                    ON CONFLICT(user_id, year, month) DO UPDATE SET
                        total_seconds = total_seconds + EXCLUDED.total_seconds,
                        updated_at = datetime('now')
                """, (user_id, y, m, month_total))

            # Delete ONLY the per day listen time for that month after saving the total.
            # The number of plays (user_song_plays) are NOT deleted in the monthly delete.
            conn.execute("""
                DELETE FROM user_daily_listen_time
                WHERE user_id = ? AND substr(date, 1, 7) = ?
            """, (user_id, ym))

        # Only delete past years' aggregated monthly times if older than the current year.
        # User play counts (user_song_plays) are NEVER deleted in monthly operations.
        conn.execute("DELETE FROM user_monthly_listen_time WHERE year < ?", (current_year,))

        conn.commit()
    except Exception as e:
        print(f"Error rolling up listen times: {e}")
    finally:
        if close_conn:
            conn.close()

# Auto-initialize database tables upon import
init_db()
