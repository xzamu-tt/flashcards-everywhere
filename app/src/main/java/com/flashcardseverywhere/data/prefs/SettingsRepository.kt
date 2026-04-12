/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    object Keys {
        val DAILY_GOAL = intPreferencesKey("daily_goal_cards")
        val PACING_INTERVAL_MIN = intPreferencesKey("pacing_interval_min")
        val QUIET_HOURS_START = intPreferencesKey("quiet_start_hour")
        val QUIET_HOURS_END = intPreferencesKey("quiet_end_hour")
        val ENABLED_DECKS = stringSetPreferencesKey("enabled_deck_ids")
        val SELECTED_DECK_ID = longPreferencesKey("selected_deck_id")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")

        // ── Interruption surfaces ─────────────────────────────────────
        val NOTIFICATION_ENABLED = booleanPreferencesKey("surface_notification_enabled")
        val LOCKSCREEN_ENABLED = booleanPreferencesKey("surface_lockscreen_enabled")
        val OVERLAY_ENABLED = booleanPreferencesKey("surface_overlay_enabled")
        val BUBBLE_ENABLED = booleanPreferencesKey("surface_bubble_enabled")
        val DREAM_ENABLED = booleanPreferencesKey("surface_dream_enabled")
        val MEDIA_SESSION_ENABLED = booleanPreferencesKey("surface_media_session_enabled")

        // ── Escalation FSM ────────────────────────────────────────────
        val ESCALATION_ENABLED = booleanPreferencesKey("escalation_enabled")
        val ESCALATION_TIMEOUT_SEC = intPreferencesKey("escalation_timeout_sec")
        val ESCALATION_LEVEL = intPreferencesKey("escalation_level")
        val LAST_CARD_SURFACED_AT = longPreferencesKey("last_card_surfaced_at")
        val LAST_CARD_GRADED_AT = longPreferencesKey("last_card_graded_at")

        // ── App blocking (full flavor) ────────────────────────────────
        val APP_BLOCKING_ENABLED = booleanPreferencesKey("app_blocking_enabled")
        val BLOCK_ALL_APPS = booleanPreferencesKey("block_all_apps")
        val BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_packages")
        val BLOCK_UNLOCK_DURATION_MIN = intPreferencesKey("block_unlock_duration_min")

        // ── Aggressive mode ───────────────────────────────────────────
        val AGGRESSIVE_MODE = booleanPreferencesKey("aggressive_mode")
        val VIBRATE_ON_CARD = booleanPreferencesKey("vibrate_on_card")
    }

    // ── Original preferences ──────────────────────────────────────────────
    val dailyGoal: Flow<Int> = store.data.map { it[Keys.DAILY_GOAL] ?: DEFAULT_DAILY_GOAL }
    val pacingInterval: Flow<Int> = store.data.map { it[Keys.PACING_INTERVAL_MIN] ?: DEFAULT_PACING_MIN }
    val quietHoursStart: Flow<Int> = store.data.map { it[Keys.QUIET_HOURS_START] ?: DEFAULT_QUIET_START }
    val quietHoursEnd: Flow<Int> = store.data.map { it[Keys.QUIET_HOURS_END] ?: DEFAULT_QUIET_END }
    val enabledDecks: Flow<Set<String>> = store.data.map { it[Keys.ENABLED_DECKS] ?: emptySet() }

    /**
     * Currently selected deck for the reviewer. [NO_DECK] (`-1L`) means
     * "the user has not picked a deck yet" — the reviewer surfaces
     * `ReviewState.NoDeckSelected` and prompts them to choose one in Settings.
     *
     * The previous "all decks" semantic was removed: passing `deckID=?` to
     * AnkiDroid's ContentProvider only works for the query side, and the
     * follow-up answer would silently fail because it answered against the
     * wrong queue head. Forcing a single-deck pick is the supported pattern.
     */
    val selectedDeckId: Flow<Long> = store.data.map { it[Keys.SELECTED_DECK_ID] ?: NO_DECK }

    val lastSyncAt: Flow<Long> = store.data.map { it[Keys.LAST_SYNC_AT] ?: 0L }
    val onboardingDone: Flow<Boolean> = store.data.map { it[Keys.ONBOARDING_DONE] ?: false }

    // ── Interruption surface toggles ──────────────────────────────────────
    val notificationEnabled: Flow<Boolean> = store.data.map { it[Keys.NOTIFICATION_ENABLED] ?: true }
    val lockscreenEnabled: Flow<Boolean> = store.data.map { it[Keys.LOCKSCREEN_ENABLED] ?: true }
    val overlayEnabled: Flow<Boolean> = store.data.map { it[Keys.OVERLAY_ENABLED] ?: false }
    val bubbleEnabled: Flow<Boolean> = store.data.map { it[Keys.BUBBLE_ENABLED] ?: false }
    val dreamEnabled: Flow<Boolean> = store.data.map { it[Keys.DREAM_ENABLED] ?: false }
    val mediaSessionEnabled: Flow<Boolean> = store.data.map { it[Keys.MEDIA_SESSION_ENABLED] ?: false }

    // ── Escalation ────────────────────────────────────────────────────────
    val escalationEnabled: Flow<Boolean> = store.data.map { it[Keys.ESCALATION_ENABLED] ?: true }
    val escalationTimeoutSec: Flow<Int> = store.data.map { it[Keys.ESCALATION_TIMEOUT_SEC] ?: DEFAULT_ESCALATION_TIMEOUT_SEC }
    val escalationLevel: Flow<Int> = store.data.map { it[Keys.ESCALATION_LEVEL] ?: 0 }
    val lastCardSurfacedAt: Flow<Long> = store.data.map { it[Keys.LAST_CARD_SURFACED_AT] ?: 0L }
    val lastCardGradedAt: Flow<Long> = store.data.map { it[Keys.LAST_CARD_GRADED_AT] ?: 0L }

    // ── App blocking ──────────────────────────────────────────────────────
    val appBlockingEnabled: Flow<Boolean> = store.data.map { it[Keys.APP_BLOCKING_ENABLED] ?: false }
    val blockAllApps: Flow<Boolean> = store.data.map { it[Keys.BLOCK_ALL_APPS] ?: false }
    val blockedPackages: Flow<Set<String>> = store.data.map { it[Keys.BLOCKED_PACKAGES] ?: emptySet() }
    val blockUnlockDurationMin: Flow<Int> = store.data.map { it[Keys.BLOCK_UNLOCK_DURATION_MIN] ?: DEFAULT_BLOCK_UNLOCK_MIN }

    // ── Aggressive mode ───────────────────────────────────────────────────
    val aggressiveMode: Flow<Boolean> = store.data.map { it[Keys.AGGRESSIVE_MODE] ?: false }
    val vibrateOnCard: Flow<Boolean> = store.data.map { it[Keys.VIBRATE_ON_CARD] ?: true }

    // ── Setters ───────────────────────────────────────────────────────────
    suspend fun setDailyGoal(value: Int) = store.edit { it[Keys.DAILY_GOAL] = value }
    suspend fun setPacingInterval(min: Int) = store.edit { it[Keys.PACING_INTERVAL_MIN] = min }
    suspend fun setQuietHours(startHour: Int, endHour: Int) = store.edit {
        it[Keys.QUIET_HOURS_START] = startHour.coerceIn(0, 23)
        it[Keys.QUIET_HOURS_END] = endHour.coerceIn(0, 23)
    }
    suspend fun setEnabledDecks(ids: Set<String>) = store.edit { it[Keys.ENABLED_DECKS] = ids }
    suspend fun setSelectedDeckId(deckId: Long) = store.edit { it[Keys.SELECTED_DECK_ID] = deckId }
    suspend fun markSyncedNow() = store.edit { it[Keys.LAST_SYNC_AT] = System.currentTimeMillis() }
    suspend fun setOnboardingDone(done: Boolean) = store.edit { it[Keys.ONBOARDING_DONE] = done }

    suspend fun setNotificationEnabled(on: Boolean) = store.edit { it[Keys.NOTIFICATION_ENABLED] = on }
    suspend fun setLockscreenEnabled(on: Boolean) = store.edit { it[Keys.LOCKSCREEN_ENABLED] = on }
    suspend fun setOverlayEnabled(on: Boolean) = store.edit { it[Keys.OVERLAY_ENABLED] = on }
    suspend fun setBubbleEnabled(on: Boolean) = store.edit { it[Keys.BUBBLE_ENABLED] = on }
    suspend fun setDreamEnabled(on: Boolean) = store.edit { it[Keys.DREAM_ENABLED] = on }
    suspend fun setMediaSessionEnabled(on: Boolean) = store.edit { it[Keys.MEDIA_SESSION_ENABLED] = on }

    suspend fun setEscalationEnabled(on: Boolean) = store.edit { it[Keys.ESCALATION_ENABLED] = on }
    suspend fun setEscalationTimeoutSec(sec: Int) = store.edit { it[Keys.ESCALATION_TIMEOUT_SEC] = sec.coerceIn(30, 600) }
    suspend fun setEscalationLevel(level: Int) = store.edit { it[Keys.ESCALATION_LEVEL] = level }
    suspend fun markCardSurfacedNow() = store.edit { it[Keys.LAST_CARD_SURFACED_AT] = System.currentTimeMillis() }
    suspend fun markCardGradedNow() = store.edit {
        it[Keys.LAST_CARD_GRADED_AT] = System.currentTimeMillis()
        it[Keys.ESCALATION_LEVEL] = 0 // reset escalation on grade
    }

    suspend fun setAppBlockingEnabled(on: Boolean) = store.edit { it[Keys.APP_BLOCKING_ENABLED] = on }
    suspend fun setBlockAllApps(on: Boolean) = store.edit { it[Keys.BLOCK_ALL_APPS] = on }
    suspend fun setBlockedPackages(pkgs: Set<String>) = store.edit { it[Keys.BLOCKED_PACKAGES] = pkgs }
    suspend fun setBlockUnlockDurationMin(min: Int) = store.edit { it[Keys.BLOCK_UNLOCK_DURATION_MIN] = min.coerceIn(1, 60) }

    suspend fun setAggressiveMode(on: Boolean) = store.edit { it[Keys.AGGRESSIVE_MODE] = on }
    suspend fun setVibrateOnCard(on: Boolean) = store.edit { it[Keys.VIBRATE_ON_CARD] = on }

    companion object {
        const val DEFAULT_DAILY_GOAL = 200
        const val DEFAULT_PACING_MIN = 10
        const val DEFAULT_QUIET_START = 23
        const val DEFAULT_QUIET_END = 7
        const val DEFAULT_ESCALATION_TIMEOUT_SEC = 120
        const val DEFAULT_BLOCK_UNLOCK_MIN = 5
        /** Sentinel meaning "the user has not picked a deck yet". */
        const val NO_DECK = -1L
    }
}
