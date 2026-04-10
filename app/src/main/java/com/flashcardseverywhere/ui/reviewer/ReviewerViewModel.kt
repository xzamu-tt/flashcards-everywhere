/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.ui.reviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardseverywhere.data.anki.Ease
import com.flashcardseverywhere.data.anki.ReviewState
import com.flashcardseverywhere.data.prefs.SettingsRepository
import com.flashcardseverywhere.domain.ReviewSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReviewerViewModel @Inject constructor(
    private val session: ReviewSession,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _revealed = MutableStateFlow(false)

    /** UI state = session state + "is the back currently revealed". */
    val state: StateFlow<ReviewerUiState> =
        combine(session.state, _revealed) { s, revealed ->
            ReviewerUiState(review = s, revealed = revealed)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReviewerUiState(ReviewState.Loading, revealed = false),
        )

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _revealed.value = false
            val deckId = settings.selectedDeckId.first()
            session.refresh(deckId = deckId)
        }
    }

    fun reveal() { _revealed.value = true }

    fun grade(ease: Ease) {
        viewModelScope.launch {
            session.grade(ease)
            _revealed.value = false
        }
    }
}

data class ReviewerUiState(
    val review: ReviewState,
    val revealed: Boolean,
)
