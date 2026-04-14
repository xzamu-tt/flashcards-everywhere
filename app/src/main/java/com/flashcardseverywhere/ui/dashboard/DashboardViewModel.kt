/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardseverywhere.data.prefs.SettingsRepository
import com.flashcardseverywhere.service.budget.ScreenTimeBudgetEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DashboardUiState(
    val screenTimeMinutes: Int = 0,
    val studyTimeMinutes: Int = 0,
    val cardsReviewed: Int = 0,
    val lockoutsToday: Int = 0,
    val doomScrollInterrupts: Int = 0,
    val budgetEarnedMin: Int = 0,
    val budgetRemainingMin: Int = 0,
    val budgetLocked: Boolean = false,
    val nextCardValueMin: Double = 0.0,
    val ratioPercent: Int = 0, // study time / screen time * 100
    val verdict: String = "",
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val budgetEngine: ScreenTimeBudgetEngine,
) : ViewModel() {

    val state: StateFlow<DashboardUiState> = combine(
        settings.statsScreenMsToday,
        settings.statsStudyMsToday,
        combine(
            settings.statsCardsReviewedToday,
            settings.statsLockoutsToday,
            settings.statsDoomScrollInterruptsToday,
            settings.budgetEarnedMs,
            settings.budgetLocked,
        ) { cards, lockouts, doom, earnedMs, locked ->
            StatsBundle(cards, lockouts, doom, earnedMs, locked)
        },
    ) { screenMs, studyMs, stats ->
        val screenMin = (screenMs / 60_000L).toInt()
        val studyMin = (studyMs / 60_000L).toInt()
        val ratio = if (screenMs > 0) ((studyMs * 100.0) / screenMs).toInt() else 0
        val budgetEarnedMin = (stats.earnedMs / 60_000L).toInt()
        val budgetRemainingMin = ((stats.earnedMs - screenMs).coerceAtLeast(0L) / 60_000L).toInt()

        DashboardUiState(
            screenTimeMinutes = screenMin,
            studyTimeMinutes = studyMin,
            cardsReviewed = stats.cards,
            lockoutsToday = stats.lockouts,
            doomScrollInterrupts = stats.doom,
            budgetEarnedMin = budgetEarnedMin,
            budgetRemainingMin = budgetRemainingMin,
            budgetLocked = stats.locked,
            ratioPercent = ratio,
            verdict = generateVerdict(screenMin, studyMin, stats.cards, ratio),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    private data class StatsBundle(
        val cards: Int, val lockouts: Int, val doom: Int,
        val earnedMs: Long, val locked: Boolean,
    )

    private fun generateVerdict(screenMin: Int, studyMin: Int, cards: Int, ratio: Int): String {
        return when {
            screenMin == 0 -> "No screen time recorded yet today."
            cards == 0 && screenMin > 30 -> "You've used your phone for ${screenMin}min and reviewed ZERO cards. Pathetic."
            ratio < 5 && screenMin > 60 -> "${screenMin}min of screen time. ${studyMin}min studying. You are losing."
            ratio < 10 -> "Study ratio: ${ratio}%. Your phone is winning."
            ratio < 25 -> "Study ratio: ${ratio}%. Getting there, but your phone still owns you."
            ratio < 50 -> "Study ratio: ${ratio}%. Respectable. Keep pushing."
            else -> "Study ratio: ${ratio}%. You're actually studying. Rare."
        }
    }
}
