package com.example.soundcapsule

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "play_events",
    indices = [
        Index(value = ["userId", "trackId", "startedAtMillis"], unique = true),
        Index(value = ["userId", "startedAtMillis"])
    ]
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Int,
    val trackId: String,
    val trackTitle: String,
    val artistId: String,
    val artistName: String,
    val artworkUrl: String?,
    val artistArtworkUrl: String? = null,
    val startedAtMillis: Long,
    val msPlayed: Long,
    val syncedMsPlayed: Long = 0L,
) {
    fun toPlayEvent(): PlayEvent = PlayEvent(
        trackId = trackId,
        trackTitle = trackTitle,
        artistId = artistId,
        artistName = artistName,
        artworkUrl = artworkUrl?.takeIf { it.isNotBlank() },
        startedAtMillis = startedAtMillis,
        msPlayed = msPlayed,
        artistArtworkUrl = artistArtworkUrl?.takeIf { it.isNotBlank() },
    )
}

@Dao
interface PlayEventDao {
    @Query("SELECT * FROM play_events WHERE userId = :userId ORDER BY startedAtMillis ASC")
    fun observeEventsForUser(userId: Int): Flow<List<PlayEventEntity>>

    @Query("SELECT * FROM play_events WHERE userId = :userId ORDER BY startedAtMillis ASC")
    suspend fun getEventsForUser(userId: Int): List<PlayEventEntity>

    @Query(
        """
        SELECT * FROM play_events
        WHERE userId = :userId
          AND startedAtMillis >= :startMs
          AND startedAtMillis < :endMs
          AND msPlayed > 0
        ORDER BY startedAtMillis ASC
        """
    )
    suspend fun getEventsForMonthRange(
        userId: Int,
        startMs: Long,
        endMs: Long,
    ): List<PlayEventEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM play_events
            WHERE userId = :userId
              AND startedAtMillis >= :startMs
              AND startedAtMillis < :endMs
              AND msPlayed > 0
            LIMIT 1
        )
        """
    )
    suspend fun hasEventsInRange(
        userId: Int,
        startMs: Long,
        endMs: Long,
    ): Boolean

    @Query("SELECT MIN(startedAtMillis) FROM play_events WHERE userId = :userId AND msPlayed > 0")
    suspend fun getEarliestEventMillis(userId: Int): Long?

    @Query(
        """
        SELECT * FROM play_events
        WHERE userId = :userId AND trackId = :trackId AND startedAtMillis = :startedAtMillis
        LIMIT 1
        """
    )
    suspend fun findSessionEvent(
        userId: Int,
        trackId: String,
        startedAtMillis: Long,
    ): PlayEventEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(event: PlayEventEntity): Long

    @Update
    suspend fun updateEvent(event: PlayEventEntity)

    @Query(
        """
        SELECT * FROM play_events
        WHERE userId = :userId AND msPlayed > syncedMsPlayed
        ORDER BY startedAtMillis ASC
        """
    )
    suspend fun getUnsyncedEvents(userId: Int): List<PlayEventEntity>

    @Query("SELECT COUNT(*) FROM play_events WHERE userId = :userId AND msPlayed > syncedMsPlayed")
    suspend fun countUnsyncedEvents(userId: Int): Int

    @Query("UPDATE play_events SET syncedMsPlayed = MAX(syncedMsPlayed, :syncedMs) WHERE id = :id")
    suspend fun markEventSynced(id: Long, syncedMs: Long)

    @Transaction
    suspend fun upsertSessionEvent(
        userId: Int,
        trackId: String,
        trackTitle: String,
        artistId: String,
        artistName: String,
        artworkUrl: String?,
        startedAtMillis: Long,
        msPlayed: Long,
        syncedMsPlayed: Long = 0L,
        artistArtworkUrl: String? = null,
    ) {
        if (msPlayed <= 0L || startedAtMillis <= 0L) return
        val cleanArt = artworkUrl?.takeIf { it.isNotBlank() }
        val cleanArtistArt = artistArtworkUrl?.takeIf { it.isNotBlank() }
        val existing = findSessionEvent(userId, trackId, startedAtMillis)
        if (existing != null) {
            val mergedMs = maxOf(existing.msPlayed, msPlayed)
            val mergedSynced = maxOf(existing.syncedMsPlayed, syncedMsPlayed)
            val mergedTitle = trackTitle.takeIf { it.isNotBlank() && it != "Unknown Song" } ?: existing.trackTitle
            val mergedArtist = artistName.takeIf { it.isNotBlank() && it != "Unknown Artist" } ?: existing.artistName
            val incomingNumericArtistId = artistId.toIntOrNull() != null
            val mergedArtistId = when {
                incomingNumericArtistId -> artistId
                mergedArtist != existing.artistName -> artistId
                else -> existing.artistId
            }
            val mergedArt = cleanArt ?: existing.artworkUrl
            val mergedArtistArt = cleanArtistArt ?: existing.artistArtworkUrl
            if (
                mergedMs != existing.msPlayed ||
                mergedSynced != existing.syncedMsPlayed ||
                mergedTitle != existing.trackTitle ||
                mergedArtist != existing.artistName ||
                mergedArtistId != existing.artistId ||
                mergedArt != existing.artworkUrl ||
                mergedArtistArt != existing.artistArtworkUrl
            ) {
                updateEvent(
                    existing.copy(
                        trackTitle = mergedTitle,
                        artistId = mergedArtistId,
                        artistName = mergedArtist,
                        artworkUrl = mergedArt,
                        artistArtworkUrl = mergedArtistArt,
                        msPlayed = mergedMs,
                        syncedMsPlayed = mergedSynced,
                    )
                )
            }
        } else {
            val insertedId = insertIgnore(
                PlayEventEntity(
                    userId = userId,
                    trackId = trackId,
                    trackTitle = trackTitle.ifBlank { "Unknown Song" },
                    artistId = artistId.ifBlank { "unknown artist" },
                    artistName = artistName.ifBlank { "Unknown Artist" },
                    artworkUrl = cleanArt,
                    artistArtworkUrl = cleanArtistArt,
                    startedAtMillis = startedAtMillis,
                    msPlayed = msPlayed,
                    syncedMsPlayed = syncedMsPlayed,
                )
            )
            if (insertedId == -1L) {
                val raceExisting = findSessionEvent(userId, trackId, startedAtMillis) ?: return
                updateEvent(
                    raceExisting.copy(
                        msPlayed = maxOf(raceExisting.msPlayed, msPlayed),
                        syncedMsPlayed = maxOf(raceExisting.syncedMsPlayed, syncedMsPlayed),
                        artworkUrl = cleanArt ?: raceExisting.artworkUrl,
                        artistArtworkUrl = cleanArtistArt ?: raceExisting.artistArtworkUrl,
                    )
                )
            }
        }
    }
}

@Database(
    entities = [PlayEventEntity::class],
    version = 2,
    exportSchema = false
)
abstract class SoundCapsuleDatabase : RoomDatabase() {
    abstract fun playEventDao(): PlayEventDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_events ADD COLUMN artistArtworkUrl TEXT")
            }
        }

        @Volatile
        private var INSTANCE: SoundCapsuleDatabase? = null

        fun getInstance(context: Context): SoundCapsuleDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SoundCapsuleDatabase::class.java,
                    "sound_capsule.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
