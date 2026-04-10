/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardseverywhere.data.anki.AnkiBridge
import com.flashcardseverywhere.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    val bridge: AnkiBridge,
    private val settings: SettingsRepository,
) : ViewModel() {
    fun markDone() = viewModelScope.launch { settings.setOnboardingDone(true) }
}
