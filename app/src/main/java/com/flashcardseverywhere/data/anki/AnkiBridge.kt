/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+. See LICENSE.
 *
 * Re-implementation of the AnkiDroid ContentProvider access patterns
 * documented in docs/ANKI_CONTRACT_REFERENCE.md and
 * docs/ANKIDROID_WEAR_NOTES.md.
 *
 * No code is copied from the GPLv2-only AnkiDroid-Wear repository.
 */
package com.flashcardseverywhere.data.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin, stateless wrapper around AnkiDroid's `FlashCardsContract`.
 *
 * All public functions are `suspend` and dispatch on [Dispatchers.IO] — every
 * ContentResolver call here is a binder round trip into AnkiDroid's process.
 *
 * Caller responsibilities:
 *   - Request the dangerous permission [FlashCardsContract.READ_WRITE_PERMISSION]
 *     before calling any read/write method.
 *   - Debounce [triggerSync] to no more than once per [SYNC_MIN_INTERVAL_MS].
 */
@Singleton
class AnkiBridge @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val ctx: Context,
) {

    private val cr get() = ctx.contentResolver

    // ─────────────────────────────────────────────────────────────────────
    //  Installation / permission
    // ─────────────────────────────────────────────────────────────────────

    fun isAnkiDroidInstalled(): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(FlashCardsContract.ANKIDROID_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            try {
                ctx.packageManager.getPackageInfo(FlashCardsContract.ANKIDROID_PACKAGE_DEBUG, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        ctx, FlashCardsContract.READ_WRITE_PERMISSION
    ) == PackageManager.PERMISSION_GRANTED

    // ─────────────────────────────────────────────────────────────────────
    //  Decks
    // ─────────────────────────────────────────────────────────────────────

    suspend fun listDecks(): List<DeckRow> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val out = mutableListOf<DeckRow>()
        cr.query(FlashCardsContract.Deck.CONTENT_ALL_URI, null, null, null, null)?.use { c ->
            val cId = c.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_ID)
            val cName = c.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_NAME)
            val cCounts = c.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_COUNTS)
            val cDyn = c.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_DYN)
            while (c.moveToNext()) {
                val name = c.getString(cName)
                val parts = name.split("::")
                val countsArr = runCatching { JSONArray(c.getString(cCounts)) }
                    .getOrDefault(JSONArray("[0,0,0]"))
                out += DeckRow(
                    id = c.getLong(cId),
                    fullName = name,
                    parent = if (parts.size > 1) parts.dropLast(1).joinToString("::") else null,
                    leafName = parts.last(),
                    learnCount = countsArr.optInt(0),
                    reviewCount = countsArr.optInt(1),
                    newCount = countsArr.optInt(2),
                    isDynamic = c.getInt(cDyn) == 1,
                )
            }
        }
        out
    }

    suspend fun getSelectedDeckId(): Long? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        cr.query(
            FlashCardsContract.Deck.CONTENT_SELECTED_URI, null, null, null, null
        )?.use { c ->
            if (c.moveToFirst())
                c.getLong(c.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_ID))
            else null
        }
    }

    suspend fun selectDeck(deckId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext false
        val v = ContentValues().apply { put(FlashCardsContract.Deck.DECK_ID, deckId) }
        cr.update(FlashCardsContract.Deck.CONTENT_SELECTED_URI, v, null, null) > 0
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Review queue
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Pull the next [limit] due cards.
     *
     * @param deckId pass `-1` to query across all decks (or any included deck
     *   filter — AnkiDroid honours the currently selected deck if you pass -1
     *   AND omit the `deckID=?` selector entirely).
     */
    suspend fun fetchDue(deckId: Long, limit: Int = 20): List<DueCard> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()

            val selection: String
            val args: Array<String>
            if (deckId == -1L) {
                selection = "limit=?"
                args = arrayOf(limit.toString())
            } else {
                // NB: comma-separated, NOT SQL `AND`. The provider parses this
                // with a bespoke key=value splitter.
                selection = "limit=?,deckID=?"
                args = arrayOf(limit.toString(), deckId.toString())
            }

            val cards = mutableListOf<DueCard>()
            cr.query(
                FlashCardsContract.ReviewInfo.CONTENT_URI,
                null, selection, args, null
            )?.use { c ->
                if (!c.moveToFirst()) return@use
                val cNote = c.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.NOTE_ID)
                val cOrd = c.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.CARD_ORD)
                val cBtns = c.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.BUTTON_COUNT)
                val cNext = c.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.NEXT_REVIEW_TIMES)
                val cMedia = c.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.MEDIA_FILES)

                do {
                    val noteId = c.getLong(cNote)
                    val ord = c.getInt(cOrd)
                    val buttonCount = c.getInt(cBtns)
                    val next = parseJsonArray(c.getString(cNext))
                    val media = parseJsonArray(c.getString(cMedia))
                    val (front, back) = fetchCardHtmlBlocking(noteId, ord)
                    cards += DueCard(
                        noteId = noteId,
                        cardOrd = ord,
                        buttonCount = buttonCount,
                        nextReviewTimes = next,
                        mediaFiles = media,
                        frontHtml = front,
                        backHtml = back,
                    )
                } while (c.moveToNext())
            }
            cards
        }

    private fun parseJsonArray(raw: String?): List<String> = runCatching {
        if (raw.isNullOrBlank()) return@runCatching emptyList()
        val a = JSONArray(raw)
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    /**
     * Fetch a card's question/answer HTML. Synchronous because it's only
     * called from inside [fetchDue], which is already on Dispatchers.IO.
     *
     * Selection / sortOrder are documented as ignored on this URI.
     */
    private fun fetchCardHtmlBlocking(noteId: Long, cardOrd: Int): Pair<String, String> {
        val cardUri: Uri = FlashCardsContract.Note.CONTENT_URI.buildUpon()
            .appendPath(noteId.toString())
            .appendPath("cards")
            .appendPath(cardOrd.toString())
            .build()
        cr.query(
            cardUri,
            arrayOf(FlashCardsContract.Card.QUESTION, FlashCardsContract.Card.ANSWER),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0).orEmpty() to c.getString(1).orEmpty()
        }
        return "" to ""
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Answer / bury / suspend
    // ─────────────────────────────────────────────────────────────────────

    suspend fun answer(noteId: Long, cardOrd: Int, ease: Ease, timeTakenMs: Long): AnswerResult =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext AnswerResult.Failed("permission denied")
            val v = ContentValues().apply {
                put(FlashCardsContract.ReviewInfo.NOTE_ID, noteId)
                put(FlashCardsContract.ReviewInfo.CARD_ORD, cardOrd)
                put(FlashCardsContract.ReviewInfo.EASE, ease.value)
                put(FlashCardsContract.ReviewInfo.TIME_TAKEN, timeTakenMs)
            }
            try {
                val updated = cr.update(
                    FlashCardsContract.ReviewInfo.CONTENT_URI, v, null, null
                )
                if (updated > 0) AnswerResult.Success
                else AnswerResult.Failed("update returned 0")
            } catch (t: Throwable) {
                AnswerResult.Failed(t.message ?: t::class.java.simpleName)
            }
        }

    suspend fun bury(noteId: Long, cardOrd: Int): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext false
        val v = ContentValues().apply {
            put(FlashCardsContract.ReviewInfo.NOTE_ID, noteId)
            put(FlashCardsContract.ReviewInfo.CARD_ORD, cardOrd)
            put(FlashCardsContract.ReviewInfo.BURY, 1)
        }
        cr.update(FlashCardsContract.ReviewInfo.CONTENT_URI, v, null, null) > 0
    }

    suspend fun suspend(noteId: Long, cardOrd: Int): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext false
        val v = ContentValues().apply {
            put(FlashCardsContract.ReviewInfo.NOTE_ID, noteId)
            put(FlashCardsContract.ReviewInfo.CARD_ORD, cardOrd)
            put(FlashCardsContract.ReviewInfo.SUSPEND, 1)
        }
        cr.update(FlashCardsContract.ReviewInfo.CONTENT_URI, v, null, null) > 0
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Sync trigger
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Ask AnkiDroid to sync to AnkiWeb. Caller must enforce the 2-minute
     * minimum interval (see [FlashCardsContract.SYNC_MIN_INTERVAL_MS]) —
     * AnkiDroid will silently no-op faster fires.
     */
    fun triggerSync(): Boolean {
        return try {
            val i = Intent(FlashCardsContract.ACTION_SYNC).apply {
                setPackage(FlashCardsContract.ANKIDROID_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
