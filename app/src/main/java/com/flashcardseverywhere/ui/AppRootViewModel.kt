/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardseverywhere.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Root ViewModel for [AppNavHost]. Owns the "should onboarding be shown right
 * now" decision and the helper to send the user back through it on demand.
 */
data class AppRootState(
    val onboardingDone: Boolean = false,
)

@HiltViewModel
class AppRootViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<AppRootState> = settings.onboardingDone
        .map { AppRootState(onboardingDone = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppRootState(),
        )

    fun runOnboardingAgain() = viewModelScope.launch {
        settings.setOnboardingDone(false)
    }
}
