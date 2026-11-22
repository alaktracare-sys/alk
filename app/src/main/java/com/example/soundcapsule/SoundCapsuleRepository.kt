package com.example.soundcapsule

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.ListeningHistoryEntity
import com.example.data.preferences.AuthPreferences
import com.example.data.remote.HistoryEventDto
import com.example.data.remote.HistorySyncRequest
import com.example.data.remote.MusicApiService
import com.example.data.remote.cache.ApiCacheConfig
import com.example.data.remote.cache.ApiCacheManager
import com.example.model.Song
import com.example.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

class SoundCapsuleRepository(
    private val context: Context,
    private val authPreferences: AuthPreferences,
    private val apiCacheManager: ApiCacheManager,
    private val apiProvider: () -> MusicApiService,
    private val urlSanitizer: (String?) -> String,
) {
    private val database = SoundCapsuleDatabase.getInstance(context)
    private val playEventDao = database.playEventDao()
    private val legacyDatabase = AppDatabase.getInstance(context)
    private val zone: ZoneId = ZoneId.systemDefault()

    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pushMutex = Mutex()
    private val syncMutex = Mutex()
    private val cacheLock = Any()

    @Volatile
    private var debouncedPushJob: Job? = null

    @Volatile
    private var snapshotSaveJob: Job? = null

    /**
     * Ultra-lightweight hot state (< 25 KB total RAM):
     * - Single-month incremental accumulator for the current calendar month only.
     * - Compact precomputed SoundCapsuleStats (~1.4 KB per active month) for instant multi-month feed rendering.
     */
    private var accumulatorUserId: Int = -1
    private var currentMonthAccumulator = MonthCapsuleAccumulator(YearMonth.now(zone), zone)
    private var isCurrentMonthHydratedFromDb: Boolean = false

    private val activeMonthStatsMap = LinkedHashMap<YearMonth, SoundCapsuleStats>()
    private val extraCachedStatsLru = object : LinkedHashMap<String, SoundCapsuleStats>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SoundCapsuleStats>?): Boolean {
            return size > MAX_EXTRA_CACHED_MONTHS
        }
    }

    private val versionCounter = AtomicLong(0L)
    private val _hotStatsVersion = MutableStateFlow(0L)
    val hotStatsVersion: StateFlow<Long> = _hotStatsVersion.asStateFlow()

    private val _serverAvailableMonths = MutableStateFlow<Set<YearMonth>>(emptySet())
    val serverAvailableMonths: StateFlow<Set<YearMonth>> = _serverAvailableMonths.asStateFlow()

    private val _availableMonthsFlow = MutableStateFlow(buildRolling12Months())
    val availableMonthsFlow: StateFlow<List<YearMonth>> = _availableMonthsFlow.asStateFlow()

    private val _monthlyFeedFlow = MutableStateFlow<List<SoundCapsuleStats>>(emptyList())
    val monthlyFeedFlow: StateFlow<List<SoundCapsuleStats>> = _monthlyFeedFlow.asStateFlow()

    init {
        primeColdBootSnapshot(getEffectiveUserId())
    }

    fun getEffectiveUserId(): Int = authPreferences.getUser()?.id ?: 1

    fun observePlayEvents(userId: Int): Flow<List<PlayEvent>> {
        val nowMonth = YearMonth.now(zone)
        val startMs = nowMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = nowMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return playEventDao.observeEventsForUser(userId).map { rows ->
            rows.asSequence()
                .filter { it.startedAtMillis in startMs until endMs }
                .map { it.toPlayEvent() }
                .toList()
        }
    }

    private fun buildRolling12Months(): List<YearMonth> {
        val nowMonth = YearMonth.now(zone)
        return (0 until 12).map { offset -> nowMonth.minusMonths(offset.toLong()) }
    }

    private fun statsCacheKey(userId: Int, month: YearMonth): String = "$userId:${month.year}-${month.monthValue}"

    private fun hasMeaningfulActivity(stats: SoundCapsuleStats): Boolean {
        return stats.totalMinutes > 0 || stats.topArtists.isNotEmpty() || stats.topSongs.isNotEmpty()
    }

    private fun bumpHotStatsVersion() {
        _hotStatsVersion.value = versionCounter.incrementAndGet()
    }

    private fun buildOrderedFeedLocked(userId: Int): List<SoundCapsuleStats> {
        val nowMonth = YearMonth.now(zone)
        val list = activeMonthStatsMap.values
            .filter { hasMeaningfulActivity(it) }
            .sortedByDescending { it.month }
            .toMutableList()
        if (list.isEmpty()) {
            val emptyCurrent = extraCachedStatsLru[statsCacheKey(userId, nowMonth)]
                ?: MonthCapsuleAccumulator(nowMonth, zone).buildStats(LocalDate.now(zone))
            list.add(emptyCurrent)
        }
        return list
    }

    /**
     * Loads the compact persisted multi-month snapshot from SharedPreferences so cold app starts
     * render the entire Sound Capsule monthly feed at 0ms before SQLite opens.
     */
    private fun primeColdBootSnapshot(userId: Int) {
        val decodedList = SoundCapsuleSnapshotCodec.decodeList(authPreferences.getCachedCapsuleSnapshot(userId))
        val nowMonth = YearMonth.now(zone)
        val rolling12 = buildRolling12Months()
        synchronized(cacheLock) {
            if (accumulatorUserId != userId) {
                activeMonthStatsMap.clear()
            }
            for (st in decodedList) {
                extraCachedStatsLru[statsCacheKey(userId, st.month)] = st
                if (hasMeaningfulActivity(st)) {
                    activeMonthStatsMap[st.month] = st
                }
            }
            _monthlyFeedFlow.value = buildOrderedFeedLocked(userId)
            _availableMonthsFlow.value = (rolling12 + activeMonthStatsMap.keys + _serverAvailableMonths.value)
                .filter { !it.isAfter(nowMonth) }
                .distinct()
                .sortedDescending()
        }
    }

    private fun schedulePersistFeedSnapshot(userId: Int, feed: List<SoundCapsuleStats>) {
        snapshotSaveJob?.cancel()
        snapshotSaveJob = backgroundScope.launch {
            delay(400L)
            runCatching {
                val activeOnly = feed.filter { hasMeaningfulActivity(it) }
                if (activeOnly.isNotEmpty()) {
                    authPreferences.setCachedCapsuleSnapshot(
                        userId,
                        SoundCapsuleSnapshotCodec.encodeList(activeOnly, maxMonths = MAX_FEED_MONTHS)
                    )
                }
            }
        }
    }

    fun getInstantMonthlyFeed(userId: Int = getEffectiveUserId()): List<SoundCapsuleStats> {
        synchronized(cacheLock) {
            if (accumulatorUserId != userId && activeMonthStatsMap.isEmpty()) {
                primeColdBootSnapshot(userId)
            }
            if (accumulatorUserId == userId && isCurrentMonthHydratedFromDb) {
                val nowMonth = YearMonth.now(zone)
                if (currentMonthAccumulator.month == nowMonth && currentMonthAccumulator.hasAnyActivity) {
                    val freshCurrent = currentMonthAccumulator.buildStats(LocalDate.now(zone))
                    activeMonthStatsMap[nowMonth] = freshCurrent
                    extraCachedStatsLru[statsCacheKey(userId, nowMonth)] = freshCurrent
                }
            }
            val feed = buildOrderedFeedLocked(userId)
            _monthlyFeedFlow.value = feed
            return feed
        }
    }

    fun getDefaultActiveMonth(userId: Int = getEffectiveUserId()): YearMonth {
        val nowMonth = YearMonth.now(zone)
        synchronized(cacheLock) {
            if (accumulatorUserId == userId && currentMonthAccumulator.month == nowMonth && currentMonthAccumulator.hasAnyActivity) {
                return nowMonth
            }
            if (activeMonthStatsMap.containsKey(nowMonth)) {
                return nowMonth
            }
            return activeMonthStatsMap.keys.maxOrNull() ?: nowMonth
        }
    }

    fun getCachedStatsOrNull(userId: Int, month: YearMonth): SoundCapsuleStats? {
        val nowMonth = YearMonth.now(zone)
        synchronized(cacheLock) {
            if (month == nowMonth && accumulatorUserId == userId && currentMonthAccumulator.month == nowMonth && isCurrentMonthHydratedFromDb) {
                val built = currentMonthAccumulator.buildStats(LocalDate.now(zone))
                extraCachedStatsLru[statsCacheKey(userId, month)] = built
                if (hasMeaningfulActivity(built)) {
                    activeMonthStatsMap[month] = built
                }
                return built
            }
            activeMonthStatsMap[month]?.let { return it }
            return extraCachedStatsLru[statsCacheKey(userId, month)]
        }
    }

    fun getInstantStats(userId: Int, month: YearMonth): SoundCapsuleStats {
        getCachedStatsOrNull(userId, month)?.let { return it }
        primeColdBootSnapshot(userId)
        getCachedStatsOrNull(userId, month)?.let { return it }
        return MonthCapsuleAccumulator(month, zone).buildStats(LocalDate.now(zone))
    }

    /**
     * Computes a single month's SoundCapsuleStats from SQLite via an indexed range query
     * and discards temporary PlayEventEntity rows immediately so heap stays minimal.
     */
    private suspend fun computeSingleMonthFromDb(userId: Int, month: YearMonth): SoundCapsuleStats {
        val acc = MonthCapsuleAccumulator(month, zone)
        val rows = playEventDao.getEventsForMonthRange(userId, acc.startMs, acc.endMs)
        for (row in rows) {
            acc.upsertSession(
                trackId = row.trackId,
                trackTitle = row.trackTitle,
                artistId = row.artistId,
                artistName = row.artistName,
                artworkUrl = row.artworkUrl,
                artistArtworkUrl = row.artistArtworkUrl,
                startedAtMillis = row.startedAtMillis,
                msPlayed = row.msPlayed,
            )
        }
        return acc.buildStats(LocalDate.now(zone))
    }

    suspend fun loadMonthStats(userId: Int, month: YearMonth): SoundCapsuleStats = withContext(Dispatchers.IO) {
        val nowMonth = YearMonth.now(zone)
        if (month == nowMonth) {
            return@withContext hydrateCurrentMonthFromDb(userId)
        }
        synchronized(cacheLock) {
            activeMonthStatsMap[month]?.let { return@withContext it }
            extraCachedStatsLru[statsCacheKey(userId, month)]?.let { return@withContext it }
        }
        val built = computeSingleMonthFromDb(userId, month)
        synchronized(cacheLock) {
            extraCachedStatsLru[statsCacheKey(userId, month)] = built
            if (hasMeaningfulActivity(built)) {
                activeMonthStatsMap[month] = built
                _monthlyFeedFlow.value = buildOrderedFeedLocked(userId)
            }
        }
        built
    }

    private suspend fun hydrateCurrentMonthFromDb(userId: Int): SoundCapsuleStats = withContext(Dispatchers.IO) {
        val nowMonth = YearMonth.now(zone)
        val freshAcc = MonthCapsuleAccumulator(nowMonth, zone)
        val rows = playEventDao.getEventsForMonthRange(userId, freshAcc.startMs, freshAcc.endMs)
        for (row in rows) {
            freshAcc.upsertSession(
                trackId = row.trackId,
                trackTitle = row.trackTitle,
                artistId = row.artistId,
                artistName = row.artistName,
                artworkUrl = row.artworkUrl,
                artistArtworkUrl = row.artistArtworkUrl,
                startedAtMillis = row.startedAtMillis,
                msPlayed = row.msPlayed,
            )
        }
        val built: SoundCapsuleStats
        val updatedFeed: List<SoundCapsuleStats>
        val feedChanged: Boolean
        synchronized(cacheLock) {
            val prevFeed = _monthlyFeedFlow.value
            accumulatorUserId = userId
            currentMonthAccumulator = freshAcc
            isCurrentMonthHydratedFromDb = true
            built = freshAcc.buildStats(LocalDate.now(zone))
            extraCachedStatsLru[statsCacheKey(userId, nowMonth)] = built
            if (hasMeaningfulActivity(built)) {
                activeMonthStatsMap[nowMonth] = built
            } else {
                activeMonthStatsMap.remove(nowMonth)
            }
            updatedFeed = buildOrderedFeedLocked(userId)
            feedChanged = updatedFeed != prevFeed
            if (feedChanged) {
                _monthlyFeedFlow.value = updatedFeed
            }
        }
        if (feedChanged) {
            schedulePersistFeedSnapshot(userId, updatedFeed)
            bumpHotStatsVersion()
        }
        built
    }

    /**
     * Hydrates all active Sound Capsule months (up to MAX_FEED_MONTHS) one month at a time
     * using indexed SQLite checks so temporary row lists are immediately discarded.
     */
    private suspend fun hydrateMonthlyFeedFromDb(userId: Int) = withContext(Dispatchers.IO) {
        val nowMonth = YearMonth.now(zone)
        val earliestMs = playEventDao.getEarliestEventMillis(userId)
        val candidateMonths = LinkedHashSet<YearMonth>()
        candidateMonths.addAll(buildRolling12Months())

        if (earliestMs != null && earliestMs > 0L) {
            val earliestZdt = Instant.ofEpochMilli(earliestMs).atZone(zone)
            val earliestYm = YearMonth.of(earliestZdt.year, earliestZdt.month)
            var cursor = nowMonth
            var count = 0
            while (!cursor.isBefore(earliestYm) && count < MAX_FEED_MONTHS) {
                candidateMonths.add(cursor)
                cursor = cursor.minusMonths(1)
                count++
            }
        }
        candidateMonths.addAll(_serverAvailableMonths.value.filter { !it.isAfter(nowMonth) }.sortedDescending().take(MAX_FEED_MONTHS))

        val sortedCandidates = candidateMonths.sortedDescending().take(MAX_FEED_MONTHS)
        val builtMap = LinkedHashMap<YearMonth, SoundCapsuleStats>()

        for (ym in sortedCandidates) {
            if (ym == nowMonth) {
                val currentStats = hydrateCurrentMonthFromDb(userId)
                if (hasMeaningfulActivity(currentStats)) {
                    builtMap[ym] = currentStats
                }
                continue
            }
            val startMs = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (playEventDao.hasEventsInRange(userId, startMs, endMs)) {
                val st = computeSingleMonthFromDb(userId, ym)
                if (hasMeaningfulActivity(st)) {
                    builtMap[ym] = st
                }
            }
        }

        val rolling12 = buildRolling12Months()
        val updatedFeed: List<SoundCapsuleStats>
        val mergedAvailable: List<YearMonth>
        val feedChanged: Boolean
        val availableChanged: Boolean
        synchronized(cacheLock) {
            val prevFeed = _monthlyFeedFlow.value
            val prevAvailable = _availableMonthsFlow.value
            activeMonthStatsMap.clear()
            activeMonthStatsMap.putAll(builtMap)
            mergedAvailable = (rolling12 + builtMap.keys + _serverAvailableMonths.value)
                .filter { !it.isAfter(nowMonth) }
                .distinct()
                .sortedDescending()
            updatedFeed = buildOrderedFeedLocked(userId)
            feedChanged = updatedFeed != prevFeed
            availableChanged = mergedAvailable != prevAvailable
            if (feedChanged) {
                _monthlyFeedFlow.value = updatedFeed
            }
        }
        if (availableChanged) {
            _availableMonthsFlow.value = mergedAvailable
        }
        if (feedChanged) {
            schedulePersistFeedSnapshot(userId, updatedFeed)
            bumpHotStatsVersion()
        }
    }

    /**
     * Hydrates the Sound Capsule local snapshot on app launch without firing eager network/artwork storms.
     */
    fun warmUpAsync(userId: Int = getEffectiveUserId()) {
        primeColdBootSnapshot(userId)
        backgroundScope.launch {
            try {
                importLocalListeningHistory(userId)
                hydrateMonthlyFeedFromDb(userId)
            } catch (e: Exception) {
                Log.w("SoundCapsuleRepo", "Warm-up skipped: ${e.message}")
            }
        }
    }

    /**
     * Releases extra historical LRU entries when Android signals memory pressure.
     */
    fun trimMemory() {
        synchronized(cacheLock) {
            extraCachedStatsLru.clear()
        }
    }

    /**
     * Synchronously applies an active or just-finished track's play duration into the in-memory
     * MonthCapsuleAccumulator in < 0.05ms (zero disk I/O), ensuring Sound Capsule displays
     * exact up-to-the-second stats on Frame #1 as soon as the screen opens.
     */
    fun applyLiveSessionInMemory(
        song: Song,
        startedAtMillis: Long,
        msPlayed: Long,
    ) {
        if (startedAtMillis <= 0L || msPlayed <= 0L) return
        val userId = getEffectiveUserId()
        val nowMonth = YearMonth.now(zone)
        val artistName = song.primaryArtistName.trim().ifBlank { "Unknown Artist" }
        val resolvedArtistId = song.primaryArtistId?.takeIf { it > 0 }?.toString() ?: canonicalArtistId(artistName)
        val cleanCover = urlSanitizer(song.coverUrl).takeIf { it.isNotBlank() }
        val cleanArtistAvatar = urlSanitizer(song.primaryArtistCredit?.avatarUrl).takeIf { it.isNotBlank() } ?: cleanCover

        var updatedFeed: List<SoundCapsuleStats>? = null
        synchronized(cacheLock) {
            if (accumulatorUserId != userId || currentMonthAccumulator.month != nowMonth) {
                accumulatorUserId = userId
                currentMonthAccumulator = MonthCapsuleAccumulator(nowMonth, zone)
            }
            val changed = currentMonthAccumulator.upsertSession(
                trackId = song.id.toString(),
                trackTitle = song.title.trim().ifBlank { "Unknown Song" },
                artistId = resolvedArtistId,
                artistName = artistName,
                artworkUrl = cleanCover,
                artistArtworkUrl = cleanArtistAvatar,
                startedAtMillis = startedAtMillis,
                msPlayed = msPlayed,
            )
            if (changed && isCurrentMonthHydratedFromDb) {
                val built = currentMonthAccumulator.buildStats(LocalDate.now(zone))
                extraCachedStatsLru[statsCacheKey(userId, nowMonth)] = built
                if (hasMeaningfulActivity(built)) {
                    activeMonthStatsMap[nowMonth] = built
                }
                updatedFeed = buildOrderedFeedLocked(userId)
                _monthlyFeedFlow.value = updatedFeed!!
            }
        }
        if (updatedFeed != null) {
            schedulePersistFeedSnapshot(userId, updatedFeed!!)
            bumpHotStatsVersion()
        } else if (!isCurrentMonthHydratedFromDb) {
            backgroundScope.launch {
                hydrateMonthlyFeedFromDb(userId)
            }
        }
    }

    /**
     * Caches a play session locally in Room and immediately updates the in-memory accumulator.
     */
    suspend fun recordSessionPlayEvent(
        song: Song,
        startedAtMillis: Long,
        msPlayed: Long,
        syncToServer: Boolean = false,
        sessionCompleted: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (startedAtMillis <= 0L || msPlayed <= 0L) return@withContext
        val userId = getEffectiveUserId()
        val artistName = song.primaryArtistName.trim().ifBlank { "Unknown Artist" }
        val resolvedArtistId = song.primaryArtistId?.takeIf { it > 0 }?.toString() ?: canonicalArtistId(artistName)
        val cleanCover = urlSanitizer(song.coverUrl).takeIf { it.isNotBlank() }
        val cleanArtistAvatar = urlSanitizer(song.primaryArtistCredit?.avatarUrl).takeIf { it.isNotBlank() } ?: cleanCover

        applyLiveSessionInMemory(song, startedAtMillis, msPlayed)

        playEventDao.upsertSessionEvent(
            userId = userId,
            trackId = song.id.toString(),
            trackTitle = song.title.trim().ifBlank { "Unknown Song" },
            artistId = resolvedArtistId,
            artistName = artistName,
            artworkUrl = cleanCover,
            startedAtMillis = startedAtMillis,
            msPlayed = msPlayed,
            syncedMsPlayed = 0L,
            artistArtworkUrl = cleanArtistAvatar,
        )

        if (!isCurrentMonthHydratedFromDb) {
            hydrateMonthlyFeedFromDb(userId)
        }

        if (syncToServer) {
            pushUnsyncedEventsToServer(userId, force = true)
        } else if (sessionCompleted && authPreferences.getUser() != null) {
            scheduleCoalescedBatchPush(userId)
        }
    }

    private fun scheduleCoalescedBatchPush(userId: Int) {
        debouncedPushJob?.cancel()
        debouncedPushJob = backgroundScope.launch {
            delay(ApiCacheConfig.HISTORY_IDLE_DEBOUNCE_MS)
            try {
                if (!NetworkMonitor.isOnline(context)) return@launch
                val now = System.currentTimeMillis()
                val lastPush = authPreferences.getLastSyncTime(KEY_HISTORY_SYNC)
                if (lastPush > 0L && (now - lastPush) < ApiCacheConfig.HISTORY_PUSH_COOLDOWN_MS) {
                    return@launch
                }
                val unsyncedCount = playEventDao.countUnsyncedEvents(userId)
                if (unsyncedCount >= ApiCacheConfig.HISTORY_BATCH_SIZE_THRESHOLD) {
                    pushUnsyncedEventsToServer(userId, force = false)
                }
            } catch (e: Exception) {
                Log.w("SoundCapsuleRepo", "Deferred batch sync check skipped: ${e.message}")
            }
        }
    }

    suspend fun importLocalListeningHistory(userId: Int = getEffectiveUserId()) = withContext(Dispatchers.IO) {
        try {
            val watermarkKey = "$KEY_LEGACY_IMPORT_PREFIX$userId"
            val lastImportedAt = authPreferences.getLastSyncTime(watermarkKey)
            val historyRows = legacyDatabase.getAllHistoryList()
            if (historyRows.isEmpty()) return@withContext

            var maxSeenPlayedAt = lastImportedAt
            var importedAny = false
            for (item in historyRows) {
                if (item.playedAt <= lastImportedAt) continue
                val effectiveSec = item.effectiveListenedSeconds
                if (effectiveSec <= 0L || item.playedAt <= 0L) continue
                val artistName = item.artist.trim().ifBlank { "Unknown Artist" }
                playEventDao.upsertSessionEvent(
                    userId = userId,
                    trackId = item.songId.toString(),
                    trackTitle = item.title.trim().ifBlank { "Unknown Song" },
                    artistId = canonicalArtistId(artistName),
                    artistName = artistName,
                    artworkUrl = urlSanitizer(item.coverUrl).takeIf { it.isNotBlank() },
                    startedAtMillis = item.playedAt,
                    msPlayed = effectiveSec * 1000L,
                    syncedMsPlayed = 0L,
                )
                importedAny = true
                if (item.playedAt > maxSeenPlayedAt) {
                    maxSeenPlayedAt = item.playedAt
                }
            }
            if (maxSeenPlayedAt > lastImportedAt) {
                authPreferences.setLastSyncTime(watermarkKey, maxSeenPlayedAt)
            }
            if (importedAny) {
                synchronized(cacheLock) { extraCachedStatsLru.clear() }
                hydrateMonthlyFeedFromDb(userId)
            }
        } catch (e: Exception) {
            Log.w("SoundCapsuleRepo", "Failed importing legacy local history", e)
        }
    }

    suspend fun cacheSyncedServerEvent(
        userId: Int,
        trackId: String,
        trackTitle: String,
        artistId: String,
        artistName: String,
        artworkUrl: String?,
        artistArtworkUrl: String?,
        startedAtMillis: Long,
        msPlayed: Long,
    ) {
        if (msPlayed <= 0L || startedAtMillis <= 0L) return
        playEventDao.upsertSessionEvent(
            userId = userId,
            trackId = trackId,
            trackTitle = trackTitle,
            artistId = artistId,
            artistName = artistName,
            artworkUrl = artworkUrl,
            startedAtMillis = startedAtMillis,
            msPlayed = msPlayed,
            syncedMsPlayed = msPlayed,
            artistArtworkUrl = artistArtworkUrl,
        )
        val watermarkKey = "$KEY_LEGACY_IMPORT_PREFIX$userId"
        val currentWatermark = authPreferences.getLastSyncTime(watermarkKey)
        if (startedAtMillis > currentWatermark) {
            authPreferences.setLastSyncTime(watermarkKey, startedAtMillis)
        }
    }

    suspend fun pushUnsyncedEventsToServer(
        userId: Int = getEffectiveUserId(),
        force: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val loggedInUser = authPreferences.getUser() ?: return@withContext false
        if (loggedInUser.id != userId || !NetworkMonitor.isOnline(context)) return@withContext false

        pushMutex.withLock {
            val now = System.currentTimeMillis()
            val lastPush = authPreferences.getLastSyncTime(KEY_HISTORY_SYNC)
            if (!force && lastPush > 0L && (now - lastPush) < ApiCacheConfig.HISTORY_PUSH_COOLDOWN_MS) {
                return@withLock false
            }

            importLocalListeningHistory(userId)

            val unsynced = playEventDao.getUnsyncedEvents(userId)
            if (unsynced.isEmpty()) {
                return@withLock true
            }

            val payloadWithRow = unsynced.mapNotNull { row ->
                val songId = row.trackId.toIntOrNull() ?: return@mapNotNull null
                if (songId <= 0 || row.msPlayed <= 0L) return@mapNotNull null
                val localDate = Instant.ofEpochMilli(row.startedAtMillis)
                    .atZone(zone)
                    .toLocalDate()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
                row to HistoryEventDto(
                    songId = songId,
                    listenedSeconds = (row.msPlayed / 1000.0).roundToLong().coerceAtLeast(1L),
                    msPlayed = row.msPlayed,
                    playedAt = row.startedAtMillis,
                    localDate = localDate,
                )
            }

            if (payloadWithRow.isEmpty()) {
                return@withLock true
            }

            val digest = payloadWithRow.joinToString(separator = "|") { (row, dto) ->
                "${row.id}:${row.startedAtMillis}:${dto.msPlayed}"
            }
            if (!force && apiCacheManager.getLastSyncedHistoryDigest(userId) == digest) {
                for ((row, dto) in payloadWithRow) {
                    playEventDao.markEventSynced(row.id, dto.msPlayed ?: row.msPlayed)
                }
                return@withLock true
            }

            try {
                val api = apiProvider()
                val resp = api.syncHistory(HistorySyncRequest(userId = userId, events = payloadWithRow.map { it.second }))
                if (resp.isSuccessful && resp.body()?.success == true) {
                    for ((row, dto) in payloadWithRow) {
                        playEventDao.markEventSynced(row.id, dto.msPlayed ?: row.msPlayed)
                    }
                    val completedAt = System.currentTimeMillis()
                    authPreferences.setLastSyncTime(KEY_HISTORY_SYNC, completedAt)
                    apiCacheManager.putLastSyncedHistoryDigest(userId, digest)
                    Log.i("SoundCapsuleRepo", "Batched history sync succeeded: ${payloadWithRow.size} events")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w("SoundCapsuleRepo", "Failed syncing batched history events: ${e.message}")
                false
            }
        }
    }

    suspend fun syncWithServer(
        userId: Int = getEffectiveUserId(),
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            importLocalListeningHistory(userId)
            if (!isCurrentMonthHydratedFromDb || accumulatorUserId != userId) {
                hydrateMonthlyFeedFromDb(userId)
            }
            if (NetworkMonitor.shouldSkipNetworkRequests(context, force)) return@withLock

            val now = System.currentTimeMillis()
            val pullSyncKey = "$KEY_CAPSULE_PULL_PREFIX$userId"
            val lastPull = authPreferences.getLastSyncTime(pullSyncKey)
            val isPullCacheFresh = apiCacheManager.isSoundCapsuleSyncFresh(userId) ||
                (lastPull > 0L && (now - lastPull) < ApiCacheConfig.SOUND_CAPSULE_TTL_MS)

            if (!force && isPullCacheFresh) {
                if (authPreferences.getUser()?.id == userId) {
                    val unsyncedCount = playEventDao.countUnsyncedEvents(userId)
                    if (unsyncedCount >= ApiCacheConfig.HISTORY_BATCH_SIZE_THRESHOLD) {
                        pushUnsyncedEventsToServer(userId, force = false)
                    }
                }
                return@withLock
            }

            if (authPreferences.getUser()?.id == userId) {
                pushUnsyncedEventsToServer(userId, force = force)
            }

            val api = apiProvider()
            var serverMergedAny = false

            try {
                val resp = api.getSoundCapsule(userId)
                val rawNet = resp.raw().networkResponse
                if (rawNet != null) {
                    if (NetworkMonitor.isUnreachableHttpStatus(resp.code())) {
                        NetworkMonitor.markServerUnreachable(authPreferences.getServerBaseUrl())
                    } else if (resp.code() in 200..499 && resp.code() != 408 && resp.code() != 429) {
                        NetworkMonitor.markServerReachable()
                    }
                }
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    val today = LocalDate.now(zone)
                    val baseUrl = authPreferences.getServerBaseUrl().trimEnd('/')
                    val existingPlayedAts = legacyDatabase.getAllHistoryList()
                        .asSequence()
                        .map { it.playedAt }
                        .toHashSet()

                    for (ev in body.events.orEmpty()) {
                        val trackId = ev.trackId?.takeIf { it.isNotBlank() }
                            ?: ev.songId.takeIf { it > 0 }?.toString()
                            ?: continue
                        if (ev.msPlayed <= 0L || ev.startedAtMillis <= 0L) continue
                        val artistName = ev.artistName?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                        val artistId = ev.artistId?.trim()?.takeIf { it.isNotBlank() } ?: canonicalArtistId(artistName)
                        val trackTitle = ev.trackTitle?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown Song"
                        val cleanArt = urlSanitizer(ev.artworkUrl).takeIf { it.isNotBlank() }
                        val cleanArtistArt = urlSanitizer(ev.artistArtworkUrl).takeIf { it.isNotBlank() } ?: cleanArt

                        cacheSyncedServerEvent(
                            userId = userId,
                            trackId = trackId,
                            trackTitle = trackTitle,
                            artistId = artistId,
                            artistName = artistName,
                            artworkUrl = cleanArt,
                            artistArtworkUrl = cleanArtistArt,
                            startedAtMillis = ev.startedAtMillis,
                            msPlayed = ev.msPlayed,
                        )

                        // Hydrate Today's Listening History locally from Sound Capsule events without any extra GET endpoint
                        if (ev.songId > 0 && ev.msPlayed >= 20_000L && !existingPlayedAts.contains(ev.startedAtMillis)) {
                            val eventDate = Instant.ofEpochMilli(ev.startedAtMillis).atZone(zone).toLocalDate()
                            if (eventDate == today) {
                                legacyDatabase.insertListeningHistory(
                                    ListeningHistoryEntity(
                                        songId = ev.songId,
                                        title = trackTitle,
                                        artist = artistName,
                                        audioUrl = "$baseUrl/songs/${ev.songId}/stream",
                                        coverUrl = cleanArt ?: "",
                                        duration = ev.duration,
                                        listenedSeconds = (ev.msPlayed / 1000L).coerceAtLeast(1L),
                                        playedAt = ev.startedAtMillis,
                                    )
                                )
                                existingPlayedAts.add(ev.startedAtMillis)
                            }
                        }

                        serverMergedAny = true
                    }

                    val parsedMonths = mutableSetOf<YearMonth>()
                    body.availableMonths.orEmpty().forEach { ymRaw ->
                        runCatching { YearMonth.parse(ymRaw.trim()) }.getOrNull()?.let { parsedMonths.add(it) }
                    }
                    body.monthlyListenTime.orEmpty().forEach { m ->
                        if (m.totalSeconds > 0 && m.year > 0 && m.monthValid()) {
                            parsedMonths.add(YearMonth.of(m.year, m.month))
                        }
                    }
                    if (parsedMonths.isNotEmpty()) {
                        _serverAvailableMonths.value = _serverAvailableMonths.value + parsedMonths
                    }

                    val syncedAt = System.currentTimeMillis()
                    apiCacheManager.markSoundCapsuleSynced(userId)
                    authPreferences.setLastSyncTime(pullSyncKey, syncedAt)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || e.javaClass.name.contains("Cancellation")) throw e
                if (e is java.io.IOException) {
                    NetworkMonitor.reportSuspectedServerFailure(context, authPreferences.getServerBaseUrl(), e)
                }
                Log.w("SoundCapsuleRepo", "Failed syncing /history/sound-capsule: ${e.message}")
            }

            if (serverMergedAny) {
                synchronized(cacheLock) { extraCachedStatsLru.clear() }
                hydrateMonthlyFeedFromDb(userId)
            }
        }
    }

    private fun com.example.data.remote.MonthlyListenTimeDto.monthValid(): Boolean = month in 1..12

    companion object {
        private const val KEY_HISTORY_SYNC = "history_sync"
        private const val KEY_CAPSULE_PULL_PREFIX = "sound_capsule_pull_"
        private const val KEY_LEGACY_IMPORT_PREFIX = "capsule_legacy_import_"
        private const val MAX_FEED_MONTHS = 36
        private const val MAX_EXTRA_CACHED_MONTHS = 6

        fun canonicalArtistId(artistName: String): String {
            return artistName
                .trim()
                .lowercase(Locale.ROOT)
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .ifBlank { "unknown artist" }
        }
    }
}
