-- Alaktra Music Server Database Schema (models.sql)

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    is_verified INTEGER DEFAULT 0,
    otp_code TEXT,
    otp_expiry DATETIME,
    created_at TEXT DEFAULT (datetime('now'))
);

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
);

CREATE TABLE IF NOT EXISTS playlists (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    is_system INTEGER DEFAULT 0,
    created_at TEXT DEFAULT (datetime('now')),
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS playlist_songs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    playlist_id INTEGER NOT NULL,
    song_id INTEGER NOT NULL,
    created_at TEXT DEFAULT (datetime('now')),
    FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
    FOREIGN KEY(song_id) REFERENCES songs(id) ON DELETE CASCADE,
    UNIQUE(playlist_id, song_id)
);

-- User daily listen time (saved till the end of the month, then aggregated into monthly and deleted)
CREATE TABLE IF NOT EXISTS user_daily_listen_time (
    user_id INTEGER NOT NULL,
    date TEXT NOT NULL, -- Format: YYYY-MM-DD
    seconds INTEGER DEFAULT 0,
    updated_at TEXT DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, date)
);
CREATE INDEX IF NOT EXISTS idx_daily_listen_date ON user_daily_listen_time(user_id, date);

-- User song plays: how many times each song was played by user (saved till end of year)
CREATE TABLE IF NOT EXISTS user_song_plays (
    user_id INTEGER NOT NULL,
    song_id INTEGER NOT NULL,
    play_count INTEGER DEFAULT 0,
    year INTEGER NOT NULL, -- e.g. 2026
    last_played_at TEXT DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, song_id, year)
);
CREATE INDEX IF NOT EXISTS idx_song_plays_user_year ON user_song_plays(user_id, year);

-- User monthly total listen time: calculated on the last day of month and saved till end of year
CREATE TABLE IF NOT EXISTS user_monthly_listen_time (
    user_id INTEGER NOT NULL,
    year INTEGER NOT NULL,
    month INTEGER NOT NULL,
    total_seconds INTEGER DEFAULT 0,
    updated_at TEXT DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, year, month)
);

-- Synced listening history log for registered users (fetched on login / sync)
CREATE TABLE IF NOT EXISTS listening_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    song_id INTEGER NOT NULL,
    listened_seconds INTEGER DEFAULT 0,
    played_at INTEGER NOT NULL, -- Epoch milliseconds
    created_at TEXT DEFAULT (datetime('now')),
    UNIQUE (user_id, song_id, played_at)
);
CREATE INDEX IF NOT EXISTS idx_server_history_user ON listening_history(user_id, played_at DESC);
