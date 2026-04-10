/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+. See LICENSE.
 */
package com.flashcardseverywhere.data.anki

/** A deck row as returned by AnkiDroid's `Deck.CONTENT_ALL_URI`. */
data class DeckRow(
    val id: Long,
    val fullName: String,
    val parent: String?,
    val leafName: String,
    val learnCount: Int,
    val reviewCount: Int,
    val newCount: Int,
    val isDynamic: Boolean,
) {
    val totalDue: Int get() = learnCount + reviewCount + newCount
}

/** A single card pulled from AnkiDroid's review queue. */
data class DueCard(
    val noteId: Long,
    val cardOrd: Int,
    val buttonCount: Int,
    val nextReviewTimes: List<String>,
    val mediaFiles: List<String>,
    val frontHtml: String,
    val backHtml: String,
) {
    val canShowHard: Boolean get() = buttonCount >= 3
    val canShowEasy: Boolean get() = buttonCount >= 4
}

/** Discriminated state for the reviewer UI / surfaces. */
sealed interface ReviewState {
    data object Loading : ReviewState
    data object PermissionDenied : ReviewState
    data object AnkiDroidNotInstalled : ReviewState
    /** User has not picked a deck in Settings yet. */
    data object NoDeckSelected : ReviewState
    data object NoDueCards : ReviewState
    data class Card(val card: DueCard, val cardsLeft: Int) : ReviewState
    /**
     * AnkiDroid silently dropped the previous answer (a known limitation of
     * the legacy `col.sched.answerCard(card, rating)` path that the public
     * ContentProvider exposes — see CardContentProvider's catch-and-log block
     * around `answerCard`). The next-fetched card has the same identity as
     * the one we just graded. Surfacing this lets the user "Skip" the card
     * via `bury` instead of being trapped in an infinite loop.
     */
    data class AnswerStuck(val card: DueCard) : ReviewState
    data class Error(val message: String) : ReviewState
}

/** Result of an answer submission. */
sealed interface AnswerResult {
    data object Success : AnswerResult
    data class Failed(val reason: String) : AnswerResult
    /** AnkiDroid was unreachable; queued locally for later flush. */
    data object Queued : AnswerResult
}
