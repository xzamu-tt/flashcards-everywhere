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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stateless-ish review session: pulls a batch of due cards, walks through them
 * one at a time, and submits answers back to AnkiDroid.
 *
 * UI surfaces (in-app, widget, lockscreen, overlay) all observe [state] and
 * call [grade]. There is exactly one session per process; concurrent surfaces
 * grade against the same queue.
 */
@Singleton
class ReviewSession @Inject constructor(
    private val bridge: AnkiBridge,
    private val answerQueue: AnswerQueueRepository,
) {
    private val _state = MutableStateFlow<ReviewState>(ReviewState.Loading)
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    private val queue = ArrayDeque<DueCard>()
    private var currentStartedAt: Long = 0L

    suspend fun refresh(deckId: Long = -1L, batchSize: Int = 20) {
        _state.value = ReviewState.Loading
        if (!bridge.isAnkiDroidInstalled()) {
            _state.value = ReviewState.AnkiDroidNotInstalled
            return
        }
        if (!bridge.hasPermission()) {
            _state.value = ReviewState.PermissionDenied
            return
        }
        runCatching { bridge.fetchDue(deckId, batchSize) }
            .onSuccess { cards ->
                queue.clear()
                queue.addAll(cards)
                advance()
            }
            .onFailure { _state.value = ReviewState.Error(it.message ?: "fetch failed") }
    }

    /** Submit an ease and advance to the next queued card. */
    suspend fun grade(ease: Ease) {
        val current = (_state.value as? ReviewState.Card)?.card ?: return
        val elapsed = System.currentTimeMillis() - currentStartedAt
        when (val r = bridge.answer(current.noteId, current.cardOrd, ease, elapsed)) {
            AnswerResult.Success -> {
                // Opportunistically drain anything still queued from offline.
                runCatching { answerQueue.drain() }
                advance()
            }
            AnswerResult.Queued -> advance()
            is AnswerResult.Failed -> {
                // Persist locally so the answer survives crashes / power-off
                // and is replayed when AnkiDroid is reachable again.
                answerQueue.enqueue(current.noteId, current.cardOrd, ease, elapsed)
                advance()
            }
        }
    }

    /** Pop the next card off the queue, refilling if exhausted. */
    private suspend fun advance() {
        if (queue.isEmpty()) {
            // Refill once before declaring NoDueCards.
            val refill = runCatching { bridge.fetchDue(-1L, 20) }.getOrDefault(emptyList())
            queue.addAll(refill)
            if (queue.isEmpty()) {
                _state.value = ReviewState.NoDueCards
                return
            }
        }
        val next = queue.removeFirst()
        currentStartedAt = System.currentTimeMillis()
        _state.value = ReviewState.Card(next, cardsLeft = queue.size + 1)
    }
}
