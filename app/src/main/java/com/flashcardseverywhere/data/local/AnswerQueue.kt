/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.flashcardseverywhere.data.anki.AnkiBridge
import com.flashcardseverywhere.data.anki.AnswerResult
import com.flashcardseverywhere.data.anki.Ease
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local persistence for answers we couldn't deliver to AnkiDroid (e.g.
 * AnkiDroid was not running, the binder failed, the user revoked permission
 * mid-session). Drained on the next successful round trip.
 */
@Entity(tableName = "queued_answers")
data class QueuedAnswer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val cardOrd: Int,
    val ease: Int,
    val timeTakenMs: Long,
    val queuedAt: Long = System.currentTimeMillis(),
)

@Dao
interface AnswerQueueDao {
    @Insert
    suspend fun insert(answer: QueuedAnswer): Long

    @Query("SELECT * FROM queued_answers ORDER BY queuedAt ASC LIMIT :limit")
    suspend fun head(limit: Int = 50): List<QueuedAnswer>

    @Query("DELETE FROM queued_answers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM queued_answers")
    suspend fun size(): Int
}

@Database(entities = [QueuedAnswer::class], version = 1, exportSchema = false)
abstract class FlashcardsDb : RoomDatabase() {
    abstract fun answerQueue(): AnswerQueueDao
}

@Module
@InstallIn(SingletonComponent::class)
object DbModule {
    @Provides
    @Singleton
    fun provideDb(@ApplicationContext ctx: Context): FlashcardsDb =
        Room.databaseBuilder(ctx, FlashcardsDb::class.java, "flashcards.db").build()

    @Provides
    fun provideAnswerQueueDao(db: FlashcardsDb): AnswerQueueDao = db.answerQueue()
}

/**
 * Drains the queue against AnkiDroid. Called by [com.flashcardseverywhere.domain.ReviewSession]
 * after a successful answer (when we know AnkiDroid is reachable) and by
 * `WorkManager` periodic on its own schedule.
 */
@Singleton
class AnswerQueueRepository @Inject constructor(
    private val dao: AnswerQueueDao,
    private val bridge: AnkiBridge,
) {
    suspend fun enqueue(noteId: Long, cardOrd: Int, ease: Ease, timeTakenMs: Long) {
        dao.insert(QueuedAnswer(
            noteId = noteId, cardOrd = cardOrd, ease = ease.value, timeTakenMs = timeTakenMs,
        ))
    }

    suspend fun drain(): Int {
        if (!bridge.hasPermission()) return 0
        var drained = 0
        for (a in dao.head(50)) {
            val ease = Ease.values().firstOrNull { it.value == a.ease } ?: continue
            val r = bridge.answer(a.noteId, a.cardOrd, ease, a.timeTakenMs)
            if (r is AnswerResult.Success) {
                dao.delete(a.id)
                drained++
            } else {
                break  // stop on first failure; try again next time
            }
        }
        return drained
    }

    suspend fun pendingCount(): Int = dao.size()
}
