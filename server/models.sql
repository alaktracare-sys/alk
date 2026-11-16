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
    is_fuse INTEGER DEFAULT 0,
    fuse_id INTEGER DEFAULT NULL,
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

-- Temporary 5-minute Fuse invites (auto-deleted if unjoined after 5 minutes)
CREATE TABLE IF NOT EXISTS fuse_invites (
    code TEXT PRIMARY KEY,
    host_user_id INTEGER NOT NULL,
    host_username TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    fuse_id INTEGER DEFAULT NULL
);
CREATE INDEX IF NOT EXISTS idx_fuse_invites_expiry ON fuse_invites(status, expires_at_ms);

-- Server-curated Fuse (Spotify Blend style) shared playlists
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
);

-- Server-decided 30-song Blend tracklist with contributor attribution ('both', 'user1', 'user2')
CREATE TABLE IF NOT EXISTS fuse_songs (
    fuse_id INTEGER NOT NULL,
    song_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    contributor TEXT NOT NULL DEFAULT 'both',
    PRIMARY KEY (fuse_id, song_id),
    FOREIGN KEY(fuse_id) REFERENCES fuses(id) ON DELETE CASCADE,
    FOREIGN KEY(song_id) REFERENCES songs(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_fuse_songs_order ON fuse_songs(fuse_id, position ASC);

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

-- Synced listening history log for registered users (fetched on login / sync / Sound Capsule)
CREATE TABLE IF NOT EXISTS listening_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    song_id INTEGER NOT NULL,
    listened_seconds INTEGER DEFAULT 0,
    ms_played INTEGER DEFAULT 0,
    played_at INTEGER NOT NULL, -- Epoch milliseconds when track started
    created_at TEXT DEFAULT (datetime('now')),
    UNIQUE (user_id, song_id, played_at)
);
CREATE INDEX IF NOT EXISTS idx_server_history_user ON listening_history(user_id, played_at DESC);
CREATE INDEX IF NOT EXISTS idx_server_history_user_asc ON listening_history(user_id, played_at ASC);

-- Segregated Artist Catalog Profiles (claimable by verified listener users)
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
);
CREATE INDEX IF NOT EXISTS idx_artists_name ON artists(name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_artists_claimed_user ON artists(claimed_by_user_id);

-- Multi-artist Song Allotment (1 Primary Artist + 0..N Featured Collaborators per song)
CREATE TABLE IF NOT EXISTS song_artists (
    song_id INTEGER NOT NULL,
    artist_id INTEGER NOT NULL,
    role TEXT NOT NULL DEFAULT 'primary', -- 'primary' or 'featured'
    position INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (song_id, artist_id),
    FOREIGN KEY (song_id) REFERENCES songs(id) ON DELETE CASCADE,
    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_song_artists_artist ON song_artists(artist_id, role);
CREATE INDEX IF NOT EXISTS idx_song_artists_song_pos ON song_artists(song_id, position ASC);

-- Artist Profile Claim Queue (listener -> artist verification workflow)
CREATE TABLE IF NOT EXISTS artist_claims (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    artist_id INTEGER DEFAULT NULL,
    requested_name TEXT NOT NULL,
    note TEXT DEFAULT '',
    status TEXT NOT NULL DEFAULT 'pending', -- 'pending', 'approved', 'rejected'
    created_at TEXT DEFAULT (datetime('now')),
    reviewed_at TEXT DEFAULT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_artist_claims_user ON artist_claims(user_id, status);
CREATE INDEX IF NOT EXISTS idx_artist_claims_status ON artist_claims(status, created_at DESC);

-- Public Song Requests submitted via alaktracare.in
CREATE TABLE IF NOT EXISTS song_requests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    song_name TEXT NOT NULL,
    artist_name TEXT NOT NULL,
    requester_name TEXT DEFAULT '',
    note TEXT DEFAULT '',
    status TEXT NOT NULL DEFAULT 'pending', -- 'pending', 'fulfilled', 'rejected'
    created_at TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_song_requests_status ON song_requests(status, id DESC);


