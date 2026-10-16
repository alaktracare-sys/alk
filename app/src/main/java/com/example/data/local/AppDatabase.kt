package com.example.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AppDatabase acts as the single Room-compatible SQLite database source of local persistence.
 *
 * Implements:
 * - SongDao (downloaded permanent songs & cached catalog songs)
 * - PlaylistDao (persisted user & system playlists, and playlist song memberships)
 * - UserDao (cached non-sensitive profile info)
 *
 * Features reactive Flow emissions and non-destructive migrations.
 */
class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION),
    SongDao, PlaylistDao, UserDao {

    private val dbScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _downloadedSongsFlow = MutableStateFlow<List<DownloadedSongEntity>>(emptyList())
    private val _listeningHistoryFlow = MutableStateFlow<List<ListeningHistoryEntity>>(emptyList())
    private val _songsFlow = MutableStateFlow<List<SongEntity>>(emptyList())
    private val _playlistsFlow = MutableStateFlow<List<PlaylistEntity>>(emptyList())
    private val _userFlow = MutableStateFlow<UserEntity?>(null)

    init {
        refreshFlow()
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Table 1: Permanent Downloaded Songs (offline MP3s)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloaded_songs (
                song_id INTEGER PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                audio_url TEXT NOT NULL,
                cover_url TEXT NOT NULL,
                local_path TEXT NOT NULL,
                duration REAL NOT NULL,
                downloaded_at INTEGER NOT NULL,
                file_size INTEGER NOT NULL,
                dominant_color TEXT
            )
            """.trimIndent()
        )

        // Table 2: Listening History (recent plays)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS listening_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                song_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                audio_url TEXT NOT NULL,
                cover_url TEXT NOT NULL,
                duration REAL NOT NULL,
                listened_seconds INTEGER NOT NULL DEFAULT 0,
                played_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_played_at ON listening_history(played_at DESC)")

        // Table 3: Cached Catalog Songs
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS songs (
                id INTEGER PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                audio_url TEXT NOT NULL,
                cover_url TEXT NOT NULL,
                duration REAL NOT NULL,
                is_liked INTEGER NOT NULL DEFAULT 0,
                dominant_color TEXT,
                last_synced_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_songs_title ON songs(title)")

        // Table 4: Cached Playlists
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlists (
                id INTEGER PRIMARY KEY NOT NULL,
                user_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                is_system INTEGER NOT NULL DEFAULT 0,
                song_count INTEGER NOT NULL DEFAULT 0,
                last_synced_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_playlists_user ON playlists(user_id)")

        // Table 5: Playlist-Song Association (preserves ordering)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlist_songs (
                playlist_id INTEGER NOT NULL,
                song_id INTEGER NOT NULL,
                position INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (playlist_id, song_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_playlist_songs ON playlist_songs(playlist_id, position)")

        // Table 6: Cached User Profile
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY NOT NULL,
                username TEXT NOT NULL,
                email TEXT NOT NULL,
                last_synced_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS listening_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    song_id INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    audio_url TEXT NOT NULL,
                    cover_url TEXT NOT NULL,
                    duration REAL NOT NULL,
                    played_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_played_at ON listening_history(played_at DESC)")
        }

        if (oldVersion < 3) {
            // Version 3 Migration: Add songs, playlists, playlist_songs, users
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS songs (
                    id INTEGER PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    audio_url TEXT NOT NULL,
                    cover_url TEXT NOT NULL,
                    duration REAL NOT NULL,
                    is_liked INTEGER NOT NULL DEFAULT 0,
                    last_synced_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_songs_title ON songs(title)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS playlists (
                    id INTEGER PRIMARY KEY NOT NULL,
                    user_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    is_system INTEGER NOT NULL DEFAULT 0,
                    song_count INTEGER NOT NULL DEFAULT 0,
                    last_synced_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_playlists_user ON playlists(user_id)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS playlist_songs (
                    playlist_id INTEGER NOT NULL,
                    song_id INTEGER NOT NULL,
                    position INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (playlist_id, song_id)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_playlist_songs ON playlist_songs(playlist_id, position)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY NOT NULL,
                    username TEXT NOT NULL,
                    email TEXT NOT NULL,
                    last_synced_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }

        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE listening_history ADD COLUMN listened_seconds INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) {}
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        addColumnIfNotExists(db, "songs", "dominant_color", "TEXT")
        addColumnIfNotExists(db, "downloaded_songs", "dominant_color", "TEXT")
    }

    private fun addColumnIfNotExists(db: SQLiteDatabase, table: String, column: String, type: String) {
        var cursor: android.database.Cursor? = null
        try {
            cursor = db.rawQuery("PRAGMA table_info($table)", null)
            val nameIndex = cursor.getColumnIndex("name")
            var exists = false
            while (cursor.moveToNext()) {
                if (nameIndex != -1 && cursor.getString(nameIndex).equals(column, ignoreCase = true)) {
                    exists = true
                    break
                }
            }
            if (!exists) {
                db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
    }

    fun songDao(): SongDao = this
    fun playlistDao(): PlaylistDao = this
    fun userDao(): UserDao = this

    private fun refreshFlow() {
        dbScope.launch {
            try {
                _downloadedSongsFlow.value = queryAllDownloadedSongs()
                _listeningHistoryFlow.value = queryAllHistory()
                _songsFlow.value = queryAllSongs()
                _playlistsFlow.value = queryAllPlaylists()
                _userFlow.value = queryLatestUser()
            } catch (_: Exception) {}
        }
    }

    // ==========================================================
    // DOWNLOADED SONGS (Permanent Downloads)
    // ==========================================================

    private fun queryAllDownloadedSongs(): List<DownloadedSongEntity> {
        val list = mutableListOf<DownloadedSongEntity>()
        val db = readableDatabase
        val cursor = db.query(
            "downloaded_songs",
            null, null, null, null, null,
            "downloaded_at DESC"
        )
        cursor.use {
            val idCol = it.getColumnIndexOrThrow("song_id")
            val titleCol = it.getColumnIndexOrThrow("title")
            val artistCol = it.getColumnIndexOrThrow("artist")
            val audioCol = it.getColumnIndexOrThrow("audio_url")
            val coverCol = it.getColumnIndexOrThrow("cover_url")
            val pathCol = it.getColumnIndexOrThrow("local_path")
            val durCol = it.getColumnIndexOrThrow("duration")
            val dateCol = it.getColumnIndexOrThrow("downloaded_at")
            val sizeCol = it.getColumnIndexOrThrow("file_size")
            val colorCol = it.getColumnIndex("dominant_color")

            while (it.moveToNext()) {
                list.add(
                    DownloadedSongEntity(
                        songId = it.getInt(idCol),
                        title = it.getString(titleCol),
                        artist = it.getString(artistCol),
                        audioUrl = it.getString(audioCol),
                        coverUrl = it.getString(coverCol),
                        localPath = it.getString(pathCol),
                        duration = it.getDouble(durCol),
                        downloadedAt = it.getLong(dateCol),
                        fileSize = it.getLong(sizeCol),
                        dominantColorHex = if (colorCol >= 0 && !it.isNull(colorCol)) it.getString(colorCol) else null
                    )
                )
            }
        }
        return list
    }

    override fun getAllDownloadedSongs(): Flow<List<DownloadedSongEntity>> = _downloadedSongsFlow.asStateFlow()

    override suspend fun getDownloadedSongsList(): List<DownloadedSongEntity> = withContext(Dispatchers.IO) {
        queryAllDownloadedSongs()
    }

    override suspend fun getDownloadedSong(songId: Int): DownloadedSongEntity? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.query(
            "downloaded_songs",
            null,
            "song_id = ?",
            arrayOf(songId.toString()),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                val colorCol = it.getColumnIndex("dominant_color")
                DownloadedSongEntity(
                    songId = it.getInt(it.getColumnIndexOrThrow("song_id")),
                    title = it.getString(it.getColumnIndexOrThrow("title")),
                    artist = it.getString(it.getColumnIndexOrThrow("artist")),
                    audioUrl = it.getString(it.getColumnIndexOrThrow("audio_url")),
                    coverUrl = it.getString(it.getColumnIndexOrThrow("cover_url")),
                    localPath = it.getString(it.getColumnIndexOrThrow("local_path")),
                    duration = it.getDouble(it.getColumnIndexOrThrow("duration")),
                    downloadedAt = it.getLong(it.getColumnIndexOrThrow("downloaded_at")),
                    fileSize = it.getLong(it.getColumnIndexOrThrow("file_size")),
                    dominantColorHex = if (colorCol >= 0 && !it.isNull(colorCol)) it.getString(colorCol) else null
                )
            } else null
        }
    }

    override suspend fun getLocalPath(songId: Int): String? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.query(
            "downloaded_songs",
            arrayOf("local_path"),
            "song_id = ?",
            arrayOf(songId.toString()),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                it.getString(0)
            } else null
        }
    }

    override suspend fun insertDownloadedSong(song: DownloadedSongEntity): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("song_id", song.songId)
            put("title", song.title)
            put("artist", song.artist)
            put("audio_url", song.audioUrl)
            put("cover_url", song.coverUrl)
            put("local_path", song.localPath)
            put("duration", song.duration)
            put("downloaded_at", song.downloadedAt)
            put("file_size", song.fileSize)
            if (song.dominantColorHex != null) {
                put("dominant_color", song.dominantColorHex)
            }
        }
        db.insertWithOnConflict("downloaded_songs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        refreshFlow()
    }

    override suspend fun deleteDownloadedSong(songId: Int): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("downloaded_songs", "song_id = ?", arrayOf(songId.toString()))
        refreshFlow()
    }

    override suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("downloaded_songs", null, null)
        refreshFlow()
    }

    // ==========================================================
    // CACHED SONGS (SongDao)
    // ==========================================================

    private fun queryAllSongs(): List<SongEntity> {
        val list = mutableListOf<SongEntity>()
        val db = readableDatabase
        val cursor = db.query("songs", null, null, null, null, null, "id DESC")
        cursor.use {
            val idCol = it.getColumnIndexOrThrow("id")
            val titleCol = it.getColumnIndexOrThrow("title")
            val artistCol = it.getColumnIndexOrThrow("artist")
            val audioCol = it.getColumnIndexOrThrow("audio_url")
            val coverCol = it.getColumnIndexOrThrow("cover_url")
            val durCol = it.getColumnIndexOrThrow("duration")
            val likedCol = it.getColumnIndexOrThrow("is_liked")
            val syncCol = it.getColumnIndexOrThrow("last_synced_at")
            val colorCol = it.getColumnIndex("dominant_color")

            while (it.moveToNext()) {
                list.add(
                    SongEntity(
                        id = it.getInt(idCol),
                        title = it.getString(titleCol),
                        artist = it.getString(artistCol),
                        audioUrl = it.getString(audioCol),
                        coverUrl = it.getString(coverCol),
                        duration = it.getDouble(durCol),
                        isLiked = it.getInt(likedCol) == 1,
                        dominantColorHex = if (colorCol >= 0 && !it.isNull(colorCol)) it.getString(colorCol) else null,
                        lastSyncedAt = it.getLong(syncCol)
                    )
                )
            }
        }
        return list
    }

    override fun getAllCachedSongsFlow(): Flow<List<SongEntity>> = _songsFlow.asStateFlow()

    override suspend fun getAllCachedSongs(): List<SongEntity> = withContext(Dispatchers.IO) {
        queryAllSongs()
    }

    override suspend fun getCachedSong(songId: Int): SongEntity? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.query("songs", null, "id = ?", arrayOf(songId.toString()), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                val colorCol = it.getColumnIndex("dominant_color")
                SongEntity(
                    id = it.getInt(it.getColumnIndexOrThrow("id")),
                    title = it.getString(it.getColumnIndexOrThrow("title")),
                    artist = it.getString(it.getColumnIndexOrThrow("artist")),
                    audioUrl = it.getString(it.getColumnIndexOrThrow("audio_url")),
                    coverUrl = it.getString(it.getColumnIndexOrThrow("cover_url")),
                    duration = it.getDouble(it.getColumnIndexOrThrow("duration")),
                    isLiked = it.getInt(it.getColumnIndexOrThrow("is_liked")) == 1,
                    dominantColorHex = if (colorCol >= 0 && !it.isNull(colorCol)) it.getString(colorCol) else null,
                    lastSyncedAt = it.getLong(it.getColumnIndexOrThrow("last_synced_at"))
                )
            } else null
        }
    }

    override suspend fun insertCachedSongs(songs: List<SongEntity>): Unit = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (song in songs) {
                val values = ContentValues().apply {
                    put("id", song.id)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("audio_url", song.audioUrl)
                    put("cover_url", song.coverUrl)
                    put("duration", song.duration)
                    put("is_liked", if (song.isLiked) 1 else 0)
                    if (song.dominantColorHex != null) {
                        put("dominant_color", song.dominantColorHex)
                    }
                    put("last_synced_at", song.lastSyncedAt)
                }
                db.insertWithOnConflict("songs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshFlow()
    }

    override suspend fun insertCachedSong(song: SongEntity): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", song.id)
            put("title", song.title)
            put("artist", song.artist)
            put("audio_url", song.audioUrl)
            put("cover_url", song.coverUrl)
            put("duration", song.duration)
            put("is_liked", if (song.isLiked) 1 else 0)
            if (song.dominantColorHex != null) {
                put("dominant_color", song.dominantColorHex)
            }
            put("last_synced_at", song.lastSyncedAt)
        }
        db.insertWithOnConflict("songs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        refreshFlow()
    }

    override suspend fun updateSongLiked(songId: Int, isLiked: Boolean): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("is_liked", if (isLiked) 1 else 0)
        }
        db.update("songs", values, "id = ?", arrayOf(songId.toString()))
        refreshFlow()
    }

    override suspend fun getLikedSongs(): List<SongEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SongEntity>()
        val db = readableDatabase
        val cursor = db.query("songs", null, "is_liked = 1", null, null, null, "title ASC")
        cursor.use {
            val idCol = it.getColumnIndexOrThrow("id")
            val titleCol = it.getColumnIndexOrThrow("title")
            val artistCol = it.getColumnIndexOrThrow("artist")
            val audioCol = it.getColumnIndexOrThrow("audio_url")
            val coverCol = it.getColumnIndexOrThrow("cover_url")
            val durCol = it.getColumnIndexOrThrow("duration")
            val syncCol = it.getColumnIndexOrThrow("last_synced_at")
            val colorCol = it.getColumnIndex("dominant_color")

            while (it.moveToNext()) {
                list.add(
                    SongEntity(
                        id = it.getInt(idCol),
                        title = it.getString(titleCol),
                        artist = it.getString(artistCol),
                        audioUrl = it.getString(audioCol),
                        coverUrl = it.getString(coverCol),
                        duration = it.getDouble(durCol),
                        isLiked = true,
                        dominantColorHex = if (colorCol >= 0 && !it.isNull(colorCol)) it.getString(colorCol) else null,
                        lastSyncedAt = it.getLong(syncCol)
                    )
                )
            }
        }
        return@withContext list
    }

    override suspend fun searchCachedSongs(query: String): List<SongEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SongEntity>()
        val db = readableDatabase
        val pattern = "%${query.trim()}%"
        val cursor = db.query(
            "songs",
            null,
            "title LIKE ? OR artist LIKE ?",
            arrayOf(pattern, pattern),
            null, null, "title ASC"
        )
        cursor.use {
            val idCol = it.getColumnIndexOrThrow("id")
            val titleCol = it.getColumnIndexOrThrow("title")
            val artistCol = it.getColumnIndexOrThrow("artist")
            val audioCol = it.getColumnIndexOrThrow("audio_url")
            val coverCol = it.getColumnIndexOrThrow("cover_url")
            val durCol = it.getColumnIndexOrThrow("duration")
            val likedCol = it.getColumnIndexOrThrow("is_liked")
            val syncCol = it.getColumnIndexOrThrow("last_synced_at")
            val colorCol = it.getColumnIndex("dominant_color")

            while (it.moveToNext()) {
                list.add(
                    SongEntity(
                        id = it.getInt(idCol),
                        title = it.getString(titleCol),
                        artist = it.getString(artistCol),
                        audioUrl = it.getString(audioCol),
                        coverUrl = it.getString(coverCol),
                        duration = it.getDouble(durCol),
                        isLiked = it.getInt(likedCol) == 1,
                        dominantColorHex = if (colorCol >= 0 && !it.isNull(colorCol)) it.getString(colorCol) else null,
                        lastSyncedAt = it.getLong(syncCol)
                    )
                )
            }
        }
        return@withContext list
    }

    // ==========================================================
    // CACHED PLAYLISTS (PlaylistDao)
    // ==========================================================

    private fun queryAllPlaylists(): List<PlaylistEntity> {
        val list = mutableListOf<PlaylistEntity>()
        val db = readableDatabase
        val cursor = db.query("playlists", null, null, null, null, null, "id DESC")
        cursor.use {
            val idCol = it.getColumnIndexOrThrow("id")
            val userCol = it.getColumnIndexOrThrow("user_id")
            val nameCol = it.getColumnIndexOrThrow("name")
            val descCol = it.getColumnIndexOrThrow("description")
            val sysCol = it.getColumnIndexOrThrow("is_system")
            val countCol = it.getColumnIndexOrThrow("song_count")
            val syncCol = it.getColumnIndexOrThrow("last_synced_at")

            while (it.moveToNext()) {
                list.add(
                    PlaylistEntity(
                        id = it.getInt(idCol),
                        userId = it.getInt(userCol),
                        name = it.getString(nameCol),
                        description = it.getString(descCol),
                        isSystem = it.getInt(sysCol) == 1,
                        songCount = it.getInt(countCol),
                        lastSyncedAt = it.getLong(syncCol)
                    )
                )
            }
        }
        return list
    }

    override fun getUserPlaylistsFlow(userId: Int): Flow<List<PlaylistEntity>> = _playlistsFlow.asStateFlow()

    override suspend fun getUserPlaylists(userId: Int): List<PlaylistEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<PlaylistEntity>()
        val db = readableDatabase
        val cursor = db.query(
            "playlists",
            null,
            "user_id = ? AND is_system = 0",
            arrayOf(userId.toString()),
            null, null, "id DESC"
        )
        cursor.use {
            val idCol = it.getColumnIndexOrThrow("id")
            val userCol = it.getColumnIndexOrThrow("user_id")
            val nameCol = it.getColumnIndexOrThrow("name")
            val descCol = it.getColumnIndexOrThrow("description")
            val sysCol = it.getColumnIndexOrThrow("is_system")
            val countCol = it.getColumnIndexOrThrow("song_count")
            val syncCol = it.getColumnIndexOrThrow("last_synced_at")

            while (it.moveToNext()) {
                list.add(
                    PlaylistEntity(
                        id = it.getInt(idCol),
                        userId = it.getInt(userCol),
                        name = it.getString(nameCol),
                        description = it.getString(descCol),
                        isSystem = it.getInt(sysCol) == 1,
                        songCount = it.getInt(countCol),
                        lastSyncedAt = it.getLong(syncCol)
                    )
                )
            }
        }
        return@withContext list
    }

    override suspend fun getPlaylistById(playlistId: Int): PlaylistEntity? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.query("playlists", null, "id = ?", arrayOf(playlistId.toString()), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                PlaylistEntity(
                    id = it.getInt(it.getColumnIndexOrThrow("id")),
                    userId = it.getInt(it.getColumnIndexOrThrow("user_id")),
                    name = it.getString(it.getColumnIndexOrThrow("name")),
                    description = it.getString(it.getColumnIndexOrThrow("description")),
                    isSystem = it.getInt(it.getColumnIndexOrThrow("is_system")) == 1,
                    songCount = it.getInt(it.getColumnIndexOrThrow("song_count")),
                    lastSyncedAt = it.getLong(it.getColumnIndexOrThrow("last_synced_at"))
                )
            } else null
        }
    }

    override suspend fun insertPlaylists(playlists: List<PlaylistEntity>): Unit = withContext(Dispatchers.IO) {
        if (playlists.isEmpty()) return@withContext
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in playlists) {
                val values = ContentValues().apply {
                    put("id", p.id)
                    put("user_id", p.userId)
                    put("name", p.name)
                    put("description", p.description)
                    put("is_system", if (p.isSystem) 1 else 0)
                    put("song_count", p.songCount)
                    put("last_synced_at", p.lastSyncedAt)
                }
                db.insertWithOnConflict("playlists", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshFlow()
    }

    override suspend fun insertPlaylist(playlist: PlaylistEntity): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", playlist.id)
            put("user_id", playlist.userId)
            put("name", playlist.name)
            put("description", playlist.description)
            put("is_system", if (playlist.isSystem) 1 else 0)
            put("song_count", playlist.songCount)
            put("last_synced_at", playlist.lastSyncedAt)
        }
        db.insertWithOnConflict("playlists", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        refreshFlow()
    }

    override suspend fun deletePlaylist(playlistId: Int): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("playlists", "id = ?", arrayOf(playlistId.toString()))
        db.delete("playlist_songs", "playlist_id = ?", arrayOf(playlistId.toString()))
        refreshFlow()
    }

    override suspend fun updatePlaylistName(playlistId: Int, newName: String): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", newName)
        }
        db.update("playlists", values, "id = ?", arrayOf(playlistId.toString()))
        refreshFlow()
    }

    override suspend fun getPlaylistSongs(playlistId: Int): List<SongEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SongEntity>()
        val db = readableDatabase
        val sql = """
            SELECT s.id, s.title, s.artist, s.audio_url, s.cover_url, s.duration, s.is_liked, s.dominant_color, s.last_synced_at
            FROM songs s
            INNER JOIN playlist_songs ps ON s.id = ps.song_id
            WHERE ps.playlist_id = ?
            ORDER BY ps.position ASC
        """.trimIndent()
        val cursor = db.rawQuery(sql, arrayOf(playlistId.toString()))
        cursor.use {
            val idCol = it.getColumnIndexOrThrow("id")
            val titleCol = it.getColumnIndexOrThrow("title")
            val artistCol = it.getColumnIndexOrThrow("artist")
            val audioCol = it.getColumnIndexOrThrow("audio_url")
            val coverCol = it.getColumnIndexOrThrow("cover_url")
            val durCol = it.getColumnIndexOrThrow("duration")
            val likedCol = it.getColumnIndexOrThrow("is_liked")
            val syncCol = it.getColumnIndexOrThrow("last_synced_at")
            val colorCol = it.getColumnIndex("dominant_color")

            while (it.moveToNext()) {
                list.add(
                    SongEntity(
                        id = it.getInt(idCol),
                        title = it.getString(titleCol),
                        artist = it.getString(artistCol),
                        audioUrl = it.getString(audioCol),
                        coverUrl = it.getString(coverCol),
                        duration = it.getDouble(durCol),
                        isLiked = it.getInt(likedCol) == 1,
                        dominantColorHex = if (colorCol >= 0 && !it.isNull(colorCol)) it.getString(colorCol) else null,
                        lastSyncedAt = it.getLong(syncCol)
                    )
                )
            }
        }
        return@withContext list
    }

    override suspend fun setPlaylistSongs(playlistId: Int, songs: List<SongEntity>): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("playlist_songs", "playlist_id = ?", arrayOf(playlistId.toString()))
            songs.forEachIndexed { index, song ->
                // Ensure song is cached in songs table
                val songValues = ContentValues().apply {
                    put("id", song.id)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("audio_url", song.audioUrl)
                    put("cover_url", song.coverUrl)
                    put("duration", song.duration)
                    put("is_liked", if (song.isLiked) 1 else 0)
                    if (song.dominantColorHex != null) {
                        put("dominant_color", song.dominantColorHex)
                    }
                    put("last_synced_at", song.lastSyncedAt)
                }
                db.insertWithOnConflict("songs", null, songValues, SQLiteDatabase.CONFLICT_REPLACE)

                val linkValues = ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("song_id", song.id)
                    put("position", index)
                }
                db.insertWithOnConflict("playlist_songs", null, linkValues, SQLiteDatabase.CONFLICT_REPLACE)
            }

            // Update song_count in playlists table
            val updateCount = ContentValues().apply {
                put("song_count", songs.size)
            }
            db.update("playlists", updateCount, "id = ?", arrayOf(playlistId.toString()))

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshFlow()
    }

    override suspend fun addSongToPlaylist(playlistId: Int, songId: Int, position: Int): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("playlist_id", playlistId)
            put("song_id", songId)
            put("position", position)
        }
        db.insertWithOnConflict("playlist_songs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("UPDATE playlists SET song_count = song_count + 1 WHERE id = ?", arrayOf(playlistId.toString()))
        refreshFlow()
    }

    override suspend fun removeSongFromPlaylist(playlistId: Int, songId: Int): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("playlist_songs", "playlist_id = ? AND song_id = ?", arrayOf(playlistId.toString(), songId.toString()))
        db.execSQL("UPDATE playlists SET song_count = MAX(0, song_count - 1) WHERE id = ?", arrayOf(playlistId.toString()))
        refreshFlow()
    }

    override suspend fun remapPlaylistId(oldId: Int, newId: Int): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE playlists SET id = ? WHERE id = ?", arrayOf(newId.toString(), oldId.toString()))
            db.execSQL("UPDATE playlist_songs SET playlist_id = ? WHERE playlist_id = ?", arrayOf(newId.toString(), oldId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshFlow()
    }

    // ==========================================================
    // USER PROFILE (UserDao)
    // ==========================================================

    private fun queryLatestUser(): UserEntity? {
        val db = readableDatabase
        val cursor = db.query("users", null, null, null, null, null, "last_synced_at DESC", "1")
        cursor.use {
            if (it.moveToFirst()) {
                return UserEntity(
                    id = it.getInt(it.getColumnIndexOrThrow("id")),
                    username = it.getString(it.getColumnIndexOrThrow("username")),
                    email = it.getString(it.getColumnIndexOrThrow("email")),
                    lastSyncedAt = it.getLong(it.getColumnIndexOrThrow("last_synced_at"))
                )
            }
        }
        return null
    }

    override fun getUserFlow(userId: Int): Flow<UserEntity?> = _userFlow.asStateFlow()

    override suspend fun getUser(userId: Int): UserEntity? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.query("users", null, "id = ?", arrayOf(userId.toString()), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                UserEntity(
                    id = it.getInt(it.getColumnIndexOrThrow("id")),
                    username = it.getString(it.getColumnIndexOrThrow("username")),
                    email = it.getString(it.getColumnIndexOrThrow("email")),
                    lastSyncedAt = it.getLong(it.getColumnIndexOrThrow("last_synced_at"))
                )
            } else queryLatestUser()
        }
    }

    override suspend fun insertUser(user: UserEntity): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", user.id)
            put("username", user.username)
            put("email", user.email)
            put("last_synced_at", user.lastSyncedAt)
        }
        db.insertWithOnConflict("users", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        refreshFlow()
    }

    override suspend fun clearUser(): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("users", null, null)
        refreshFlow()
    }

    // ==========================================================
    // LISTENING HISTORY
    // ==========================================================

    private fun queryAllHistory(): List<ListeningHistoryEntity> {
        val list = mutableListOf<ListeningHistoryEntity>()
        val db = readableDatabase
        val cursor = db.query(
            "listening_history",
            null, null, null, null, null,
            "played_at DESC"
        )
        cursor.use {
            val idCol = it.getColumnIndexOrThrow("id")
            val songIdCol = it.getColumnIndexOrThrow("song_id")
            val titleCol = it.getColumnIndexOrThrow("title")
            val artistCol = it.getColumnIndexOrThrow("artist")
            val audioCol = it.getColumnIndexOrThrow("audio_url")
            val coverCol = it.getColumnIndexOrThrow("cover_url")
            val durCol = it.getColumnIndexOrThrow("duration")
            val listenedCol = it.getColumnIndex("listened_seconds")
            val dateCol = it.getColumnIndexOrThrow("played_at")

            while (it.moveToNext()) {
                val duration = it.getDouble(durCol)
                val listenedSec = if (listenedCol >= 0 && !it.isNull(listenedCol)) {
                    it.getLong(listenedCol).coerceAtLeast(0L)
                } else {
                    0L
                }
                list.add(
                    ListeningHistoryEntity(
                        id = it.getLong(idCol),
                        songId = it.getInt(songIdCol),
                        title = it.getString(titleCol),
                        artist = it.getString(artistCol),
                        audioUrl = it.getString(audioCol),
                        coverUrl = it.getString(coverCol),
                        duration = duration,
                        listenedSeconds = listenedSec,
                        playedAt = it.getLong(dateCol)
                    )
                )
            }
        }
        return list
    }

    fun getAllHistory(): Flow<List<ListeningHistoryEntity>> = _listeningHistoryFlow.asStateFlow()

    fun getAllHistoryList(): List<ListeningHistoryEntity> = queryAllHistory()

    suspend fun insertListeningHistory(item: ListeningHistoryEntity): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("song_id", item.songId)
            put("title", item.title)
            put("artist", item.artist)
            put("audio_url", item.audioUrl)
            put("cover_url", item.coverUrl)
            put("duration", item.duration)
            put("listened_seconds", item.listenedSeconds)
            put("played_at", item.playedAt)
        }
        val insertedId = db.insert("listening_history", null, values)
        refreshFlow()
        insertedId
    }

    suspend fun updateListeningDuration(id: Long, listenedSeconds: Long): Unit = withContext(Dispatchers.IO) {
        if (id <= 0L) return@withContext
        val db = writableDatabase
        val values = ContentValues().apply {
            put("listened_seconds", listenedSeconds)
        }
        db.update("listening_history", values, "id = ?", arrayOf(id.toString()))
        refreshFlow()
    }

    suspend fun deleteHistoryItem(id: Long): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("listening_history", "id = ?", arrayOf(id.toString()))
        refreshFlow()
    }

    suspend fun clearAllHistory(): Unit = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("listening_history", null, null)
        refreshFlow()
    }

    companion object {
        private const val DATABASE_NAME = "already_music.db"
        private const val DATABASE_VERSION = 4

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: AppDatabase(context.applicationContext).also { instance = it }
            }
        }
    }
}
