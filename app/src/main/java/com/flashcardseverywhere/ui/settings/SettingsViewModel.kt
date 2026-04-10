/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardseverywhere.data.anki.AnkiBridge
import com.flashcardseverywhere.data.anki.DeckRow
import com.flashcardseverywhere.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Settings screen.
 *
 * `decks == null` means "we haven't tried loading yet" (or AnkiDroid permission
 * isn't granted). An empty list means "loaded but AnkiDroid returned nothing".
 */
data class SettingsUiState(
    val dailyGoal: Int = SettingsRepository.DEFAULT_DAILY_GOAL,
    val pacingIntervalMin: Int = SettingsRepository.DEFAULT_PACING_MIN,
    val quietHoursStart: Int = SettingsRepository.DEFAULT_QUIET_START,
    val quietHoursEnd: Int = SettingsRepository.DEFAULT_QUIET_END,
    val selectedDeckId: Long = SettingsRepository.ALL_DECKS,
    val decks: List<DeckRow>? = null,
    val ankiInstalled: Boolean = false,
    val ankiPermissionGranted: Boolean = false,
    val lastSyncAt: Long = 0L,
    val deckLoadError: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val bridge: AnkiBridge,
) : ViewModel() {

    private val _decks = MutableStateFlow<List<DeckRow>?>(null)
    private val _deckLoadError = MutableStateFlow<String?>(null)
    private val _bridgeProbe = MutableStateFlow(probeBridge())

    private data class PrefsBundle(
        val dailyGoal: Int,
        val pacingMin: Int,
        val quietStart: Int,
        val quietEnd: Int,
        val selectedDeckId: Long,
    )

    private val prefsBundle = combine(
        settings.dailyGoal,
        settings.pacingInterval,
        settings.quietHoursStart,
        settings.quietHoursEnd,
        settings.selectedDeckId,
    ) { dg, pi, qs, qe, sd -> PrefsBundle(dg, pi, qs, qe, sd) }

    val state: StateFlow<SettingsUiState> = combine(
        prefsBundle,
        settings.lastSyncAt,
        _decks,
        _deckLoadError,
        _bridgeProbe,
    ) { p, lastSync, decks, err, probe ->
        SettingsUiState(
            dailyGoal = p.dailyGoal,
            pacingIntervalMin = p.pacingMin,
            quietHoursStart = p.quietStart,
            quietHoursEnd = p.quietEnd,
            selectedDeckId = p.selectedDeckId,
            lastSyncAt = lastSync,
            decks = decks,
            deckLoadError = err,
            ankiInstalled = probe.installed,
            ankiPermissionGranted = probe.permissionGranted,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    init { loadDecks() }

    /** Re-probe AnkiDroid + reload deck list. Called from the screen on resume. */
    fun refresh() {
        _bridgeProbe.value = probeBridge()
        loadDecks()
    }

    fun loadDecks() {
        viewModelScope.launch {
            _deckLoadError.value = null
            if (!bridge.isAnkiDroidInstalled() || !bridge.hasPermission()) {
                _decks.value = null
                return@launch
            }
            runCatching { bridge.listDecks() }
                .onSuccess { _decks.value = it }
                .onFailure {
                    _decks.value = emptyList()
                    _deckLoadError.value = it.message ?: it::class.java.simpleName
                }
        }
    }

    fun setDailyGoal(value: Int) = viewModelScope.launch { settings.setDailyGoal(value) }
    fun setPacingInterval(min: Int) = viewModelScope.launch { settings.setPacingInterval(min) }
    fun setQuietHours(start: Int, end: Int) =
        viewModelScope.launch { settings.setQuietHours(start, end) }
    fun selectDeck(deckId: Long) = viewModelScope.launch { settings.setSelectedDeckId(deckId) }
    fun runOnboardingAgain() = viewModelScope.launch { settings.setOnboardingDone(false) }

    fun syncNow(): Boolean {
        val ok = bridge.triggerSync()
        if (ok) viewModelScope.launch { settings.markSyncedNow() }
        return ok
    }

    private fun probeBridge() = BridgeProbe(
        installed = bridge.isAnkiDroidInstalled(),
        permissionGranted = bridge.hasPermission(),
    )

    private data class BridgeProbe(val installed: Boolean, val permissionGranted: Boolean)
}
