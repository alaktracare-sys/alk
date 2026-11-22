package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.example.model.ArtistCreditJsonCodec
import com.example.model.ArtistDetailBundle
import com.example.model.ArtistProfile
import com.example.model.ClubPlaylistDetails
import com.example.model.ClubPlaylistSummary
import com.example.model.DailyPlaylist
import com.example.model.Playlist
import com.example.model.Song
import com.example.model.User
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject

class AuthPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("already_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val artistListType = object : TypeToken<List<ArtistProfile>>() {}.type
    private val artistDetailMapType = object : TypeToken<LinkedHashMap<Int, ArtistDetailBundle>>() {}.type
    private val clubSummaryListType = object : TypeToken<List<ClubPlaylistSummary>>() {}.type
    private val clubDetailMapType = object : TypeToken<LinkedHashMap<Int, ClubPlaylistDetails>>() {}.type

    @Volatile
    private var memoryCachedPins: List<com.example.model.LocationPin>? = null

    @Volatile
    private var memoryDailySnapshot: Pair<Int, DailyPlaylistsSnapshot>? = null

    @Volatile
    private var memoryCachedArtists: List<ArtistProfile>? = null

    @Volatile
    private var memoryArtistDetails: LinkedHashMap<Int, ArtistDetailBundle>? = null

    @Volatile
    private var memoryMyClubs: Pair<Int, List<ClubPlaylistSummary>>? = null

    @Volatile
    private var memoryClubDetails: Pair<Int, LinkedHashMap<Int, ClubPlaylistDetails>>? = null

    fun saveUser(userId: Int, username: String, email: String, token: String? = null, name: String? = null, avatarUrl: String? = null) {
        prefs.edit()
            .putInt(KEY_USER_ID, userId)
            .putString(KEY_USERNAME, username)
            .putString(KEY_EMAIL, email)
            .putString(KEY_TOKEN, token ?: "")
            .putString(KEY_FULL_NAME, name ?: "")
            .putString(KEY_AVATAR_URL, avatarUrl ?: "")
            .apply()
    }

    fun getUser(): User? {
        val id = prefs.getInt(KEY_USER_ID, -1)
        if (id == -1) return null
        val username = prefs.getString(KEY_USERNAME, "User") ?: "User"
        val email = prefs.getString(KEY_EMAIL, "") ?: ""
        val name = prefs.getString(KEY_FULL_NAME, "") ?: ""
        val avatarUrl = prefs.getString(KEY_AVATAR_URL, null)
        return User(id, username, email, name = if (name.isBlank()) username else name, avatarUrl = avatarUrl)
    }

    fun updateProfile(name: String, username: String, avatarUrl: String? = null) {
        val editor = prefs.edit()
            .putString(KEY_FULL_NAME, name.trim())
            .putString(KEY_USERNAME, username.trim())
        if (avatarUrl != null) {
            editor.putString(KEY_AVATAR_URL, avatarUrl)
        }
        editor.apply()
    }

    fun savePassword(password: String) {
        prefs.edit().putString(KEY_PASSWORD, password).apply()
    }

    fun getPassword(): String {
        return prefs.getString(KEY_PASSWORD, "password123") ?: "password123"
    }

    fun changePassword(newPassword: String) {
        savePassword(newPassword)
    }

    fun isLoggedIn(): Boolean {
        return prefs.getInt(KEY_USER_ID, -1) != -1
    }

    fun clear() {
        memoryCachedPins = null
        memoryDailySnapshot = null
        memoryCachedArtists = null
        memoryArtistDetails = null
        memoryMyClubs = null
        memoryClubDetails = null
        prefs.edit().clear().apply()
    }

    fun getLikedSongIds(): Set<Int> {
        val set = prefs.getStringSet(KEY_LIKED_SONGS, emptySet()) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun setSongLiked(songId: Int, isLiked: Boolean) {
        val current = getLikedSongIds().toMutableSet()
        if (isLiked) {
            current.add(songId)
        } else {
            current.remove(songId)
        }
        prefs.edit()
            .putStringSet(KEY_LIKED_SONGS, current.map { it.toString() }.toSet())
            .apply()
    }

    fun getOfflineQueue(): List<JSONObject> {
        val json = prefs.getString(KEY_OFFLINE_QUEUE, "[]") ?: "[]"
        val list = mutableListOf<JSONObject>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                list.add(array.getJSONObject(i))
            }
        } catch (_: Exception) {}
        return list
    }

    fun addOfflineAction(action: JSONObject) {
        val current = getOfflineQueue().toMutableList()
        current.add(action)
        setOfflineQueue(current)
    }

    fun setOfflineQueue(actions: List<JSONObject>) {
        val array = JSONArray()
        actions.forEach { array.put(it) }
        prefs.edit().putString(KEY_OFFLINE_QUEUE, array.toString()).apply()
    }

    fun hasPendingOfflineActions(): Boolean {
        return getOfflineQueue().isNotEmpty()
    }

    fun clearOfflineQueue() {
        prefs.edit().putString(KEY_OFFLINE_QUEUE, "[]").apply()
    }

    fun getServerBaseUrl(): String {
        val url = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        return if (url.endsWith("/")) url else "$url/"
    }

    fun setServerBaseUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            prefs.edit().putString(KEY_SERVER_URL, DEFAULT_SERVER_URL).apply()
            return
        }
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "http://$trimmed"
        }
        val safeUrl = if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        prefs.edit().putString(KEY_SERVER_URL, safeUrl).apply()
    }

    fun getLikedPlaylistId(): Int {
        return prefs.getInt(KEY_LIKED_PLAYLIST_ID, -1)
    }

    fun setLikedPlaylistId(id: Int) {
        prefs.edit().putInt(KEY_LIKED_PLAYLIST_ID, id).apply()
    }

    // ==========================================
    // PLAYBACK STATE PERSISTENCE
    // ==========================================

    fun savePlaybackState(
        song: Song?,
        positionMs: Long,
        shuffle: Boolean,
        repeatModeName: String
    ) {
        val editor = prefs.edit()
            .putLong(KEY_LAST_PLAYBACK_POSITION_MS, positionMs)
            .putBoolean(KEY_SHUFFLE_ENABLED, shuffle)
            .putString(KEY_REPEAT_MODE, repeatModeName)

        if (song != null) {
            val json = JSONObject().apply {
                put("id", song.id)
                put("title", song.title)
                put("artist", song.artist)
                put("audioUrl", song.audioUrl)
                put("coverUrl", song.coverUrl)
                put("duration", song.duration)
                put("isLiked", song.isLiked)
                put("localPath", song.localPath ?: "")
                put("isDownloaded", song.isDownloaded)
                put("dominantColorHex", song.dominantColorHex ?: "")
            }
            editor.putString(KEY_LAST_SONG_JSON, json.toString())
        }
        editor.apply()
    }

    fun getLastPlayedSong(): Song? {
        val jsonStr = prefs.getString(KEY_LAST_SONG_JSON, null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            val localPathStr = json.optString("localPath", "")
            val dominantHexStr = json.optString("dominantColorHex", "")
            Song(
                id = json.getInt("id"),
                title = json.getString("title"),
                artist = json.getString("artist"),
                audioUrl = json.getString("audioUrl"),
                coverUrl = json.getString("coverUrl"),
                duration = json.optDouble("duration", 0.0),
                isLiked = json.optBoolean("isLiked", false),
                localPath = if (localPathStr.isNotBlank()) localPathStr else null,
                isDownloaded = json.optBoolean("isDownloaded", false),
                dominantColorHex = if (dominantHexStr.isNotBlank()) dominantHexStr else null
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getLastPlaybackPosition(): Long {
        return prefs.getLong(KEY_LAST_PLAYBACK_POSITION_MS, 0L)
    }

    fun getShuffleEnabled(): Boolean {
        return prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
    }

    fun getRepeatModeName(): String {
        return prefs.getString(KEY_REPEAT_MODE, "OFF") ?: "OFF"
    }

    fun getRecentPlayedSongIds(): List<Int> {
        val json = prefs.getString(KEY_RECENT_PLAYED_IDS, "[]") ?: "[]"
        val list = mutableListOf<Int>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                list.add(arr.getInt(i))
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveRecentPlayedSongIds(ids: List<Int>) {
        val arr = JSONArray()
        ids.take(30).forEach { arr.put(it) }
        prefs.edit().putString(KEY_RECENT_PLAYED_IDS, arr.toString()).apply()
    }

    fun saveQueue(songs: List<Song>) {
        val arr = JSONArray()
        songs.take(100).forEach { song ->
            val json = JSONObject().apply {
                put("id", song.id)
                put("title", song.title)
                put("artist", song.artist)
                put("audioUrl", song.audioUrl)
                put("coverUrl", song.coverUrl)
                put("duration", song.duration)
                put("isLiked", song.isLiked)
                put("localPath", song.localPath ?: "")
                put("isDownloaded", song.isDownloaded)
                put("dominantColorHex", song.dominantColorHex ?: "")
            }
            arr.put(json)
        }
        prefs.edit().putString(KEY_SAVED_QUEUE, arr.toString()).apply()
    }

    fun getSavedQueue(): List<Song> {
        val jsonStr = prefs.getString(KEY_SAVED_QUEUE, null) ?: return emptyList()
        val list = mutableListOf<Song>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val json = arr.getJSONObject(i)
                val localPathStr = json.optString("localPath", "")
                val dominantHexStr = json.optString("dominantColorHex", "")
                list.add(
                    Song(
                        id = json.getInt("id"),
                        title = json.getString("title"),
                        artist = json.getString("artist"),
                        audioUrl = json.getString("audioUrl"),
                        coverUrl = json.getString("coverUrl"),
                        duration = json.optDouble("duration", 0.0),
                        isLiked = json.optBoolean("isLiked", false),
                        localPath = if (localPathStr.isNotBlank()) localPathStr else null,
                        isDownloaded = json.optBoolean("isDownloaded", false),
                        dominantColorHex = if (dominantHexStr.isNotBlank()) dominantHexStr else null
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    data class SavedContextInfo(
        val uri: String,
        val typeName: String,
        val name: String,
        val tracks: List<Song>
    )

    fun savePlaybackContext(uri: String, typeName: String, name: String, tracks: List<Song>) {
        val arr = JSONArray()
        tracks.take(150).forEach { song ->
            val json = JSONObject().apply {
                put("id", song.id)
                put("title", song.title)
                put("artist", song.artist)
                put("audioUrl", song.audioUrl)
                put("coverUrl", song.coverUrl)
                put("duration", song.duration)
                put("isLiked", song.isLiked)
                put("localPath", song.localPath ?: "")
                put("isDownloaded", song.isDownloaded)
                put("dominantColorHex", song.dominantColorHex ?: "")
            }
            arr.put(json)
        }
        prefs.edit()
            .putString(KEY_SAVED_CONTEXT_URI, uri)
            .putString(KEY_SAVED_CONTEXT_TYPE, typeName)
            .putString(KEY_SAVED_CONTEXT_NAME, name)
            .putString(KEY_SAVED_CONTEXT_TRACKS, arr.toString())
            .apply()
    }

    fun getSavedPlaybackContext(): SavedContextInfo? {
        val uri = prefs.getString(KEY_SAVED_CONTEXT_URI, null) ?: return null
        val typeName = prefs.getString(KEY_SAVED_CONTEXT_TYPE, "PLAYLIST") ?: "PLAYLIST"
        val name = prefs.getString(KEY_SAVED_CONTEXT_NAME, "Alaktra Stream") ?: "Alaktra Stream"
        val tracksJson = prefs.getString(KEY_SAVED_CONTEXT_TRACKS, null) ?: return null
        val tracks = mutableListOf<Song>()
        try {
            val arr = JSONArray(tracksJson)
            for (i in 0 until arr.length()) {
                val json = arr.getJSONObject(i)
                val localPathStr = json.optString("localPath", "")
                val dominantHexStr = json.optString("dominantColorHex", "")
                tracks.add(
                    Song(
                        id = json.getInt("id"),
                        title = json.getString("title"),
                        artist = json.getString("artist"),
                        audioUrl = json.getString("audioUrl"),
                        coverUrl = json.getString("coverUrl"),
                        duration = json.optDouble("duration", 0.0),
                        isLiked = json.optBoolean("isLiked", false),
                        localPath = if (localPathStr.isNotBlank()) localPathStr else null,
                        isDownloaded = json.optBoolean("isDownloaded", false),
                        dominantColorHex = if (dominantHexStr.isNotBlank()) dominantHexStr else null
                    )
                )
            }
        } catch (_: Exception) {
            return null
        }
        if (tracks.isEmpty()) return null
        return SavedContextInfo(uri = uri, typeName = typeName, name = name, tracks = tracks)
    }

    data class PreOfflineHint(
        val uri: String,
        val resumeIndex: Int,
        val preserveExactRandomOrder: Boolean
    )

    fun savePreOfflineHint(uri: String, resumeIndex: Int, preserveExactRandomOrder: Boolean) {
        prefs.edit()
            .putString(KEY_PRE_OFFLINE_URI, uri)
            .putInt(KEY_PRE_OFFLINE_RESUME_IDX, resumeIndex)
            .putBoolean(KEY_PRE_OFFLINE_PRESERVE_SHUFFLE, preserveExactRandomOrder)
            .apply()
    }

    fun getPreOfflineHint(): PreOfflineHint? {
        val uri = prefs.getString(KEY_PRE_OFFLINE_URI, null)?.takeIf { it.isNotBlank() } ?: return null
        val idx = prefs.getInt(KEY_PRE_OFFLINE_RESUME_IDX, 0).coerceAtLeast(0)
        val preserve = prefs.getBoolean(KEY_PRE_OFFLINE_PRESERVE_SHUFFLE, false)
        return PreOfflineHint(uri = uri, resumeIndex = idx, preserveExactRandomOrder = preserve)
    }

    fun clearPreOfflineHint() {
        prefs.edit()
            .remove(KEY_PRE_OFFLINE_URI)
            .remove(KEY_PRE_OFFLINE_RESUME_IDX)
            .remove(KEY_PRE_OFFLINE_PRESERVE_SHUFFLE)
            .apply()
    }

    fun clearPlaybackState() {
        prefs.edit()
            .remove(KEY_LAST_SONG_JSON)
            .remove(KEY_LAST_PLAYBACK_POSITION_MS)
            .remove(KEY_SAVED_QUEUE)
            .remove(KEY_SAVED_CONTEXT_URI)
            .remove(KEY_SAVED_CONTEXT_TYPE)
            .remove(KEY_SAVED_CONTEXT_NAME)
            .remove(KEY_SAVED_CONTEXT_TRACKS)
            .remove(KEY_PRE_OFFLINE_URI)
            .remove(KEY_PRE_OFFLINE_RESUME_IDX)
            .remove(KEY_PRE_OFFLINE_PRESERVE_SHUFFLE)
            .apply()
    }

    fun getLastSyncTime(syncKey: String): Long {
        return prefs.getLong("sync_time_$syncKey", 0L)
    }

    fun setLastSyncTime(syncKey: String, timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("sync_time_$syncKey", timestamp).apply()
    }

    fun getCachedCapsuleSnapshot(userId: Int): String? {
        return prefs.getString("capsule_snapshot_v1_$userId", null)
    }

    fun setCachedCapsuleSnapshot(userId: Int, json: String) {
        prefs.edit().putString("capsule_snapshot_v1_$userId", json).apply()
    }

    fun getAudioCacheCapMb(): Int {
        return prefs.getInt(KEY_AUDIO_CACHE_CAP_MB, com.example.audio.AudioCache.DEFAULT_CACHE_CAP_MB)
    }

    fun setAudioCacheCapMb(capMb: Int) {
        prefs.edit().putInt(KEY_AUDIO_CACHE_CAP_MB, capMb).apply()
    }

    // ==========================================
    // RECENT SEARCHES
    // ==========================================

    fun getRecentSearches(): List<String> {
        val json = prefs.getString(KEY_RECENT_SEARCHES, "[]") ?: "[]"
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val item = arr.getString(i).trim()
                if (item.isNotEmpty() && !list.contains(item)) {
                    list.add(item)
                }
            }
        } catch (_: Exception) {}
        return list
    }

    fun addRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        val current = getRecentSearches().toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        val arr = JSONArray()
        current.take(20).forEach { arr.put(it) }
        prefs.edit().putString(KEY_RECENT_SEARCHES, arr.toString()).apply()
    }

    fun removeRecentSearch(query: String) {
        val trimmed = query.trim()
        val current = getRecentSearches().toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        prefs.edit().putString(KEY_RECENT_SEARCHES, arr.toString()).apply()
    }

    fun clearRecentSearches() {
        prefs.edit().remove(KEY_RECENT_SEARCHES).apply()
    }

    // ==========================================
    // CACHED LOCATION PINS (Offline Persistence)
    // ==========================================

    fun saveCachedPins(pins: List<com.example.model.LocationPin>) {
        val capped = pins.take(100)
        memoryCachedPins = capped
        val arr = JSONArray()
        capped.forEach { pin ->
            val songJson = JSONObject().apply {
                put("id", pin.song.id)
                put("title", pin.song.title)
                put("artist", pin.song.artist)
                put("audioUrl", pin.song.audioUrl)
                put("coverUrl", pin.song.coverUrl)
                put("duration", pin.song.duration)
                put("isLiked", pin.song.isLiked)
                put("localPath", pin.song.localPath ?: "")
                put("isDownloaded", pin.song.isDownloaded)
                put("dominantColorHex", pin.song.dominantColorHex ?: "")
            }
            val pinJson = JSONObject().apply {
                put("id", pin.id)
                put("userId", pin.userId)
                put("songId", pin.songId)
                put("latitude", pin.latitude)
                put("longitude", pin.longitude)
                put("distanceMeters", pin.distanceMeters)
                put("imageUrl", pin.imageUrl)
                put("createdAt", pin.createdAt)
                put("song", songJson)
            }
            arr.put(pinJson)
        }
        prefs.edit().putString(KEY_CACHED_PINS, arr.toString()).apply()
    }

    fun getCachedPins(): List<com.example.model.LocationPin> {
        memoryCachedPins?.let { return it }
        val jsonStr = prefs.getString(KEY_CACHED_PINS, null) ?: run {
            memoryCachedPins = emptyList()
            return emptyList()
        }
        val list = mutableListOf<com.example.model.LocationPin>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val songObj = obj.optJSONObject("song")
                val localPathStr = songObj?.optString("localPath", "") ?: ""
                val domColorStr = songObj?.optString("dominantColorHex", "") ?: ""
                val songId = obj.getInt("songId")
                val song = if (songObj != null) {
                    Song(
                        id = songObj.optInt("id", songId),
                        title = songObj.optString("title", "Pinned Track"),
                        artist = songObj.optString("artist", "Unknown"),
                        audioUrl = songObj.optString("audioUrl", ""),
                        coverUrl = songObj.optString("coverUrl", ""),
                        duration = songObj.optDouble("duration", 0.0),
                        isLiked = songObj.optBoolean("isLiked", false),
                        localPath = if (localPathStr.isNotBlank()) localPathStr else null,
                        isDownloaded = songObj.optBoolean("isDownloaded", false),
                        dominantColorHex = if (domColorStr.isNotBlank()) domColorStr else null
                    )
                } else {
                    Song(id = songId, title = "Pinned Track", artist = "Unknown", audioUrl = "", coverUrl = "")
                }
                list.add(
                    com.example.model.LocationPin(
                        id = obj.getInt("id"),
                        userId = obj.optInt("userId", 1),
                        songId = songId,
                        latitude = obj.getDouble("latitude"),
                        longitude = obj.getDouble("longitude"),
                        distanceMeters = obj.optDouble("distanceMeters", 0.0),
                        imageUrl = obj.optString("imageUrl", ""),
                        createdAt = obj.optString("createdAt", ""),
                        song = song
                    )
                )
            }
        } catch (_: Exception) {}
        memoryCachedPins = list
        return list
    }

    fun getDeviceId(): String {
        var devId = prefs.getString("device_id", null)
        if (devId.isNullOrBlank()) {
            devId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", devId).apply()
        }
        return devId!!
    }

    // ==========================================
    // DAILY MIXES PERSISTENCE (Per User, Per Day)
    // ==========================================

    data class DailyPlaylistsSnapshot(
        val date: String,
        val source: String, // "server" | "local"
        val playlists: List<DailyPlaylist>
    )

    fun saveDailyPlaylistsSnapshot(
        userId: Int,
        date: String,
        source: String,
        playlists: List<DailyPlaylist>
    ) {
        memoryDailySnapshot = userId to DailyPlaylistsSnapshot(date = date, source = source, playlists = playlists)
        val playlistsArr = JSONArray()
        playlists.forEach { dp ->
            val coversArr = JSONArray()
            dp.previewCovers.forEach { coversArr.put(it) }
            val songsArr = JSONArray()
            dp.songs.forEach { song ->
                val songJson = JSONObject().apply {
                    put("id", song.id)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("audioUrl", song.audioUrl)
                    put("coverUrl", song.coverUrl)
                    put("duration", song.duration)
                    put("isLiked", song.isLiked)
                    put("localPath", song.localPath ?: "")
                    put("isDownloaded", song.isDownloaded)
                    put("dominantColorHex", song.dominantColorHex ?: "")
                    put("primaryArtistId", song.primaryArtistId ?: 0)
                    put("artistsJson", ArtistCreditJsonCodec.encode(song.artists) ?: "")
                    put("playCount", song.playCount)
                }
                songsArr.put(songJson)
            }
            val dpJson = JSONObject().apply {
                put("id", dp.id)
                put("serverId", dp.serverId)
                put("slotIndex", dp.slotIndex)
                put("date", dp.date)
                put("name", dp.name)
                put("subtitle", dp.subtitle)
                put("accentColorHex", dp.accentColorHex)
                put("previewCovers", coversArr)
                put("songCount", dp.songCount)
                put("songs", songsArr)
            }
            playlistsArr.put(dpJson)
        }
        val root = JSONObject().apply {
            put("date", date)
            put("source", source)
            put("playlists", playlistsArr)
        }
        prefs.edit().putString("$KEY_DAILY_PLAYLISTS_PREFIX$userId", root.toString()).apply()
    }

    fun getDailyPlaylistsSnapshot(userId: Int, expectedDate: String): DailyPlaylistsSnapshot? {
        val mem = memoryDailySnapshot
        if (mem != null && mem.first == userId) {
            if (mem.second.date == expectedDate) {
                return mem.second
            } else {
                memoryDailySnapshot = null
            }
        }
        val key = "$KEY_DAILY_PLAYLISTS_PREFIX$userId"
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val root = JSONObject(raw)
            val storedDate = root.optString("date", "")
            if (storedDate != expectedDate) {
                // New day: delete old saved playlists for this user
                memoryDailySnapshot = null
                prefs.edit().remove(key).apply()
                return null
            }
            val source = root.optString("source", "local")
            val playlistsArr = root.optJSONArray("playlists") ?: return null
            val list = mutableListOf<DailyPlaylist>()
            for (i in 0 until playlistsArr.length()) {
                val obj = playlistsArr.getJSONObject(i)
                val slotIdx = obj.optInt("slotIndex", i + 1)
                val dpId = obj.optInt("id", Playlist.dailyPlaylistIdForSlot(slotIdx))
                val coversArr = obj.optJSONArray("previewCovers")
                val covers = mutableListOf<String>()
                if (coversArr != null) {
                    for (c in 0 until coversArr.length()) {
                        val cu = coversArr.optString(c, "")
                        if (cu.isNotBlank()) covers.add(cu)
                    }
                }
                val songsArr = obj.optJSONArray("songs")
                val songs = mutableListOf<Song>()
                if (songsArr != null) {
                    for (sIdx in 0 until songsArr.length()) {
                        val sObj = songsArr.getJSONObject(sIdx)
                        val localPathStr = sObj.optString("localPath", "")
                        val domColorStr = sObj.optString("dominantColorHex", "")
                        val primId = sObj.optInt("primaryArtistId", 0).takeIf { it > 0 }
                        val artistsJson = sObj.optString("artistsJson", "").takeIf { it.isNotBlank() }
                        songs.add(
                            Song(
                                id = sObj.getInt("id"),
                                title = sObj.optString("title", "Untitled Track"),
                                artist = sObj.optString("artist", "Unknown Artist"),
                                audioUrl = sObj.optString("audioUrl", ""),
                                coverUrl = sObj.optString("coverUrl", ""),
                                duration = sObj.optDouble("duration", 0.0),
                                isLiked = sObj.optBoolean("isLiked", false),
                                localPath = if (localPathStr.isNotBlank()) localPathStr else null,
                                isDownloaded = sObj.optBoolean("isDownloaded", false),
                                dominantColorHex = if (domColorStr.isNotBlank()) domColorStr else null,
                                primaryArtistId = primId,
                                artists = ArtistCreditJsonCodec.decode(artistsJson),
                                playCount = sObj.optInt("playCount", 0)
                            )
                        )
                    }
                }
                list.add(
                    DailyPlaylist(
                        id = dpId,
                        serverId = obj.optInt("serverId", 0),
                        slotIndex = slotIdx,
                        date = storedDate,
                        name = obj.optString("name", "Daily Mix $slotIdx"),
                        subtitle = obj.optString("subtitle", ""),
                        accentColorHex = obj.optString("accentColorHex", "#00E599"),
                        previewCovers = if (covers.isNotEmpty()) covers else songs.mapNotNull { it.coverUrl.takeIf(String::isNotBlank) }.distinct().take(4),
                        songCount = obj.optInt("songCount", songs.size).let { if (it > 0) it else songs.size },
                        songs = songs
                    )
                )
            }
            if (list.isEmpty()) return null
            val snapshot = DailyPlaylistsSnapshot(date = storedDate, source = source, playlists = list)
            memoryDailySnapshot = userId to snapshot
            snapshot
        } catch (_: Exception) {
            memoryDailySnapshot = null
            prefs.edit().remove(key).apply()
            null
        }
    }

    fun clearDailyPlaylistsSnapshot(userId: Int) {
        if (memoryDailySnapshot?.first == userId) {
            memoryDailySnapshot = null
        }
        prefs.edit().remove("$KEY_DAILY_PLAYLISTS_PREFIX$userId").apply()
    }

    // ==========================================
    // SEARCH ARTISTS & ARTIST PROFILE PERSISTENCE
    // ==========================================

    fun saveCachedArtists(artists: List<ArtistProfile>) {
        val capped = artists.take(250)
        memoryCachedArtists = capped
        runCatching {
            prefs.edit().putString(KEY_CACHED_ARTISTS, gson.toJson(capped, artistListType)).apply()
        }
    }

    fun getCachedArtists(): List<ArtistProfile> {
        memoryCachedArtists?.let { return it }
        val raw = prefs.getString(KEY_CACHED_ARTISTS, null)
        if (raw.isNullOrBlank()) {
            memoryCachedArtists = emptyList()
            return emptyList()
        }
        return try {
            val parsed: List<ArtistProfile>? = gson.fromJson(raw, artistListType)
            val list = parsed.orEmpty()
            memoryCachedArtists = list
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCachedArtistDetail(bundle: ArtistDetailBundle) {
        val updated = LinkedHashMap<Int, ArtistDetailBundle>(MAX_CACHED_ARTIST_DETAILS)
        synchronized(this) {
            val existing = getOrLoadArtistDetailMap()
            updated.putAll(existing)
            updated.remove(bundle.artist.id)
            updated[bundle.artist.id] = bundle
            while (updated.size > MAX_CACHED_ARTIST_DETAILS) {
                val eldestKey = updated.keys.firstOrNull() ?: break
                updated.remove(eldestKey)
            }
            memoryArtistDetails = updated
        }
        runCatching {
            prefs.edit().putString(KEY_CACHED_ARTIST_DETAILS, gson.toJson(updated, artistDetailMapType)).apply()
        }
    }

    fun getCachedArtistDetail(artistId: Int): ArtistDetailBundle? {
        return synchronized(this) {
            getOrLoadArtistDetailMap()[artistId]
        }
    }

    private fun getOrLoadArtistDetailMap(): LinkedHashMap<Int, ArtistDetailBundle> {
        memoryArtistDetails?.let { return it }
        val raw = prefs.getString(KEY_CACHED_ARTIST_DETAILS, null)
        val parsed = if (raw.isNullOrBlank()) {
            LinkedHashMap()
        } else {
            try {
                gson.fromJson<LinkedHashMap<Int, ArtistDetailBundle>>(raw, artistDetailMapType) ?: LinkedHashMap()
            } catch (_: Exception) {
                LinkedHashMap()
            }
        }
        memoryArtistDetails = parsed
        return parsed
    }

    // ==========================================
    // CLUB PLAYLISTS PERSISTENCE (Per User)
    // ==========================================

    fun saveCachedMyClubs(userId: Int, clubs: List<ClubPlaylistSummary>) {
        val capped = clubs.take(50)
        memoryMyClubs = userId to capped
        runCatching {
            prefs.edit().putString("$KEY_CACHED_MY_CLUBS_PREFIX$userId", gson.toJson(capped, clubSummaryListType)).apply()
        }
    }

    fun getCachedMyClubs(userId: Int): List<ClubPlaylistSummary> {
        val mem = memoryMyClubs
        if (mem != null && mem.first == userId) {
            return mem.second
        }
        val raw = prefs.getString("$KEY_CACHED_MY_CLUBS_PREFIX$userId", null)
        if (raw.isNullOrBlank()) {
            val empty = emptyList<ClubPlaylistSummary>()
            memoryMyClubs = userId to empty
            return empty
        }
        return try {
            val parsed: List<ClubPlaylistSummary>? = gson.fromJson(raw, clubSummaryListType)
            val list = parsed.orEmpty()
            memoryMyClubs = userId to list
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCachedClubDetails(userId: Int, details: ClubPlaylistDetails) {
        val updated = LinkedHashMap<Int, ClubPlaylistDetails>()
        synchronized(this) {
            val existing = getOrLoadClubDetailsMap(userId)
            updated.putAll(existing)
            updated.remove(details.clubId)
            updated[details.clubId] = details
            while (updated.size > MAX_CACHED_CLUB_DETAILS) {
                val eldestKey = updated.keys.firstOrNull() ?: break
                updated.remove(eldestKey)
            }
            memoryClubDetails = userId to updated
        }
        runCatching {
            prefs.edit().putString("$KEY_CACHED_CLUB_DETAILS_PREFIX$userId", gson.toJson(updated, clubDetailMapType)).apply()
        }
    }

    fun getCachedClubDetails(userId: Int, clubId: Int): ClubPlaylistDetails? {
        return synchronized(this) {
            getOrLoadClubDetailsMap(userId)[clubId]
        }
    }

    private fun getOrLoadClubDetailsMap(userId: Int): LinkedHashMap<Int, ClubPlaylistDetails> {
        val mem = memoryClubDetails
        if (mem != null && mem.first == userId) {
            return mem.second
        }
        val raw = prefs.getString("$KEY_CACHED_CLUB_DETAILS_PREFIX$userId", null)
        val parsed = if (raw.isNullOrBlank()) {
            LinkedHashMap()
        } else {
            try {
                gson.fromJson<LinkedHashMap<Int, ClubPlaylistDetails>>(raw, clubDetailMapType) ?: LinkedHashMap()
            } catch (_: Exception) {
                LinkedHashMap()
            }
        }
        memoryClubDetails = userId to parsed
        return parsed
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://api.alaktracare.in/"
        private const val KEY_SERVER_URL = "server_base_url"
        private const val KEY_LIKED_PLAYLIST_ID = "liked_playlist_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_FULL_NAME = "full_name"
        private const val KEY_AVATAR_URL = "avatar_url"
        private const val KEY_PASSWORD = "user_password"
        private const val KEY_EMAIL = "email"
        private const val KEY_TOKEN = "token"
        private const val KEY_LIKED_SONGS = "liked_song_ids"
        private const val KEY_OFFLINE_QUEUE = "offline_action_queue"
        private const val KEY_LAST_SONG_JSON = "last_played_song_json"
        private const val KEY_LAST_PLAYBACK_POSITION_MS = "last_playback_pos_ms"
        private const val KEY_SHUFFLE_ENABLED = "playback_shuffle_enabled"
        private const val KEY_REPEAT_MODE = "playback_repeat_mode"
        private const val KEY_RECENT_PLAYED_IDS = "recent_played_song_ids"
        private const val KEY_SAVED_QUEUE = "saved_playback_queue"
        private const val KEY_SAVED_CONTEXT_URI = "saved_playback_context_uri"
        private const val KEY_SAVED_CONTEXT_TYPE = "saved_playback_context_type"
        private const val KEY_SAVED_CONTEXT_NAME = "saved_playback_context_name"
        private const val KEY_SAVED_CONTEXT_TRACKS = "saved_playback_context_tracks"
        private const val KEY_PRE_OFFLINE_URI = "pre_offline_context_uri"
        private const val KEY_PRE_OFFLINE_RESUME_IDX = "pre_offline_resume_idx"
        private const val KEY_PRE_OFFLINE_PRESERVE_SHUFFLE = "pre_offline_preserve_shuffle"
        private const val KEY_AUDIO_CACHE_CAP_MB = "audio_cache_cap_mb"
        private const val KEY_RECENT_SEARCHES = "recent_searches_list"
        private const val KEY_CACHED_PINS = "cached_location_pins"
        private const val KEY_DAILY_PLAYLISTS_PREFIX = "daily_playlists_v1_"
        private const val KEY_CACHED_ARTISTS = "cached_artists_directory_v1"
        private const val KEY_CACHED_ARTIST_DETAILS = "cached_artist_details_v1"
        private const val KEY_CACHED_MY_CLUBS_PREFIX = "cached_my_clubs_v1_"
        private const val KEY_CACHED_CLUB_DETAILS_PREFIX = "cached_club_details_v1_"
        private const val MAX_CACHED_ARTIST_DETAILS = 25
        private const val MAX_CACHED_CLUB_DETAILS = 20
    }
}
