/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardseverywhere.data.anki.AnkiBridge
import com.flashcardseverywhere.data.anki.DeckRow
import com.flashcardseverywhere.data.prefs.SettingsRepository
import com.flashcardseverywhere.service.blocking.AppBlockerService
import com.flashcardseverywhere.service.enforcement.EnforcementService
import com.flashcardseverywhere.service.grayscale.GrayscaleManager
import com.flashcardseverywhere.surface.media.StudyMediaSessionService
import com.flashcardseverywhere.util.PermissionChecks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    // ── Original ──────────────────────────────────────────────────────
    val dailyGoal: Int = SettingsRepository.DEFAULT_DAILY_GOAL,
    val pacingIntervalMin: Int = SettingsRepository.DEFAULT_PACING_MIN,
    val quietHoursStart: Int = SettingsRepository.DEFAULT_QUIET_START,
    val quietHoursEnd: Int = SettingsRepository.DEFAULT_QUIET_END,
    val selectedDeckId: Long = SettingsRepository.NO_DECK,
    val decks: List<DeckRow>? = null,
    val ankiInstalled: Boolean = false,
    val ankiPermissionGranted: Boolean = false,
    val lastSyncAt: Long = 0L,
    val deckLoadError: String? = null,

    // ── Interruption surfaces ─────────────────────────────────────────
    val notificationEnabled: Boolean = true,
    val lockscreenEnabled: Boolean = true,
    val overlayEnabled: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val bubbleEnabled: Boolean = false,
    val dreamEnabled: Boolean = false,
    val mediaSessionEnabled: Boolean = false,

    // ── Escalation ────────────────────────────────────────────────────
    val escalationEnabled: Boolean = true,
    val escalationTimeoutSec: Int = SettingsRepository.DEFAULT_ESCALATION_TIMEOUT_SEC,

    // ── App blocking ──────────────────────────────────────────────────
    val appBlockingEnabled: Boolean = false,
    val blockAllApps: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
    val blockUnlockDurationMin: Int = SettingsRepository.DEFAULT_BLOCK_UNLOCK_MIN,
    val cardsToUnlock: Int = SettingsRepository.DEFAULT_CARDS_TO_UNLOCK,

    // ── Aggressive mode ───────────────────────────────────────────────
    val aggressiveMode: Boolean = false,
    val vibrateOnCard: Boolean = true,

    // ── Screen-time budget (M8) ──────────────────────────────────────
    val budgetEnabled: Boolean = false,
    val budgetBaseMinPerCard: Int = SettingsRepository.DEFAULT_BUDGET_MIN_PER_CARD,

    // ── Doom-scroll interceptor (M8) ─────────────────────────────────
    val doomScrollEnabled: Boolean = false,
    val doomScrollThresholdMin: Int = SettingsRepository.DEFAULT_DOOM_SCROLL_MIN,

    // ── Grayscale enforcement (M8) ───────────────────────────────────
    val grayscaleEnabled: Boolean = false,
    val grayscalePermissionGranted: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val bridge: AnkiBridge,
    private val grayscaleManager: GrayscaleManager,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    private val _decks = MutableStateFlow<List<DeckRow>?>(null)
    private val _deckLoadError = MutableStateFlow<String?>(null)
    private val _bridgeProbe = MutableStateFlow(probeBridge())
    private val _overlayPermission = MutableStateFlow(PermissionChecks.hasOverlayPermission(ctx))

    // --- Flow bundles (combine() supports max 5 flows) ---

    private data class CoreBundle(
        val dailyGoal: Int, val pacingMin: Int,
        val quietStart: Int, val quietEnd: Int, val selectedDeckId: Long,
    )
    private val coreBundle = combine(
        settings.dailyGoal, settings.pacingInterval,
        settings.quietHoursStart, settings.quietHoursEnd, settings.selectedDeckId,
    ) { dg, pi, qs, qe, sd -> CoreBundle(dg, pi, qs, qe, sd) }

    private data class SurfaceBundle(
        val notificationOn: Boolean, val lockscreenOn: Boolean, val overlayOn: Boolean,
        val bubbleOn: Boolean, val dreamOn: Boolean,
    )
    private val surfaceBundle = combine(
        settings.notificationEnabled, settings.lockscreenEnabled, settings.overlayEnabled,
        settings.bubbleEnabled, settings.dreamEnabled,
    ) { n, l, o, b, d -> SurfaceBundle(n, l, o, b, d) }

    private data class EscalationBundle(
        val enabled: Boolean, val timeout: Int, val mediaSessionOn: Boolean,
        val aggressive: Boolean, val vibrate: Boolean,
    )
    private val escalationBundle = combine(
        settings.escalationEnabled, settings.escalationTimeoutSec,
        settings.mediaSessionEnabled, settings.aggressiveMode, settings.vibrateOnCard,
    ) { ee, et, ms, ag, vb -> EscalationBundle(ee, et, ms, ag, vb) }

    private data class BlockBundle(
        val enabled: Boolean, val blockAll: Boolean,
        val packages: Set<String>, val unlockMin: Int,
        val cardsToUnlock: Int,
    )
    private val blockBundle = combine(
        settings.appBlockingEnabled, settings.blockAllApps,
        settings.blockedPackages, settings.blockUnlockDurationMin,
        settings.cardsToUnlock,
    ) { e, ba, p, um, ctu -> BlockBundle(e, ba, p, um, ctu) }

    private data class MetaBundle(
        val lastSync: Long, val decks: List<DeckRow>?,
        val deckErr: String?, val probe: BridgeProbe, val overlayPerm: Boolean,
    )
    private val metaBundle = combine(
        settings.lastSyncAt, _decks, _deckLoadError, _bridgeProbe, _overlayPermission,
    ) { ls, d, err, bp, op -> MetaBundle(ls, d, err, bp, op) }

    private data class M8Bundle(
        val budgetEnabled: Boolean, val budgetBaseMin: Int,
        val doomEnabled: Boolean, val doomThreshold: Int,
        val grayscaleEnabled: Boolean,
    )
    private val m8Bundle = combine(
        settings.budgetEnabled, settings.budgetBaseMinutesPerCard,
        settings.doomScrollEnabled, settings.doomScrollThresholdMin,
        settings.grayscaleEnabled,
    ) { be, bbm, de, dt, ge -> M8Bundle(be, bbm, de, dt, ge) }

    val state: StateFlow<SettingsUiState> = combine(
        coreBundle, surfaceBundle, escalationBundle, blockBundle,
        combine(metaBundle, m8Bundle) { m, m8 -> m to m8 },
    ) { core, surface, esc, block, (meta, m8) ->
        SettingsUiState(
            dailyGoal = core.dailyGoal,
            pacingIntervalMin = core.pacingMin,
            quietHoursStart = core.quietStart,
            quietHoursEnd = core.quietEnd,
            selectedDeckId = core.selectedDeckId,
            lastSyncAt = meta.lastSync,
            decks = meta.decks,
            deckLoadError = meta.deckErr,
            ankiInstalled = meta.probe.installed,
            ankiPermissionGranted = meta.probe.permissionGranted,
            overlayPermissionGranted = meta.overlayPerm,
            notificationEnabled = surface.notificationOn,
            lockscreenEnabled = surface.lockscreenOn,
            overlayEnabled = surface.overlayOn,
            bubbleEnabled = surface.bubbleOn,
            dreamEnabled = surface.dreamOn,
            mediaSessionEnabled = esc.mediaSessionOn,
            escalationEnabled = esc.enabled,
            escalationTimeoutSec = esc.timeout,
            appBlockingEnabled = block.enabled,
            blockAllApps = block.blockAll,
            blockedPackages = block.packages,
            blockUnlockDurationMin = block.unlockMin,
            cardsToUnlock = block.cardsToUnlock,
            aggressiveMode = esc.aggressive,
            vibrateOnCard = esc.vibrate,
            budgetEnabled = m8.budgetEnabled,
            budgetBaseMinPerCard = m8.budgetBaseMin,
            doomScrollEnabled = m8.doomEnabled,
            doomScrollThresholdMin = m8.doomThreshold,
            grayscaleEnabled = m8.grayscaleEnabled,
            grayscalePermissionGranted = grayscaleManager.hasWriteSecureSettings(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    init { loadDecks() }

    fun refresh() {
        _bridgeProbe.value = probeBridge()
        _overlayPermission.value = PermissionChecks.hasOverlayPermission(ctx)
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

    // ── Original setters ──────────────────────────────────────────────────
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

    // ── Surface toggles ───────────────────────────────────────────────────
    fun setNotificationEnabled(on: Boolean) = viewModelScope.launch { settings.setNotificationEnabled(on) }
    fun setLockscreenEnabled(on: Boolean) = viewModelScope.launch { settings.setLockscreenEnabled(on) }
    fun setOverlayEnabled(on: Boolean) = viewModelScope.launch { settings.setOverlayEnabled(on) }
    fun openOverlaySettings() = PermissionChecks.openOverlaySettings(ctx)

    fun setBubbleEnabled(on: Boolean) = viewModelScope.launch { settings.setBubbleEnabled(on) }
    fun setDreamEnabled(on: Boolean) = viewModelScope.launch { settings.setDreamEnabled(on) }
    fun setMediaSessionEnabled(on: Boolean) {
        viewModelScope.launch {
            settings.setMediaSessionEnabled(on)
            if (on) StudyMediaSessionService.start(ctx)
            else StudyMediaSessionService.stop(ctx)
        }
    }

    // ── Escalation ────────────────────────────────────────────────────────
    fun setEscalationEnabled(on: Boolean) = viewModelScope.launch { settings.setEscalationEnabled(on) }
    fun setEscalationTimeout(sec: Int) = viewModelScope.launch { settings.setEscalationTimeoutSec(sec) }

    // ── App blocking ──────────────────────────────────────────────────────
    fun setAppBlockingEnabled(on: Boolean) {
        viewModelScope.launch {
            settings.setAppBlockingEnabled(on)
            if (on) AppBlockerService.start(ctx)
            else AppBlockerService.stop(ctx)
        }
    }
    fun setBlockAllApps(on: Boolean) = viewModelScope.launch { settings.setBlockAllApps(on) }
    fun addBlockedPackage(pkg: String) = viewModelScope.launch {
        val current = settings.blockedPackages.stateIn(viewModelScope).value
        settings.setBlockedPackages(current + pkg)
    }
    fun removeBlockedPackage(pkg: String) = viewModelScope.launch {
        val current = settings.blockedPackages.stateIn(viewModelScope).value
        settings.setBlockedPackages(current - pkg)
    }
    fun setBlockUnlockDuration(min: Int) = viewModelScope.launch { settings.setBlockUnlockDurationMin(min) }
    fun setCardsToUnlock(count: Int) = viewModelScope.launch { settings.setCardsToUnlock(count) }
    fun addAllPopularApps() = viewModelScope.launch {
        val current = settings.blockedPackages.stateIn(viewModelScope).value
        settings.setBlockedPackages(current + SettingsRepository.POPULAR_DISTRACTION_APPS.keys)
    }

    // ── Aggressive mode ───────────────────────────────────────────────────
    fun setAggressiveMode(on: Boolean) = viewModelScope.launch { settings.setAggressiveMode(on) }
    fun setVibrateOnCard(on: Boolean) = viewModelScope.launch { settings.setVibrateOnCard(on) }

    // ── Screen-time budget (M8) ──────────────────────────────────────────
    fun setBudgetEnabled(on: Boolean) {
        viewModelScope.launch {
            settings.setBudgetEnabled(on)
            if (on) {
                settings.resetBudgetDay()
                EnforcementService.start(ctx)
            }
        }
    }
    fun setBudgetBaseMinPerCard(min: Int) = viewModelScope.launch { settings.setBudgetBaseMinutesPerCard(min) }

    // ── Doom-scroll interceptor (M8) ─────────────────────────────────────
    fun setDoomScrollEnabled(on: Boolean) {
        viewModelScope.launch {
            settings.setDoomScrollEnabled(on)
            if (on) EnforcementService.start(ctx)
        }
    }
    fun setDoomScrollThreshold(min: Int) = viewModelScope.launch { settings.setDoomScrollThresholdMin(min) }

    // ── Grayscale enforcement (M8) ───────────────────────────────────────
    fun setGrayscaleEnabled(on: Boolean) {
        viewModelScope.launch {
            settings.setGrayscaleEnabled(on)
            if (!on) grayscaleManager.disableGrayscale()
        }
    }

    private fun probeBridge() = BridgeProbe(
        installed = bridge.isAnkiDroidInstalled(),
        permissionGranted = bridge.hasPermission(),
    )

    private data class BridgeProbe(val installed: Boolean, val permissionGranted: Boolean)
}
