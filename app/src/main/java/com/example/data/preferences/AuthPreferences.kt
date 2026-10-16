package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.example.model.Song
import com.example.model.User
import org.json.JSONArray
import org.json.JSONObject

class AuthPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("already_prefs", Context.MODE_PRIVATE)

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
            Song(
                id = json.getInt("id"),
                title = json.getString("title"),
                artist = json.getString("artist"),
                audioUrl = json.getString("audioUrl"),
                coverUrl = json.getString("coverUrl"),
                duration = json.optDouble("duration", 0.0),
                isLiked = json.optBoolean("isLiked", false),
                localPath = if (localPathStr.isNotBlank()) localPathStr else null,
                isDownloaded = json.optBoolean("isDownloaded", false)
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
                        isDownloaded = json.optBoolean("isDownloaded", false)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun clearPlaybackState() {
        prefs.edit()
            .remove(KEY_LAST_SONG_JSON)
            .remove(KEY_LAST_PLAYBACK_POSITION_MS)
            .remove(KEY_SAVED_QUEUE)
            .apply()
    }

    fun getLastSyncTime(syncKey: String): Long {
        return prefs.getLong("sync_time_$syncKey", 0L)
    }

    fun setLastSyncTime(syncKey: String, timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("sync_time_$syncKey", timestamp).apply()
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

    companion object {
        const val DEFAULT_SERVER_URL = "http://100.65.126.106:8000/"
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
        private const val KEY_AUDIO_CACHE_CAP_MB = "audio_cache_cap_mb"
        private const val KEY_RECENT_SEARCHES = "recent_searches_list"
    }
}
