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
    }

    val dailyGoal: Flow<Int> = store.data.map { it[Keys.DAILY_GOAL] ?: DEFAULT_DAILY_GOAL }
    val pacingInterval: Flow<Int> = store.data.map { it[Keys.PACING_INTERVAL_MIN] ?: DEFAULT_PACING_MIN }
    val quietHoursStart: Flow<Int> = store.data.map { it[Keys.QUIET_HOURS_START] ?: DEFAULT_QUIET_START }
    val quietHoursEnd: Flow<Int> = store.data.map { it[Keys.QUIET_HOURS_END] ?: DEFAULT_QUIET_END }
    val enabledDecks: Flow<Set<String>> = store.data.map { it[Keys.ENABLED_DECKS] ?: emptySet() }

    /**
     * Currently selected deck for the reviewer. `-1L` means "all decks" and is
     * the default for fresh installs (matches AnkiDroid's `-1` sentinel for the
     * `ReviewInfo` URI).
     */
    val selectedDeckId: Flow<Long> = store.data.map { it[Keys.SELECTED_DECK_ID] ?: ALL_DECKS }

    val lastSyncAt: Flow<Long> = store.data.map { it[Keys.LAST_SYNC_AT] ?: 0L }
    val onboardingDone: Flow<Boolean> = store.data.map { it[Keys.ONBOARDING_DONE] ?: false }

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

    companion object {
        const val DEFAULT_DAILY_GOAL = 200
        const val DEFAULT_PACING_MIN = 10
        const val DEFAULT_QUIET_START = 23
        const val DEFAULT_QUIET_END = 7
        const val ALL_DECKS = -1L
    }
}
