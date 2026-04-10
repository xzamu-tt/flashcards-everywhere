/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.domain

import com.flashcardseverywhere.data.anki.AnkiBridge
import com.flashcardseverywhere.data.anki.AnswerResult
import com.flashcardseverywhere.data.anki.DueCard
import com.flashcardseverywhere.data.anki.Ease
import com.flashcardseverywhere.data.anki.ReviewState
import com.flashcardseverywhere.data.local.AnswerQueueRepository
import com.flashcardseverywhere.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-card review session against AnkiDroid's `FlashCardsContract` provider.
 *
 * **Why we don't pre-fetch a batch of cards.** AnkiDroid's `CardContentProvider`
 * routes every external answer through the legacy `col.sched.answerCard(card, rating)`
 * path. That method peeks the head of the scheduler queue with `getQueuedCards(1)`,
 * builds a `CardAnswer` proto using *the queue head's* scheduling state, and
 * sets `cardId` to whatever card the caller passed. If the card we send isn't
 * the actual queue head, the answer is built with mismatched state and the
 * Rust backend either rejects it (RuntimeException, silently swallowed by the
 * provider's catch block) or applies the wrong transition. From the caller's
 * perspective `cr.update` returns 1 ("✅ I tried") but nothing actually persists.
 *
 * The only working pattern is therefore: fetch one card, answer it, fetch the
 * next one. We also pin AnkiDroid's currently-selected deck via
 * `Deck.CONTENT_SELECTED_URI` before every query and every answer, because the
 * `deckID=?` query selector temporarily switches the deck for the query and
 * then restores it — meaning a follow-up answer would hit the wrong deck's
 * queue head.
 *
 * If after a "successful" answer the next card has the same `noteId+cardOrd`
 * as the one we just graded, we know the silent-failure path was hit and
 * surface [ReviewState.AnswerStuck] so the user can [skip] (bury) the card.
 */
@Singleton
class ReviewSession @Inject constructor(
    private val bridge: AnkiBridge,
    private val answerQueue: AnswerQueueRepository,
) {
    private val _state = MutableStateFlow<ReviewState>(ReviewState.Loading)
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    private var currentDeckId: Long = SettingsRepository.NO_DECK
    private var currentStartedAt: Long = 0L

    /**
     * Re-pin the deck and load the queue head. Called from the reviewer on
     * resume, on user-initiated refresh, and after every grade/skip.
     *
     * @param deckId the deck the user picked in Settings, or [SettingsRepository.NO_DECK] for
     *   "no deck has been selected yet" (shows [ReviewState.NoDeckSelected]).
     */
    suspend fun refresh(deckId: Long) {
        currentDeckId = deckId
        _state.value = ReviewState.Loading
        if (!bridge.isAnkiDroidInstalled()) {
            _state.value = ReviewState.AnkiDroidNotInstalled
            return
        }
        if (!bridge.hasPermission()) {
            _state.value = ReviewState.PermissionDenied
            return
        }
        if (deckId == SettingsRepository.NO_DECK) {
            _state.value = ReviewState.NoDeckSelected
            return
        }
        loadHead(previous = null)
    }

    /** Submit an ease, then fetch the new queue head. */
    suspend fun grade(ease: Ease) {
        val current = currentCard() ?: return
        if (currentDeckId == SettingsRepository.NO_DECK) {
            _state.value = ReviewState.NoDeckSelected
            return
        }
        val elapsed = System.currentTimeMillis() - currentStartedAt

        // Pin the deck again — defensive, in case the user opened AnkiDroid
        // and changed the active deck since our last refresh.
        bridge.selectDeck(currentDeckId)

        when (bridge.answer(current.noteId, current.cardOrd, ease, elapsed)) {
            AnswerResult.Success -> {
                runCatching { answerQueue.drain() }
            }
            AnswerResult.Queued -> {
                // AnkiDroid was unreachable; persist locally for replay.
                answerQueue.enqueue(current.noteId, current.cardOrd, ease, elapsed)
            }
            is AnswerResult.Failed -> {
                answerQueue.enqueue(current.noteId, current.cardOrd, ease, elapsed)
            }
        }
        loadHead(previous = current)
    }

    /**
     * Bury the current card (it disappears from today's queue but is *not*
     * graded — comes back in a future session). Used as the recovery action
     * when the reviewer detects [ReviewState.AnswerStuck].
     */
    suspend fun skip() {
        val current = currentCard() ?: return
        if (currentDeckId == SettingsRepository.NO_DECK) return
        bridge.selectDeck(currentDeckId)
        bridge.bury(current.noteId, current.cardOrd)
        loadHead(previous = current)
    }

    private fun currentCard(): DueCard? = when (val s = _state.value) {
        is ReviewState.Card -> s.card
        is ReviewState.AnswerStuck -> s.card
        else -> null
    }

    /**
     * Pin the deck, fetch one card, and either show it, surface
     * [ReviewState.AnswerStuck] (if it's identical to [previous]), or fall
     * through to [ReviewState.NoDueCards].
     */
    private suspend fun loadHead(previous: DueCard?) {
        bridge.selectDeck(currentDeckId)
        val cards = runCatching { bridge.fetchDue(deckId = -1L, limit = 1) }
            .onFailure {
                _state.value = ReviewState.Error(it.message ?: "fetch failed")
                return
            }
            .getOrDefault(emptyList())

        val next = cards.firstOrNull()
        if (next == null) {
            _state.value = ReviewState.NoDueCards
            return
        }

        if (previous != null &&
            previous.noteId == next.noteId &&
            previous.cardOrd == next.cardOrd
        ) {
            // The previous answer didn't take effect on AnkiDroid's side.
            // See class doc for why this happens.
            _state.value = ReviewState.AnswerStuck(card = next)
            return
        }

        val due = runCatching { bridge.getDeckDueCount(currentDeckId) }.getOrDefault(0)
        currentStartedAt = System.currentTimeMillis()
        _state.value = ReviewState.Card(next, cardsLeft = due)
    }

}
